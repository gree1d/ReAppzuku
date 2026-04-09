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
import androidx.core.app.RemoteInput;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.gree1d.reappzuku.AppConstants.*;
import static com.gree1d.reappzuku.PreferenceKeys.*;

/**
 * Manages ADB Wireless Debugging connectivity for Android 10+ rooted devices.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Banner shown when {@link #checkNeedsAdbService} returns {@code true}.</li>
 *   <li>User taps "Запустить сервис" → {@link #showPairingNotification(String)} is called
 *       and the user is sent to Wireless Debugging settings.</li>
 *   <li>User taps "Подключить устройство с помощью ввода кода" in Android settings,
 *       gets a port + 6-digit code, enters {@code PORT:CODE} in the notification.</li>
 *   <li>{@link AdbPairingReceiver} fires → {@link #pairAndConnect} runs:
 *       pairs via SPAKE2, reads main TLS port, connects.</li>
 *   <li>On success: banner hidden, ongoing "connected" notification shown.</li>
 * </ol>
 *
 * <p>Uses a singleton pattern so {@link AdbPairingReceiver} can access the instance
 * without having to re-construct it.</p>
 */
public class RootHelper {

    private static final String TAG = "RootHelper";

    // Singleton
    private static volatile RootHelper instance;

    private final Context context;
    private final ShellManager shellManager;
    private final SharedPreferences prefs;
    private final LocalAdbClient adbClient;

    /** Callback to update MainActivity UI after pairing result. */
    private volatile AdbServiceCallback pendingCallback;

    /** Pairing port discovered via mDNS in AdbPairingService. -1 until discovered. */
    private volatile int discoveredPairingPort = -1;

    // ── Callback ──────────────────────────────────────────────────────────────

    public interface AdbServiceCallback {
        void onStarted();
        void onFailed(String reason);
        void onStopped();
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static RootHelper getInstance(Context context) {
        return instance;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public RootHelper(Context context, Handler handler, ShellManager shellManager) {
        this.context = context.getApplicationContext();
        this.shellManager = shellManager;
        this.prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.adbClient = new LocalAdbClient(context);
        instance = this;
    }

    // ── Banner logic ──────────────────────────────────────────────────────────

    /**
     * Asynchronously decides whether the banner should be visible.
     * Returns true when: root + Android 10+ + not yet connected.
     */
    public void checkNeedsAdbService(ExecutorService executor, Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean needs = shellManager.hasRootAccess()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && !adbClient.isConnected();
            android.os.Handler main = new android.os.Handler(
                    android.os.Looper.getMainLooper());
            main.post(() -> callback.accept(needs));
        });
    }

    // ── Entry point from MainActivity ─────────────────────────────────────────

    /**
     * Called when user taps "Запустить сервис" in the banner.
     * Starts AdbPairingService (foreground) which runs mDNS discovery,
     * then opens Wireless Debugging settings.
     */
    public void startServiceFlow(Context activityContext,
            ExecutorService executor, AdbServiceCallback callback) {
        this.pendingCallback = callback;
        this.discoveredPairingPort = -1;
        // Start foreground service FIRST — it will keep NsdManager alive in background
        AdbPairingService.start(context);
        // Then open settings so the user taps "Pair device with pairing code"
        openWirelessDebuggingSettings(activityContext);
    }

    /**
     * Called by {@link AdbPairingService} when the pairing port is discovered via mDNS.
     * Shows the code-input notification to the user.
     */
    public void onPairingPortDiscovered(int port) {
        this.discoveredPairingPort = port;
        showPairingNotification(null); // show code input field
    }

    // ── Pairing (called from AdbPairingReceiver) ──────────────────────────────

    /**
     * Pairs using the 6-digit code. Pairing port was set by {@link #onPairingPortDiscovered}.
     * Must be called from a background thread.
     */
    public void pairAndConnect(String code) {
        int pairingPort = discoveredPairingPort;
        if (pairingPort <= 0) {
            Log.e(TAG, "Pairing port not discovered yet");
            showPairingNotification(
                    context.getString(R.string.adb_error_wd_not_enabled));
            notifyCallback(false,
                    context.getString(R.string.adb_error_wd_not_enabled));
            return;
        }
        String host = "127.0.0.1";
        // 1. Pair via SPAKE2
        boolean paired = adbClient.pair(host, pairingPort, code);
        if (!paired) {
            Log.e(TAG, "Pairing failed");
            AdbPairingService.stop(context);
            showPairingNotification(
                    context.getString(R.string.adb_error_pair_failed));
            notifyCallback(false,
                    context.getString(R.string.adb_error_pair_failed));
            return;
        }

        // 2. Read main TLS port
        int tlsPort = getWirelessDebuggingPort();
        if (tlsPort <= 0) {
            Log.e(TAG, "Cannot read TLS port");
            AdbPairingService.stop(context);
            showPairingNotification(
                    context.getString(R.string.adb_error_wd_not_enabled));
            notifyCallback(false,
                    context.getString(R.string.adb_error_wd_not_enabled));
            return;
        }

        // 3. Connect
        boolean connected = adbClient.connect("127.0.0.1", tlsPort);
        if (!connected) {
            Log.e(TAG, "Connection failed");
            AdbPairingService.stop(context);
            showPairingNotification(
                    context.getString(R.string.adb_error_connection_failed));
            notifyCallback(false,
                    context.getString(R.string.adb_error_connection_failed));
            return;
        }

        // 4. Success
        AdbPairingService.stop(context);
        prefs.edit()
                .putBoolean(KEY_ADB_WIFI_RUNNING, true)
                .putInt(KEY_ADB_TLS_PORT, tlsPort)
                .apply();
        cancelPairingNotification();
        postConnectedNotification();
        Log.d(TAG, "Connected to adbd on port " + tlsPort);
        notifyCallback(true, null);
    }

    // ── ps via ADB shell ──────────────────────────────────────────────────────

    /**
     * Runs the ps command via ADB shell context.
     * Must be called from a background thread.
     */
    public String runPsViaAdb() {
        if (!adbClient.isConnected()) {
            Log.w(TAG, "runPsViaAdb: not connected");
            return null;
        }
        return adbClient.runPsCommand();
    }

    // ── Settings shortcut ─────────────────────────────────────────────────────

    public static void openWirelessDebuggingSettings(Context context) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intent = new Intent("android.settings.WIRELESS_DEBUG_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); } catch (Exception ignored) {}
    }

    // ── Notification: pairing input ───────────────────────────────────────────

    /**
     * Shows (or updates) the notification with a RemoteInput field
     * for entering {@code PORT:CODE}.
     *
     * @param errorMessage shown below the input hint when non-null
     */
    public void showPairingNotification(String errorMessage) {
        NotificationManager nm = nm();
        if (nm == null) return;
        ensureChannel(nm);

        // RemoteInput — text field in the notification
        RemoteInput remoteInput = new RemoteInput.Builder(AdbPairingReceiver.KEY_CODE_INPUT)
                .setLabel(context.getString(R.string.adb_notif_input_hint))
                .build();

        // Intent for the "Подключить" action button
        Intent pairIntent = new Intent(context, AdbPairingReceiver.class)
                .setAction(AdbPairingReceiver.ACTION_PAIR);
        PendingIntent pairPi = PendingIntent.getBroadcast(
                context, 0, pairIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Action action =
                new NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_send,
                        context.getString(R.string.adb_notif_btn_connect),
                        pairPi)
                        .addRemoteInput(remoteInput)
                        .build();

        String text = errorMessage != null
                ? errorMessage
                : context.getString(R.string.adb_notif_pair_text);

        NotificationCompat.Builder b =
                new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.adb_notif_pair_title))
                        .setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setOngoing(true)
                        .addAction(action);

        nm.notify(NOTIFICATION_ID_ADB_SERVICE, b.build());
    }

    /** Updates the notification to show a progress/connecting state. */
    public void showPairingProgressNotification() {
        NotificationManager nm = nm();
        if (nm == null) return;
        ensureChannel(nm);

        NotificationCompat.Builder b =
                new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.adb_notif_pair_title))
                        .setContentText(context.getString(R.string.adb_banner_btn_start_loading))
                        .setProgress(0, 0, true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setOngoing(true);

        nm.notify(NOTIFICATION_ID_ADB_SERVICE, b.build());
    }

    private void cancelPairingNotification() {
        NotificationManager nm = nm();
        if (nm != null) nm.cancel(NOTIFICATION_ID_ADB_SERVICE);
    }

    // ── Notification: connected (ongoing) ────────────────────────────────────

    private void postConnectedNotification() {
        NotificationManager nm = nm();
        if (nm == null) return;
        ensureChannel(nm);

        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context, 1, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b =
                new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.adb_notification_title))
                        .setContentText(context.getString(R.string.adb_notification_text))
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(pi);

        nm.notify(NOTIFICATION_ID_ADB_CONNECTED, b.build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int getWirelessDebuggingPort() {
        String portStr = shellManager.runShellCommandAndGetFullOutput(
                "getprop service.adb.tls.port");
        if (portStr == null) return 0;
        portStr = portStr.trim();
        if (portStr.isEmpty() || portStr.equals("0")) return 0;
        try { return Integer.parseInt(portStr); }
        catch (NumberFormatException e) { return 0; }
    }

    private void notifyCallback(boolean success, String error) {
        AdbServiceCallback cb = pendingCallback;
        if (cb == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (success) cb.onStarted();
            else cb.onFailed(error != null ? error : "Unknown error");
        });
    }

    private void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID_ADB_SERVICE,
                    context.getString(R.string.adb_notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(
                    context.getString(R.string.adb_notification_channel_desc));
            nm.createNotificationChannel(ch);
        }
    }

    private NotificationManager nm() {
        return (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
