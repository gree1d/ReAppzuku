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

/**
 * Manages collection and aggregation of per-app resource usage snapshots.
 *
 * Data sources:
 *  - Battery:  dumpsys batterystats --charged --checkin   (pwi uid lines → mAh per UID)
 *  - RAM:      dumpsys procstats --hours N                (avg PSS per package)
 *  - CPU:      batterystats per-app cpu= fields           (cumulative ms since charge)
 *
 * Snapshot strategy:
 *  Every 30–60 minutes a full snapshot is saved to Room (ResourceSnapshot table).
 *  Period queries (2h / 6h / 12h / 24h) diff the two closest snapshots to that window.
 *
 *  NOTE: batterystats --charged resets on every charge cycle.
 *  batteryMah is a CUMULATIVE value since last charge — diffing works correctly as long
 *  as no charge event occurs between two snapshots. If the device was charged between
 *  snapshots the delta goes negative; we clamp it to 0 in that case.
 */
public class BatteryStatsManager {

    private static final String TAG = "BatteryStatsManager";

    /** Minimum interval between snapshots (10 min). Prevents duplicate writes. */
    private static final long MIN_SNAPSHOT_INTERVAL_MS = 10 * 60 * 1000L;

    /** Percentage threshold — apps at or below this share are grouped into "Others". */
    public static final float OTHERS_THRESHOLD_PCT = 5.0f;

    /** Minimum number of top apps always shown as individual slices (even if < threshold). */
    public static final int MIN_TOP_SLICES = 3;

    // ──────────────────────────────────────────────────────────────────────────
    // Regex patterns
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Matches a pwi uid line from --checkin output.
     *
     * Real format (verified on Android 12–14):
     *   9,<uid>,l,pwi,uid,<mAh>,1,...
     *
     * Fields (0-based, comma-split):
     *   [0] version      → "9"
     *   [1] uid          → numeric app UID  (THIS is the UID we need)
     *   [2] "l"
     *   [3] "pwi"
     *   [4] "uid"        → literal token distinguishing per-uid rows
     *   [5] mAh          → battery drain value
     *   [6] "1"          → present flag
     *   [7] foreground mAh (may be 0)
     *
     * We detect the line by the literal pattern "^9,\d+,l,pwi,uid," and then
     * extract uid from parts[1] and mAh from parts[5].
     */
    private static final Pattern PWI_UID_LINE = Pattern.compile("^9,\\d+,l,pwi,uid,");

    /**
     * Matches a cpu line from --checkin output.
     *
     * Format:  9,<uid>,l,cpu,<user_ms>,<system_ms>,0
     *
     * Fields (0-based, comma-split):
     *   [1] uid
     *   [4] user CPU ms (cumulative since last charge)
     *   [5] system CPU ms (cumulative since last charge)
     *
     * cpuTimeMs = parts[4] + parts[5]
     */
    private static final Pattern CPU_UID_LINE = Pattern.compile("^9,\\d+,l,cpu,");

    /**
     * Matches package stat lines from procstats.
     * Example:  "  * com.example.app / u0a123 / v456:"
     */
    private static final Pattern PROCSTATS_PKG =
            Pattern.compile("^\\s{2}\\*\\s([\\w.]+)\\s*/\\su\\d+a(\\d+)");

    /**
     * Extracts the average PSS value from a procstats TOTAL line.
     *
     * Real format:
     *   TOTAL: 100% (346MB-346MB-346MB/161MB-161MB-161MB/288MB-288MB-288MB over 1)
     *   Fields: PSS min-avg-max / USS min-avg-max / RSS min-avg-max
     *
     * We want the PSS average (second number in the first triplet).
     * Pattern captures the three PSS values; group(2) = avg PSS in MB.
     */
    private static final Pattern PROCSTATS_PSS =
            Pattern.compile("(\\d+(?:\\.\\d+)?)MB-(\\d+(?:\\.\\d+)?)MB-(\\d+(?:\\.\\d+)?)MB");

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
        this.context      = context.getApplicationContext();
        this.handler      = handler;
        this.executor     = executor;
        this.shellManager = shellManager;
        this.dao          = AppDatabase.getInstance(context).resourceSnapshotDao();
    }

    /**
     * Convenience constructor for ShappkyService where Handler/Executor are managed externally.
     */
    public BatteryStatsManager(@NonNull Context context,
                               @NonNull ShellManager shellManager) {
        this.context      = context.getApplicationContext();
        this.handler      = new Handler(Looper.getMainLooper());
        this.executor     = java.util.concurrent.Executors.newSingleThreadExecutor();
        this.shellManager = shellManager;
        this.dao          = AppDatabase.getInstance(context).resourceSnapshotDao();
    }

    /** Public data container for one app's resource stats over a period. */
    public static class AppResourceStats {
        public final String packageName;
        public final String appName;
        /** Battery drain estimate in mAh over the period. */
        public final double batteryMah;
        /** Actual CPU usage over the period as % of wall-clock time (0–100+). */
        public final double cpuPct;
        /** Average RAM in MB (PSS) over the period. */
        public final double ramMb;
        /** Whether this app is ReAppzuku itself. */
        public final boolean isSelf;

        public AppResourceStats(String packageName, String appName,
                                double batteryMah, double cpuPct, double ramMb,
                                boolean isSelf) {
            this.packageName = packageName;
            this.appName     = appName;
            this.batteryMah  = batteryMah;
            this.cpuPct      = cpuPct;
            this.ramMb       = ramMb;
            this.isSelf      = isSelf;
        }
    }

    /** Result of a period query, ready for the chart. */
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

    /** Async snapshot trigger. Posts onComplete to the main thread when done. */
    public void takeSnapshotAsync(@Nullable Runnable onComplete) {
        executor.execute(() -> {
            takeSnapshotBlocking();
            if (onComplete != null) handler.post(onComplete);
        });
    }

    /**
     * Returns aggregated stats for the given period in hours (2 / 6 / 12 / 24).
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

    // ──────────────────────────────────────────────────────────────────────────
    // Private — snapshot collection
    // ──────────────────────────────────────────────────────────────────────────

    @WorkerThread
    private void takeSnapshotBlocking() {
        long now = System.currentTimeMillis();

        // Throttle: skip if last snapshot was too recent
        ResourceSnapshot last = dao.getLatestSnapshot();
        if (last != null && (now - last.timestamp) < MIN_SNAPSHOT_INTERVAL_MS) {
            Log.d(TAG, "Snapshot skipped — too soon after last one");
            return;
        }

        // 1. Battery (mAh) + CPU time (ms) — single batterystats --checkin call
        Map<String, Double> batteryMahByPkg = new HashMap<>();
        Map<String, Long>   cpuMsByPkg      = new HashMap<>();
        collectCheckinStats(batteryMahByPkg, cpuMsByPkg);

        // 2. RAM (PSS MB) from procstats
        Map<String, Double> ramMbByPkg = new HashMap<>();
        collectProcStatsRam(24, ramMbByPkg);

        // 3. System-wide CPU baseline from /proc/stat (no root required).
        // Stored in every snapshot row so period queries can compute accurate
        // per-app CPU% using: appCpuPct = dAppCpuMs / (dTotalJiffies * 10) * 100
        long[] jiffies = readProcStatJiffies(); // [totalJiffies, activeJiffies]

        // 4. Merge into one snapshot row per package
        java.util.Set<String> allPkgs = new java.util.HashSet<>();
        allPkgs.addAll(batteryMahByPkg.keySet());
        allPkgs.addAll(ramMbByPkg.keySet());
        allPkgs.addAll(cpuMsByPkg.keySet());

        for (String pkg : allPkgs) {
            ResourceSnapshot snap = new ResourceSnapshot();
            snap.timestamp       = now;
            snap.packageName     = pkg;
            snap.batteryMah      = getOrZero(batteryMahByPkg, pkg);
            snap.ramMb           = getOrZero(ramMbByPkg, pkg);
            snap.cpuTimeMs       = cpuMsByPkg.containsKey(pkg) ? cpuMsByPkg.get(pkg) : 0L;
            snap.totalCpuJiffies  = jiffies[0];
            snap.activeCpuJiffies = jiffies[1];
            dao.insert(snap);
        }

        // 5. Prune old snapshots (keep 24 hours)
        dao.deleteOlderThan(now - 24 * 3600_000L);

        Log.d(TAG, "Snapshot saved: " + allPkgs.size() + " apps"
                + "  battery=" + batteryMahByPkg.size()
                + "  ram=" + ramMbByPkg.size()
                + "  cpu=" + cpuMsByPkg.size());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — checkin stats parsing (battery + CPU in one call)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses both battery mAh and CPU time from a single
     * "dumpsys batterystats --charged --checkin" call.
     *
     * pwi uid line  →  battery mAh per UID:
     *   9,<uid>,l,pwi,uid,<mAh>,1,<fg_mAh>,0
     *   uid=parts[1], mAh=parts[5]
     *
     * cpu line  →  cumulative CPU ms per UID:
     *   9,<uid>,l,cpu,<user_ms>,<system_ms>,0
     *   uid=parts[1], cpuMs=parts[4]+parts[5]
     *
     * Both maps are populated with per-package values (UID split equally
     * across all packages sharing that UID).
     */
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
                // 9,<uid>,l,pwi,uid,<mAh>,...
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
                // 9,<uid>,l,cpu,<user_ms>,<system_ms>,0
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

        // Map UID → package name(s), splitting value equally across shared UIDs
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

    // ──────────────────────────────────────────────────────────────────────────
    // Private — procstats RAM parsing  (FIX 3)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses average PSS RAM from "dumpsys procstats --hours N".
     *
     * Real TOTAL line format:
     *   TOTAL: 100% (346MB-346MB-346MB/161MB-161MB-161MB/288MB-288MB-288MB over 1)
     *   Structure: PSS(min-avg-max) / USS(min-avg-max) / RSS(min-avg-max)
     *
     * We take PSS avg (second value in the first triplet).
     * If there are multiple TOTAL lines per package (different process states),
     * we keep the maximum avg PSS across states.
     */
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

            // TOTAL line with PSS triplet
            if (!line.contains("TOTAL")) continue;
            Matcher pssMatcher = PROCSTATS_PSS.matcher(line);
            if (pssMatcher.find()) {
                try {
                    // group(2) = avg PSS
                    double avgPssMb = Double.parseDouble(pssMatcher.group(2));
                    // Keep max across multiple state rows for the same package
                    ramMbOut.merge(currentPkg, avgPssMb, Math::max);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — /proc/stat CPU baseline (no root required)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Reads the first "cpu" line from /proc/stat and returns cumulative jiffies.
     *
     * /proc/stat first line format:
     *   cpu  user nice system idle iowait irq softirq steal guest guest_nice
     *
     * All values are in USER_HZ units (jiffies). On Android, USER_HZ = 100,
     * so 1 jiffy = 10 ms.  The values are cumulative since boot, summed across
     * ALL cores.
     *
     * Returns long[2]:
     *   [0] totalJiffies  = sum of all fields
     *   [1] activeJiffies = totalJiffies - idle - iowait
     *
     * On failure returns {0, 0} — callers should treat 0 as "no data".
     */
    @WorkerThread
    @NonNull
    private long[] readProcStatJiffies() {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader("/proc/stat"))) {
            String line = br.readLine(); // first line: "cpu  ..."
            if (line == null || !line.startsWith("cpu ")) return new long[]{0, 0};

            String[] parts = line.trim().split("\\s+");
            // parts[0] = "cpu", parts[1..] = jiffie fields
            if (parts.length < 5) return new long[]{0, 0};

            long total  = 0;
            long idle   = 0;
            long iowait = 0;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
                if (i == 4) idle   = v; // field index 4 = idle
                if (i == 5) iowait = v; // field index 5 = iowait
            }
            long active = total - idle - iowait;
            return new long[]{total, active};
        } catch (Exception e) {
            Log.w(TAG, "readProcStatJiffies failed", e);
            return new long[]{0, 0};
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — period aggregation
    // ──────────────────────────────────────────────────────────────────────────

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
        // No fallback to getOldestSnapshot() — if there is no snapshot before the
        // requested period boundary, report "no data" for this period.
        // Without this guard every period with insufficient history (6h, 12h, 24h)
        // would silently reuse the same oldest snapshot and show identical charts.
        if (previous == null || previous.timestamp >= current.timestamp) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_no_history));
        }

        double actualHours = (current.timestamp - previous.timestamp) / 3600_000.0;
        if (actualHours < 0.08) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_too_close));
        }

        // Load all snapshots in the window, ordered by (packageName, timestamp)
        List<ResourceSnapshot> windowSnaps =
                dao.getSnapshotsBetween(previous.timestamp, current.timestamp);

        // Build per-package deltas using consecutive snapshot pairs.
        //
        // Why consecutive pairs instead of first-vs-last:
        //   batteryMah and cpuTimeMs are cumulative since the last charge event.
        //   If the device was charged in the middle of the window, the counter resets
        //   to near-zero. With first-vs-last, a pre-charge first snapshot and a
        //   post-charge last snapshot produce a large negative delta that is clamped
        //   to 0 — losing ALL data for that window (e.g. 12h showing 0 while 6h
        //   shows real data because it falls entirely after the charge event).
        //
        //   With consecutive pairs:
        //     • Normal step (no charge):  positive delta → accumulated correctly.
        //     • Charge-reset step:        negative delta → clamped to 0; only that
        //                                one step is lost; both pre- and post-charge
        //                                contributions are correctly counted.
        Map<String, double[]> perPkg    = new HashMap<>(); // [batteryMah, ramMb, cpuMs]
        Map<String, ResourceSnapshot> prevByPkg = new HashMap<>();

        for (ResourceSnapshot snap : windowSnaps) {
            String pkg = snap.packageName;
            if (!prevByPkg.containsKey(pkg)) {
                prevByPkg.put(pkg, snap);
            } else {
                ResourceSnapshot prev = prevByPkg.get(pkg);
                double dBat = Math.max(0, snap.batteryMah - prev.batteryMah);
                double dCpu = Math.max(0, snap.cpuTimeMs  - prev.cpuTimeMs);
                double dRam = snap.ramMb; // PSS is already an average — use latest value

                double[] acc = perPkg.get(pkg);
                if (acc == null) {
                    acc = new double[]{ dBat, dRam, dCpu };
                } else {
                    acc[0] += dBat;
                    acc[1]  = dRam;
                    acc[2] += dCpu;
                }
                perPkg.put(pkg, acc);
                prevByPkg.put(pkg, snap); // advance the sliding window
            }
        }

        // Resolve app names and compute accurate CPU %.
        //
        // Denominator priority:
        //   1. dTotalJiffies from /proc/stat (stored in snapshots since v4).
        //      Formula: cpuPct = dAppCpuMs / (dTotalJiffies * 10) * 100
        //      dTotalJiffies * 10 converts jiffies → ms (USER_HZ=100 → 1 jiffy=10ms),
        //      giving the total CPU-time capacity across all cores for the period.
        //      This is the most accurate baseline: app load relative to all CPU cores.
        //   2. Fallback to wall-clock elapsed time for snapshots without jiffies data
        //      (rows collected before DB migration to v4, where totalCpuJiffies = 0).
        android.content.pm.PackageManager pm = context.getPackageManager();
        List<AppResourceStats> result = new ArrayList<>();

        // jiffies are cumulative since boot (never reset on charge), so first-vs-last is fine
        long dTotalJiffies = current.totalCpuJiffies - previous.totalCpuJiffies;
        // dTotalJiffies * 10 = total CPU capacity in ms across all cores for the period
        double cpuDenominatorMs = dTotalJiffies > 0
                ? dTotalJiffies * 10.0
                : actualHours * 3600_000.0; // fallback: wall-clock ms (single-core equivalent)

        for (Map.Entry<String, double[]> e : perPkg.entrySet()) {
            String pkg = e.getKey();
            double[] v = e.getValue();
            String name = resolveAppName(pm, pkg);
            // v[2] = total CPU ms accumulated by this app over the period
            double cpuPct = cpuDenominatorMs > 0 ? v[2] / cpuDenominatorMs * 100.0 : 0;
            result.add(new AppResourceStats(pkg, name, v[0], cpuPct, v[1],
                    pkg.equals(context.getPackageName())));
        }

        result.sort((a, b) -> Double.compare(b.batteryMah, a.batteryMah));
        return new PeriodStats(result, true, actualHours, null);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — hourly breakdown
    // ──────────────────────────────────────────────────────────────────────────

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

            double dBat   = Math.max(0, curr.batteryMah - prev.batteryMah);
            double dCpuMs = Math.max(0, curr.cpuTimeMs  - prev.cpuTimeMs);

            // Use /proc/stat jiffies as denominator when available (v4+ snapshots).
            // Fallback: actual elapsed ms between the two snapshots.
            long dTotalJiffies = curr.totalCpuJiffies - prev.totalCpuJiffies;
            double cpuDenominatorMs = dTotalJiffies > 0
                    ? dTotalJiffies * 10.0
                    : (double)(curr.timestamp - prev.timestamp);
            double cpuPct = cpuDenominatorMs > 0
                    ? (dCpuMs / cpuDenominatorMs) * 100.0
                    : 0.0;
            double ram = curr.ramMb;

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(bucketStart);
            String label = String.format(Locale.US, "%02d:00",
                    cal.get(java.util.Calendar.HOUR_OF_DAY));
            points.add(new HourlyPoint(label, dBat, cpuPct, ram));
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

    private static String resolveAppName(android.content.pm.PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
