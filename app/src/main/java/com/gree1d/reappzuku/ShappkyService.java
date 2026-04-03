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
    private KillTriggerReceiver screenOffReceiver;

    // True if background restricted apps are currently frozen
    private boolean isFrozen = false;

    public static boolean isRunning() {
        return isRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        shellManager = new ShellManager(this, handler, executor);
        appManager = new BackgroundAppManager(this, handler, executor, shellManager);
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
                .setContentTitle("Appzuku Service")
                .setContentText("Monitoring background apps...")
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
                            appManager.performAutoKill(null);
                        }
                    } else {
                        appManager.performAutoKill(null);
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
                boolean periodicKillEnabled = prefs.getBoolean(KEY_PERIODIC_KILL_ENABLED, false);
                boolean ramThresholdEnabled = prefs.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);

                if (periodicKillEnabled) {
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
                    // Periodic kill disabled, just schedule next check
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
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID_SERVICE, "Appzuku Foreground Service",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}
