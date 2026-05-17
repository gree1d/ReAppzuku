package com.gree1d.reappzuku;

import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class CpuMonitor {

    private static final String TAG = "CpuMonitor";
    private static final long POLL_INTERVAL_MS = 1500;

    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;

    private final Object appsLock = new Object();
    private List<AppModel> appsList = Collections.emptyList();
    private boolean running = false;

    public interface OnCpuUpdateListener {
        void onCpuUpdated();
    }

    private OnCpuUpdateListener updateListener;

    public void setOnCpuUpdateListener(OnCpuUpdateListener listener) {
        this.updateListener = listener;
    }

    private final Map<String, Long> prevProcTimes = new HashMap<>();
    private long prevTotalCpu = -1;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            executor.execute(() -> {
                pollCpu();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            });
        }
    };

    public CpuMonitor(Handler handler, ExecutorService executor, ShellManager shellManager) {
        this.handler      = handler;
        this.executor     = executor;
        this.shellManager = shellManager;
    }

    public void setAppsList(List<AppModel> appsList) {
        synchronized (appsLock) {
            this.appsList = appsList != null ? appsList : Collections.emptyList();
        }
    }

    public void refreshAppsList(List<AppModel> newList) {
        synchronized (appsLock) {
            this.appsList = newList != null ? newList : Collections.emptyList();
            if (this.appsList.isEmpty()) {
                prevProcTimes.clear();
                return;
            }
            HashSet<String> current = new HashSet<>();
            for (AppModel app : this.appsList) current.add(app.getPackageName());
            prevProcTimes.keySet().retainAll(current);
        }
    }

    public void startMonitoring() {
        if (running) return;
        running = true;
        Log.d(TAG, "startMonitoring: appsList.size=" + appsList.size());
        handler.post(pollRunnable);
    }

    public void stopMonitoring() {
        Log.d(TAG, "stopMonitoring");
        running = false;
        handler.removeCallbacks(pollRunnable);
    }


    private void pollCpu() {
        List<AppModel> snapshot;
        synchronized (appsLock) {
            snapshot = new ArrayList<>(appsList);
        }

        Log.d(TAG, "pollCpu: apps=" + snapshot.size() + " prevTotalCpu=" + prevTotalCpu);

        StringBuilder cmd = new StringBuilder("cat /proc/stat");
        for (AppModel app : snapshot) {
            int pid = app.getPid();
            if (pid > 0) {
                cmd.append("; echo ---; cat /proc/").append(pid).append("/stat");
            } else {
                cmd.append("; echo ---");
            }
        }

        String output = shellManager.runCommandAndGetOutput(cmd.toString());
        if (output == null || output.isEmpty()) {
            Log.w(TAG, "Shell command returned null");
            return;
        }

        String[] sections = output.split("---");

        long totalCpu = parseTotalCpuTicks(sections[0]);
        if (totalCpu < 0) {
            Log.w(TAG, "Failed to parse /proc/stat");
            return;
        }

        int numCores = Runtime.getRuntime().availableProcessors();
        boolean hasBaseline = (prevTotalCpu >= 0);
        long deltaTotalCpu = totalCpu - prevTotalCpu;

        for (int i = 0; i < snapshot.size(); i++) {
            AppModel app = snapshot.get(i);
            String pkg = app.getPackageName();

            if (app.getPid() <= 0) {
                app.setCpuUsage("", -1f);
                prevProcTimes.remove(pkg);
                continue;
            }

            long procTime = -1;
            if (i + 1 < sections.length) {
                procTime = parseProcStatTime(sections[i + 1].trim());
            }

            if (procTime < 0) {
                app.setCpuUsage("", -1f);
                prevProcTimes.remove(pkg);
                continue;
            }

            Long prevProc = prevProcTimes.get(pkg);
            prevProcTimes.put(pkg, procTime);

            if (!hasBaseline || prevProc == null || deltaTotalCpu <= 0) {
                app.setCpuUsage("", -1f);
                continue;
            }

            long deltaProcTime = procTime - prevProc;
            float cpu = (deltaProcTime * 100f * numCores) / deltaTotalCpu;
            if (cpu < 0) cpu = 0;
            if (cpu > 100) cpu = 100;

            app.setCpuUsage(String.format("CPU: %.1f%%", cpu), cpu);
        }

        prevTotalCpu = totalCpu;

        if (updateListener != null) {
            handler.post(() -> updateListener.onCpuUpdated());
        }
    }

    private long parseTotalCpuTicks(String statOutput) {
        for (String line : statOutput.split("\n")) {
            line = line.trim();
            if (!line.startsWith("cpu ")) continue;
            String[] fields = line.split("\\s+");
            long total = 0;
            for (int i = 1; i < fields.length; i++) {
                try { total += Long.parseLong(fields[i]); }
                catch (NumberFormatException ignored) {}
            }
            return total;
        }
        return -1;
    }

    private long parseProcStatTime(String line) {
        if (line == null || line.isEmpty()) return -1;
        int lastParen = line.lastIndexOf(')');
        if (lastParen < 0) return -1;
        String[] fields = line.substring(lastParen + 2).split(" ");
        try {
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }
}
