package com.northmendo.Appzuku;

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

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.IntentFilter;

import static com.northmendo.Appzuku.PreferenceKeys.*;
import static com.northmendo.Appzuku.AppConstants.*;

/**
 * A foreground service that periodically kills background applications
 */
public class ShappkyService extends Service {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean isRunning = false;

    private ShellManager shellManager;
    private BackgroundAppManager appManager;
    private KillTriggerReceiver screenOffReceiver;

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

        // Register screen off receiver
        screenOffReceiver = new KillTriggerReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOffReceiver, filter);

        scheduleNextKill();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "TRIGGER_KILL".equals(intent.getAction())) {
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
        }
        return START_STICKY;
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
