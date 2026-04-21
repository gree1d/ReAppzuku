package com.gree1d.reappzuku;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.IntentFilter;

import static com.gree1d.reappzuku.PreferenceKeys.*;
import static com.gree1d.reappzuku.AppConstants.*;

/**
 * A foreground service that periodically kills background applications
 */
public class ShappkyService extends Service {

    private static final String TAG = "ShappkyService";
    static final String ACTION_IDLE_FREEZE = "com.gree1d.reappzuku.IDLE_FREEZE";
    private static final int FREEZE_ALARM_REQUEST_CODE = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean isRunning = false;

    private ShellManager shellManager;
    private BackgroundAppManager appManager;
    private BatteryStatsManager batteryStatsManager;
    private KillTriggerReceiver screenOffReceiver;

    // True if background restricted apps are currently frozen
    private boolean isFrozen = false;

    // True if Shizuku lost notification is currently shown
    private boolean shizukuLostNotificationShown = false;

    public static boolean isRunning() {
        return isRunning;
    }

    /**
     * Returns true if non-critical (informational) notifications are allowed
     * based on the user's notification mode preference.
     */
    private boolean isAllNotificationsEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        int mode = prefs.getInt(KEY_NOTIFICATION_MODE, NOTIFICATION_MODE_ALL);
        return mode == NOTIFICATION_MODE_ALL;
    }

    /**
     * Updates the persistent foreground notification text.
     * Called after each auto-kill to show how many apps were stopped.
     * Only updates if the user has enabled all notifications.
     */
    public static void updateNotification(Context context, String title, String text) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        int mode = prefs.getInt(KEY_NOTIFICATION_MODE, NOTIFICATION_MODE_ALL);
        if (mode != NOTIFICATION_MODE_ALL) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shappky)
                .setOngoing(true)
                .setSilent(true);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID_SERVICE, builder.build());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        shellManager = new ShellManager(this, handler, executor);
        appManager = new BackgroundAppManager(this, handler, executor, shellManager);
        batteryStatsManager = new BatteryStatsManager(this, shellManager);
        createNotificationChannel();

        // The foreground service notification is always shown (critical — keeps the service alive).
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(R.drawable.ic_shappky)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID_SERVICE, notification);
        isRunning = true;

        // Register screen off/on receiver
        screenOffReceiver = new KillTriggerReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenOffReceiver, filter);

        scheduleNextKill();
        
        cancelShizukuLostNotification();
        scheduleShizukuCheck();
        scheduleSnapshotCollection();

        appManager.reapplySavedBackgroundRestrictions(null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (action == null) return START_STICKY;

        switch (action) {
            case "TRIGGER_KILL":
                // Screen lock kill - respect RAM threshold if enabled
                executor.execute(() -> {
                    SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
                    boolean ramThresholdEnabled = prefs.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);
                    if (ramThresholdEnabled) {
                        int threshold = prefs.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
                        if (getCurrentRamUsagePercent() >= threshold) {
                            appManager.performAutoKill(() -> KillTriggerReceiver.releaseAutoKillWakeLock());
                        } else {
                          
                            KillTriggerReceiver.releaseAutoKillWakeLock();
                        }
                    } else {
                        appManager.performAutoKill(() -> KillTriggerReceiver.releaseAutoKillWakeLock());
                    }
                });
                break;

            case "SCREEN_OFF":
                // Schedule exact alarm to fire after idle threshold
                // AlarmManager.setExactAndAllowWhileIdle works even in Doze mode
                if (appManager.isSleepModeEnabled()) {
                    scheduleIdleFreezeAlarm();
                    long delayMs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                            .getLong(KEY_SLEEP_MODE_DELAY, DEFAULT_SLEEP_MODE_DELAY_MS);
                    Log.d(TAG, "Idle freeze alarm scheduled (" + (delayMs / 60000) + " min)");
                }
                break;

            case "SCREEN_ON":
                // Delay 1500ms to allow HyperOS keyguard state to update after unlock
                handler.postDelayed(() -> {
                    android.app.KeyguardManager km = (android.app.KeyguardManager)
                            getSystemService(Context.KEYGUARD_SERVICE);
                    boolean isLocked = km != null && km.isKeyguardLocked();
                    if (!isLocked) {
                        cancelIdleFreezeAlarm();
                        if (isFrozen) {
                            Log.d(TAG, "Screen on after idle freeze, unfreezing apps");
                            isFrozen = false;
                            appManager.unfreezeBackgroundRestrictedApps(null);
                        } else {
                            Log.d(TAG, "Screen on before idle threshold, alarm cancelled");
                        }
                    } else {
                        Log.d(TAG, "Screen on but keyguard still active, ignoring");
                    }
                }, 1500);
                break;

            case "IDLE_FREEZE":
                // Triggered by KillTriggerReceiver when AlarmManager alarm fires
                if (!appManager.isSleepModeEnabled()) {
                    Log.d(TAG, "Sleep mode disabled, skipping freeze");
                    break;
                }
                Log.d(TAG, "Idle threshold reached, freezing background restricted apps");
                appManager.freezeBackgroundRestrictedApps(() -> {
                    isFrozen = true;
                    Log.d(TAG, "Apps frozen successfully");
                });
                break;
        }

        return START_STICKY;
    }

    /**
     * Periodically checks Shizuku availability.
     * If Shizuku permission is lost, shows a persistent notification (always critical — shown in any mode).
     * Cancels the notification once access is restored.
     * Root users are not affected — check is skipped if root is available.
     */
    private void scheduleShizukuCheck() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                // Root users don't rely on Shizuku — skip check
                if (shellManager.hasRootAccess()) {
                    handler.postDelayed(this, SHIZUKU_POLL_INTERVAL_MS);
                    return;
                }

                boolean shizukuOk = shellManager.hasShizukuPermission();

                if (!shizukuOk) {
                    if (!shizukuLostNotificationShown) {
                        Log.w(TAG, "Shizuku permission lost, sending notification");
                        shizukuLostNotificationShown = true;
                    }
                   
                    sendShizukuLostNotification();
                } else {
                    if (shizukuLostNotificationShown) {
                        Log.d(TAG, "Shizuku permission restored, cancelling notification");
                        shizukuLostNotificationShown = false;
                    }
                    
                    cancelShizukuLostNotification();
                }

                handler.postDelayed(this, SHIZUKU_POLL_INTERVAL_MS);
            }
        }, 0); 
    }

    /**
     * Sends a persistent notification informing the user that Shizuku access is lost.
     * This is a critical notification — always shown regardless of notification mode.
     */
    private void sendShizukuLostNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ACTIONS)
                .setContentTitle(getString(R.string.service_shizuku_lost_title))
                .setContentText(getString(R.string.service_shizuku_lost_text))
                .setSmallIcon(R.drawable.ic_shappky)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID_SHIZUKU_LOST, builder.build());
        }
    }

    /**
     * Cancels the Shizuku lost notification.
     */
    private void cancelShizukuLostNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID_SHIZUKU_LOST);
        }
    }

    /**
     * Schedules an exact alarm to trigger freeze after the configured delay.
     * Uses setExactAndAllowWhileIdle to work even in Doze mode.
     */
    private void scheduleIdleFreezeAlarm() {
        cancelIdleFreezeAlarm();
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        long delayMs = prefs.getLong(KEY_SLEEP_MODE_DELAY, DEFAULT_SLEEP_MODE_DELAY_MS);
        PendingIntent pendingIntent = getFreezeAlarmIntent();
        long triggerAt = System.currentTimeMillis() + delayMs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    /**
     * Cancels the pending freeze alarm if it exists.
     */
    private void cancelIdleFreezeAlarm() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        alarmManager.cancel(getFreezeAlarmIntent());
        Log.d(TAG, "Idle freeze alarm cancelled");
    }

    private PendingIntent getFreezeAlarmIntent() {
        Intent intent = new Intent(this, KillTriggerReceiver.class);
        intent.setAction(ACTION_IDLE_FREEZE);
        return PendingIntent.getBroadcast(
                this,
                FREEZE_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static final long SNAPSHOT_INTERVAL_MS = 15 * 60 * 1000L; // 15 minutes

    /**
     * Periodically collects battery/RAM snapshots so that Statistics history
     * is populated even when the user never opens the Statistics screen.
     * Fires every 15 minutes while the service is running.
     */
    private void scheduleSnapshotCollection() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                batteryStatsManager.takeSnapshotAsync(null);
                handler.postDelayed(this, SNAPSHOT_INTERVAL_MS);
            }
        }, SNAPSHOT_INTERVAL_MS);
    }

    private void scheduleNextKill() {
        if (!isRunning)
            return;

        SharedPreferences prefs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        int killInterval = prefs.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);

        handler.postDelayed(() -> {
            if (!isRunning)
                return;

            // Move logic to background thread to avoid Main Thread I/O
            executor.execute(() -> {
                boolean autoKillEnabled = prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false);
                boolean periodicKillEnabled = prefs.getBoolean(KEY_PERIODIC_KILL_ENABLED, false);
                boolean ramThresholdEnabled = prefs.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);

                if (autoKillEnabled && periodicKillEnabled) {
                    if (ramThresholdEnabled) {
                        int threshold = prefs.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
                        if (getCurrentRamUsagePercent() >= threshold) {
                            appManager.performAutoKill(() -> handler.post(this::scheduleNextKill));
                        } else {
                            handler.post(this::scheduleNextKill);
                        }
                    } else {
                        appManager.performAutoKill(() -> handler.post(this::scheduleNextKill));
                    }
                } else {
                    handler.post(this::scheduleNextKill);
                }
            });
        }, killInterval);
    }

    private int getCurrentRamUsagePercent() {
        try (java.io.RandomAccessFile reader = new java.io.RandomAccessFile("/proc/meminfo", "r")) {
            String load = reader.readLine();
            long totalRam = Long.parseLong(load.replaceAll("\\D+", ""));
            load = reader.readLine(); // Free
            load = reader.readLine(); // Available
            long availableRam = Long.parseLong(load.replaceAll("\\D+", ""));
            return (int) ((totalRam - availableRam) * 100 / totalRam);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        cancelIdleFreezeAlarm();
        cancelShizukuLostNotification();
        if (screenOffReceiver != null) {
            unregisterReceiver(screenOffReceiver);
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID_SERVICE,
                    getString(R.string.service_channel_foreground_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(serviceChannel);

            NotificationChannel actionsChannel = new NotificationChannel(
                    CHANNEL_ID_ACTIONS,
                    getString(R.string.service_channel_actions_name),
                    NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(actionsChannel);
        }
    }
}
