package com.gree1d.reappzuku;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.gree1d.reappzuku.AppConstants.*;
import static com.gree1d.reappzuku.PreferenceKeys.*;

/**
 * Manages ADB-over-WiFi service lifecycle for rooted devices.
 *
 * <p>On Android 10+, SELinux restricts the {@code su} context so that
 * {@code ps -A -o rss,name} returns no output. The same command works correctly
 * in the {@code shell} SELinux context (i.e. via {@code adb shell}).
 * This helper starts {@code adbd} in TCP mode on {@link AppConstants#ADB_WIFI_PORT}
 * using root, after which {@link ShellManager} can send {@code ps} through the
 * local ADB shell context instead of {@code su}.</p>
 *
 * <p>All other commands (am force-stop, dumpsys, appops, etc.) continue to run
 * through {@code su} as before — only {@code ps} needs the shell context.</p>
 *
 * <p>Typical lifecycle managed by {@link MainActivity}:</p>
 * <ol>
 *   <li>Banner shown when {@link #checkNeedsAdbService} returns {@code true}.</li>
 *   <li>User taps "Запустить сервис" → {@link #startAdbWifiService}.</li>
 *   <li>On success, banner hidden; ongoing notification posted.</li>
 *   <li>State persisted in SharedPreferences; verified via TCP probe on next launch.</li>
 * </ol>
 */
public class RootHelper {

    private static final String TAG = "RootHelper";

    private final Context context;
    private final Handler handler;
    private final ShellManager shellManager;
    private final SharedPreferences prefs;
    private final LocalAdbClient adbClient;

    // ── Public callback ───────────────────────────────────────────────────────

    public interface AdbServiceCallback {
        void onStarted();
        void onFailed(String reason);
        void onStopped();
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public RootHelper(Context context, Handler handler, ShellManager shellManager) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.shellManager = shellManager;
        this.prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.adbClient = new LocalAdbClient(context, shellManager);
    }

    // ── Banner visibility logic ───────────────────────────────────────────────

    /**
     * Asynchronously checks whether the root-ADB banner should be shown.
     * Condition: root available + Android 10+ + service not already running.
     * Runs the TCP probe on the executor to avoid blocking the main thread.
     */
    public void checkNeedsAdbService(ExecutorService executor, Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean needs = shellManager.hasRootAccess()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q // Android 10+
                    && !isAdbServiceRunningBlocking();
            handler.post(() -> callback.accept(needs));
        });
    }

    /**
     * Synchronous variant – must only be called from a background thread.
     */
    public boolean isAdbServiceRunningBlocking() {
        if (!prefs.getBoolean(KEY_ADB_WIFI_RUNNING, false)) return false;
        return isPortOpen(ADB_WIFI_PORT);
    }

    // ── Service start / stop ──────────────────────────────────────────────────

    /**
     * Starts {@code adbd} in TCP mode on {@link AppConstants#ADB_WIFI_PORT} using root.
     * Posts result callbacks on the main handler.
     */
    public void startAdbWifiService(ExecutorService executor, AdbServiceCallback callback) {
        executor.execute(() -> {
            Log.d(TAG, "Starting ADB WiFi service on port " + ADB_WIFI_PORT);

            // 1. Tell adbd which TCP port to listen on
            shellManager.runShellCommandAndGetFullOutput(
                    "setprop service.adb.tcp.port " + ADB_WIFI_PORT);

            // 2. Restart adbd so it picks up the new property
            shellManager.runShellCommandAndGetFullOutput("stop adbd");
            sleep(600);
            shellManager.runShellCommandAndGetFullOutput("start adbd");

            // 3. Allow daemon to fully start
            sleep(1400);

            // 4. Register our RSA key in adb_keys (adbd re-reads after restart above)
            adbClient.ensureKeyRegistered();
            sleep(400);

            // 5. Verify the port is actually open
            if (isPortOpen(ADB_WIFI_PORT)) {
                prefs.edit().putBoolean(KEY_ADB_WIFI_RUNNING, true).apply();
                postAdbNotification();
                Log.d(TAG, "ADB WiFi service started successfully");
                handler.post(callback::onStarted);
            } else {
                Log.w(TAG, "adbd started but port " + ADB_WIFI_PORT + " is not open");
                handler.post(() -> callback.onFailed(
                        context.getString(R.string.adb_service_failed,
                                "Порт " + ADB_WIFI_PORT + " не открылся после запуска adbd.")));
            }
        });
    }

    /**
     * Resets {@code adbd} back to USB-only mode.
     */
    public void stopAdbWifiService(ExecutorService executor, AdbServiceCallback callback) {
        executor.execute(() -> {
            Log.d(TAG, "Stopping ADB WiFi service");
            shellManager.runShellCommandAndGetFullOutput("setprop service.adb.tcp.port -1");
            shellManager.runShellCommandAndGetFullOutput("stop adbd");
            sleep(400);
            shellManager.runShellCommandAndGetFullOutput("start adbd");

            prefs.edit().putBoolean(KEY_ADB_WIFI_RUNNING, false).apply();
            cancelAdbNotification();
            handler.post(callback::onStopped);
        });
    }

    // ── ps command via ADB shell ──────────────────────────────────────────────

    /**
     * Runs {@code ps -A -o rss,name | grep '\.' | grep -v '[-:@]' | awk '{print $2}'}
     * through the local ADB shell context (127.0.0.1:5555).
     *
     * <p>Use this instead of {@link ShellManager#runShellCommandAndGetFullOutput}
     * for the {@code ps} command on Android 10+ rooted devices, where the {@code su}
     * SELinux context blocks {@code ps -A -o} output.</p>
     *
     * <p>Must be called from a background thread.</p>
     *
     * @return stdout of the ps command, or {@code null} if adbd is not running
     *         or the connection failed.
     */
    public String runPsViaAdb() {
        if (!isAdbServiceRunningBlocking()) {
            Log.w(TAG, "runPsViaAdb: adbd not running");
            return null;
        }
        return adbClient.runPsCommand();
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void postAdbNotification() {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID_ADB_SERVICE,
                    context.getString(R.string.adb_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(context.getString(R.string.adb_notification_channel_desc));
            nm.createNotificationChannel(ch);
        }

        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.adb_notification_title))
                .setContentText(context.getString(R.string.adb_notification_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi);

        nm.notify(NOTIFICATION_ID_ADB_SERVICE, b.build());
    }

    private void cancelAdbNotification() {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID_ADB_SERVICE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Non-blocking TCP probe. Returns true if port is open on localhost. */
    private boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
