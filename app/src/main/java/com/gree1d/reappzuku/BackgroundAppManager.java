package com.gree1d.reappzuku;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import android.os.Build;
import java.io.IOException;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Comparator;

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
    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final String FORCE_STOP_COMMAND_PREFIX = "am force-stop ";
    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final List<AppModel> currentAppsList = new ArrayList<>();
    private boolean showSystemApps = false;
    private boolean showPersistentApps = false;
    private SharedPreferences sharedpreferences;

    public BackgroundAppManager(Context context, Handler handler, ExecutorService executor,
            ShellManager shellManager) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.sharedpreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Runs a {@code ps -A} command, routing through ADB shell on Android 10+ with root
     * (where the {@code su} SELinux context blocks {@code ps -A -o} output),
     * or falling back to {@link ShellManager} via {@code su} otherwise.
     *
     * @param psCommand the full ps command with pipes, e.g.
     *             {@code "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]'"}
     */
    private String runPs(String psCommand) {
        return shellManager.runShellCommandAndGetFullOutput(psCommand);
    }

    public boolean supportsBackgroundRestriction() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public boolean canApplyBackgroundRestrictionNow() {
        return supportsBackgroundRestriction() && shellManager.hasAnyShellPermission();
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

    public void performAutoKill(Runnable onComplete) {
        executor.execute(() -> {
            if (!shellManager.resolveAnyShellPermission()) {
                if (onComplete != null)
                    handler.post(onComplete);
                return;
            }

            Set<String> hiddenApps = getHiddenApps();
            Set<String> whitelistedApps = getWhitelistedApps();
            Set<String> blacklistedApps = getBlacklistedApps();
            int killMode = getKillMode(); // 0 = Whitelist, 1 = Blacklist

            Log.d(TAG, "=== performAutoKill start ===");
            Log.d(TAG, "killMode=" + (killMode == 1 ? "BLACKLIST" : "WHITELIST"));
            Log.d(TAG, "whitelistedApps=" + whitelistedApps);
            Log.d(TAG, "blacklistedApps=" + blacklistedApps);
            Log.d(TAG, "hiddenApps=" + hiddenApps);

            String dumpOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity activities");
            Log.d(TAG, "dumpsys output length: " + (dumpOutput == null ? "null" : dumpOutput.length()));
            if (dumpOutput == null) {
                Log.w(TAG, "dumpsys returned null — aborting kill");
                if (onComplete != null)
                    handler.post(onComplete);
                return;
            }

            String psOutput = runPs(
                    "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
            Log.d(TAG, "ps output length: " + (psOutput == null ? "null" : psOutput.trim().length()));
            if (psOutput == null || psOutput.trim().isEmpty()) {
                Log.w(TAG, "ps returned null/empty — aborting kill");
                if (onComplete != null)
                    handler.post(onComplete);
                return;
            }

            Set<String> runningPackages = new HashSet<>();
            PackageManager pm = context.getPackageManager();
            try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String packageName = line.trim();
                    if (!packageName.isEmpty() && packageName.contains(".")) {
                        try {
                            pm.getApplicationInfo(packageName, 0);
                            runningPackages.add(packageName);
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }

            List<String> toKill = runningPackages.stream()
                    .filter(pkg -> {
                        try {
                            if (hiddenApps.contains(pkg)) {
                                Log.d(TAG, "SKIP (hidden): " + pkg);
                                return false;
                            }
                            if (ProtectedApps.isProtected(context, pkg)) {
                                Log.d(TAG, "SKIP (protected): " + pkg);
                                return false;
                            }
                            if (containsPackage(dumpOutput, pkg)) {
                                Log.d(TAG, "SKIP (foreground): " + pkg);
                                return false;
                            }
                            if (killMode == 1) { // Blacklist Mode (Default)
                                boolean inBlacklist = blacklistedApps.contains(pkg);
                                Log.d(TAG, (inBlacklist ? "KILL (blacklist): " : "SKIP (not in blacklist): ") + pkg);
                                return inBlacklist;
                            } else { // Whitelist Mode
                                if (whitelistedApps.contains(pkg)) {
                                    Log.d(TAG, "SKIP (whitelisted): " + pkg);
                                    return false;
                                }
                                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                                boolean persistent = (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
                                Log.d(TAG, (persistent ? "SKIP (persistent): " : "KILL (whitelist mode): ") + pkg);
                                return !persistent;
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.d(TAG, "SKIP (not found): " + pkg);
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            Log.d(TAG, "toKill list (" + toKill.size() + "): " + toKill);

            if (!toKill.isEmpty()) {
                Map<String, Long> recoveredKbByPackage = new HashMap<>();
                for (AppModel app : currentAppsList) {
                    recoveredKbByPackage.put(app.getPackageName(), app.getAppRamBytes());
                }
                recordSuccessfulKills(toKill, recoveredKbByPackage);

                String killCommand = toKill.stream()
                        .map(this::buildKillCommand)
                        .collect(Collectors.joining("; "));
                        
                String finalCommand = killCommand;

                shellManager.runShellCommandAndGetFullOutput(finalCommand);

                sendKillNotification(toKill.size());
                updateWidget();

                try {
                    Thread.sleep(RELAUNCH_CHECK_DELAY_MS);
                } catch (InterruptedException ignored) {
                }
                com.gree1d.reappzuku.db.AppDatabase db = com.gree1d.reappzuku.db.AppDatabase.getInstance(context);
                checkRelaunches(toKill, db);
            }

            if (onComplete != null)
                handler.post(onComplete);
        });
    }

    private void checkRelaunches(List<String> recentlyKilled, com.gree1d.reappzuku.db.AppDatabase db) {
        String psOutput = runPs("ps -A -o name | grep '\\.'");
        if (psOutput == null)
            return;

        long now = System.currentTimeMillis();
        try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String pkg = line.trim();
                // === FIX 3: Clean package name for correct matching ===
                if (pkg.contains(":")) {
                    pkg = pkg.split(":")[0];
                }
                if (recentlyKilled.contains(pkg)) {
                    db.appStatsDao().incrementRelaunch(pkg, now);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void sendKillNotification(int count) {
        ShappkyService.updateNotification(context,
                context.getString(R.string.bg_manager_auto_kill_active),
                context.getString(R.string.bg_manager_stopped_apps, count));
    }

    private String formatMemorySize(long kb) {
        if (kb < 1024)
            return kb + " KB";
        else if (kb < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2f MB", kb / 1024f);
        else
            return String.format(java.util.Locale.US, "%.2f GB", kb / (1024f * 1024f));
    }

    // Load background apps using 'ps' command via Shizuku
    public void loadBackgroundApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            List<AppModel> result = new ArrayList<>();
            PackageManager packageManager = context.getPackageManager();
            Set<String> runningPackagesFromPs = new HashSet<>();
            Set<String> hiddenApps = getHiddenApps();
            Set<String> whitelistedApps = getWhitelistedApps();
            Set<String> desiredBackgroundRestrictedApps = getBackgroundRestrictedApps();
            BackgroundRestrictionState backgroundRestrictionState = getBackgroundRestrictionState();

            // Execute shell command to get running processes
            if (shellManager.hasAnyShellPermission()) {
                String command = "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]'";
                try {
                    String fullOutput = runPs(command);
                    if (fullOutput != null) {
                        try (BufferedReader reader = new BufferedReader(new StringReader(fullOutput))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] parts = line.trim().split("\\s+");
                                if (parts.length >= 2) {
                                    String packageName = parts[1].trim();
                                    String appRam = parts[0].trim();
                                    if (!packageName.isEmpty() && packageName.contains(".")
                                            && !packageName.startsWith("ERROR:")) {
                                        try {
                                            packageManager.getApplicationInfo(packageName, 0);
                                            runningPackagesFromPs.add(packageName + ":" + appRam); // Store with RAM
                                        } catch (PackageManager.NameNotFoundException ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        handler.post(() -> Toast
                                .makeText(context, "Failed to get running apps output", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    handler.post(() -> Toast
                            .makeText(context, "Error getting running apps: " + e.getMessage(), Toast.LENGTH_SHORT)
                            .show());
                }
            }

            // Process running packages
            for (String packageEntry : runningPackagesFromPs) {
                String[] parts = packageEntry.split(":");
                String packageName = parts[0];
                long ramUsage = 0;
                try {
                    ramUsage = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse RAM value for " + packageName, e);
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

    /**
     * Updates the running state and RAM usage of the provided app list.
     * This runs asynchronously and updates the models in-place.
     */
    public void updateRunningState(List<AppModel> apps, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            if (onComplete != null) handler.post(onComplete);
            return;
        }

        executor.execute(() -> {
            String psOutput = runPs(
                    "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]'");
            
            java.util.Map<String, Long> runningMap = new java.util.HashMap<>();
            if (psOutput != null) {
                try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            String packageName = parts[1].trim();
                            try {
                                long ramUsage = Long.parseLong(parts[0].trim());
                                runningMap.put(packageName, ramUsage);
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Failed to parse RAM value for " + packageName, e);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            for (AppModel app : apps) {
                if (runningMap.containsKey(app.getPackageName())) {
                    long ram = runningMap.get(app.getPackageName());
                    app.setAppRamBytes(ram);
                    app.setAppRam(formatMemorySize(ram));
                } else {
                    app.setAppRamBytes(0);
                    app.setAppRam("-");
                }
            }
            
            if (onComplete != null) handler.post(onComplete);
        });
    }

    /**
     * Sort app list based on the specified sort mode
     */
    public void sortAppList(List<AppModel> apps, int sortMode) {
        if (apps == null || apps.isEmpty()) {
            return;
        }

        switch (sortMode) {
            case SORT_MODE_RAM_DESC:
                // Most RAM to least RAM
                Collections.sort(apps, (a1, a2) -> Long.compare(a2.getAppRamBytes(), a1.getAppRamBytes()));
                break;
            case SORT_MODE_RAM_ASC:
                // Least RAM to most RAM
                Collections.sort(apps, (a1, a2) -> Long.compare(a1.getAppRamBytes(), a2.getAppRamBytes()));
                break;
            case SORT_MODE_NAME_ASC:
                // Name A to Z
                Collections.sort(apps, (a1, a2) -> a1.getAppName().compareToIgnoreCase(a2.getAppName()));
                break;
            case SORT_MODE_NAME_DESC:
                // Name Z to A
                Collections.sort(apps, (a1, a2) -> a2.getAppName().compareToIgnoreCase(a1.getAppName()));
                break;
            case SORT_MODE_DEFAULT:
            default:
                // Default: System → Persistent → Name
                Collections.sort(apps,
                        Comparator.comparing(AppModel::isSystemApp)
                                .thenComparing(AppModel::isPersistentApp)
                                .thenComparing(a -> a.getAppName().toLowerCase()));
                break;
        }
    }

    // Load all installed applications
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
                        "-", // RAM placeholder
                        0, // Raw RAM bytes
                        pm.getApplicationIcon(appInfo),
                        isSystem,
                        isPersistent,
                        false));
            }
            // Sort alphabetically
            Collections.sort(allApps, (a1, a2) -> a1.getAppName().compareToIgnoreCase(a2.getAppName()));
            handler.post(() -> callback.accept(allApps));
        });
    }

    // Get the set of hidden app package names
    public Set<String> getHiddenApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_HIDDEN_APPS, new HashSet<>()));
    }

    // Save the set of hidden app package names
    public void saveHiddenApps(Set<String> hiddenApps) {
        sharedpreferences.edit().putStringSet(KEY_HIDDEN_APPS, new HashSet<>(hiddenApps)).apply();
    }

    // Get the set of whitelisted (never kill) app package names
    public Set<String> getWhitelistedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>()));
    }

    // Save the set of whitelisted app package names
    public void saveWhitelistedApps(Set<String> whitelistedApps) {
        sharedpreferences.edit().putStringSet(KEY_WHITELISTED_APPS, new HashSet<>(whitelistedApps)).apply();
    }

    public Set<String> getBackgroundRestrictedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_AUTOSTART_DISABLED_APPS, new HashSet<>()));
    }

    public void saveBackgroundRestrictedApps(Set<String> packageNames) {
        sharedpreferences.edit().putStringSet(KEY_AUTOSTART_DISABLED_APPS, new HashSet<>(packageNames)).apply();
    }

    /**
     * Returns the set of packages that have HARD restriction (START_FOREGROUND ignore).
     * All other restricted packages use SOFT restriction (RUN_ANY_IN_BACKGROUND ignore).
     */
    public Set<String> getHardRestrictedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_HARD_RESTRICTION_APPS, new HashSet<>()));
    }

    public void saveHardRestrictedApps(Set<String> packageNames) {
        sharedpreferences.edit().putStringSet(KEY_HARD_RESTRICTION_APPS, new HashSet<>(packageNames)).apply();
    }

    /**
     * Returns true if the given package has HARD restriction type.
     */
    public boolean isHardRestricted(String packageName) {
        return getHardRestrictedApps().contains(packageName);
    }

    /**
     * Sets the restriction type for a package (hard or soft).
     * The package must already be in the restricted list.
     */
    public void setRestrictionType(String packageName, boolean hard) {
        Set<String> hardSet = getHardRestrictedApps();
        if (hard) {
            hardSet.add(packageName);
        } else {
            hardSet.remove(packageName);
        }
        saveHardRestrictedApps(hardSet);
    }

    public Set<String> getBlacklistedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_BLACKLISTED_APPS, new HashSet<>()));
    }

    public void saveBlacklistedApps(Set<String> apps) {
        sharedpreferences.edit().putStringSet(KEY_BLACKLISTED_APPS, new HashSet<>(apps)).apply();
    }

    public int getKillMode() {
        return sharedpreferences.getInt(KEY_KILL_MODE, 1);
    }

    public void setKillMode(int mode) {
        sharedpreferences.edit().putInt(KEY_KILL_MODE, mode).apply();
    }
    
    public int getAutoKillType() {
        return sharedpreferences.getInt(KEY_AUTO_KILL_TYPE, 0);
    }

    public void setAutoKillType(int type) {
        sharedpreferences.edit().putInt(KEY_AUTO_KILL_TYPE, type).apply();
    }

    private String buildKillCommand(String packageName) {
        return (getAutoKillType() == 1 ? "am kill " : "am force-stop ") + packageName;
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

    /**
     * Applies background restrictions.
     * @param targetPackages  full set of packages that should be restricted
     * @param hardPackages    subset of targetPackages that should use HARD restriction (START_FOREGROUND ignore).
     *                        Pass null to preserve existing hard/soft assignments from preferences.
     * @param onComplete      called on main thread when done
     */
    public void applyBackgroundRestriction(Set<String> targetPackages, Set<String> hardPackages, Runnable onComplete) {
        Set<String> desiredPackages = sanitizeBackgroundRestrictionTargets(targetPackages);
        saveBackgroundRestrictedApps(desiredPackages);

        // If caller explicitly passes hardPackages, save them; otherwise keep existing
        if (hardPackages != null) {
            // Only keep hard assignments for packages that are still restricted
            Set<String> sanitizedHard = new HashSet<>(hardPackages);
            sanitizedHard.retainAll(desiredPackages);
            saveHardRestrictedApps(sanitizedHard);
        } else {
            // Clean up any hard assignments for packages no longer restricted
            Set<String> existingHard = getHardRestrictedApps();
            existingHard.retainAll(desiredPackages);
            saveHardRestrictedApps(existingHard);
        }

        if (!supportsBackgroundRestriction()) {
            BackgroundRestrictionLog.log(context, null, "apply", "skipped", "Android 11+ required");
            Toast.makeText(context, "Background restriction requires Android 11+", Toast.LENGTH_SHORT).show();
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
            Set<String> hardSet = getHardRestrictedApps();

            // Packages to allow (were restricted, now should not be)
            Set<String> packagesToAllow = new HashSet<>(currentPackages);
            packagesToAllow.removeAll(desiredPackages);

            // Packages to restrict (newly added)
            Set<String> packagesToRestrict = new HashSet<>(desiredPackages);
            packagesToRestrict.removeAll(currentPackages);

            // Packages already restricted but whose TYPE may have changed
            Set<String> packagesToUpdate = new HashSet<>(desiredPackages);
            packagesToUpdate.retainAll(currentPackages);

            boolean success = true;

            // Allow (remove restriction) — clear all ops, restore whitelist
            for (String packageName : packagesToAllow) {
                ShellManager.ShellResult r1 = shellManager
                        .runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "allow"));
                ShellManager.ShellResult r2 = shellManager
                        .runShellCommandForResult(buildHardRestrictionCommand(packageName, "allow"));
                // Clear all hard extra ops
                applyHardExtraOps(packageName, "allow");
                // Restore boot op to default
                shellManager.runShellCommandForResult(buildBootRestrictionCommand(packageName, "allow"));
                // Restore battery whitelist if we removed it
                restoreBatteryWhitelist(packageName);
                if (!r1.succeeded()) success = false;
                logRestrictionResult(packageName, "allow", r1, null);
            }

            // Restrict (newly added) — apply correct type
            for (String packageName : packagesToRestrict) {
                boolean isHard = hardSet.contains(packageName);
                ShellManager.ShellResult restrictResult = isHard
                        ? shellManager.runShellCommandForResult(buildHardRestrictionCommand(packageName, "ignore"))
                        : shellManager.runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "ignore"));
                if (!restrictResult.succeeded()) {
                    success = false;
                    logRestrictionResult(packageName, isHard ? "restrict-hard" : "restrict-soft", restrictResult, null);
                    continue;
                }
                if (isHard) {
                    // Hard: apply all extra ops, block boot broadcast, remove from battery whitelist
                    applyHardExtraOps(packageName, "ignore");
                    shellManager.runShellCommandForResult(buildBootRestrictionCommand(packageName, "ignore"));
                    applyBatteryWhitelistRemoval(packageName);
                }
                ShellManager.ShellResult forceStopResult = shellManager.runShellCommandForResult(FORCE_STOP_COMMAND_PREFIX + packageName);
                if (!forceStopResult.succeeded()) success = false;
                logRestrictionResult(packageName, isHard ? "restrict-hard" : "restrict-soft", restrictResult, forceStopResult);
            }

            // Update type for already-restricted packages (re-apply to ensure correct op is set)
            for (String packageName : packagesToUpdate) {
                boolean isHard = hardSet.contains(packageName);
                if (isHard) {
                    // Ensure soft is cleared, hard is set + extra ops
                    shellManager.runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "allow"));
                    shellManager.runShellCommandForResult(buildHardRestrictionCommand(packageName, "ignore"));
                    applyHardExtraOps(packageName, "ignore");
                    shellManager.runShellCommandForResult(buildBootRestrictionCommand(packageName, "ignore"));
                    applyBatteryWhitelistRemoval(packageName);
                } else {
                    // Ensure hard + extra ops are cleared, soft is set
                    shellManager.runShellCommandForResult(buildHardRestrictionCommand(packageName, "allow"));
                    applyHardExtraOps(packageName, "allow");
                    shellManager.runShellCommandForResult(buildBootRestrictionCommand(packageName, "allow"));
                    restoreBatteryWhitelist(packageName);
                    shellManager.runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "ignore"));
                }
            }

            BackgroundRestrictionState actualState = getBackgroundRestrictionState();
            for (String packageName : packagesToAllow) {
                logRestrictionVerification(packageName, "allow", actualState, false);
            }
            for (String packageName : packagesToRestrict) {
                logRestrictionVerification(packageName, "restrict", actualState, true);
            }

            final boolean finalSuccess = success;
            handler.post(() -> {
                Toast.makeText(context,
                        finalSuccess ? context.getString(R.string.bg_manager_restriction_applied)
                                : "Some background restriction changes failed",
                        Toast.LENGTH_SHORT).show();
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
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

    /**
     * Applies or clears the full set of HARD restriction ops for a package.
     * Called in addition to {@link #buildHardRestrictionCommand} (START_FOREGROUND)
     * and {@link #buildBootRestrictionCommand} (RECEIVE_BOOT_COMPLETED).
     * Covers: RUN_ANY_IN_BACKGROUND, RUN_IN_BACKGROUND,
     *         START_FOREGROUND_SERVICES_FROM_BACKGROUND, WAKE_LOCK, ALARM_WAKEUP.
     */
    private void applyHardExtraOps(String packageName, String mode) {
        shellManager.runShellCommandForResult(
                "cmd appops set --user current " + packageName + " " + BACKGROUND_RESTRICTION_OP + " " + mode);
        shellManager.runShellCommandForResult(
                "cmd appops set --user current " + packageName + " " + BG_RUN_RESTRICTION_OP + " " + mode);
        shellManager.runShellCommandForResult(
                "cmd appops set --user current " + packageName + " " + FGS_FROM_BG_RESTRICTION_OP + " " + mode);
        shellManager.runShellCommandForResult(
                "cmd appops set --user current " + packageName + " " + WAKE_LOCK_RESTRICTION_OP + " " + mode);
        shellManager.runShellCommandForResult(
                "cmd appops set --user current " + packageName + " " + ALARM_RESTRICTION_OP + " " + mode);
    }

    /**
     * Checks if the package is currently in the user battery optimization whitelist.
     * Only user-added entries can be removed — system/temp entries are skipped.
     */
    private boolean isInBatteryWhitelist(String packageName) {
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys deviceidle whitelist");
        if (output == null) return false;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            // Only match user-added entries: "user,com.example.app,uid"
            if (trimmed.startsWith("user,") && trimmed.contains("," + packageName + ",")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes package from battery optimization whitelist and records the removal
     * so it can be restored when hard restriction is lifted.
     */
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

    /**
     * Restores package to battery optimization whitelist if ReAppzuku was the one who removed it.
     */
    private void restoreBatteryWhitelist(String packageName) {
        Set<String> removed = getBatteryWhitelistRemoved();
        if (!removed.contains(packageName)) return;
        shellManager.runShellCommandForResult("cmd deviceidle whitelist +" + packageName);
        removed.remove(packageName);
        saveBatteryWhitelistRemoved(removed);
        BackgroundRestrictionLog.log(context, packageName, "allow",
                "battery-whitelist-restored", "restored to deviceidle whitelist");
    }

    public Set<String> getBatteryWhitelistRemoved() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_BATTERY_WHITELIST_REMOVED, new HashSet<>()));
    }

    public void saveBatteryWhitelistRemoved(Set<String> packages) {
        sharedpreferences.edit().putStringSet(KEY_BATTERY_WHITELIST_REMOVED, new HashSet<>(packages)).apply();
    }

    /**
     * Forcefully re-applies all saved background restrictions without comparing
     * against current actual state. This ensures firmware resets are corrected.
     * Unlike applyBackgroundRestriction(), this always executes shell commands
     * for every saved package and always writes to the restriction log.
     */
    public void reapplySavedBackgroundRestrictions(Runnable onComplete) {
        Set<String> desired = sanitizeBackgroundRestrictionTargets(getBackgroundRestrictedApps());
        Set<String> hard = getHardRestrictedApps();

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
                boolean isHard = hard.contains(packageName);
            
                ShellManager.ShellResult result = isHard
                        ? shellManager.runShellCommandForResult(buildHardRestrictionCommand(packageName, "ignore"))
                        : shellManager.runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "ignore"));
                if (!result.succeeded()) success = false;
                logRestrictionResult(packageName, isHard ? "reapply-hard" : "reapply-soft", result, null);

                if (isHard) {
                    applyHardExtraOps(packageName, "ignore");
                    shellManager.runShellCommandForResult(buildBootRestrictionCommand(packageName, "ignore"));
                    applyBatteryWhitelistRemoval(packageName);
                }
                
                BackgroundRestrictionState actualState = getBackgroundRestrictionState();
                logRestrictionVerification(packageName, isHard ? "reapply-hard" : "reapply-soft",
                        actualState, true);
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

    // --- Sleep Mode ---

    public Set<String> getSleepModeApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_SLEEP_MODE_APPS, new HashSet<>()));
    }

    public void saveSleepModeApps(Set<String> packages) {
        sharedpreferences.edit().putStringSet(KEY_SLEEP_MODE_APPS, new HashSet<>(packages)).apply();
    }

    public boolean isSleepModeEnabled() {
        return sharedpreferences.getBoolean(KEY_SLEEP_MODE_ENABLED, false);
    }

    public void setSleepModeEnabled(boolean enabled) {
        sharedpreferences.edit().putBoolean(KEY_SLEEP_MODE_ENABLED, enabled).apply();
    }

    /**
     * Loads all non-system installed apps for Sleep Mode selection.
     * System apps are excluded because shell cannot freeze them.
     */
    public void loadSleepModeApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            Set<String> sleepModeApps = getSleepModeApps();
            List<AppModel> result = new ArrayList<>();
            for (ApplicationInfo appInfo : packages) {
                if (appInfo.packageName.equals(context.getPackageName())) continue;
                // Exclude system apps — shell cannot freeze them
                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (isSystem) continue;
                AppModel model = new AppModel(
                        pm.getApplicationLabel(appInfo).toString(),
                        appInfo.packageName,
                        "-",
                        0,
                        pm.getApplicationIcon(appInfo),
                        false,
                        (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0,
                        ProtectedApps.isProtected(context, appInfo.packageName));
                model.setSelected(sleepModeApps.contains(appInfo.packageName));
                result.add(model);
            }
            Collections.sort(result, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
            handler.post(() -> callback.accept(result));
        });
    }

    /**
     * Freeze all apps that are in the Sleep Mode list.
     * Uses pm disable-user via Shizuku Binder API.
     * Should be called after the device has been idle for 1+ hour.
     */
    public void freezeBackgroundRestrictedApps(Runnable onComplete) {
        Set<String> packages = getSleepModeApps();
        if (packages.isEmpty()) {
            if (onComplete != null) handler.post(onComplete);
            return;
        }
        executor.execute(() -> {
            for (String packageName : packages) {
                shellManager.freezePackage(packageName);
            }
            if (onComplete != null) handler.post(onComplete);
        });
    }

    /**
     * Unfreeze all apps that are in the Sleep Mode list.
     * Uses pm enable-user via Shizuku Binder API.
     * Should be called when the screen turns on.
     */
    public void unfreezeBackgroundRestrictedApps(Runnable onComplete) {
        Set<String> packages = getSleepModeApps();
        if (packages.isEmpty()) {
            if (onComplete != null) handler.post(onComplete);
            return;
        }
        executor.execute(() -> {
            for (String packageName : packages) {
                shellManager.unfreezePackage(packageName);
            }
            if (onComplete != null) handler.post(onComplete);
        });
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

        // Soft restriction: RUN_ANY_IN_BACKGROUND ignore/deny
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

        // Hard restriction: START_FOREGROUND ignore/deny — тоже считаем как restricted
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

    private Set<String> sanitizeBackgroundRestrictionTargets(Set<String> targetPackages) {
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

    private void logRestrictionResult(String packageName, String action,
            ShellManager.ShellResult appOpsResult, ShellManager.ShellResult forceStopResult) {
        StringBuilder detail = new StringBuilder()
                .append("appops=")
                .append(formatShellOutcome(appOpsResult));
        if (forceStopResult != null) {
            detail.append(" force-stop=").append(formatShellOutcome(forceStopResult));
        }
        String outcome = appOpsResult != null && appOpsResult.succeeded()
                && (forceStopResult == null || forceStopResult.succeeded())
                        ? "ok"
                        : "failed";
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

    // Toggle whitelist status for a package
    public boolean toggleWhitelist(String packageName) {
        Set<String> whitelisted = getWhitelistedApps();
        boolean isNowWhitelisted;
        if (whitelisted.contains(packageName)) {
            whitelisted.remove(packageName);
            isNowWhitelisted = false;
        } else {
            whitelisted.add(packageName);
            isNowWhitelisted = true;
        }
        saveWhitelistedApps(whitelisted);
        return isNowWhitelisted;
    }

    // Kill specified packages using shell
    public void killPackages(List<String> packageNames, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }

        if (packageNames == null || packageNames.isEmpty()) {
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        long totalKb = 0;
        Map<String, Long> recoveredKbByPackage = new HashMap<>();
        for (String pkg : packageNames) {
            long appRamKb = 0;
            for (AppModel app : currentAppsList) {
                if (app.getPackageName().equals(pkg)) {
                    appRamKb = app.getAppRamBytes();
                    break;
                }
            }
            if (appRamKb > 0) {
                recoveredKbByPackage.put(pkg, appRamKb);
                totalKb += appRamKb;
            }
        }

        String command = packageNames.stream()
                .map(this::buildKillCommand)
                .collect(Collectors.joining("; "));
                
        final long finalTotalKb = totalKb;
        final List<String> packagesToLog = new ArrayList<>(packageNames);
        final Map<String, Long> recoveredToLog = new HashMap<>(recoveredKbByPackage);
        shellManager.runShellCommand(command, () -> {
            executor.execute(() -> recordSuccessfulKills(packagesToLog, recoveredToLog));
            String message = "Free up " + formatMemorySize(finalTotalKb);
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            if (onComplete != null) {
                onComplete.run();
            }
        }, () -> {
            Toast.makeText(context, context.getString(R.string.bg_manager_kill_failed), Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                onComplete.run();
            }
        });

    }

    // Kill a single app by package name
    public void killApp(String packageName, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        if (packageName == null || packageName.isEmpty()) {
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        // Find the app's RAM before killing
        long appRamBytes = 0;
        for (AppModel app : currentAppsList) {
            if (app.getPackageName().equals(packageName)) {
                appRamBytes = app.getAppRamBytes();
                break;
            }
        }
        final String packageToKill = packageName;
        final Map<String, Long> recoveredKbByPackage = new HashMap<>();
        if (appRamBytes > 0) {
            recoveredKbByPackage.put(packageToKill, appRamBytes);
        }
        final long finalAppRamBytes = appRamBytes;
        shellManager.runShellCommand(buildKillCommand(packageToKill), () -> {
            executor.execute(() -> recordSuccessfulKills(Collections.singletonList(packageToKill), recoveredKbByPackage));
            if (finalAppRamBytes > 0) {
                String message = "Free up " + formatMemorySize(finalAppRamBytes);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }, () -> {
            Toast.makeText(context, "Failed to stop " + packageName, Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    // Uninstall an app using shell command
    public void uninstallPackage(String packageName, Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            shellManager.checkShellPermissions();
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }
        if (packageName == null || packageName.isEmpty()) {
            if (onComplete != null) {
                handler.post(onComplete);
            }
            return;
        }

        String command = "pm uninstall " + packageName;
        shellManager.runShellCommand(command, () -> {
            Toast.makeText(context, "Uninstall command sent for " + packageName, Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                onComplete.run();
            }
        }, () -> {
            Toast.makeText(context, "Failed to uninstall " + packageName, Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    // Toggle visibility of system apps
    public void setShowSystemApps(boolean show) {
        this.showSystemApps = show;
    }

    // Toggle visibility of persistent apps
    public void setShowPersistentApps(boolean show) {
        this.showPersistentApps = show;
    }

    public void clearCaches(Runnable onComplete) {
        if (!shellManager.hasAnyShellPermission()) {
            if (onComplete != null)
                handler.post(onComplete);
            return;
        }

        executor.execute(() -> {
            // Standard Android command to trim caches to 0
            shellManager.runShellCommand("pm trim-caches 4096G", () -> {
                Toast.makeText(context, "Caches cleared", Toast.LENGTH_SHORT).show();
                if (onComplete != null)
                    onComplete.run();
            }, () -> {
                Toast.makeText(context, "Failed to clear caches", Toast.LENGTH_SHORT).show();
                if (onComplete != null)
                    onComplete.run();
            });
        });
    }

    private void recordSuccessfulKills(List<String> packageNames, Map<String, Long> recoveredKbByPackage) {
        if (packageNames == null || packageNames.isEmpty()) {
            return;
        }

        com.gree1d.reappzuku.db.AppStatsDao appStatsDao =
                com.gree1d.reappzuku.db.AppDatabase.getInstance(context).appStatsDao();
        PackageManager packageManager = context.getPackageManager();
        long now = System.currentTimeMillis();

        Set<String> uniquePackages = new HashSet<>(packageNames);
        for (String packageName : uniquePackages) {
            if (packageName == null || packageName.isEmpty()) {
                continue;
            }

            com.gree1d.reappzuku.db.AppStats stats = appStatsDao.getStats(packageName);
            String appName = resolveInstalledAppName(packageManager, packageName);

            if (stats == null) {
                stats = new com.gree1d.reappzuku.db.AppStats(packageName);
                stats.appName = appName;
                appStatsDao.insert(stats);
            } else if ((stats.appName == null || stats.appName.trim().isEmpty())
                    && appName != null && !appName.trim().isEmpty()) {
                appStatsDao.updateAppName(packageName, appName);
            }

            appStatsDao.incrementKill(packageName, now);

            long recoveredKb = recoveredKbByPackage != null ? recoveredKbByPackage.getOrDefault(packageName, 0L) : 0L;
            if (recoveredKb > 0) {
                appStatsDao.addRecoveredKb(packageName, recoveredKb);
            }
        }
    }

    private String resolveInstalledAppName(PackageManager packageManager, String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence label = packageManager.getApplicationLabel(appInfo);
            if (label != null) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return null;
    }

    private static boolean containsPackage(String output, String packageName) {
        if (output == null || packageName == null) return false;

        int idx = output.indexOf(packageName);
        if (idx == -1) {
            Log.d(TAG, "containsPackage: NOT FOUND in dumpsys: " + packageName);
            return false;
        }

        while (idx != -1) {
            int end = idx + packageName.length();
            boolean endOk = end >= output.length()
                    || !Character.isLetterOrDigit(output.charAt(end)) && output.charAt(end) != '.';
            boolean startOk = idx == 0
                    || !Character.isLetterOrDigit(output.charAt(idx - 1)) && output.charAt(idx - 1) != '.';
            if (startOk && endOk) {
                int from = Math.max(0, idx - 40);
                int to = Math.min(output.length(), end + 40);
                Log.d(TAG, "containsPackage: FOUND " + packageName
                        + " | context: [" + output.substring(from, to).replace("\n", "↵") + "]");
                return true;
            }
            idx = output.indexOf(packageName, idx + 1);
        }

        Log.d(TAG, "containsPackage: found as substring but boundaries failed: " + packageName);
        return false;
    }

    /**
     * Updates the home screen widget to reflect current RAM state.
     */
    private void updateWidget() {
        try {
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName widgetComponent = new ComponentName(context, AppzukuWidget.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent);
            for (int appWidgetId : appWidgetIds) {
                AppzukuWidget.updateAppWidget(context, appWidgetManager, appWidgetId);
            }
        } catch (Exception ignored) {
            // Widget may not exist or other issue - fail silently
        }
    }
}