package com.gree1d.reappzuku;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.gree1d.reappzuku.AppConstants.*;
import static com.gree1d.reappzuku.PreferenceKeys.*;

public class RootHelper {

    private static final String TAG = "RootHelper";

    private static volatile RootHelper instance;

    private final Context context;
    private final ShellManager shellManager;
    private final SharedPreferences prefs;
    private LocalAdbClient adbClient;

    private volatile AdbServiceCallback pendingCallback;
    private volatile int discoveredPairingPort = -1;

    public interface AdbServiceCallback {
        void onStarted();
        void onFailed(String reason);
        void onStopped();
    }

    public static RootHelper getInstance(Context context) {
        return instance;
    }

    public RootHelper(Context context, Handler handler, ShellManager shellManager) {
        this.context = context.getApplicationContext();
        this.shellManager = shellManager;
        this.prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        LocalAdbClient client = null;
        try {
            client = LocalAdbClient.getInstance(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to init LocalAdbClient: " + e.getMessage(), e);
        }
        this.adbClient = client;
        instance = this;
    }

    public void checkNeedsAdbService(ExecutorService executor, Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean connected = false;

            if (adbClient != null && adbClient.isConnected()) {
                connected = true;
            } else if (adbClient != null) {
                int savedPort = prefs.getInt(KEY_ADB_TLS_PORT, 0);
                if (savedPort > 0) {
                    connected = tryReconnect(savedPort);
                    if (!connected) {
                        Log.w(TAG, "Reconnect failed — need re-pairing");
                        prefs.edit()
                                .putInt(KEY_ADB_TLS_PORT, 0)
                                .putBoolean(KEY_ADB_WIFI_RUNNING, false)
                                .apply();
                    }
                }
            }

            boolean needs = !connected
                    && adbClient != null
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.accept(needs));
        });
    }

    private boolean tryReconnect(int savedPort) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean enabled = enableWirelessDebugging();
            if (!enabled) {
                Log.w(TAG, "Could not enable Wireless Debugging — trying saved port anyway");
            }
        }

        int port = savedPort;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int freshPort = getWirelessDebuggingPort();
            if (freshPort > 0) {
                port = freshPort;
                prefs.edit().putInt(KEY_ADB_TLS_PORT, port).apply();
            }
        }

        Log.d(TAG, "Reconnecting to adbd on 127.0.0.1:" + port);
        boolean connected = adbClient.connect("127.0.0.1", port);
        if (connected) {
            Log.d(TAG, "Reconnected successfully");
            postConnectedNotification();
        }
        return connected;
    }

    public void startServiceFlow(Context activityContext,
            ExecutorService executor, AdbServiceCallback callback) {
        this.pendingCallback = callback;
        this.discoveredPairingPort = -1;
        AdbPairingService.start(context);
        openWirelessDebuggingSettings(activityContext);
    }

    public void onPairingPortDiscovered(int port) {
        this.discoveredPairingPort = port;
        showPairingNotification(null);
    }

    public void pairAndConnect(String code) {
        if (adbClient == null) {
            Log.e(TAG, "ADB client not initialized");
            showPairingNotification(context.getString(R.string.adb_error_pair_failed));
            notifyCallback(false, "ADB client not initialized");
            return;
        }
        int pairingPort = discoveredPairingPort;
        if (pairingPort <= 0) {
            Log.e(TAG, "Pairing port not discovered yet");
            showPairingNotification(context.getString(R.string.adb_error_wd_not_enabled));
            notifyCallback(false, context.getString(R.string.adb_error_wd_not_enabled));
            return;
        }

        discoveredPairingPort = -1;
        boolean paired = adbClient.pair("127.0.0.1", pairingPort, code);
        AdbPairingService.stop(context);
        if (!paired) {
            showPairingNotification(context.getString(R.string.adb_error_pair_failed));
            notifyCallback(false, context.getString(R.string.adb_error_pair_failed));
            return;
        }

        int tlsPort = getWirelessDebuggingPort();
        if (tlsPort <= 0) {
            showPairingNotification(context.getString(R.string.adb_error_wd_not_enabled));
            notifyCallback(false, context.getString(R.string.adb_error_wd_not_enabled));
            return;
        }

        boolean connected = adbClient.connect("127.0.0.1", tlsPort);
        if (!connected) {
            showPairingNotification(context.getString(R.string.adb_error_connection_failed));
            notifyCallback(false, context.getString(R.string.adb_error_connection_failed));
            return;
        }

        prefs.edit()
                .putBoolean(KEY_ADB_WIFI_RUNNING, true)
                .putInt(KEY_ADB_TLS_PORT, tlsPort)
                .apply();
        cancelPairingNotification();
        postConnectedNotification();
        Log.d(TAG, "Connected via TLS on port " + tlsPort);
        notifyCallback(true, null);
    }

    public boolean isAdbConnected() {
        return adbClient != null && adbClient.isConnected();
    }

    public String runPsViaAdb(String command) {
        if (adbClient == null || !adbClient.isConnected()) {
            Log.w(TAG, "runPsViaAdb: not connected");
            return null;
        }
        return adbClient.runShellCommand(command);
    }

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
        intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); } catch (Exception ignored) {}
    }

    private boolean enableWirelessDebugging() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            int current = Settings.Global.getInt(resolver, "adb_wifi_enabled", 0);
            if (current != 0) return true;

            Settings.Global.putInt(resolver, "adb_wifi_enabled", 1);

            for (int i = 0; i < 5; i++) {
                SystemClock.sleep(500);
                if (getWirelessDebuggingPort() > 0) return true;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "enableWirelessDebugging failed: " + e.getMessage());
            return false;
        }
    }

    public void showPairingNotification(String errorMessage) {
        NotificationManager nm = nm();
        if (nm == null) return;
        ensureChannel(nm);

        RemoteInput remoteInput = new RemoteInput.Builder(AdbPairingReceiver.KEY_CODE_INPUT)
                .setLabel(context.getString(R.string.adb_notif_input_hint))
                .build();

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

        nm.notify(NOTIFICATION_ID_ADB_SERVICE,
                new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.adb_notif_pair_title))
                        .setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setOngoing(true)
                        .addAction(action)
                        .build());
    }

    public void showPairingProgressNotification() {
        NotificationManager nm = nm();
        if (nm == null) return;
        ensureChannel(nm);

        nm.notify(NOTIFICATION_ID_ADB_SERVICE,
                new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.adb_notif_pair_title))
                        .setContentText(context.getString(R.string.adb_banner_btn_start_loading))
                        .setProgress(0, 0, true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setOngoing(true)
                        .build());
    }

    private void cancelPairingNotification() {
        NotificationManager nm = nm();
        if (nm != null) nm.cancel(NOTIFICATION_ID_ADB_SERVICE);
    }

    private void postConnectedNotification() {
        NotificationManager nm = nm();
        if (nm == null) return;
        ensureChannel(nm);

        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context, 1, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        nm.notify(NOTIFICATION_ID_ADB_CONNECTED,
                new NotificationCompat.Builder(context, CHANNEL_ID_ADB_SERVICE)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(context.getString(R.string.adb_notification_title))
                        .setContentText(context.getString(R.string.adb_notification_text))
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(pi)
                        .build());
    }

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
            ch.setDescription(context.getString(R.string.adb_notification_channel_desc));
            nm.createNotificationChannel(ch);
        }
    }

    private NotificationManager nm() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
