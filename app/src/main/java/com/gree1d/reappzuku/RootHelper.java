package com.gree1d.reappzuku;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.gree1d.reappzuku.AppConstants.*;
import static com.gree1d.reappzuku.PreferenceKeys.*;

/**
 * Manages ADB connectivity for rooted Android 10+ devices.
 *
 * <p>On Android 10+, SELinux prevents {@code ps -A -o rss,name} from returning
 * output when run via {@code su}. The same command works correctly in the ADB
 * shell SELinux context.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Detects whether Wireless Debugging is enabled by reading
 *       {@code service.adb.tls.port} via root.</li>
 *   <li>Writes our RSA public key directly to {@code /data/misc/adb/adb_keys}
 *       via root — bypassing the pairing flow entirely.</li>
 *   <li>Connects to the TLS port using our AndroidKeyStore certificate.
 *       adbd verifies us by matching the cert's public key against {@code adb_keys}.</li>
 * </ol>
 *
 * <p>The user only needs to enable <em>Wireless Debugging</em> once in
 * Developer Options — no pairing code required.</p>
 *
 * <h3>Lifecycle (managed by {@link MainActivity})</h3>
 * <ol>
 *   <li>Banner shown when {@link #checkNeedsAdbService} returns {@code true}.</li>
 *   <li>User taps "Включить отладку" → {@link #openWirelessDebuggingSettings}.</li>
 *   <li>User returns to app → {@link #tryConnect} runs automatically.</li>
 *   <li>On success: banner hidden, ongoing notification posted.</li>
 * </ol>
 */
public class RootHelper {

    private static final String TAG = "RootHelper";

    private final Context context;
    private final Handler handler;
    private final ShellManager shellManager;
    private final SharedPreferences prefs;
    private final LocalAdbClient adbClient;

    // ── Callback ──────────────────────────────────────────────────────────────

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

        // Restore saved port if we connected before
        int savedPort = prefs.getInt(KEY_ADB_TLS_PORT, 0);
        if (savedPort > 0) {
            adbClient.setTlsPort(savedPort);
        }
    }

    // ── Banner logic ──────────────────────────────────────────────────────────

    /**
     * Asynchronously decides whether the banner should be visible.
     * Returns {@code true} when: root + Android 10+ + ps doesn't work yet.
     */
    public void checkNeedsAdbService(ExecutorService executor, Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean needs = shellManager.hasRootAccess()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && !isConnectedBlocking();
            handler.post(() -> callback.accept(needs));
        });
    }

    /**
     * Synchronous check — must be called from a background thread.
     * Returns true if we have an active TLS port and ps returns output.
     */
    public boolean isConnectedBlocking() {
        int port = adbClient.getTlsPort();
        if (port <= 0) return false;
        // Quick validation: verify the port is still open
        return adbClient.testTlsConnection(port);
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    /**
     * Attempts to connect to Wireless Debugging:
     * <ol>
     *   <li>Reads the TLS port via {@code getprop service.adb.tls.port}.</li>
     *   <li>Writes our public key to {@code adb_keys} via root.</li>
     *   <li>Tests the TLS connection.</li>
     * </ol>
     * Posts callbacks on the main handler.
     */
    public void tryConnect(ExecutorService executor, AdbServiceCallback callback) {
        executor.execute(() -> {
            Log.d(TAG, "tryConnect: reading wireless debugging port");

            int port = getWirelessDebuggingPortBlocking();
            if (port <= 0) {
                Log.w(TAG, "Wireless Debugging not enabled (port=0)");
                handler.post(() -> callback.onFailed(
                        context.getString(R.string.adb_error_wd_not_enabled)));
                return;
            }
            Log.d(TAG, "Wireless Debugging port: " + port);

            // Register our key so adbd will trust our TLS cert
            adbClient.ensureKeyRegistered();
            sleep(300);

            // Test connection
            if (adbClient.testTlsConnection(port)) {
                adbClient.setTlsPort(port);
                prefs.edit()
                        .putBoolean(KEY_ADB_WIFI_RUNNING, true)
                        .putInt(KEY_ADB_TLS_PORT, port)
                        .apply();
                postConnectedNotification();
                Log.d(TAG, "Connected to adbd via TLS on port " + port);
                handler.post(callback::onStarted);
            } else {
                Log.w(TAG, "TLS connection test failed on port " + port);
                handler.post(() -> callback.onFailed(
                        context.getString(R.string.adb_error_connection_failed)));
            }
        });
    }

    /**
     * Clears the saved connection and cancels the notification.
     */
    public void disconnect(ExecutorService executor, AdbServiceCallback callback) {
        executor.execute(() -> {
            adbClient.setTlsPort(0);
            prefs.edit()
                    .putBoolean(KEY_ADB_WIFI_RUNNING, false)
                    .putInt(KEY_ADB_TLS_PORT, 0)
                    .apply();
            cancelConnectedNotification();
            handler.post(callback::onStopped);
        });
    }

    // ── ps via ADB shell ──────────────────────────────────────────────────────

    /**
     * Runs {@code ps -A -o rss,name …} via the ADB shell context.
     * Returns stdout, or {@code null} if not connected.
     * Must be called from a background thread.
     */
    public String runPsViaAdb() {
        if (adbClient.getTlsPort() <= 0) {
            Log.w(TAG, "runPsViaAdb: not connected");
            return null;
        }
        return adbClient.runPsCommand();
    }

    // ── Settings shortcut ─────────────────────────────────────────────────────

    /**
     * Opens the Wireless Debugging settings page directly (Android 11+),
     * falling back to general Developer Options on older versions.
     */
    public static void openWirelessDebuggingSettings(Context context) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — direct Wireless Debugging page
            intent = new Intent("android.settings.WIRELESS_DEBUG_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {
                // Some OEMs don't expose this action — fall through
            }
        }
        // Fallback: Developer Options
        intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open developer settings", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads the Wireless Debugging TLS port from system properties via root.
     * Returns 0 if not available.
     */
    private int getWirelessDebuggingPortBlocking() {
        // Primary: TLS port (Android 11+ Wireless Debugging)
        String portStr = shellManager.runShellCommandAndGetFullOutput(
                "getprop service.adb.tls.port");
        if (portStr != null) {
            portStr = portStr.trim();
            if (!portStr.isEmpty() && !portStr.equals("0")) {
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void postConnectedNotification() {
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

        NotificationCompat.Builder b =
                new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.adb_notification_title))
                        .setContentText(context.getString(R.string.adb_notification_text))
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(pi);

        nm.notify(NOTIFICATION_ID_ADB_SERVICE, b.build());
    }

    private void cancelConnectedNotification() {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID_ADB_SERVICE);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
