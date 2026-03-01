package com.northmendo.Appzuku;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.northmendo.Appzuku.PreferenceKeys.*;
import static com.northmendo.Appzuku.AppConstants.*;

public class AutoKillWorker extends Worker {
    private static final String UNIQUE_WORK_NAME = "AutoKillWorker";

    public AutoKillWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AutoKillWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);

        if (!prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false)) {
            return Result.success();
        }
        if (!prefs.getBoolean(KEY_PERIODIC_KILL_ENABLED, false)) {
            return Result.success();
        }
        if (ShappkyService.isRunning()) {
            return Result.success();
        }

        boolean ramThresholdEnabled = prefs.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);
        if (ramThresholdEnabled) {
            int threshold = prefs.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
            if (getCurrentRamUsagePercent() < threshold) {
                return Result.success();
            }
        }

        // Initialize components for background work
        Handler handler = new Handler(Looper.getMainLooper());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            ShellManager shellManager = new ShellManager(getApplicationContext(), handler, executor);
            BackgroundAppManager appManager = new BackgroundAppManager(getApplicationContext(), handler, executor,
                    shellManager);

            // Wait for root check or just proceed if Shizuku
            if (!shellManager.hasAnyShellPermission()) {
                // Try to wait a bit for root check or fail
                try {
                    Thread.sleep(ROOT_CHECK_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure();
                }
                if (!shellManager.hasAnyShellPermission()) {
                    return Result.failure();
                }
            }

            // Synchronous waiting for async kill
            CountDownLatch latch = new CountDownLatch(1);
            appManager.performAutoKill(latch::countDown);

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.retry();
            }

            // Prune old stats periodically
            long pruneThreshold = System.currentTimeMillis() - STATS_PRUNE_THRESHOLD_MS;
            com.northmendo.Appzuku.db.AppDatabase.getInstance(getApplicationContext())
                    .appStatsDao().deleteOldStats(pruneThreshold);

            return Result.success();
        } finally {
            // Fixed: Always shut down executor to prevent thread leaks
            executor.shutdownNow();
        }
    }

    private int getCurrentRamUsagePercent() {
        long totalRam = 0;
        long availableRam = 0;

        try (RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r")) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    totalRam = Long.parseLong(line.replaceAll("\\D+", ""));
                } else if (line.startsWith("MemAvailable:")) {
                    availableRam = Long.parseLong(line.replaceAll("\\D+", ""));
                    break;
                }
            }
        } catch (IOException | NumberFormatException e) {
            return 0;
        }

        if (totalRam <= 0) {
            return 0;
        }
        return (int) ((totalRam - availableRam) * 100 / totalRam);
    }
}
