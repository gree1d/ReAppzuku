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
import android.app.NotificationChannel;
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

    public BackgroundAppManager(Context context, Handler handler, ExecutorService executor, ShellManager shellManager) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.sharedpreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
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

            String dumpOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity activities");
            if (dumpOutput == null) {
                if (onComplete != null)
                    handler.post(onComplete);
                return;
            }

            String psOutput = shellManager.runShellCommandAndGetFullOutput(
                    "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
            if (psOutput == null) {
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
                            // Common checks: foreground, hidden, protected
                            if (hiddenApps.contains(pkg) || ProtectedApps.isProtected(context, pkg)
                                    || dumpOutput.contains(pkg)) {
                                return false;
                            }

                            if (killMode == 1) { // Blacklist Mode
                                return blacklistedApps.contains(pkg);
                            } else { // Whitelist Mode (Default)
                                if (whitelistedApps.contains(pkg))
                                    return false;
                                // In whitelist mode, check persistent flag
                                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                                return (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) == 0;
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            if (!toKill.isEmpty()) {
                Map<String, Long> recoveredKbByPackage = new HashMap<>();
                for (AppModel app : currentAppsList) {
                    recoveredKbByPackage.put(app.getPackageName(), app.getAppRamBytes());
                }
                recordSuccessfulKills(toKill, recoveredKbByPackage);

                String killCommand = toKill.stream()
                        .map(pkg -> "am force-stop " + pkg)
                        .collect(Collectors.joining("; "));
                String finalCommand = killCommand + "; am kill-all";

                shellManager.runShellCommandAndGetFullOutput(finalCommand);

                sendKillNotification(toKill.size());

                // Update widget to reflect new RAM state
                updateWidget();

                // Short delay to allow system to react, then check for relaunches
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
        String psOutput = shellManager.runShellCommandAndGetFullOutput("ps -A -o name | grep '\\.'");
        if (psOutput == null)
            return;

        long now = System.currentTimeMillis();
        try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String pkg = line.trim();
                if (recentlyKilled.contains(pkg)) {
                    db.appStatsDao().incrementRelaunch(pkg, now);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void sendKillNotification(int count) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID_ACTIONS, "Appzuku Actions",
                    NotificationManager.IMPORTANCE_LOW);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID_ACTIONS)
                .setSmallIcon(R.drawable.ic_shappky)
                .setContentTitle("Auto-Kill Executed")
                .setContentText("Stopped " + count + " background apps")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);

        context.getSystemService(NotificationManager.class).notify(2, builder.build());
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
                    String fullOutput = shellManager.runShellCommandAndGetFullOutput(command);
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

                    // Use centralized protected apps check
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
                    // Set whitelist status
                    appModel.setWhitelisted(whitelistedApps.contains(packageName));
                    applyBackgroundRestrictionState(appModel, desiredBackgroundRestrictedApps, backgroundRestrictionState);
                    result.add(appModel);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            sortAppList(result, SORT_MODE_DEFAULT);

            // Update UI with results
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
            String psOutput = shellManager.runShellCommandAndGetFullOutput(
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

    public Set<String> getBlacklistedApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_BLACKLISTED_APPS, new HashSet<>()));
    }

    public void saveBlacklistedApps(Set<String> apps) {
        sharedpreferences.edit().putStringSet(KEY_BLACKLISTED_APPS, new HashSet<>(apps)).apply();
    }

    public int getKillMode() {
        return sharedpreferences.getInt(KEY_KILL_MODE, 0);
    }

    public void setKillMode(int mode) {
        sharedpreferences.edit().putInt(KEY_KILL_MODE, mode).apply();
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
        Set<String> desiredPackages = sanitizeBackgroundRestrictionTargets(targetPackages);
        saveBackgroundRestrictedApps(desiredPackages);

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
            Set<String> packagesToAllow = new HashSet<>(currentPackages);
            packagesToAllow.removeAll(desiredPackages);

            Set<String> packagesToRestrict = new HashSet<>(desiredPackages);
            packagesToRestrict.removeAll(currentPackages);

            boolean success = true;
            for (String packageName : packagesToAllow) {
                ShellManager.ShellResult allowResult = shellManager
                        .runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "allow"));
                if (!allowResult.succeeded()) {
                    success = false;
                }
                logRestrictionResult(packageName, "allow", allowResult, null);
            }

            for (String packageName : packagesToRestrict) {
                ShellManager.ShellResult restrictResult = shellManager
                        .runShellCommandForResult(buildBackgroundRestrictionCommand(packageName, "ignore"));
                if (!restrictResult.succeeded()) {
                    success = false;
                    logRestrictionResult(packageName, "restrict", restrictResult, null);
                    continue;
                }
                ShellManager.ShellResult forceStopResult = shellManager.runShellCommandForResult(FORCE_STOP_COMMAND_PREFIX + packageName);
                if (!forceStopResult.succeeded()) {
                    success = false;
                }
                logRestrictionResult(packageName, "restrict", restrictResult, forceStopResult);
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
                        finalSuccess ? "Background restriction settings applied"
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

    public void reapplySavedBackgroundRestrictions(Runnable onComplete) {
        applyBackgroundRestriction(getBackgroundRestrictedApps(), onComplete);
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
                .map(pkg -> "am force-stop " + pkg)
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
            Toast.makeText(context, "Failed to kill selected apps", Toast.LENGTH_SHORT).show();
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
        shellManager.runShellCommand("am force-stop " + packageToKill, () -> {
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
