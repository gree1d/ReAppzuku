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
import androidx.core.app.RemoteInput;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.gree1d.reappzuku.AppConstants.*;
import static com.gree1d.reappzuku.PreferenceKeys.*;

/**
 * Manages ADB Wireless Debugging connectivity for Android 11+.
 *
 * <h3>Механизм (как Shizuku)</h3>
 * <ol>
 *   <li><b>Первый запуск:</b> пользователь проходит pairing через mDNS + код.
 *       После успешного connect — выдаём себе WRITE_SECURE_SETTINGS через ADB shell,
 *       сразу отключаем Wireless Debugging (соединение остаётся живым),
 *       сохраняем TLS-порт в prefs.</li>
 *   <li><b>Каждый последующий запуск:</b> у нас есть WRITE_SECURE_SETTINGS.
 *       Программно включаем Wireless Debugging → connect("127.0.0.1", savedPort) →
 *       сразу выключаем Wireless Debugging. Соединение остаётся живым.
 *       Пользователь ничего не видит, баннер не показывается.</li>
 *   <li><b>После перезагрузки телефона:</b> TLS-порт меняется — connect упадёт.
 *       Сбрасываем prefs, показываем баннер для повторного pairing.</li>
 * </ol>
 */
public class RootHelper {

    private static final String TAG = "RootHelper";

    private static volatile RootHelper instance;

    private final Context context;
    private final ShellManager shellManager;
    private final SharedPreferences prefs;
    private LocalAdbClient adbClient;

    private volatile AdbServiceCallback pendingCallback;
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
        LocalAdbClient client = null;
        try {
            client = LocalAdbClient.getInstance(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to init LocalAdbClient: " + e.getMessage(), e);
        }
        this.adbClient = client;
        instance = this;
    }

    // ── Banner logic ──────────────────────────────────────────────────────────

    /**
     * Решает показывать ли баннер.
     *
     * Если у нас есть WRITE_SECURE_SETTINGS и сохранённый порт — молча
     * переподключаемся по схеме Shizuku: включаем WD → connect → выключаем WD.
     * Баннер показывается только если это не удалось или pairing ещё не делали.
     */
    public void checkNeedsAdbService(ExecutorService executor, Consumer<Boolean> callback) {
        executor.execute(() -> {
            boolean connected = false;

            if (adbClient != null && adbClient.isConnected()) {
                connected = true;
            } else if (adbClient != null) {
                int savedPort = prefs.getInt(KEY_ADB_TLS_PORT, 0);
                if (savedPort > 0) {
                    // adbd уже в TCP-режиме на порту 5555 — просто подключаемся.
                    // Pairing не нужен — ключ уже авторизован в keystore.
                    Log.d(TAG, "Reconnecting to adbd on 127.0.0.1:" + savedPort);
                    connected = adbClient.connect("127.0.0.1", savedPort);
                    if (connected) {
                        Log.d(TAG, "Reconnected successfully");
                        postConnectedNotification();
                    } else {
                        // Скорее всего телефон перезагрузился — adbd сбросился в USB-режим
                        Log.w(TAG, "Reconnect failed — device likely rebooted");
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

    /**
     * Shizuku-style тихое переподключение без участия пользователя.
     * Требует WRITE_SECURE_SETTINGS.
     *
     * 1. Включаем Wireless Debugging программно
     * 2. connect() — ключ уже в keystore, pairing не нужен
     * 3. Сразу выключаем Wireless Debugging — соединение не рвётся
     */
    // ── Entry point from MainActivity ─────────────────────────────────────────

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

    // ── Pairing ───────────────────────────────────────────────────────────────

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

        // 1. Pair via SPAKE2
        boolean paired = adbClient.pair("127.0.0.1", pairingPort, code);
        if (!paired) {
            AdbPairingService.stop(context);
            showPairingNotification(context.getString(R.string.adb_error_pair_failed));
            notifyCallback(false, context.getString(R.string.adb_error_pair_failed));
            return;
        }

        // 2. Читаем TLS-порт Wireless Debugging
        int tlsPort = getWirelessDebuggingPort();
        if (tlsPort <= 0) {
            AdbPairingService.stop(context);
            showPairingNotification(context.getString(R.string.adb_error_wd_not_enabled));
            notifyCallback(false, context.getString(R.string.adb_error_wd_not_enabled));
            return;
        }

        // 3. Подключаемся по TLS-порту Wireless Debugging
        boolean connected = adbClient.connect("127.0.0.1", tlsPort);
        if (!connected) {
            AdbPairingService.stop(context);
            showPairingNotification(context.getString(R.string.adb_error_connection_failed));
            notifyCallback(false, context.getString(R.string.adb_error_connection_failed));
            return;
        }

        // 4. КЛЮЧЕВОЙ ШАГ: отправляем transport-команду "tcpip:5555".
        //    adbd перезапускается и начинает слушать 127.0.0.1:5555 независимо от Wi-Fi.
        //    После этого Wireless Debugging больше не нужен.
        Log.d(TAG, "Sending tcpip:5555 transport command");
        adbClient.switchToTcpIp();
        try { adbClient.disconnect(); } catch (Exception ignored) {}

        // 5. Даём adbd 3 с перезапуститься в TCP-режиме и принять ключ авторизации
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        // 6. Переподключаемся на фиксированный loopback-порт 5555.
        //    Ключ уже авторизован — повторный pairing не нужен никогда.
        boolean reconnected = adbClient.connect("127.0.0.1", 5555);
        if (!reconnected) {
            AdbPairingService.stop(context);
            showPairingNotification(context.getString(R.string.adb_error_connection_failed));
            notifyCallback(false, context.getString(R.string.adb_error_connection_failed));
            return;
        }

        // 7. Успех. Сохраняем порт 5555 — при следующих запусках просто connect(5555)
        AdbPairingService.stop(context);
        prefs.edit()
                .putBoolean(KEY_ADB_WIFI_RUNNING, true)
                .putInt(KEY_ADB_TLS_PORT, 5555)
                .apply();
        cancelPairingNotification();
        postConnectedNotification();
        Log.d(TAG, "Connected on loopback 127.0.0.1:5555 — survives Wi-Fi off and app restart");
        notifyCallback(true, null);
    }

    // ── ps via ADB shell ──────────────────────────────────────────────────────

    public boolean isAdbConnected() {
        return adbClient != null && adbClient.isConnected();
    }

    /**
     * Runs an arbitrary shell command via ADB (UID 2000 / shell).
     * Use this instead of root shell on Android 10+ where su SELinux context
     * blocks ps -A output.
     * Must be called from a background thread.
     */
    public String runPsViaAdb(String command) {
        if (adbClient == null || !adbClient.isConnected()) {
            Log.w(TAG, "runPsViaAdb: not connected");
            return null;
        }
        return adbClient.runShellCommand(command);
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
        intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); } catch (Exception ignored) {}
    }

    // ── Notifications ─────────────────────────────────────────────────────────

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

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Читает текущий TLS-порт Wireless Debugging через shell (getprop).
     */
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
