package com.gree1d.reappzuku;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.ResourceSnapshot;
import com.gree1d.reappzuku.db.ResourceSnapshotDao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BatteryStatsManager {

    private static final String TAG = "BatteryStatsManager";

    private static final long MIN_SNAPSHOT_INTERVAL_MS = 10 * 60 * 1000L;

    public static final float OTHERS_THRESHOLD_PCT = 5.0f;

    public static final int MIN_TOP_SLICES = 3;

    
    private static final Pattern PWI_UID_LINE = Pattern.compile("^9,\\d+,l,pwi,uid,");

    private static final Pattern CPU_UID_LINE = Pattern.compile("^9,\\d+,l,cpu,");

    private static final Pattern PROCSTATS_PKG =
            Pattern.compile("^\\s{2}\\*\\s([\\w.]+)\\s*/\\su\\d+a(\\d+)");

    private static final Pattern PROCSTATS_PSS =
            Pattern.compile("(\\d+(?:\\.\\d+)?)MB-(\\d+(?:\\.\\d+)?)MB-(\\d+(?:\\.\\d+)?)MB");

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final ResourceSnapshotDao dao;

    public BatteryStatsManager(@NonNull Context context,
                               @NonNull Handler handler,
                               @NonNull ExecutorService executor,
                               @NonNull ShellManager shellManager) {
        this.context      = context.getApplicationContext();
        this.handler      = handler;
        this.executor     = executor;
        this.shellManager = shellManager;
        this.dao          = AppDatabase.getInstance(context).resourceSnapshotDao();
    }

    public BatteryStatsManager(@NonNull Context context,
                               @NonNull ShellManager shellManager) {
        this.context      = context.getApplicationContext();
        this.handler      = new Handler(Looper.getMainLooper());
        this.executor     = java.util.concurrent.Executors.newSingleThreadExecutor();
        this.shellManager = shellManager;
        this.dao          = AppDatabase.getInstance(context).resourceSnapshotDao();
    }

    public static class AppResourceStats {
        public final String packageName;
        public final String appName;

        public final double batteryMah;

        public final double cpuFraction;

        public final double ramMb;

        public final boolean isSelf;

        public AppResourceStats(String packageName, String appName,
                                double batteryMah, double cpuFraction, double ramMb,
                                boolean isSelf) {
            this.packageName = packageName;
            this.appName     = appName;
            this.batteryMah  = batteryMah;
            this.cpuFraction = cpuFraction;
            this.ramMb       = ramMb;
            this.isSelf      = isSelf;
        }
    }

    public static class PeriodStats {
        public final List<AppResourceStats> sorted;
        public final boolean hasData;
        public final double actualHours;
        public final String dataHint;

        PeriodStats(List<AppResourceStats> sorted, boolean hasData,
                    double actualHours, String dataHint) {
            this.sorted      = sorted;
            this.hasData     = hasData;
            this.actualHours = actualHours;
            this.dataHint    = dataHint;
        }
    }

    public void takeSnapshotAsync(@Nullable Runnable onComplete) {
        executor.execute(() -> {
            takeSnapshotBlocking();
            if (onComplete != null) handler.post(onComplete);
        });
    }

    public void getStatsForPeriodAsync(int hours, @NonNull StatsCallback callback) {
        executor.execute(() -> {
            PeriodStats result = getStatsForPeriodBlocking(hours);
            handler.post(() -> callback.onResult(result));
        });
    }

    public void getHourlyStatsAsync(String packageName, int hours,
                                    @NonNull HourlyCallback callback) {
        executor.execute(() -> {
            List<HourlyPoint> points = getHourlyStatsBlocking(packageName, hours);
            handler.post(() -> callback.onResult(points));
        });
    }

    public interface StatsCallback  { void onResult(PeriodStats stats); }
    public interface HourlyCallback { void onResult(List<HourlyPoint> points); }

    public static class HourlyPoint {
        public final String hourLabel;
        public final double batteryMah;
        public final double cpuPercent;
        public final double ramMb;

        HourlyPoint(String hourLabel, double batteryMah, double cpuPercent, double ramMb) {
            this.hourLabel  = hourLabel;
            this.batteryMah = batteryMah;
            this.cpuPercent = cpuPercent;
            this.ramMb      = ramMb;
        }
    }

    @WorkerThread
    private void takeSnapshotBlocking() {
        long now = System.currentTimeMillis();

        ResourceSnapshot last = dao.getLatestSnapshot();
        if (last != null && (now - last.timestamp) < MIN_SNAPSHOT_INTERVAL_MS) {
            Log.d(TAG, "Snapshot skipped — too soon after last one");
            return;
        }

        Map<String, Double> batteryMahByPkg = new HashMap<>();
        Map<String, Long>   cpuMsByPkg      = new HashMap<>();
        collectCheckinStats(batteryMahByPkg, cpuMsByPkg);

        Map<String, Double> ramMbByPkg = new HashMap<>();
        collectProcStatsRam(24, ramMbByPkg);

        java.util.Set<String> allPkgs = new java.util.HashSet<>();
        allPkgs.addAll(batteryMahByPkg.keySet());
        allPkgs.addAll(ramMbByPkg.keySet());
        allPkgs.addAll(cpuMsByPkg.keySet());

        for (String pkg : allPkgs) {
            ResourceSnapshot snap = new ResourceSnapshot();
            snap.timestamp   = now;
            snap.packageName = pkg;
            snap.batteryMah  = getOrZero(batteryMahByPkg, pkg);
            snap.ramMb       = getOrZero(ramMbByPkg, pkg);
            snap.cpuTimeMs   = cpuMsByPkg.containsKey(pkg) ? cpuMsByPkg.get(pkg) : 0L;
            dao.insert(snap);
        }

        dao.deleteOlderThan(now - 24 * 3600_000L);

        Log.d(TAG, "Snapshot saved: " + allPkgs.size() + " apps"
                + "  battery=" + batteryMahByPkg.size()
                + "  ram=" + ramMbByPkg.size()
                + "  cpu=" + cpuMsByPkg.size());
    }

    @WorkerThread
    private void collectCheckinStats(@NonNull Map<String, Double> batteryMahOut,
                                     @NonNull Map<String, Long> cpuMsOut) {
        String cmd = "dumpsys batterystats --charged --checkin";
        String output = shellManager.runCommandAndGetOutput(cmd);
        if (output == null || output.isEmpty()) return;

        Map<Integer, Double> uidToMah   = new HashMap<>();
        Map<Integer, Long>   uidToCpuMs = new HashMap<>();

        for (String line : output.split("\n")) {
            if (PWI_UID_LINE.matcher(line).find()) {
                
                try {
                    String[] parts = line.split(",");
                    if (parts.length < 6) continue;
                    int uid    = Integer.parseInt(parts[1].trim());
                    double mah = Double.parseDouble(parts[5].trim());
                    if (mah > 0) uidToMah.put(uid, mah);
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    Log.w(TAG, "pwi parse error: " + line, e);
                }
            } else if (CPU_UID_LINE.matcher(line).find()) {
                
                try {
                    String[] parts = line.split(",");
                    if (parts.length < 6) continue;
                    int uid       = Integer.parseInt(parts[1].trim());
                    long userMs   = Long.parseLong(parts[4].trim());
                    long systemMs = Long.parseLong(parts[5].trim());
                    long total    = userMs + systemMs;
                    if (total > 0) uidToCpuMs.put(uid, total);
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    Log.w(TAG, "cpu parse error: " + line, e);
                }
            }
        }

        android.content.pm.PackageManager pm = context.getPackageManager();

        for (Map.Entry<Integer, Double> e : uidToMah.entrySet()) {
            String[] pkgs = pm.getPackagesForUid(e.getKey());
            if (pkgs == null || pkgs.length == 0) continue;
            double share = e.getValue() / pkgs.length;
            for (String pkg : pkgs) batteryMahOut.merge(pkg, share, Double::sum);
        }

        for (Map.Entry<Integer, Long> e : uidToCpuMs.entrySet()) {
            String[] pkgs = pm.getPackagesForUid(e.getKey());
            if (pkgs == null || pkgs.length == 0) continue;
            long share = e.getValue() / pkgs.length;
            for (String pkg : pkgs) cpuMsOut.merge(pkg, share, Long::sum);
        }
    }

    @WorkerThread
    private void collectProcStatsRam(int hours, Map<String, Double> ramMbOut) {
        String cmd = "dumpsys procstats --hours " + hours;
        String output = shellManager.runCommandAndGetOutput(cmd);
        if (output == null || output.isEmpty()) return;

        String currentPkg = null;
        for (String line : output.split("\n")) {
            Matcher pkgMatcher = PROCSTATS_PKG.matcher(line);
            if (pkgMatcher.find()) {
                currentPkg = pkgMatcher.group(1);
                continue;
            }
            if (currentPkg == null) continue;

            if (!line.contains("TOTAL")) continue;
            Matcher pssMatcher = PROCSTATS_PSS.matcher(line);
            if (pssMatcher.find()) {
                try {
                    
                    double avgPssMb = Double.parseDouble(pssMatcher.group(2));
                    ramMbOut.merge(currentPkg, avgPssMb, Math::max);
                } catch (NumberFormatException ignored) {}
            }
        }
    }
    

    @WorkerThread
    @NonNull
    private PeriodStats getStatsForPeriodBlocking(int hours) {
        long now    = System.currentTimeMillis();
        long target = now - (long) hours * 3600_000L;

        ResourceSnapshot current  = dao.getLatestSnapshot();
        ResourceSnapshot previous = dao.getClosestSnapshotBefore(target);

        if (current == null) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_no_snapshot));
        }

        if (previous == null || previous.timestamp >= current.timestamp) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_no_history));
        }

        double actualHours = (current.timestamp - previous.timestamp) / 3600_000.0;
        if (actualHours < 0.08) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_too_close));
        }

        List<ResourceSnapshot> windowSnaps =
                dao.getSnapshotsBetween(previous.timestamp, current.timestamp);

        Map<String, double[]> perPkg = new HashMap<>(); // [batteryMah, ramMb, cpuMs]
        Map<String, ResourceSnapshot> firstByPkg = new HashMap<>();

        for (ResourceSnapshot snap : windowSnaps) {
            if (!firstByPkg.containsKey(snap.packageName)) {
                firstByPkg.put(snap.packageName, snap);
            } else {
                ResourceSnapshot f = firstByPkg.get(snap.packageName);

                double dBat = Math.max(0, snap.batteryMah - f.batteryMah);
                double dCpu = Math.max(0, snap.cpuTimeMs  - f.cpuTimeMs);
                double dRam = snap.ramMb; 
                perPkg.put(snap.packageName, new double[]{ dBat, dRam, dCpu });
            }
        }

        android.content.pm.PackageManager pm = context.getPackageManager();
        List<AppResourceStats> result = new ArrayList<>();

        double totalCpuMs = 0;
        for (double[] vals : perPkg.values()) totalCpuMs += vals[2];

        for (Map.Entry<String, double[]> e : perPkg.entrySet()) {
            String pkg = e.getKey();
            double[] v = e.getValue();
            String name = resolveAppName(pm, pkg);
            double cpuFraction = totalCpuMs > 0 ? v[2] / totalCpuMs : 0;
            result.add(new AppResourceStats(pkg, name, v[0], cpuFraction, v[1],
                    pkg.equals(context.getPackageName())));
        }

        result.sort((a, b) -> Double.compare(b.batteryMah, a.batteryMah));
        return new PeriodStats(result, true, actualHours, null);
    }


    @WorkerThread
    @NonNull
    private List<HourlyPoint> getHourlyStatsBlocking(String packageName, int hours) {
        long now   = System.currentTimeMillis();
        long start = now - (long) hours * 3600_000L;

        List<ResourceSnapshot> snaps =
                dao.getSnapshotsForPackageBetween(packageName, start, now);
        List<HourlyPoint> points = new ArrayList<>();
        if (snaps.size() < 2) return points;

        for (int h = 0; h < hours; h++) {
            long bucketStart = start + (long) h * 3600_000L;
            long bucketEnd   = bucketStart + 3600_000L;

            ResourceSnapshot prev = null, curr = null;
            for (ResourceSnapshot s : snaps) {
                if (s.timestamp <= bucketStart) prev = s;
                if (s.timestamp <= bucketEnd)   curr = s;
            }
            if (prev == null || curr == null || prev == curr) continue;

            double dBat = Math.max(0, curr.batteryMah - prev.batteryMah);
            double dCpuMs = Math.max(0, curr.cpuTimeMs - prev.cpuTimeMs);

            long actualElapsedMs = curr.timestamp - prev.timestamp;
            double cpuPct = actualElapsedMs > 0
                    ? (dCpuMs / (double) actualElapsedMs) * 100.0
                    : 0.0;
            double ram    = curr.ramMb;

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(bucketStart);
            String label = String.format(Locale.US, "%02d:00",
                    cal.get(java.util.Calendar.HOUR_OF_DAY));
            points.add(new HourlyPoint(label, dBat, cpuPct, ram));
        }
        return points;
    }

    private static double getOrZero(Map<String, Double> map, String key) {
        Double v = map.get(key);
        return v != null ? v : 0.0;
    }

    private static String resolveAppName(android.content.pm.PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
