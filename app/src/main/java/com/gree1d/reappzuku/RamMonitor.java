package com.gree1d.reappzuku;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.gree1d.reappzuku.AppConstants.RAM_MONITOR_UPDATE_INTERVAL_MS;

public class RamMonitor {
    private static final String TAG = "RamMonitor";

    private final Context context;
    private final Handler handler;
    private final ProgressBar ramUsageBar;
    private final TextView ramUsageText;
    private final ExecutorService executor;
    private volatile boolean isMonitoring = false;
    private Runnable monitorRunnable;

    public RamMonitor(Context context, Handler handler, ProgressBar ramUsageBar, TextView ramUsageText) {
        this.context = context;
        this.handler = handler;
        this.ramUsageBar = ramUsageBar;
        this.ramUsageText = ramUsageText;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void startMonitoring() {
        if (isMonitoring) {
            return;
        }

        isMonitoring = true;
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isMonitoring) {
                    return;
                }

                executor.execute(() -> {
                    final RamInfo ramInfo = readRamUsage();

                    handler.post(() -> {
                        if (!isMonitoring)
                            return;

                        if (ramInfo != null && ramInfo.totalMb > 0) {
                            ramUsageBar.setMax((int) ramInfo.totalMb);
                            ramUsageBar.setProgress((int) ramInfo.usedMb);
                            ramUsageText.setText(context.getString(R.string.ram_usage,
                                    ramInfo.usedMb, ramInfo.totalMb));
                        } else {
                            ramUsageText.setText(context.getString(R.string.ram_usage_unavailable));
                        }
                    });
                });

                if (isMonitoring) {
                    handler.postDelayed(this, RAM_MONITOR_UPDATE_INTERVAL_MS);
                }
            }
        };

        handler.post(monitorRunnable);
    }

    private RamInfo readRamUsage() {
        try (RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r")) {
            String line;
            long memTotal = 0;
            long memAvailable = 0;

            for (int i = 0; i < 3 && (line = reader.readLine()) != null; i++) {
                if (line.startsWith("MemTotal")) {
                    memTotal = parseMemValue(line);
                } else if (line.startsWith("MemAvailable")) {
                    memAvailable = parseMemValue(line);
                }
            }

            if (memTotal > 0) {
                long memUsed = memTotal - memAvailable;
                return new RamInfo(memUsed / 1024, memTotal / 1024);
            }
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "Failed to read RAM usage", e);
        }
        return null;
    }

    private long parseMemValue(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            return Long.parseLong(parts[1]);
        }
        return 0;
    }

    public void stopMonitoring() {
        isMonitoring = false;
        if (monitorRunnable != null) {
            handler.removeCallbacks(monitorRunnable);
            monitorRunnable = null;
        }
        executor.shutdownNow();
    }

    private static class RamInfo {
        final long usedMb;
        final long totalMb;

        RamInfo(long usedMb, long totalMb) {
            this.usedMb = usedMb;
            this.totalMb = totalMb;
        }
    }
}
