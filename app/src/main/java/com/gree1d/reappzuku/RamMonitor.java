package com.gree1d.reappzuku;

import android.os.Handler;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.gree1d.reappzuku.AppConstants.RAM_MONITOR_UPDATE_INTERVAL_MS;

/**
 * Monitors system RAM usage and updates UI elements periodically.
 * Reads from /proc/meminfo on a background thread to avoid ANRs.
 */
public class RamMonitor {
    private static final String TAG = "RamMonitor";

    private final Handler handler;
    private final ProgressBar ramUsageBar;
    private final TextView ramUsageText;
    private final ExecutorService executor;
    private volatile boolean isMonitoring = false;
    private Runnable monitorRunnable;

    public RamMonitor(Handler handler, ProgressBar ramUsageBar, TextView ramUsageText) {
        this.handler = handler;
        this.ramUsageBar = ramUsageBar;
        this.ramUsageText = ramUsageText;
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Start monitoring RAM usage with periodic updates
     */
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

                // Run I/O on background thread
                executor.execute(() -> {
                    final RamInfo ramInfo = readRamUsage();

                    // Post UI updates to main handler
                    handler.post(() -> {
                        if (!isMonitoring)
                            return;

                        if (ramInfo != null && ramInfo.totalMb > 0) {
                            ramUsageBar.setMax((int) ramInfo.totalMb);
                            ramUsageBar.setProgress((int) ramInfo.usedMb);
                            ramUsageText.setText(String.format("RAM Usage: %dMB / %dMB",
                                    ramInfo.usedMb, ramInfo.totalMb));
                        } else {
                            ramUsageText.setText("RAM Usage: --");
                        }
                    });
                });

                // Schedule the next update on the main handler
                if (isMonitoring) {
                    handler.postDelayed(this, RAM_MONITOR_UPDATE_INTERVAL_MS);
                }
            }
        };

        handler.post(monitorRunnable);
    }

    /**
     * Read RAM usage from /proc/meminfo on background thread.
     * Uses RandomAccessFile for direct file reading without spawning a process.
     */
    private RamInfo readRamUsage() {
        try (RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r")) {
            String line;
            long memTotal = 0;
            long memAvailable = 0;

            // Read meminfo lines
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

    /**
     * Stop monitoring RAM usage and clean up resources
     */
    public void stopMonitoring() {
        isMonitoring = false;
        if (monitorRunnable != null) {
            handler.removeCallbacks(monitorRunnable);
            monitorRunnable = null;
        }
        executor.shutdownNow();
    }

    /**
     * Simple data class to hold RAM info
     */
    private static class RamInfo {
        final long usedMb;
        final long totalMb;

        RamInfo(long usedMb, long totalMb) {
            this.usedMb = usedMb;
            this.totalMb = totalMb;
        }
    }
}
