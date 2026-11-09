package com.gree1d.reappzuku.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.gree1d.reappzuku.core.KillIntervalPolicy;
import com.gree1d.reappzuku.core.ShellManager;
import com.gree1d.reappzuku.core.App;
import com.gree1d.reappzuku.manager.BackgroundAppManager;
import com.gree1d.reappzuku.manager.AutoKillManager;
import com.gree1d.reappzuku.db.AppPolicy;

import static com.gree1d.reappzuku.core.PreferenceKeys.*;
import static com.gree1d.reappzuku.core.AppConstants.*;

public class AutoKillWorker extends Worker {
    private static final String UNIQUE_WORK_NAME = "AutoKillWorker";
    private static final String KEY_SOURCE = "kill_source";

    public AutoKillWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    public static void schedule(Context context, String source) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        int intervalMs = prefs.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        long clampedInterval = KillIntervalPolicy.workerIntervalMinutes(intervalMs);

        Data inputData = new Data.Builder()
                .putString(KEY_SOURCE, source)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AutoKillWorker.class, clampedInterval, TimeUnit.MINUTES)
                .setInputData(inputData)
                .build();
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

        boolean autoKillEnabled = prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false);
        boolean presetActive = prefs.getInt(KEY_ACTIVE_PRESET, 0) != 0;
        if (!autoKillEnabled && !presetActive) return Result.success();
        if (!prefs.getBoolean(KEY_PERIODIC_KILL_ENABLED, false)) return Result.success();
        if (ShappkyService.isRunning()) return Result.success();

        boolean ramThresholdEnabled = prefs.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);
        if (ramThresholdEnabled) {
            int threshold = prefs.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
            if (getCurrentRamUsagePercent() < threshold) return Result.success();
        }

        App app = (App) getApplicationContext();
        Handler handler = app.getSharedHandler();
        ExecutorService executor = app.getSharedExecutor();

        try {
            ShellManager shellManager = app.getShellManager();
            BackgroundAppManager appManager = new BackgroundAppManager(
                    getApplicationContext(), handler, executor, app.getShellExecutor(), shellManager);
            AutoKillManager autoKillManager = new AutoKillManager(
                    getApplicationContext(), handler, executor, shellManager, appManager.getCurrentAppsList());

            if (!shellManager.hasAnyShellPermission()) {
                try { Thread.sleep(ROOT_CHECK_TIMEOUT_MS); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure();
                }
                if (!shellManager.hasAnyShellPermission()) return Result.failure();
            }

            CountDownLatch latch = new CountDownLatch(1);


            String source = getInputData().getString(KEY_SOURCE);
            if (source == null) source = "Periodic Kill";
            long trigger = ramThresholdEnabled
                    ? AppPolicy.TRIGGER_RAM_THRESHOLD
                    : AppPolicy.TRIGGER_PERIODIC;
            autoKillManager.performAutoKill(latch::countDown, source, trigger);

            boolean finished = latch.await(60, TimeUnit.SECONDS);
            if (!finished) {

                return Result.retry();
            }

            return Result.success();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
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
