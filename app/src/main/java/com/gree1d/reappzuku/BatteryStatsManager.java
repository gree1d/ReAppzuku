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
     * Matches per-app cpu= lines inside the non-checkin batterystats output.
     * Used to obtain cumulative CPU time in ms.
     *
     * Example:
     *   Proc com.example.app:
     *     CPU: 3m 53s 330ms usr + 2m 22s 40ms krn ; 1m 21s 260ms fg
     *
     * We capture the package name from the "Proc" line and parse the "CPU:" line
     * for total user+kernel time.
     */
    private static final Pattern BSTATS_PROC_LINE =
            Pattern.compile("^\\s+Proc ([\\w./:]+):");

    /**
     * Matches a CPU summary line such as:
     *   CPU: 3m 53s 330ms usr + 2m 22s 40ms krn ; ...
     * We parse every time component (Nm, Ns, Nms) and sum them to milliseconds.
     */
    private static final Pattern CPU_TIME_COMPONENT =
            Pattern.compile("(\\d+)m|(\\d+)s(?!\\d)|(\\d+)ms");

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

        // 1. Battery (mAh) from batterystats --checkin pwi uid lines
        Map<String, Double> batteryMahByPkg = collectBatteryStats();

        // 2. RAM (PSS MB) from procstats
        Map<String, Double> ramMbByPkg = new HashMap<>();
        collectProcStatsRam(24, ramMbByPkg);

        // 3. CPU time (ms) from batterystats non-checkin Proc/CPU lines
        Map<String, Long> cpuMsByPkg = collectCpuStats();

        // 4. Merge into one snapshot row per package
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

        // 5. Prune old snapshots (keep 3 days)
        dao.deleteOlderThan(now - 3 * 24 * 3600_000L);

        Log.d(TAG, "Snapshot saved: " + allPkgs.size() + " apps"
                + "  battery=" + batteryMahByPkg.size()
                + "  ram=" + ramMbByPkg.size()
                + "  cpu=" + cpuMsByPkg.size());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — battery stats parsing  (FIX 1)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses "dumpsys batterystats --charged --checkin" pwi uid lines.
     *
     * Real line format:
     *   9,<uid>,l,pwi,uid,<mAh>,1,<fg_mAh>,0
     *
     * uid  = parts[1]   (NOT parts[pwiIdx+2] — that would be the mAh value)
     * mAh  = parts[5]   (first field after the literal "uid" token at parts[4])
     */
    @WorkerThread
    @NonNull
    private Map<String, Double> collectBatteryStats() {
        String cmd = "dumpsys batterystats --charged --checkin";
        String output = shellManager.runCommandAndGetOutput(cmd);
        if (output == null || output.isEmpty()) return Collections.emptyMap();

        Map<Integer, Double> uidToMah = new HashMap<>();
        for (String line : output.split("\n")) {
            if (!PWI_UID_LINE.matcher(line).find()) continue;
            try {
                String[] parts = line.split(",");
                // parts[1] = UID, parts[5] = mAh  (format: 9,<uid>,l,pwi,uid,<mAh>,...)
                if (parts.length < 6) continue;
                int uid      = Integer.parseInt(parts[1].trim());
                double mah   = Double.parseDouble(parts[5].trim());
                if (mah > 0) uidToMah.put(uid, mah);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                Log.w(TAG, "pwi parse error: " + line, e);
            }
        }

        // Map UID → package name(s)
        Map<String, Double> result = new HashMap<>();
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (Map.Entry<Integer, Double> entry : uidToMah.entrySet()) {
            String[] pkgs = pm.getPackagesForUid(entry.getKey());
            if (pkgs == null || pkgs.length == 0) continue;
            double share = entry.getValue() / pkgs.length;
            for (String pkg : pkgs) result.merge(pkg, share, Double::sum);
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private — CPU stats parsing  (FIX 2)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses per-app cumulative CPU time from the non-checkin batterystats output.
     *
     * Looks for blocks like:
     *   Proc com.example.app:
     *     CPU: 3m 53s 330ms usr + 2m 22s 40ms krn ; ...
     *
     * Returns map of package → cumulative CPU ms (user + kernel).
     */
    @WorkerThread
    @NonNull
    private Map<String, Long> collectCpuStats() {
        String cmd = "dumpsys batterystats --charged";
        String output = shellManager.runCommandAndGetOutput(cmd);
        if (output == null || output.isEmpty()) return Collections.emptyMap();

        Map<String, Long> result = new HashMap<>();
        String currentPkg = null;

        for (String line : output.split("\n")) {
            Matcher procMatcher = BSTATS_PROC_LINE.matcher(line);
            if (procMatcher.find()) {
                currentPkg = procMatcher.group(1);
                // Strip trailing ":isolatedXXX" or "/XXX" suffixes to get base package
                if (currentPkg != null && currentPkg.contains("/")) {
                    currentPkg = currentPkg.split("/")[0];
                }
                continue;
            }

            if (currentPkg == null) continue;

            // CPU line: "CPU: Xm Ys Zms usr + Am Bs Cms krn ; ..."
            String trimmed = line.trim();
            if (!trimmed.startsWith("CPU:")) continue;

            // Sum all time components before the ";" (user + kernel)
            String beforeSemicolon = trimmed.contains(";")
                    ? trimmed.substring(0, trimmed.indexOf(';'))
                    : trimmed;
            long totalMs = 0;
            Matcher m = CPU_TIME_COMPONENT.matcher(beforeSemicolon);
            while (m.find()) {
                if (m.group(1) != null) totalMs += Long.parseLong(m.group(1)) * 60_000L;
                if (m.group(2) != null) totalMs += Long.parseLong(m.group(2)) * 1_000L;
                if (m.group(3) != null) totalMs += Long.parseLong(m.group(3));
            }
            if (totalMs > 0) {
                result.merge(currentPkg, totalMs, Long::sum);
            }
            currentPkg = null; // CPU line always follows immediately after Proc line
        }
        return result;
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
                    "Данные ещё не собраны. Подождите ~30 минут.");
        }
        if (previous == null) {
            // Not enough history to cover the full requested window.
            // Fall back to the oldest available snapshot so we can still show
            // partial data (e.g. 20 min instead of 2 h).
            previous = dao.getOldestSnapshot();
        }
        if (previous == null || previous.timestamp >= current.timestamp) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    "Недостаточно истории. Данные накапливаются — зайдите позже.");
        }

        double actualHours = (current.timestamp - previous.timestamp) / 3600_000.0;
        if (actualHours < 0.08) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    "Снапшоты слишком близко. Попробуйте позже.");
        }

        // Load all snapshots in the window, ordered by (packageName, timestamp)
        List<ResourceSnapshot> windowSnaps =
                dao.getSnapshotsBetween(previous.timestamp, current.timestamp);

        // Build per-package deltas
        Map<String, double[]> perPkg = new HashMap<>(); // [batteryMah, ramMb, cpuMs]
        Map<String, ResourceSnapshot> firstByPkg = new HashMap<>();

        for (ResourceSnapshot snap : windowSnaps) {
            if (!firstByPkg.containsKey(snap.packageName)) {
                firstByPkg.put(snap.packageName, snap);
            } else {
                ResourceSnapshot f = firstByPkg.get(snap.packageName);
                // batteryMah and cpuTimeMs are cumulative since last charge.
                // Clamp negatives to 0 (charge event between snapshots resets the counter).
                double dBat = Math.max(0, snap.batteryMah - f.batteryMah);
                double dCpu = Math.max(0, snap.cpuTimeMs  - f.cpuTimeMs);
                double dRam = snap.ramMb; // PSS is already an average — use latest value
                perPkg.put(snap.packageName, new double[]{ dBat, dRam, dCpu });
            }
        }

        // Resolve app names and compute CPU fractions
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

            double dBat = Math.max(0, curr.batteryMah - prev.batteryMah);
            double dCpuMs = Math.max(0, curr.cpuTimeMs - prev.cpuTimeMs);
            // Express CPU as % of wall-clock time in this bucket
            double cpuPct = (dCpuMs / 3600_000.0) * 100.0;
            double ram    = curr.ramMb;

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
