package com.gree1d.reappzuku;

import android.content.Context;
import android.os.Handler;
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

/**
 * Manages collection and aggregation of per-app resource usage snapshots.
 *
 * Data sources:
 *  - Battery + CPU:  dumpsys batterystats --charged --checkin   (pwi lines → mAh per UID)
 *  - RAM:            dumpsys procstats --hours N                (avg PSS per package)
 *
 * Snapshot strategy:
 *  Every 30–60 minutes a full snapshot is saved to Room (ResourceSnapshot table).
 *  Period queries (2h / 6h / 12h / 24h) diff the two closest snapshots to that window.
 *
 * Usage:
 *  BatteryStatsManager mgr = new BatteryStatsManager(context, handler, executor, shellManager);
 *  mgr.takeSnapshotAsync(null);                       // background snapshot
 *  mgr.getStatsForPeriodAsync(6, callback);           // returns AppResourceStats list
 */
public class BatteryStatsManager {

    private static final String TAG = "BatteryStatsManager";

    /** Minimum interval between snapshots (30 min). Prevents duplicate writes. */
    private static final long MIN_SNAPSHOT_INTERVAL_MS = 30 * 60 * 1000L;

    /** Percentage threshold — apps at or below this share are grouped into "Others". */
    public static final float OTHERS_THRESHOLD_PCT = 5.0f;

    /** Minimum number of top apps always shown as individual slices (even if < threshold). */
    public static final int MIN_TOP_SLICES = 3;

    /** Own UID — excluded from "all apps" total but shown separately in UI. */
    private static final int MY_UID = android.os.Process.myUid();

    // ──────────────────────────────────────────────────────────────────────────
    // Regex patterns
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Matches a pwi (Power Use Item) line from --checkin output.
     * Format (Android 14+):  9,0,l,pwi,uid,<uid>,<mAh>,0,0,0,0
     * Field index (0-based after "pwi,"): 0=type/uid-token, 1=uid, 2=mAh, ...
     *
     * We use a simpler split-based approach because the format varies slightly between
     * Android versions. Pattern here is just used to detect the line.
     */
    private static final Pattern PWI_LINE = Pattern.compile("^\\d+,\\d+,l,pwi,uid,");

    /**
     * Matches package stat lines from procstats.
     * Example:  "  * com.example.app / u0a123:"
     */
    private static final Pattern PROCSTATS_PKG = Pattern.compile("^\\s{2}\\*\\s([\\w.]+)\\s*/\\su\\d+a(\\d+):");

    /**
     * Extracts avg PSS in kB from procstats package block.
     * Example:  "    TOTAL: 20% (184MB avg pss)"
     */
    private static final Pattern PROCSTATS_AVG_PSS = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*MB\\s+avg\\s+pss", Pattern.CASE_INSENSITIVE);

    // ──────────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────────

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final ResourceSnapshotDao dao;

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    public BatteryStatsManager(@NonNull Context context,
                               @NonNull Handler handler,
                               @NonNull ExecutorService executor,
                               @NonNull ShellManager shellManager) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.dao = AppDatabase.getInstance(context).resourceSnapshotDao();
    }

    /** Public data container for one app's resource stats over a period. */
    public static class AppResourceStats {
        public final String packageName;
        public final String appName;        // resolved by caller
        /** Battery drain estimate in mAh over the period. */
        public final double batteryMah;
        /** CPU time fraction over the period, 0.0–1.0. */
        public final double cpuFraction;
        /** Average RAM in MB (PSS) over the period. */
        public final double ramMb;
        /** Whether this app is ReAppzuku itself. */
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

    /** Result of a period query, ready for the chart. */
    public static class PeriodStats {
        /** All apps sorted by the primary metric (battery, CPU or RAM). */
        public final List<AppResourceStats> sorted;
        /** True if there are enough snapshots for this period. */
        public final boolean hasData;
        /** Actual hours covered by the diff (may differ from requested period). */
        public final double actualHours;
        /** Human-readable explanation if hasData == false. */
        public final String dataHint;

        PeriodStats(List<AppResourceStats> sorted, boolean hasData,
                    double actualHours, String dataHint) {
            this.sorted      = sorted;
            this.hasData     = hasData;
            this.actualHours = actualHours;
            this.dataHint    = dataHint;
        }
    }

    /** Async snapshot trigger. Posts result to the optional callback on the main thread. */
    public void takeSnapshotAsync(@Nullable Runnable onComplete) {
        executor.execute(() -> {
            takeSnapshotBlocking();
            if (onComplete != null) handler.post(onComplete);
        });
    }

    /**
     * Returns aggregated stats for the given period in hours.
     * Valid values: 2, 6, 12, 24.
     * Callback is invoked on the main thread.
     */
    public void getStatsForPeriodAsync(int hours, @NonNull StatsCallback callback) {
        executor.execute(() -> {
            PeriodStats result = getStatsForPeriodBlocking(hours);
            handler.post(() -> callback.onResult(result));
        });
    }

    /**
     * Returns per-hour breakdown for a single app (for the detail graph).
     * Covers the last `hours` hours split into hourly buckets.
     * Callback is invoked on the main thread.
     */
    public void getHourlyStatsAsync(String packageName, int hours,
                                    @NonNull HourlyCallback callback) {
        executor.execute(() -> {
            List<HourlyPoint> points = getHourlyStatsBlocking(packageName, hours);
            handler.post(() -> callback.onResult(points));
        });
    }

    public interface StatsCallback  { void onResult(PeriodStats stats); }
    public interface HourlyCallback { void onResult(List<HourlyPoint> points); }

    /** One data point on the per-app hourly graph. */
    public static class HourlyPoint {
        /** Hour label, e.g. "14:00" */
        public final String hourLabel;
        public final double batteryMah;
        public final double cpuPercent;
        public final double ramMb;

        HourlyPoint(String hourLabel, double batteryMah, double cpuPercent, double ramMb) {
            this.hourLabel   = hourLabel;
            this.batteryMah  = batteryMah;
            this.cpuPercent  = cpuPercent;
            this.ramMb       = ramMb;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — snapshot collection
    // ──────────────────────────────────────────────────────────────────────────

    @WorkerThread
    private void takeSnapshotBlocking() {
        long now = System.currentTimeMillis();

        // Throttle: skip if last snapshot was taken recently
        ResourceSnapshot last = dao.getLatestSnapshot();
        if (last != null && (now - last.timestamp) < MIN_SNAPSHOT_INTERVAL_MS) {
            Log.d(TAG, "Snapshot skipped — too soon after last one");
            return;
        }

        // 1. Battery + CPU from batterystats
        Map<String, Double> batteryMahByPkg = collectBatteryStats();

        // 2. RAM + CPU-time from procstats (24h window gives broadest coverage)
        Map<String, Double> ramMbByPkg    = new HashMap<>();
        Map<String, Double> cpuTimeByPkg  = new HashMap<>();
        collectProcStats(24, ramMbByPkg, cpuTimeByPkg);

        // 3. Merge into a single snapshot row per package
        for (String pkg : mergedKeySet(batteryMahByPkg, ramMbByPkg)) {
            ResourceSnapshot snap = new ResourceSnapshot();
            snap.timestamp   = now;
            snap.packageName = pkg;
            snap.batteryMah  = getOrZero(batteryMahByPkg, pkg);
            snap.ramMb       = getOrZero(ramMbByPkg, pkg);
            snap.cpuTimeMs   = (long)(getOrZero(cpuTimeByPkg, pkg) * 60_000); // fraction→ms approx
            dao.insert(snap);
        }

        // 4. Prune old snapshots (keep 3 days)
        dao.deleteOlderThan(now - 3 * 24 * 3600_000L);

        Log.d(TAG, "Snapshot saved: " + mergedKeySet(batteryMahByPkg, ramMbByPkg).size() + " apps");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — battery stats parsing
    // ──────────────────────────────────────────────────────────────────────────

    @WorkerThread
    @NonNull
    private Map<String, Double> collectBatteryStats() {
        String cmd = "dumpsys batterystats --charged --checkin";
        String output = shellManager.runCommandAndGetOutput(cmd);
        if (output == null || output.isEmpty()) return Collections.emptyMap();

        // Step 1: build UID → mAh map from pwi lines
        // Line format: 9,0,l,pwi,uid,<uid>,<mAh>,0,0,0,0
        Map<Integer, Double> uidToMah = new HashMap<>();
        for (String line : output.split("\n")) {
            if (!PWI_LINE.matcher(line).find()) continue;
            try {
                String[] parts = line.split(",");
                // parts[5] = uid, parts[6] = mAh  (indices may shift across Android versions)
                // Locate "pwi" token and offset from there for robustness
                int pwiIdx = -1;
                for (int i = 0; i < parts.length; i++) {
                    if ("pwi".equals(parts[i])) { pwiIdx = i; break; }
                }
                if (pwiIdx < 0 || pwiIdx + 2 >= parts.length) continue;
                // After "pwi": next field is "uid", then uid value, then mAh
                // e.g. [..., "pwi", "uid", "10234", "12.5", ...]
                int uidVal = Integer.parseInt(parts[pwiIdx + 2].trim());
                double mah = Double.parseDouble(parts[pwiIdx + 3].trim());
                if (mah > 0) uidToMah.put(uidVal, mah);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {}
        }

        // Step 2: map UID → package name
        Map<String, Double> result = new HashMap<>();
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (Map.Entry<Integer, Double> entry : uidToMah.entrySet()) {
            String[] pkgs = pm.getPackagesForUid(entry.getKey());
            if (pkgs == null || pkgs.length == 0) continue;
            // If multiple packages share UID, distribute mAh equally
            double share = entry.getValue() / pkgs.length;
            for (String pkg : pkgs) {
                result.merge(pkg, share, Double::sum);
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — procstats parsing
    // ──────────────────────────────────────────────────────────────────────────

    @WorkerThread
    private void collectProcStats(int hours,
                                  Map<String, Double> ramMbOut,
                                  Map<String, Double> cpuFractionOut) {
        String cmd = "dumpsys procstats --hours " + hours;
        String output = shellManager.runCommandAndGetOutput(cmd);
        if (output == null || output.isEmpty()) return;

        // Simple state machine: detect package header, then scan following lines for PSS
        String currentPkg = null;
        for (String line : output.split("\n")) {
            Matcher pkgMatcher = PROCSTATS_PKG.matcher(line);
            if (pkgMatcher.find()) {
                currentPkg = pkgMatcher.group(1);
                continue;
            }
            if (currentPkg == null) continue;

            // Average PSS
            Matcher pssMatcher = PROCSTATS_AVG_PSS.matcher(line);
            if (pssMatcher.find()) {
                try {
                    double mb = Double.parseDouble(pssMatcher.group(1));
                    ramMbOut.merge(currentPkg, mb, (a, b) -> Math.max(a, b)); // take max across states
                } catch (NumberFormatException ignored) {}
            }

            // CPU fraction — procstats format: "XX%: <n>ms" or "Total cpu:" line varies
            // We parse the simpler "XX% (NNms cpu)" pattern
            if (line.contains("cpu") && line.contains("%")) {
                try {
                    Matcher cpuM = Pattern.compile("(\\d+(?:\\.\\d+)?)%").matcher(line);
                    if (cpuM.find()) {
                        double pct = Double.parseDouble(cpuM.group(1)) / 100.0;
                        cpuFractionOut.merge(currentPkg, pct, Double::sum);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — period aggregation
    // ──────────────────────────────────────────────────────────────────────────

    @WorkerThread
    @NonNull
    private PeriodStats getStatsForPeriodBlocking(int hours) {
        long now     = System.currentTimeMillis();
        long target  = now - (long) hours * 3600_000L;

        ResourceSnapshot current  = dao.getLatestSnapshot();
        ResourceSnapshot previous = dao.getClosestSnapshotBefore(target);

        if (current == null) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    "Данные ещё не собраны. Подождите ~30 минут.");
        }
        if (previous == null) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    "Недостаточно истории. Данные накапливаются — зайдите позже.");
        }

        double actualHours = (current.timestamp - previous.timestamp) / 3600_000.0;
        if (actualHours < 0.08) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    "Снапшоты слишком близко. Попробуйте позже.");
        }

        // Load all snapshots in window
        List<ResourceSnapshot> windowSnaps = dao.getSnapshotsBetween(previous.timestamp, current.timestamp);

        // Build per-package deltas
        Map<String, double[]> perPkg = new HashMap<>(); // [batteryMah, ramMb, cpuMs]
        Map<String, ResourceSnapshot> first = new HashMap<>();

        for (ResourceSnapshot snap : windowSnaps) {
            if (!first.containsKey(snap.packageName)) {
                first.put(snap.packageName, snap);
            } else {
                ResourceSnapshot f = first.get(snap.packageName);
                double dBat = Math.max(0, snap.batteryMah - f.batteryMah);
                double dRam = snap.ramMb; // use latest value for RAM
                double dCpu = Math.max(0, snap.cpuTimeMs - f.cpuTimeMs);
                perPkg.put(snap.packageName, new double[]{ dBat, dRam, dCpu });
            }
        }

        // Resolve app names and build result list
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

        // Sort by battery descending
        result.sort((a, b) -> Double.compare(b.batteryMah, a.batteryMah));
        return new PeriodStats(result, true, actualHours, null);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — hourly breakdown
    // ──────────────────────────────────────────────────────────────────────────

    @WorkerThread
    @NonNull
    private List<HourlyPoint> getHourlyStatsBlocking(String packageName, int hours) {
        long now    = System.currentTimeMillis();
        long start  = now - (long) hours * 3600_000L;

        List<ResourceSnapshot> snaps = dao.getSnapshotsForPackageBetween(packageName, start, now);
        List<HourlyPoint> points = new ArrayList<>();
        if (snaps.size() < 2) return points;

        // Group snapshots into hourly buckets
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
            double dCpu = (double) Math.max(0, curr.cpuTimeMs - prev.cpuTimeMs) / 3600_000.0 * 100;
            double ram  = curr.ramMb;

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(bucketStart);
            String label = String.format(Locale.US, "%02d:00", cal.get(java.util.Calendar.HOUR_OF_DAY));
            points.add(new HourlyPoint(label, dBat, dCpu, ram));
        }
        return points;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static double getOrZero(Map<String, Double> map, String key) {
        Double v = map.get(key);
        return v != null ? v : 0.0;
    }

    private static java.util.Set<String> mergedKeySet(Map<String, Double> a, Map<String, Double> b) {
        java.util.Set<String> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
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
