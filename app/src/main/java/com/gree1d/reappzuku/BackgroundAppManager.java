package com.gree1d.reappzuku;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gree1d.reappzuku.PreferenceKeys.*;
import static com.gree1d.reappzuku.AppConstants.*;

public class BackgroundAppManager {
    private static final String TAG = "BackgroundAppManager";
    private static final String BACKGROUND_RESTRICTION_OP = "RUN_ANY_IN_BACKGROUND";
    private static final String BG_RUN_RESTRICTION_OP = "RUN_IN_BACKGROUND";
    private static final String FOREGROUND_RESTRICTION_OP = "START_FOREGROUND";
    private static final String FGS_FROM_BG_RESTRICTION_OP = "START_FOREGROUND_SERVICES_FROM_BACKGROUND";
    private static final String BOOT_RESTRICTION_OP = "RECEIVE_BOOT_COMPLETED";
    private static final String WAKE_LOCK_RESTRICTION_OP = "WAKE_LOCK";
    private static final String ALARM_RESTRICTION_OP = "ALARM_WAKEUP";
    private static final String INTERACT_ACROSS_PROFILES_OP = "INTERACT_ACROSS_PROFILES";
    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final String FORCE_STOP_COMMAND_PREFIX = "am force-stop ";


    public static final String[] ALL_OPS = {
        BACKGROUND_RESTRICTION_OP,
        BG_RUN_RESTRICTION_OP,
        FOREGROUND_RESTRICTION_OP,
        FGS_FROM_BG_RESTRICTION_OP,
        WAKE_LOCK_RESTRICTION_OP,
        ALARM_RESTRICTION_OP,
        BOOT_RESTRICTION_OP,
        INTERACT_ACROSS_PROFILES_OP
    };


    public enum RestrictionType { SOFT, HARD, MANUAL }

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final List<AppModel> currentAppsList = new ArrayList<>();
    private boolean showSystemApps = false;
    private boolean showPersistentApps = false;
    private SharedPreferences sharedpreferences;
    private RestrictionsScheduler scheduler;

    public BackgroundAppManager(Context context, Handler handler, ExecutorService executor,
            ShellManager shellManager) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.sharedpreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public void setScheduler(RestrictionsScheduler scheduler) {
        this.scheduler = scheduler;
    }

    private String runPs(String psCommand) {
        return shellManager.runShellCommandAndGetFullOutput(psCommand);
    }

    public boolean supportsBackgroundRestriction() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public boolean canApplyBackgroundRestrictionNow() {
        return supportsBackgroundRestriction() && shellManager.hasAnyShellPermission();
    }


    public void loadBackgroundApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            List<AppModel> result = new ArrayList<>();
            PackageManager packageManager = context.getPackageManager();
            Set<String> runningPackagesFromPs = new HashSet<>();
            Set<String> hiddenApps = getHiddenApps();
            Set<String> whitelistedApps = getWhitelistedApps();
            Set<String> desiredBackgroundRestrictedApps = getBackgroundRestrictedApps();
            BackgroundRestrictionState backgroundRestrictionState = getBackgroundRestrictionState();

            if (shellManager.hasAnyShellPermission()) {
                String command = "ps -A -o pid,rss,name | grep '\\.' | grep -v '[-:@]'";
                try {
                    String fullOutput = runPs(command);
                    if (fullOutput != null) {
                        try (BufferedReader reader = new BufferedReader(new StringReader(fullOutput))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] parts = line.trim().split("\\s+");
                                if (parts.length >= 3) {
                                    String packageName = parts[2].trim();
                                    String appRam = parts[1].trim();
                                    String pid = parts[0].trim();
                                    if (!packageName.isEmpty() && packageName.contains(".")
                                            && !packageName.startsWith("ERROR:")) {
                                        try {
                                            packageManager.getApplicationInfo(packageName, 0);
                                            runningPackagesFromPs.add(packageName + ":" + appRam + ":" + pid);
                                        } catch (PackageManager.NameNotFoundException ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        handler.post(() -> Toast
                                .makeText(context, context.getString(R.string.toast_failed_get_running_apps), Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    handler.post(() -> Toast
                            .makeText(context, context.getString(R.string.toast_error_getting_running_apps, e.getMessage()), Toast.LENGTH_SHORT)
                            .show());
                }
            }

            for (String packageEntry : runningPackagesFromPs) {
                String[] parts = packageEntry.split(":");
                String packageName = parts[0];
                long ramUsage = 0;
                int pid = -1;
                try {
                    ramUsage = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse RAM value for " + packageName, e);
                }
                try {
                    pid = parts.length > 2 ? Integer.parseInt(parts[2]) : -1;
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse PID for " + packageName, e);
                }

                try {
                    if (hiddenApps.contains(packageName)) {
                        continue;
                    }

                    boolean isProtected = ProtectedApps.isProtected(context, packageName);
                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);

                    boolean isPersistentApp = (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
                    boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                    if (!showSystemApps && isSystemApp || !showPersistentApps && isPersistentApp) {
                        continue;
                    }

                    AppModel appModel = new AppModel(
                            packageManager.getApplicationLabel(appInfo).toString(),
                            packageName,
                            formatMemorySize(ramUsage),
                            ramUsage,
                            packageManager.getApplicationIcon(appInfo),
                            isSystemApp,
                            isPersistentApp,
                            isProtected);
                    appModel.setPid(pid);

                    appModel.setWhitelisted(whitelistedApps.contains(packageName));
                    applyBackgroundRestrictionState(appModel, desiredBackgroundRestrictedApps, backgroundRestrictionState);
                    result.add(appModel);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }

            sortAppList(result, SORT_MODE_DEFAULT);

            handler.post(() -> {
                currentAppsList.clear();
                currentAppsList.addAll(result);
                if (callback != null) {
                    callback.accept(new ArrayList<>(result));
                }
            });
        });
    }

    public void loadBackgroundRestrictionApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            Set<String> desiredPackages = getBackgroundRestrictedApps();
            BackgroundRestrictionState state = getBackgroundRestrictionState();
            List<AppModel> result = new ArrayList<>();
            for (ApplicationInfo appInfo : packages) {
                String packageName = appInfo.packageName;
                if (packageName.equals(context.getPackageName())) {
                    continue;
                }

                AppModel model = new AppModel(
                        pm.getApplicationLabel(appInfo).toString(),
                        packageName,
                        "-",
                        0,
                        pm.getApplicationIcon(appInfo),
                        (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                        (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0,
                        ProtectedApps.isProtected(context, packageName));
                applyBackgroundRestrictionState(model, desiredPackages, state);
                result.add(model);
            }

            Collections.sort(result, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
            handler.post(() -> callback.accept(result));
        });
    }

    public void loadAllApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppModel> allApps = new ArrayList<>();
            for (ApplicationInfo appInfo : packages) {
                if (appInfo.packageName.equals(context.getPackageName())) {
                    continue;
                }
                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isPersistent = (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
                allApps.add(new AppModel(
                        pm.getApplicationLabel(appInfo).toString(),
                        appInfo.packageName,
                        "-",
                        0,
                        pm.getApplicationIcon(appInfo),
                        isSystem,
                        isPersistent,
                        ProtectedApps.isProtected(context, appInfo.packageName)));
            }
            Collections.sort(allApps, (a1, a2) -> a1.getAppName().compareToIgnoreCase(a2.getAppName()));
            handler.post(() -> callback.accept(allApps));
        });
    }

    public void updateRunningState(List<AppModel> apps, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            if (onComplete != null) handler.post(onComplete);
            return;
        }

        executor.execute(() -> {
            String psOutput = runPs("ps -A -o pid,rss,name | grep '\\.' | grep -v '[-:@]'");

            Map<String, long[]> runningMap = new HashMap<>();
            if (psOutput != null) {
                try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) {
                            String packageName = parts[2].trim();
                            try {
                                long ramUsage = Long.parseLong(parts[1].trim());
                                int pid = Integer.parseInt(parts[0].trim());
                                runningMap.put(packageName, new long[]{ramUsage, pid});
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Failed to parse values for " + packageName, e);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            for (AppModel app : apps) {
                if (runningMap.containsKey(app.getPackageName())) {
                    long[] vals = runningMap.get(app.getPackageName());
                    app.setAppRamBytes(vals[0]);
                    app.setAppRam(formatMemorySize(vals[0]));
                    app.setPid((int) vals[1]);
                } else {
                    app.setAppRamBytes(0);
                    app.setAppRam("-");
                    app.setPid(-1);
                }
            }

            if (onComplete != null) handler.post(onComplete);
        });
    }

    public void sortAppList(List<AppModel> apps, int sortMode) {
        if (apps == null || apps.isEmpty()) {
            return;
        }

        switch (sortMode) {
            case SORT_MODE_RAM_DESC:
                Collections.sort(apps, (a1, a2) -> Long.compare(a2.getAppRamBytes(), a1.getAppRamBytes()));
                break;
            case SORT_MODE_RAM_ASC:
                Collections.sort(apps, (a1, a2) -> Long.compare(a1.getAppRamBytes(), a2.getAppRamBytes()));
                break;
            case SORT_MODE_CPU_DESC:
                Collections.sort(apps, (a1, a2) -> Float.compare(
                        parseCpuValue(a2.getCpuUsage()),
                        parseCpuValue(a1.getCpuUsage())));
                break;
            case SORT_MODE_CPU_ASC:
                Collections.sort(apps, (a1, a2) -> Float.compare(
                        parseCpuValue(a1.getCpuUsage()),
                        parseCpuValue(a2.getCpuUsage())));
                break;
            case SORT_MODE_NAME_ASC:
                Collections.sort(apps, (a1, a2) -> a1.getAppName().compareToIgnoreCase(a2.getAppName()));
                break;
            case SORT_MODE_NAME_DESC:
                Collections.sort(apps, (a1, a2) -> a2.getAppName().compareToIgnoreCase(a1.getAppName()));
                break;
            case SORT_MODE_DEFAULT:
            default:
                Collections.sort(apps,
                        Comparator.comparing(AppModel::isSystemApp)
                                .thenComparing(AppModel::isPersistentApp)
                                .thenComparing(a -> a.getAppName().toLowerCase()));
                break;
        }
    }

    private float parseCpuValue(String cpuUsage) {
        if (cpuUsage == null || cpuUsage.isEmpty()) return 0f;
        try {
            return Float.parseFloat(cpuUsage.replace("CPU:", "").replace("%", "").replace(",", ".").trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    public void clearCaches(Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            if (onComplete != null)
                handler.post(onComplete);
            return;
        }

        executor.execute(() -> {
            shellManager.runShellCommand("pm trim-caches 4096G", () -> {
                Toast.makeText(context, context.getString(R.string.toast_caches_cleared), Toast.LENGTH_SHORT).show();
                if (onComplete != null)
                    onComplete.run();
            }, () -> {
                Toast.makeText(context, context.getString(R.string.toast_failed_clear_caches), Toast.LENGTH_SHORT).show();
                if (onComplete != null)
                    onComplete.run();
            });
        });
    }

    public void setShowSystemApps(boolean show) {
        this.showSystemApps = show;
    }

    public void setShowPersistentApps(boolean show) {
        this.showPersistentApps = show;
    }


    public void setBackgroundRestricted(String packageName, boolean restricted, Runnable onComplete) {
        Set<String> targetPackages = getBackgroundRestrictedApps();
        if (restricted) {
            targetPackages.add(packageName);
        } else {
            targetPackages.remove(packageName);
        }
        applyBackgroundRestriction(targetPackages, onComplete);
    }

    public void applyBackgroundRestriction(Set<String> targetPackages, Runnable onComplete) {
        applyBackgroundRestriction(targetPackages, null, onComplete);
    }


    public void applyBackgroundRestriction(Set<String> targetPackages, Set<String> hardPackages, Runnable onComplete) {
        Set<String> desiredPackages = sanitizeBackgroundRestrictionTargets(targetPackages);
        Log.d(TAG, "[DBG] applyBackgroundRestriction called: targetPackages=" + (targetPackages != null ? targetPackages.size() : "null")
                + " desiredPackages=" + desiredPackages.size()
                + " hardPackages=" + (hardPackages != null ? hardPackages.size() : "null(use existing)"));
        saveBackgroundRestrictedApps(desiredPackages);

        if (hardPackages != null) {
            Set<String> sanitizedHard = new HashSet<>(hardPackages);
            sanitizedHard.retainAll(desiredPackages);
            saveHardRestrictedApps(sanitizedHard);
            Log.d(TAG, "[DBG] hardSet saved: " + sanitizedHard.size() + " packages");
        } else {
            Set<String> existingHard = getHardRestrictedApps();
            existingHard.retainAll(desiredPackages);
            saveHardRestrictedApps(existingHard);
            Log.d(TAG, "[DBG] hardSet (existing) retained: " + existingHard.size() + " packages");
        }

        Set<String> dbgManual = getManualRestrictedApps();
        Log.d(TAG, "[DBG] manualSet in prefs at call time: " + dbgManual.size() + " packages: " + dbgManual);


        if (!supportsBackgroundRestriction()) {
            BackgroundRestrictionLog.log(context, null, "apply", "skipped", "Android 11+ required");
            Toast.makeText(context, context.getString(R.string.toast_bg_restriction_requires_android11), Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        if (!shellManager.hasAnyShellPermission()) {
            BackgroundRestrictionLog.log(context, null, "apply", "failed", "No Root or Shizuku permission available");
            shellManager.checkShellPermissions();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }

        executor.execute(() -> {

            Set<String> currentPackages = getActualBackgroundRestrictedApps();
            Set<String> packagesToAllow = new HashSet<>(currentPackages);
            packagesToAllow.removeAll(desiredPackages);
            Log.d(TAG, "[DBG] executor start: desiredPackages=" + desiredPackages.size()
                    + " currentPackages=" + currentPackages.size()
                    + " packagesToAllow=" + packagesToAllow.size());

            boolean success = true;
            for (String packageName : packagesToAllow) {
                int[] opsCount = applyAllHardOps(packageName, "allow");
                restoreBatteryWhitelist(packageName);
                if (opsCount[0] == 0) success = false;
                logRestrictionResult(packageName, "allow", null, null, opsCount);
            }


            Set<String> hardSet = getHardRestrictedApps();
            Set<String> manualSet = getManualRestrictedApps();
            Log.d(TAG, "[DBG] step2 start: desiredPackages=" + desiredPackages.size()
                    + " hardSet=" + hardSet.size()
                    + " manualSet=" + manualSet.size());
            for (String packageName : desiredPackages) {
                Log.d(TAG, "[DBG] step2 pkg=" + packageName
                        + " type=" + (manualSet.contains(packageName) ? "MANUAL"
                                    : hardSet.contains(packageName) ? "HARD" : "SOFT"));
                applyAllHardOps(packageName, "allow");
                restoreBatteryWhitelist(packageName);
                if (manualSet.contains(packageName)) {
                    int opsMask = getManualOpsMask(packageName);
                    int[] opsCount = applyManualOps(packageName, opsMask, "ignore");
                    ShellManager.ShellResult forceStopResult = shellManager
                            .runShellCommandForResult(FORCE_STOP_COMMAND_PREFIX + packageName);
                    if (opsCount[0] == 0) success = false;
                    logRestrictionResult(packageName, "reapply-manual", null, forceStopResult, opsCount);
                } else if (hardSet.contains(packageName)) {
                    int[] opsCount = applyAllHardOps(packageName, "ignore");
                    applyBatteryWhitelistRemoval(packageName);
                    ShellManager.ShellResult forceStopResult = shellManager
                            .runShellCommandForResult(FORCE_STOP_COMMAND_PREFIX + packageName);
                    if (opsCount[0] == 0) success = false;
                    logRestrictionResult(packageName, "reapply-hard", null, forceStopResult, opsCount);
                } else {
                    ShellManager.ShellResult restrictResult = shellManager
                            .runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "ignore"));
                    if (!restrictResult.succeeded()) {
                        success = false;
                        logRestrictionResult(packageName, "reapply-soft", restrictResult, null);
                        continue;
                    }
                    ShellManager.ShellResult forceStopResult = shellManager
                            .runShellCommandForResult(FORCE_STOP_COMMAND_PREFIX + packageName);
                    if (!forceStopResult.succeeded()) success = false;
                    logRestrictionResult(packageName, "reapply-soft", restrictResult, forceStopResult);
                }
            }

            BackgroundRestrictionState actualState = getBackgroundRestrictionState();
            for (String packageName : packagesToAllow) {
                logRestrictionVerification(packageName, "allow", actualState, false);
            }
            for (String packageName : desiredPackages) {
                String verifyAction = manualSet.contains(packageName) ? "reapply-manual"
                        : hardSet.contains(packageName) ? "reapply-hard" : "reapply-soft";
                logRestrictionVerification(packageName, verifyAction, actualState, true);
            }

            final boolean finalSuccess = success;
            handler.post(() -> {
                Toast.makeText(context,
                        finalSuccess ? context.getString(R.string.bg_manager_restriction_applied)
                                : context.getString(R.string.toast_bg_restriction_changes_partial),
                        Toast.LENGTH_SHORT).show();
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    public void reapplySavedBackgroundRestrictions(Runnable onComplete) {
        Set<String> desired = sanitizeBackgroundRestrictionTargets(getBackgroundRestrictedApps());
        Set<String> hard = getHardRestrictedApps();
        Set<String> manual = getManualRestrictedApps();

        if (desired.isEmpty()) {
            BackgroundRestrictionLog.log(context, null, "reapply", "skipped", "No saved restrictions");
            if (onComplete != null) handler.post(onComplete);
            return;
        }
        if (!supportsBackgroundRestriction()) {
            BackgroundRestrictionLog.log(context, null, "reapply", "skipped", "Android 11+ required");
            if (onComplete != null) handler.post(onComplete);
            return;
        }
        if (!shellManager.hasAnyShellPermission()) {
            BackgroundRestrictionLog.log(context, null, "reapply", "failed", "No shell permission");
            if (onComplete != null) handler.post(onComplete);
            return;
        }

        executor.execute(() -> {
            boolean success = true;
            for (String packageName : desired) {
                if (scheduler != null && scheduler.isProtected(packageName, RestrictionsScheduler.PROTECT_BG_RESTRICTIONS)) {
                    Log.d(TAG, "reapply SKIP (temp protected): " + packageName);
                    continue;
                }

                if (manual.contains(packageName)) {
                    int opsMask = getManualOpsMask(packageName);
                    int[] opsCount = applyManualOps(packageName, opsMask, "ignore");
                    if (opsCount[0] == 0) success = false;
                    logRestrictionResult(packageName, "reapply-manual", null, null, opsCount);
                } else if (hard.contains(packageName)) {
                    int[] opsCount = applyAllHardOps(packageName, "ignore");
                    applyBatteryWhitelistRemoval(packageName);
                    if (opsCount[0] == 0) success = false;
                    logRestrictionResult(packageName, "reapply-hard", null, null, opsCount);
                } else {
                    ShellManager.ShellResult result = shellManager
                            .runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "ignore"));
                    if (!result.succeeded()) success = false;
                    logRestrictionResult(packageName, "reapply-soft", result, null);
                }

                BackgroundRestrictionState actualState = getBackgroundRestrictionState();
                String action = manual.contains(packageName) ? "reapply-manual"
                        : (hard.contains(packageName) ? "reapply-hard" : "reapply-soft");
                logRestrictionVerification(packageName, action, actualState, true);
            }

            final boolean finalSuccess = success;
            handler.post(() -> {
                Toast.makeText(context,
                        finalSuccess ? context.getString(R.string.bg_manager_restrictions_reapplied)
                                : context.getString(R.string.bg_manager_restrictions_reapply_partial),
                        Toast.LENGTH_SHORT).show();
                if (onComplete != null) onComplete.run();
            });
        });
    }

    public void setRestrictionType(String packageName, RestrictionType type) {
        Set<String> hardSet = getHardRestrictedApps();
        Set<String> manualSet = getManualRestrictedApps();


        hardSet.remove(packageName);
        manualSet.remove(packageName);

        switch (type) {
            case HARD:
                hardSet.add(packageName);
                break;
            case MANUAL:
                manualSet.add(packageName);
                break;
            case SOFT:
            default:
                break;
        }

        saveHardRestrictedApps(hardSet);
        saveManualRestrictedApps(manualSet);
    }


    public void setRestrictionType(String packageName, boolean hard) {
        setRestrictionType(packageName, hard ? RestrictionType.HARD : RestrictionType.SOFT);
    }

    public RestrictionType getRestrictionType(String packageName) {
        if (getManualRestrictedApps().contains(packageName)) return RestrictionType.MANUAL;
        if (getHardRestrictedApps().contains(packageName)) return RestrictionType.HARD;
        return RestrictionType.SOFT;
    }


    private String buildBackgroundRestrictionCommand(String packageName, String mode) {
        return "cmd appops set --user current " + packageName + " " + BACKGROUND_RESTRICTION_OP + " " + mode;
    }

    private String buildHardRestrictionCommand(String packageName, String mode) {
        return "cmd appops set --user current " + packageName + " " + FOREGROUND_RESTRICTION_OP + " " + mode;
    }

    private String buildBootRestrictionCommand(String packageName, String mode) {
        return "cmd appops set --user current " + packageName + " " + BOOT_RESTRICTION_OP + " " + mode;
    }


    int[] applyAllHardOps(String packageName, String mode) {
        Log.d(TAG, "applyAllHardOps → " + packageName + " mode=" + mode
                + " ops=" + Arrays.toString(ALL_OPS));
        int ok = 0, fail = 0;
        int succeededMask = 0;
        for (int i = 0; i < ALL_OPS.length; i++) {
            boolean succeeded = shellManager.runShellCommandForResult(
                    "cmd appops set --user current " + packageName + " " + ALL_OPS[i] + " " + mode)
                    .succeeded();
            if (succeeded) {
                ok++;
                succeededMask |= (1 << i);
                Log.d(TAG, "  [OK  ] " + ALL_OPS[i]);
            } else {
                fail++;
                Log.w(TAG, "  [FAIL] " + ALL_OPS[i]);
            }
        }
        Log.d(TAG, "applyAllHardOps result: ok=" + ok + " fail=" + fail + " pkg=" + packageName);
        if ("ignore".equals(mode)) {
            saveAppliedOpsMask(packageName, succeededMask);
        } else {
            clearAppliedOpsMask(packageName);
        }
        return new int[]{ok, fail};
    }


    int[] applyManualOps(String packageName, int opsMask, String mode) {
        int selectedCount = Integer.bitCount(opsMask);
        Log.d(TAG, "applyManualOps → " + packageName + " mode=" + mode
                + " mask=0x" + Integer.toHexString(opsMask)
                + " selectedOps=" + selectedCount + "/" + ALL_OPS.length);

        int ok = 0, fail = 0;
        int succeededMask = 0;
        for (int i = 0; i < ALL_OPS.length; i++) {
            if ((opsMask & (1 << i)) == 0) {
                Log.d(TAG, "  [SKIP] " + ALL_OPS[i] + " (not selected)");
                continue;
            }
            boolean succeeded = shellManager.runShellCommandForResult(
                    "cmd appops set --user current " + packageName + " " + ALL_OPS[i] + " " + mode)
                    .succeeded();
            if (succeeded) {
                ok++;
                succeededMask |= (1 << i);
                Log.d(TAG, "  [OK  ] " + ALL_OPS[i]);
            } else {
                fail++;
                Log.w(TAG, "  [FAIL] " + ALL_OPS[i]);
            }
        }
        Log.d(TAG, "applyManualOps result: ok=" + ok + " fail=" + fail + " pkg=" + packageName);
        if ("ignore".equals(mode)) {
            saveAppliedOpsMask(packageName, succeededMask);
        } else {
            clearAppliedOpsMask(packageName);
        }
        return new int[]{ok, fail};
    }


    @Deprecated
    void applyHardExtraOps(String packageName, String mode) {
        applyAllHardOps(packageName, mode);
    }


    public String liftRestrictionsForScheduler(String packageName) {
        if (!getBackgroundRestrictedApps().contains(packageName)) return "skipped";
        restoreBatteryWhitelist(packageName);
        int[] counts = applyAllHardOps(packageName, "allow");
        if (counts[0] == 0) return "error";
        if (counts[1] == 0) return "ok";
        return "partial";
    }


    public String restoreRestrictionsForScheduler(String packageName) {
        if (!getBackgroundRestrictedApps().contains(packageName)) return "skipped";
        RestrictionType type = getRestrictionType(packageName);
        int[] counts;
        switch (type) {
            case HARD:
                counts = applyAllHardOps(packageName, "ignore");
                applyBatteryWhitelistRemoval(packageName);
                break;
            case MANUAL:
                int opsMask = getManualOpsMask(packageName);
                counts = applyManualOps(packageName, opsMask, "ignore");
                break;
            case SOFT:
            default:
                boolean ok = shellManager.runShellCommandForResult(
                        buildBackgroundRestrictionCommand(packageName, "ignore")).succeeded();
                counts = ok ? new int[]{1, 0} : new int[]{0, 1};
                break;
        }
        if (counts[0] == 0) return "error";
        if (counts[1] == 0) return "ok";
        return "partial";
    }


    private boolean isInBatteryWhitelist(String packageName) {
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys deviceidle whitelist");
        if (output == null) return false;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("user,") && trimmed.contains("," + packageName + ",")) {
                return true;
            }
        }
        return false;
    }

    private void applyBatteryWhitelistRemoval(String packageName) {
        if (!isInBatteryWhitelist(packageName)) return;
        ShellManager.ShellResult result = shellManager.runShellCommandForResult(
                "cmd deviceidle whitelist -" + packageName);
        if (result.succeeded()) {
            Set<String> removed = getBatteryWhitelistRemoved();
            removed.add(packageName);
            saveBatteryWhitelistRemoved(removed);
            BackgroundRestrictionLog.log(context, packageName, "restrict-hard",
                    "battery-whitelist-removed", "removed from deviceidle whitelist");
        }
    }

    private void restoreBatteryWhitelist(String packageName) {
        Set<String> removed = getBatteryWhitelistRemoved();
        if (!removed.contains(packageName)) return;
        shellManager.runShellCommandForResult("cmd deviceidle whitelist +" + packageName);
        removed.remove(packageName);
        saveBatteryWhitelistRemoved(removed);
        BackgroundRestrictionLog.log(context, packageName, "allow",
                "battery-whitelist-restored", "restored to deviceidle whitelist");
    }


    private Set<String> getActualBackgroundRestrictedApps() {
        return getBackgroundRestrictionState().restrictedPackages;
    }

    private BackgroundRestrictionState getBackgroundRestrictionState() {
        Set<String> fallbackPackages = getBackgroundRestrictedApps();
        if (!supportsBackgroundRestriction() || !shellManager.hasAnyShellPermission()) {
            return new BackgroundRestrictionState(fallbackPackages, false);
        }

        Set<String> restrictedPackages = new HashSet<>();
        boolean querySucceeded = false;

        String ignoreOutput = shellManager.runShellCommandAndGetFullOutput(
                "cmd appops query-op --user current " + BACKGROUND_RESTRICTION_OP + " ignore");
        if (ignoreOutput != null) {
            querySucceeded = true;
            mergeBackgroundRestrictedPackages(restrictedPackages, ignoreOutput);
        }

        String denyOutput = shellManager.runShellCommandAndGetFullOutput(
                "cmd appops query-op --user current " + BACKGROUND_RESTRICTION_OP + " deny");
        if (denyOutput != null) {
            querySucceeded = true;
            mergeBackgroundRestrictedPackages(restrictedPackages, denyOutput);
        }

        String hardIgnoreOutput = shellManager.runShellCommandAndGetFullOutput(
                "cmd appops query-op --user current " + FOREGROUND_RESTRICTION_OP + " ignore");
        if (hardIgnoreOutput != null) {
            querySucceeded = true;
            mergeBackgroundRestrictedPackages(restrictedPackages, hardIgnoreOutput);
        }

        String hardDenyOutput = shellManager.runShellCommandAndGetFullOutput(
                "cmd appops query-op --user current " + FOREGROUND_RESTRICTION_OP + " deny");
        if (hardDenyOutput != null) {
            querySucceeded = true;
            mergeBackgroundRestrictedPackages(restrictedPackages, hardDenyOutput);
        }

        return new BackgroundRestrictionState(querySucceeded ? restrictedPackages : fallbackPackages, querySucceeded);
    }

    private void mergeBackgroundRestrictedPackages(Set<String> packages, String output) {
        if (output == null || output.trim().isEmpty()) {
            return;
        }
        Matcher matcher = PACKAGE_NAME_PATTERN.matcher(output);
        while (matcher.find()) {
            packages.add(matcher.group());
        }
    }

    private void applyBackgroundRestrictionState(AppModel model, Set<String> desiredPackages,
            BackgroundRestrictionState actualState) {
        boolean desired = desiredPackages.contains(model.getPackageName());
        model.setBackgroundRestrictionDesired(desired);
        model.setBackgroundRestrictionActualKnown(actualState.querySucceeded);
        if (actualState.querySucceeded) {
            model.setBackgroundRestrictionActual(actualState.restrictedPackages.contains(model.getPackageName()));
        } else {
            model.setBackgroundRestrictionActual(desired);
        }
    }

    Set<String> sanitizeBackgroundRestrictionTargets(Set<String> targetPackages) {
        Set<String> desiredPackages = new HashSet<>();
        if (targetPackages == null) {
            return desiredPackages;
        }
        for (String packageName : targetPackages) {
            if (packageName != null && !packageName.isEmpty() && !packageName.equals(context.getPackageName())) {
                desiredPackages.add(packageName);
            }
        }
        return desiredPackages;
    }


    public void checkAndRepairRestrictions(Set<String> desired, RestrictionsScheduler scheduler) {
        executor.execute(() -> {
            Set<String> hardSet   = getHardRestrictedApps();
            Set<String> manualSet = getManualRestrictedApps();

            Map<String, List<String>> drifted = collectDriftedOps(desired, hardSet, manualSet);

            if (drifted.isEmpty()) {
                Log.d(TAG, "watchdog: all restrictions intact (" + desired.size() + " packages)");
                return;
            }

            int repairedCount = 0;
            for (Map.Entry<String, List<String>> entry : drifted.entrySet()) {
                String pkg           = entry.getKey();
                List<String> missing = entry.getValue();


                if (scheduler != null
                        && scheduler.isProtected(pkg, RestrictionsScheduler.PROTECT_BG_RESTRICTIONS)) {
                    Log.d(TAG, "watchdog SKIP (scheduler active): " + pkg);
                    BackgroundRestrictionLog.log(context, pkg, "watchdog",
                            "skipped", "scheduler-protected");
                    continue;
                }

                Log.w(TAG, "watchdog: drift detected " + pkg + " missing=" + missing);
                int ok = 0, fail = 0;
                for (String op : missing) {
                    boolean succeeded = shellManager.runShellCommandForResult(
                            "cmd appops set --user current " + pkg + " " + op + " ignore")
                            .succeeded();
                    if (succeeded) ok++; else fail++;
                }

                if (hardSet.contains(pkg)) {
                    applyBatteryWhitelistRemoval(pkg);
                }

                String outcome = fail == 0 ? "ok" : (ok > 0 ? "partial" : "failed");
                BackgroundRestrictionLog.log(context, pkg, "watchdog", outcome,
                        "missing=" + missing.size() + " repaired=" + ok + "/" + (ok + fail));
                if (ok > 0) repairedCount++;
            }

            final int total    = drifted.size();
            final int repaired = repairedCount;
            Log.i(TAG, "watchdog: repair complete — " + repaired + "/" + total);
        });
    }


    private Map<String, List<String>> collectDriftedOps(Set<String> desired,
            Set<String> hardSet, Set<String> manualSet) {


        Map<String, Set<String>> actualRestrictedByOp = new HashMap<>();
        for (String op : ALL_OPS) {
            Set<String> restricted = new HashSet<>();
            String ignoreOut = shellManager.runShellCommandAndGetFullOutput(
                    "cmd appops query-op --user current " + op + " ignore");
            if (ignoreOut != null) mergeBackgroundRestrictedPackages(restricted, ignoreOut);
            String denyOut = shellManager.runShellCommandAndGetFullOutput(
                    "cmd appops query-op --user current " + op + " deny");
            if (denyOut != null) mergeBackgroundRestrictedPackages(restricted, denyOut);
            actualRestrictedByOp.put(op, restricted);
        }

        Map<String, List<String>> drifted = new LinkedHashMap<>();
        for (String pkg : desired) {
            List<String> required = getRequiredOpsForPackage(pkg, hardSet, manualSet);
            List<String> missing  = new ArrayList<>();
            for (String op : required) {
                if (!actualRestrictedByOp.getOrDefault(op, Collections.emptySet()).contains(pkg)) {
                    missing.add(op);
                }
            }
            if (!missing.isEmpty()) drifted.put(pkg, missing);
        }
        return drifted;
    }


    private List<String> getRequiredOpsForPackage(String pkg,
            Set<String> hardSet, Set<String> manualSet) {
        if (hardSet.contains(pkg) || manualSet.contains(pkg)) {
            int appliedMask = getAppliedOpsMask(pkg);
            if (appliedMask != 0) {

                List<String> ops = new ArrayList<>();
                for (int i = 0; i < ALL_OPS.length; i++) {
                    if ((appliedMask & (1 << i)) != 0) ops.add(ALL_OPS[i]);
                }
                return ops;
            }


            if (hardSet.contains(pkg)) return Arrays.asList(ALL_OPS);
            int mask = getManualOpsMask(pkg);
            List<String> ops = new ArrayList<>();
            for (int i = 0; i < ALL_OPS.length; i++) {
                if ((mask & (1 << i)) != 0) ops.add(ALL_OPS[i]);
            }
            return ops;
        }

        return Collections.singletonList(BACKGROUND_RESTRICTION_OP);
    }


    private void logRestrictionResult(String packageName, String action,
            ShellManager.ShellResult appOpsResult, ShellManager.ShellResult forceStopResult) {
        logRestrictionResult(packageName, action, appOpsResult, forceStopResult, null);
    }

    private void logRestrictionResult(String packageName, String action,
            ShellManager.ShellResult appOpsResult, ShellManager.ShellResult forceStopResult,
            int[] opsCount) {
        StringBuilder detail = new StringBuilder();
        if (opsCount != null) {
            int ok   = opsCount[0];
            int fail = opsCount[1];
            int total = ok + fail;
            if (fail == 0) {
                detail.append("appops=ok(").append(ok).append("/").append(total).append(")");
            } else if (ok == 0) {
                detail.append("appops=failed(0/").append(total).append(")");
            } else {
                detail.append("appops=partial(").append(ok).append("/").append(total).append(")");
            }
        } else {
            detail.append("appops=").append(formatShellOutcome(appOpsResult));
        }
        if (forceStopResult != null) {
            detail.append(" force-stop=").append(formatShellOutcome(forceStopResult));
        }
        boolean appOpsOk = opsCount != null
                ? opsCount[1] == 0
                : appOpsResult != null && appOpsResult.succeeded();
        String outcome = appOpsOk && (forceStopResult == null || forceStopResult.succeeded())
                ? "ok"
                : (opsCount != null && opsCount[0] > 0 ? "partial" : "failed");
        BackgroundRestrictionLog.log(context, packageName, action, outcome, detail.toString());
    }

    private void logRestrictionVerification(String packageName, String action, BackgroundRestrictionState state,
            boolean desiredRestricted) {
        if (!state.querySucceeded) {
            BackgroundRestrictionLog.log(context, packageName, action, "verify-unavailable", "AppOps query failed");
            return;
        }
        boolean isRestricted = state.restrictedPackages.contains(packageName);
        BackgroundRestrictionLog.log(context, packageName, action,
                desiredRestricted == isRestricted ? "verified" : "verify-failed",
                "expected=" + (desiredRestricted ? "restricted" : "allowed")
                        + " actual=" + (isRestricted ? "restricted" : "allowed"));
    }

    private String formatShellOutcome(ShellManager.ShellResult result) {
        if (result == null) {
            return "n/a";
        }
        StringBuilder detail = new StringBuilder(result.succeeded() ? "ok" : "failed");
        if (!result.output().isEmpty()) {
            detail.append("[").append(result.output()).append("]");
        } else if (!result.succeeded() && result.exitCode() >= 0) {
            detail.append("[exit=").append(result.exitCode()).append("]");
        }
        return detail.toString();
    }

    public String getBackgroundRestrictionLogText() {
        return BackgroundRestrictionLog.readDisplayText(context);
    }

    public void clearBackgroundRestrictionLog() {
        BackgroundRestrictionLog.clear(context);
    }


    private static final class BackgroundRestrictionState {
        private final Set<String> restrictedPackages;
        private final boolean querySucceeded;

        private BackgroundRestrictionState(Set<String> restrictedPackages, boolean querySucceeded) {
            this.restrictedPackages = restrictedPackages;
            this.querySucceeded = querySucceeded;
        }
    }


    public Set<String> getHiddenApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_HIDDEN_APPS, new HashSet<>()));
    }

    public void saveHiddenApps(Set<String> hiddenApps) {
        sharedpreferences.edit().putStringSet(KEY_HIDDEN_APPS, new HashSet<>(hiddenApps)).apply();
    }

    public Set<String> getWhitelistedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>()));
    }

    public void saveWhitelistedApps(Set<String> whitelistedApps) {
        sharedpreferences.edit().putStringSet(KEY_WHITELISTED_APPS, new HashSet<>(whitelistedApps)).apply();
    }

    public Set<String> getBackgroundRestrictedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_AUTOSTART_DISABLED_APPS, new HashSet<>()));
    }

    public void saveBackgroundRestrictedApps(Set<String> packageNames) {
        sharedpreferences.edit().putStringSet(KEY_AUTOSTART_DISABLED_APPS, new HashSet<>(packageNames)).apply();
    }


    public int getAppliedOpsMask(String packageName) {
        return sharedpreferences.getInt(KEY_APPLIED_OPS_MASK_PREFIX + packageName, 0);
    }

    private void saveAppliedOpsMask(String packageName, int mask) {
        sharedpreferences.edit()
                .putInt(KEY_APPLIED_OPS_MASK_PREFIX + packageName, mask)
                .apply();
        Log.d(TAG, "saveAppliedOpsMask " + packageName
                + " mask=0x" + Integer.toHexString(mask) + " ops=" + describeOpsMask(mask));
    }

    private void clearAppliedOpsMask(String packageName) {
        sharedpreferences.edit()
                .remove(KEY_APPLIED_OPS_MASK_PREFIX + packageName)
                .apply();
    }

    public Set<String> getHardRestrictedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_HARD_RESTRICTION_APPS, new HashSet<>()));
    }

    public void saveHardRestrictedApps(Set<String> packageNames) {
        sharedpreferences.edit().putStringSet(KEY_HARD_RESTRICTION_APPS, new HashSet<>(packageNames)).apply();
    }

    public boolean isHardRestricted(String packageName) {
        return getHardRestrictedApps().contains(packageName);
    }


    public Set<String> getManualRestrictedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_MANUAL_RESTRICTION_APPS, new HashSet<>()));
    }

    public void saveManualRestrictedApps(Set<String> packageNames) {
        sharedpreferences.edit().putStringSet(KEY_MANUAL_RESTRICTION_APPS, new HashSet<>(packageNames)).apply();
    }


    public int getManualOpsMask(String packageName) {
        return sharedpreferences.getInt(KEY_MANUAL_OPS_PREFIX + packageName, 0x01);
    }

    public void saveManualOpsMask(String packageName, int mask) {
        sharedpreferences.edit().putInt(KEY_MANUAL_OPS_PREFIX + packageName, mask).apply();
        Log.d(TAG, "saveManualOpsMask " + packageName + " mask=0x" + Integer.toHexString(mask)
                + " ops=" + describeOpsMask(mask));
    }


    public String describeOpsMask(int mask) {
        if (mask == 0) return "[none]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ALL_OPS.length; i++) {
            if ((mask & (1 << i)) != 0) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(ALL_OPS[i]);
            }
        }
        sb.append("]");
        return sb.toString();
    }

    public Set<String> getBatteryWhitelistRemoved() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_BATTERY_WHITELIST_REMOVED, new HashSet<>()));
    }

    public void saveBatteryWhitelistRemoved(Set<String> packages) {
        sharedpreferences.edit().putStringSet(KEY_BATTERY_WHITELIST_REMOVED, new HashSet<>(packages)).apply();
    }


    public String formatMemorySize(long kb) {
        if (kb < 1024)
            return kb + " KB";
        else if (kb < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2f MB", kb / 1024f);
        else
            return String.format(java.util.Locale.US, "%.2f GB", kb / (1024f * 1024f));
    }

    public List<AppModel> getCurrentAppsList() {
        return currentAppsList;
    }
}
