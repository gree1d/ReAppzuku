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
            Pattern.compile(
                "(\\d+(?:[.,]\\d+)?)MB-(\\d+(?:[.,]\\d+)?)MB-(\\d+(?:[.,]\\d+)?)MB"
            );


    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private volatile ResourceSnapshotDao dao;


    private volatile double cachedCapacityMah = -1;


    private volatile int cachedCpuCoreCount = 0;


    public BatteryStatsManager(@NonNull Context context,
                               @NonNull Handler handler,
                               @NonNull ExecutorService executor,
                               @NonNull ShellManager shellManager) {
        this.context      = context.getApplicationContext();
        this.handler      = handler;
        this.executor     = executor;
        this.shellManager = shellManager;
    }


    public BatteryStatsManager(@NonNull Context context,
                               @NonNull ShellManager shellManager) {
        this.context      = context.getApplicationContext();
        this.handler      = new Handler(Looper.getMainLooper());
        this.executor     = java.util.concurrent.Executors.newSingleThreadExecutor();
        this.shellManager = shellManager;
    }


    private ResourceSnapshotDao getDao() {
        if (dao == null) {
            dao = AppDatabase.getInstance(context).resourceSnapshotDao();
        }
        return dao;
    }


    public static class AppResourceStats {
        public final String packageName;
        public final String appName;

        public final double batteryMah;

        public final double cpuPct;

        public final double ramMb;

        public final double peakRamMb;

        public final boolean isSelf;

        public AppResourceStats(String packageName, String appName,
                                double batteryMah, double cpuPct, double ramMb,
                                double peakRamMb, boolean isSelf) {
            this.packageName = packageName;
            this.appName     = appName;
            this.batteryMah  = batteryMah;
            this.cpuPct      = cpuPct;
            this.ramMb       = ramMb;
            this.peakRamMb   = peakRamMb;
            this.isSelf      = isSelf;
        }
    }


    public static class PeriodStats {
        public final List<AppResourceStats> sorted;
        public final boolean hasData;
        public final double actualHours;
        public final String dataHint;

        public final boolean isPartialData;

        PeriodStats(List<AppResourceStats> sorted, boolean hasData,
                    double actualHours, String dataHint, boolean isPartialData) {
            this.sorted        = sorted;
            this.hasData       = hasData;
            this.actualHours   = actualHours;
            this.dataHint      = dataHint;
            this.isPartialData = isPartialData;
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


    public interface StatsCallback  { void onResult(PeriodStats stats); }
    public interface HourlyCallback { void onResult(HourlyResult result); }


    public enum ActivityLevel {
        NONE,
        LOW,
        MEDIUM,
        HIGH
    }


    public static class ActivitySlice {
        public final long slotTimestamp;
        public final ActivityLevel level;
        public final double cpuPercent;
        public final double ramMb;

        ActivitySlice(long slotTimestamp, ActivityLevel level, double cpuPercent, double ramMb) {
            this.slotTimestamp = slotTimestamp;
            this.level         = level;
            this.cpuPercent    = cpuPercent;
            this.ramMb         = ramMb;
        }
    }


    public static class HourlyPeriodStats {
        public final double minBatteryMah, avgBatteryMah, maxBatteryMah;
        public final double minCpuPct,     avgCpuPct,     maxCpuPct;
        public final double minRamMb,      avgRamMb,      maxRamMb;

        HourlyPeriodStats(double minBat, double avgBat, double maxBat,
                    double minCpu, double avgCpu, double maxCpu,
                    double minRam, double avgRam, double maxRam) {
            minBatteryMah = minBat; avgBatteryMah = avgBat; maxBatteryMah = maxBat;
            minCpuPct     = minCpu; avgCpuPct     = avgCpu; maxCpuPct     = maxCpu;
            minRamMb      = minRam; avgRamMb      = avgRam; maxRamMb      = maxRam;
        }
    }


    public static class HourlyResult {
        public final List<ActivitySlice> slices;
        public final HourlyPeriodStats stats;
        public final boolean isPartialData;

        HourlyResult(List<ActivitySlice> slices, HourlyPeriodStats stats, boolean isPartialData) {
            this.slices        = slices;
            this.stats         = stats;
            this.isPartialData = isPartialData;
        }


        static HourlyResult empty(boolean isPartialData) {
            return new HourlyResult(new ArrayList<>(), null, isPartialData);
        }
    }


    @Deprecated
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

        try {

            ResourceSnapshot last = getDao().getLatestSnapshot();
            if (last != null && (now - last.timestamp) < MIN_SNAPSHOT_INTERVAL_MS) {
                Log.d(TAG, "Snapshot skipped — too soon after last one");
                return;
            }


            Map<String, Double> batteryMahByPkg = new HashMap<>();
            Map<String, Long>   cpuMsByPkg      = new HashMap<>();
            try {
                collectCheckinStats(batteryMahByPkg, cpuMsByPkg);
            } catch (Exception e) {
                Log.e(TAG, "collectCheckinStats failed, battery/cpu data will be empty", e);
            }


            Map<String, Double> ramMbByPkg = new HashMap<>();
            try {
                collectProcStatsRam(24, ramMbByPkg);
                if (ramMbByPkg.isEmpty()) {
                    Log.d(TAG, "procstats returned no RAM data, trying meminfo fallback");
                    collectMeminfoRam(ramMbByPkg);
                }
            } catch (Exception e) {
                Log.e(TAG, "RAM collection failed, trying meminfo fallback", e);
                try {
                    collectMeminfoRam(ramMbByPkg);
                } catch (Exception e2) {
                    Log.e(TAG, "meminfo fallback also failed, RAM data will be empty", e2);
                }
            }


            long[] jiffies = readProcStatJiffies();


            android.os.BatteryManager bm =
                    (android.os.BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int batteryLevel = bm != null
                    ? bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    : 50;


            java.util.Set<String> allPkgs = new java.util.HashSet<>();
            allPkgs.addAll(batteryMahByPkg.keySet());
            allPkgs.addAll(ramMbByPkg.keySet());
            allPkgs.addAll(cpuMsByPkg.keySet());


            double totalRawPwiBatch = 0;
            for (double v : batteryMahByPkg.values()) totalRawPwiBatch += v;

            for (String pkg : allPkgs) {
                ResourceSnapshot snap = new ResourceSnapshot();
                snap.timestamp        = now;
                snap.packageName      = pkg;
                snap.batteryMah       = getOrZero(batteryMahByPkg, pkg);
                snap.ramMb            = getOrZero(ramMbByPkg, pkg);
                snap.cpuTimeMs        = cpuMsByPkg.containsKey(pkg) ? cpuMsByPkg.get(pkg) : 0L;
                snap.totalCpuJiffies  = jiffies[0];
                snap.activeCpuJiffies = jiffies[1];
                snap.batteryLevelPct  = batteryLevel;
                snap.totalRawPwiBatch = totalRawPwiBatch;
                getDao().insert(snap);
            }


            getDao().deleteOlderThan(now - 24 * 3600_000L);

            Log.d(TAG, "Snapshot saved: " + allPkgs.size() + " apps"
                    + "  battery=" + batteryMahByPkg.size()
                    + "  ram=" + ramMbByPkg.size()
                    + "  cpu=" + cpuMsByPkg.size());

        } catch (Exception e) {
            Log.e(TAG, "takeSnapshotBlocking: unexpected error, snapshot aborted", e);
        }
    }


    @WorkerThread
    public double getBatteryCapacityMah() {
        if (cachedCapacityMah > 0) return cachedCapacityMah;


        String[] sysPaths = {
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/charge_full"
        };
        for (String path : sysPaths) {
            try {
                String line = shellManager.runCommandAndGetOutput("cat " + path);
                if (line != null && !line.isEmpty()) {
                    long uah = Long.parseLong(line.trim());
                    if (uah > 100_000) {
                        cachedCapacityMah = uah / 1000.0;
                        Log.d(TAG, "Battery capacity from " + path + ": " + cachedCapacityMah + " mAh");
                        return cachedCapacityMah;
                    }
                }
            } catch (Exception ignored) {}
        }


        try {
            String output = shellManager.runCommandAndGetOutput(
                    "dumpsys batterystats | grep -m1 'Capacity:'");
            if (output != null) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("Capacity:\\s*(\\d+)").matcher(output);
                if (m.find()) {
                    double cap = parseLocaleDouble(m.group(1));
                    if (cap > 100) {
                        cachedCapacityMah = cap;
                        Log.d(TAG, "Battery capacity from dumpsys: " + cachedCapacityMah + " mAh");
                        return cachedCapacityMah;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "dumpsys batterystats capacity read failed", e);
        }


        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int chargeUah = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (chargeUah > 500_000) {


                    int levelPct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (levelPct > 5 && levelPct <= 100) {
                        double estimatedCapacity = (chargeUah / 1000.0) / (levelPct / 100.0);
                        if (estimatedCapacity > 500 && estimatedCapacity < 30_000) {
                            cachedCapacityMah = estimatedCapacity;
                            Log.d(TAG, "Battery capacity estimated from BatteryManager: "
                                    + cachedCapacityMah + " mAh (level=" + levelPct + "%)");
                            return cachedCapacityMah;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "BatteryManager capacity read failed", e);
        }

        Log.w(TAG, "Could not read battery capacity, using 4000 mAh fallback");
        cachedCapacityMah = 4000.0;
        return cachedCapacityMah;
    }


    @WorkerThread
    private int getCpuCoreCount() {
        if (cachedCpuCoreCount > 0) return cachedCpuCoreCount;
        try {
            String output = shellManager.runCommandAndGetOutput(
                    "cat /sys/devices/system/cpu/present");
            if (output != null && !output.isEmpty()) {
                String[] parts = output.trim().split("-");
                cachedCpuCoreCount = parts.length == 2
                        ? Integer.parseInt(parts[1]) + 1
                        : 1;
                Log.d(TAG, "CPU cores: " + cachedCpuCoreCount);
                return cachedCpuCoreCount;
            }
        } catch (Exception e) {
            Log.w(TAG, "readCpuCoreCount failed", e);
        }
        cachedCpuCoreCount = Runtime.getRuntime().availableProcessors();
        return cachedCpuCoreCount;
    }


    @WorkerThread
    private void collectCheckinStats(@NonNull Map<String, Double> batteryMahOut,
                                     @NonNull Map<String, Long> cpuMsOut) {
        String cmd = "dumpsys batterystats --charged --checkin";
        String output = shellManager.runCommandAndGetOutput(cmd);


        boolean hasCheckinData = output != null && (output.contains(",l,pwi,") || output.contains(",l,cpu,"));

        if (!hasCheckinData) {
            Log.d(TAG, "--checkin returned no usable data, trying human-readable fallback");
            collectCheckinStatsFallback(batteryMahOut, cpuMsOut);
            return;
        }

        Map<Integer, Double> uidToMah   = new HashMap<>();
        Map<Integer, Long>   uidToCpuMs = new HashMap<>();

        for (String line : output.split("\n")) {
            if (PWI_UID_LINE.matcher(line).find()) {

                try {
                    String[] parts = line.split(",");
                    if (parts.length < 6) continue;
                    int uid    = Integer.parseInt(parts[1].trim());


                    if (uid >= 100_000) continue;
                    double mah = parseLocaleDouble(parts[5].trim());
                    if (mah > 0) uidToMah.put(uid, mah);
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    Log.w(TAG, "pwi parse error: " + line, e);
                }
            } else if (CPU_UID_LINE.matcher(line).find()) {

                try {
                    String[] parts = line.split(",");
                    if (parts.length < 6) continue;
                    int uid = Integer.parseInt(parts[1].trim());
                    if (uid >= 100_000) continue;
                    long userMs   = parseLocaleDoubleToLong(parts[4].trim());
                    long systemMs = parseLocaleDoubleToLong(parts[5].trim());
                    long total    = userMs + systemMs;
                    if (total > 0) uidToCpuMs.put(uid, total);
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    Log.w(TAG, "cpu parse error: " + line, e);
                }
            }
        }

        mapUidsToPkgs(uidToMah, uidToCpuMs, batteryMahOut, cpuMsOut);
    }


    private static final Pattern HR_UID_HEADER =
            Pattern.compile("^\\s+Uid\\s+u0a(\\d+):");
    private static final Pattern HR_CPU_LINE =
            Pattern.compile("cpu=(\\d+(?:[.,]\\d+)?)ms\\s+usr\\s*\\+\\s*(\\d+(?:[.,]\\d+)?)ms\\s+krn");

    @WorkerThread
    private void collectCheckinStatsFallback(@NonNull Map<String, Double> batteryMahOut,
                                             @NonNull Map<String, Long> cpuMsOut) {
        String output = shellManager.runCommandAndGetOutput("dumpsys batterystats --charged");
        if (output == null || output.isEmpty()) {
            Log.w(TAG, "batterystats fallback also returned empty output");
            return;
        }

        Map<Integer, Long> uidToCpuMs = new HashMap<>();
        int currentAppUid = -1;

        for (String line : output.split("\n")) {
            Matcher uidMatcher = HR_UID_HEADER.matcher(line);
            if (uidMatcher.find()) {
                try {
                    currentAppUid = Integer.parseInt(uidMatcher.group(1)) + 10000;
                } catch (NumberFormatException e) {
                    currentAppUid = -1;
                }
                continue;
            }
            if (currentAppUid < 0) continue;

            Matcher cpuMatcher = HR_CPU_LINE.matcher(line);
            if (cpuMatcher.find()) {
                long userMs   = parseLocaleDoubleToLong(cpuMatcher.group(1));
                long systemMs = parseLocaleDoubleToLong(cpuMatcher.group(2));
                long total    = userMs + systemMs;
                if (total > 0) uidToCpuMs.merge(currentAppUid, total, Long::sum);
            }
        }

        Log.d(TAG, "batterystats fallback: parsed cpu for " + uidToCpuMs.size() + " UIDs");
        mapUidsToPkgs(new HashMap<>(), uidToCpuMs, batteryMahOut, cpuMsOut);
    }


    @WorkerThread
    private void mapUidsToPkgs(@NonNull Map<Integer, Double> uidToMah,
                                @NonNull Map<Integer, Long> uidToCpuMs,
                                @NonNull Map<String, Double> batteryMahOut,
                                @NonNull Map<String, Long> cpuMsOut) {
        android.content.pm.PackageManager pm = context.getPackageManager();

        for (Map.Entry<Integer, Double> e : uidToMah.entrySet()) {
            String[] pkgs;
            try {
                pkgs = pm.getPackagesForUid(e.getKey());
            } catch (SecurityException ex) {
                Log.d(TAG, "getPackagesForUid(" + e.getKey() + ") denied: " + ex.getMessage());
                continue;
            }
            if (pkgs == null || pkgs.length == 0) continue;
            double share = e.getValue() / pkgs.length;
            for (String pkg : pkgs) batteryMahOut.merge(pkg, share, Double::sum);
        }

        for (Map.Entry<Integer, Long> e : uidToCpuMs.entrySet()) {
            String[] pkgs;
            try {
                pkgs = pm.getPackagesForUid(e.getKey());
            } catch (SecurityException ex) {
                Log.d(TAG, "getPackagesForUid(" + e.getKey() + ") denied: " + ex.getMessage());
                continue;
            }
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

                    double avgPssMb = parseLocaleDouble(pssMatcher.group(2));

                    ramMbOut.merge(currentPkg, avgPssMb, Math::max);
                } catch (NumberFormatException ignored) {}
            }
        }
    }


    private static final Pattern MEMINFO_PKG =
            Pattern.compile("^\\s*(\\d+)\\s+kB:\\s+([\\w.:]+)\\s+\\(pid");

    @WorkerThread
    private void collectMeminfoRam(@NonNull Map<String, Double> ramMbOut) {
        String output = shellManager.runCommandAndGetOutput("dumpsys meminfo -a");
        if (output == null || output.isEmpty()) return;

        int parsedCount = 0;
        for (String line : output.split("\n")) {
            Matcher m = MEMINFO_PKG.matcher(line);
            if (!m.find()) continue;
            try {
                double pssKb = parseLocaleDouble(m.group(1));
                String pkg   = m.group(2);
                if (pkg == null || pkg.isEmpty() || pssKb <= 0) continue;

                int colon = pkg.indexOf(':');
                if (colon > 0) pkg = pkg.substring(0, colon);
                double pssMb = pssKb / 1024.0;
                ramMbOut.merge(pkg, pssMb, Math::max);
                parsedCount++;
            } catch (Exception ignored) {}
        }
        Log.d(TAG, "meminfo fallback: parsed RAM for " + parsedCount + " entries → "
                + ramMbOut.size() + " packages");
    }


    @WorkerThread
    @NonNull
    private long[] readProcStatJiffies() {
        try {
            String output = shellManager.runCommandAndGetOutput("cat /proc/stat");
            if (output == null || output.isEmpty()) return new long[]{0, 0};


            String line = output.split("\n")[0];
            if (!line.startsWith("cpu ")) return new long[]{0, 0};

            String[] parts = line.trim().split("\\s+");

            if (parts.length < 5) return new long[]{0, 0};

            long total  = 0;
            long idle   = 0;
            long iowait = 0;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
                if (i == 4) idle   = v;
                if (i == 5) iowait = v;
            }
            long active = total - idle - iowait;
            return new long[]{total, active};
        } catch (Exception e) {
            Log.w(TAG, "readProcStatJiffies failed", e);
            return new long[]{0, 0};
        }
    }


    @WorkerThread
    @NonNull
    private PeriodStats getStatsForPeriodBlocking(int hours) {
        long now    = System.currentTimeMillis();
        long target = now - (long) hours * 3600_000L;

        ResourceSnapshot current = getDao().getLatestSnapshot();

        if (current == null) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_no_snapshot), false);
        }


        ResourceSnapshot previous = getDao().getClosestSnapshotBefore(target);

        if (previous == null) {


            ResourceSnapshot oldest = getDao().getOldestSnapshot();
            if (oldest != null && oldest.timestamp < current.timestamp) {
                double oldestHoursFromTarget =
                        (oldest.timestamp - target) / 3600_000.0;
                if (hours == 2 || oldestHoursFromTarget <= hours * 0.1) {
                    previous = oldest;
                }
            }
        }

        if (previous == null || previous.timestamp >= current.timestamp) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_no_history), false);
        }

        double actualHours = (current.timestamp - previous.timestamp) / 3600_000.0;
        if (actualHours < 0.08) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_too_close), false);
        }


        if (hours > 2 && actualHours < hours * 0.9) {
            return new PeriodStats(Collections.emptyList(), false, 0,
                    context.getString(R.string.stats_no_data_hint_no_history), false);
        }


        boolean isPartialData = (hours == 2) && (actualHours < hours * 0.9);


        List<ResourceSnapshot> windowSnaps =
                getDao().getSnapshotsBetween(previous.timestamp, current.timestamp);

        Map<String, double[]> perPkg     = new HashMap<>();
        Map<String, ResourceSnapshot> prevByPkg = new HashMap<>();

        for (ResourceSnapshot snap : windowSnaps) {
            String pkg = snap.packageName;
            if (!prevByPkg.containsKey(pkg)) {
                prevByPkg.put(pkg, snap);
                perPkg.put(pkg, new double[]{ 0, snap.ramMb, 0, snap.ramMb, 1 });
            } else {
                ResourceSnapshot prev = prevByPkg.get(pkg);
                double dBat = snap.batteryMah >= prev.batteryMah
                        ? snap.batteryMah - prev.batteryMah
                        : snap.batteryMah;
                double dCpu = Math.max(0, snap.cpuTimeMs - prev.cpuTimeMs);

                double[] acc = perPkg.get(pkg);
                acc[0] += dBat;
                acc[1] += snap.ramMb;
                acc[2] += dCpu;
                acc[3]  = Math.max(acc[3], snap.ramMb);
                acc[4] += 1;
                prevByPkg.put(pkg, snap);
            }
        }

        android.content.pm.PackageManager pm = context.getPackageManager();
        List<AppResourceStats> result = new ArrayList<>();

        double totalRawPwi = 0;
        for (double[] v : perPkg.values()) totalRawPwi += v[0];

        java.util.TreeMap<Long, Integer> levelByTime = new java.util.TreeMap<>();
        for (ResourceSnapshot s : windowSnaps) {
            if (s.batteryLevelPct > 0) {
                levelByTime.put(s.timestamp, s.batteryLevelPct);
            }
        }

        double drainMah = 0;
        boolean batteryNormalizationValid = false;
        long dTotalJiffies = current.totalCpuJiffies - previous.totalCpuJiffies;

        if (totalRawPwi > 0 && levelByTime.size() >= 2) {
            double capacityMah = getBatteryCapacityMah();
            double totalDrainPct = 0;
            Integer prevLevel = null;
            for (Integer lvl : levelByTime.values()) {
                if (prevLevel != null && lvl < prevLevel) {
                    totalDrainPct += prevLevel - lvl;
                }
                prevLevel = lvl;
            }
            if (totalDrainPct > 0) {
                drainMah = totalDrainPct / 100.0 * capacityMah;
                batteryNormalizationValid = true;
            }
        }

        double cpuDenominatorMs = dTotalJiffies > 0
                ? (dTotalJiffies * 10.0)
                : actualHours * 3600_000.0;

        for (Map.Entry<String, double[]> e : perPkg.entrySet()) {
            String pkg = e.getKey();
            double[] v = e.getValue();
            String name = resolveAppName(pm, pkg);

            double avgRamMb  = v[4] > 0 ? v[1] / v[4] : 0;
            double peakRamMb = v[3];
            double batteryMah = batteryNormalizationValid && totalRawPwi > 0
                    ? (v[0] / totalRawPwi) * drainMah
                    : 0;
            double cpuPct = Math.min(100.0,
                    cpuDenominatorMs > 0 ? v[2] / cpuDenominatorMs * 100.0 : 0);

            result.add(new AppResourceStats(pkg, name, batteryMah, cpuPct, avgRamMb, peakRamMb,
                    pkg.equals(context.getPackageName())));
        }

        result.sort((a, b) -> Double.compare(b.batteryMah, a.batteryMah));
        return new PeriodStats(result, true, actualHours, null, isPartialData);
    }


    public void getHourlyStatsAsync(String packageName, int hours,
                                    double totalAllAppsCpuPct, double totalAllAppsRamMb,
                                    @NonNull HourlyCallback callback) {
        executor.execute(() -> {
            HourlyResult result = getHourlyStatsBlocking(
                    packageName, hours, totalAllAppsCpuPct, totalAllAppsRamMb);
            handler.post(() -> callback.onResult(result));
        });
    }

    @WorkerThread
    @NonNull
    private HourlyResult getHourlyStatsBlocking(String packageName, int hours,
                                                 double totalAllAppsCpuPct,
                                                 double totalAllAppsRamMb) {
        long now = System.currentTimeMillis();

        java.util.Calendar endCal = java.util.Calendar.getInstance();
        endCal.setTimeInMillis(now);
        endCal.set(java.util.Calendar.MINUTE, 0);
        endCal.set(java.util.Calendar.SECOND, 0);
        endCal.set(java.util.Calendar.MILLISECOND, 0);
        long endAligned   = endCal.getTimeInMillis();
        long startAligned = endAligned - (long) hours * 3600_000L;

        List<ResourceSnapshot> snaps =
                getDao().getSnapshotsForPackageBetween(packageName, startAligned, endAligned);

        ResourceSnapshot oldestForPkg = getDao().getOldestSnapshotForPackage(packageName);
        long oldestPkgTime = oldestForPkg != null ? oldestForPkg.timestamp : endAligned;
        double pkgAgeHours = (endAligned - oldestPkgTime) / 3600_000.0;
        boolean isPartialData = (hours == 2) && (pkgAgeHours < hours * 0.9);

        if (snaps.size() < 2) return HourlyResult.empty(isPartialData);
        if (hours > 2 && pkgAgeHours < hours * 0.9) return HourlyResult.empty(false);

        double periodTotalDrainPct = 0, periodTotalBatRaw = 0, periodTotalCpuMs = 0;
        double periodTotalRawPwiBatch = 0;
        int    periodBatchCount = 0;
        {
            Integer prevLevel = null;
            for (int i = 0; i < snaps.size(); i++) {
                ResourceSnapshot s = snaps.get(i);
                if (s.batteryLevelPct > 0) {
                    if (prevLevel != null && s.batteryLevelPct < prevLevel)
                        periodTotalDrainPct += prevLevel - s.batteryLevelPct;
                    prevLevel = s.batteryLevelPct;
                }
                if (i > 0) {
                    ResourceSnapshot p = snaps.get(i - 1);
                    double dBat = s.batteryMah >= p.batteryMah
                            ? s.batteryMah - p.batteryMah : s.batteryMah;
                    periodTotalBatRaw += dBat;
                    periodTotalCpuMs  += Math.max(0, s.cpuTimeMs - p.cpuTimeMs);
                }
                if (s.totalRawPwiBatch > 0) { periodTotalRawPwiBatch += s.totalRawPwiBatch; periodBatchCount++; }
            }
        }
        double periodDrainMah  = periodTotalDrainPct / 100.0 * getBatteryCapacityMah();
        double avgPeriodBatch  = periodBatchCount > 0 ? periodTotalRawPwiBatch / periodBatchCount : 0;
        double periodAppMah    = (periodTotalDrainPct > 0 && avgPeriodBatch > 0 && periodTotalBatRaw > 0)
                ? (periodTotalBatRaw / avgPeriodBatch) * periodDrainMah : 0;
        boolean batValid       = periodAppMah > 0 && periodTotalCpuMs > 0;

        int numSlots = hours * 2;
        double avgAllCpuPerSlot = totalAllAppsCpuPct > 0 ? totalAllAppsCpuPct / numSlots : 0;
        double avgAllRamPerSlot = totalAllAppsRamMb  > 0 ? totalAllAppsRamMb  / numSlots : 0;

        final long SLOT_MS = 30 * 60 * 1000L;
        List<ActivitySlice> slices = new ArrayList<>();

        List<Double> slotBatList = new ArrayList<>();
        List<Double> slotCpuList = new ArrayList<>();
        List<Double> slotRamList = new ArrayList<>();

        for (int s = 0; s < numSlots; s++) {
            long slotStart = startAligned + (long) s * SLOT_MS;
            long slotEnd   = slotStart + SLOT_MS;

            double slotCpuMs = 0, slotRamSum = 0;
            int    slotRamCount = 0;
            long   slotJiffies = 0, slotFirstTs = -1, slotLastTs = -1;

            for (int i = 1; i < snaps.size(); i++) {
                ResourceSnapshot prev = snaps.get(i - 1);
                ResourceSnapshot curr = snaps.get(i);
                if (curr.timestamp <= slotStart || curr.timestamp > slotEnd) continue;

                slotCpuMs    += Math.max(0, curr.cpuTimeMs - prev.cpuTimeMs);
                slotJiffies  += Math.max(0, curr.totalCpuJiffies - prev.totalCpuJiffies);
                slotRamSum   += curr.ramMb;
                slotRamCount++;
                if (slotFirstTs < 0) slotFirstTs = prev.timestamp;
                slotLastTs = curr.timestamp;
            }

            double slotBat = 0;
            if (batValid && slotCpuMs > 0)
                slotBat = (slotCpuMs / periodTotalCpuMs) * periodAppMah;

            double slotCpuDenMs = slotJiffies > 0 ? slotJiffies * 10.0
                    : (slotFirstTs >= 0 ? (double)(slotLastTs - slotFirstTs) : 0);
            double slotCpu = (slotRamCount > 0 && slotCpuDenMs > 0)
                    ? Math.min(100.0, slotCpuMs / slotCpuDenMs * 100.0) : 0;

            double slotRam = slotRamCount > 0 ? slotRamSum / slotRamCount : 0;

            ActivityLevel level;
            if (slotRamCount == 0) {
                level = ActivityLevel.NONE;
            } else {
                double cpuRatio = avgAllCpuPerSlot > 0 ? slotCpu / avgAllCpuPerSlot : 0;
                double ramRatio = avgAllRamPerSlot > 0 ? slotRam / avgAllRamPerSlot : 0;
                double score = cpuRatio * 0.6 + ramRatio * 0.4;
                if (score <= 0.0)       level = ActivityLevel.NONE;
                else if (score < 0.5)   level = ActivityLevel.LOW;
                else if (score < 1.5)   level = ActivityLevel.MEDIUM;
                else                    level = ActivityLevel.HIGH;
            }

            slices.add(new ActivitySlice(slotStart, level, slotCpu, slotRam));
            if (slotRamCount > 0) {
                slotBatList.add(slotBat);
                slotCpuList.add(slotCpu);
                slotRamList.add(slotRam);
            }
        }

        slices.add(new ActivitySlice(endAligned, ActivityLevel.NONE, 0, 0));

        HourlyPeriodStats periodStats = null;
        if (!slotBatList.isEmpty()) {
            double minBat = Double.MAX_VALUE, maxBat = 0, sumBat = 0;
            double minCpu = Double.MAX_VALUE, maxCpu = 0, sumCpu = 0;
            double minRam = Double.MAX_VALUE, maxRam = 0, sumRam = 0;
            int n = slotBatList.size();
            for (int i = 0; i < n; i++) {
                double bat = slotBatList.get(i), cpu = slotCpuList.get(i), ram = slotRamList.get(i);
                minBat = Math.min(minBat, bat); maxBat = Math.max(maxBat, bat); sumBat += bat;
                minCpu = Math.min(minCpu, cpu); maxCpu = Math.max(maxCpu, cpu); sumCpu += cpu;
                minRam = Math.min(minRam, ram); maxRam = Math.max(maxRam, ram); sumRam += ram;
            }
            periodStats = new HourlyPeriodStats(
                    minBat, sumBat / n, maxBat,
                    minCpu, sumCpu / n, maxCpu,
                    minRam, sumRam / n, maxRam);
        }

        return new HourlyResult(slices, periodStats, isPartialData);
    }


    private static double parseLocaleDouble(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }


    private static long parseLocaleDoubleToLong(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {

            String normalized = s.trim().replace(',', '.');
            return (long) Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return 0L;
        }
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
