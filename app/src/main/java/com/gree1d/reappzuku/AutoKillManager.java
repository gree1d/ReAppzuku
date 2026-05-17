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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.gree1d.reappzuku.PreferenceKeys.*;
import static com.gree1d.reappzuku.AppConstants.*;

public class AutoKillManager {
    private static final String TAG = "AutoKillManager";

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final SharedPreferences sharedpreferences;
    private final List<AppModel> currentAppsList;
    private RestrictionsScheduler scheduler;

    public AutoKillManager(Context context, Handler handler, ExecutorService executor,
            ShellManager shellManager, List<AppModel> currentAppsList) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.currentAppsList = currentAppsList;
        this.sharedpreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public void setScheduler(RestrictionsScheduler scheduler) {
        this.scheduler = scheduler;
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
            int killMode = getKillMode();

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

            String psOutput = shellManager.runShellCommandAndGetFullOutput(
                    "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]'");
            Log.d(TAG, "ps output length: " + (psOutput == null ? "null" : psOutput.trim().length()));
            if (psOutput == null || psOutput.trim().isEmpty()) {
                Log.w(TAG, "ps returned null/empty — aborting kill");
                if (onComplete != null)
                    handler.post(onComplete);
                return;
            }

            Set<String> runningPackages = new HashSet<>();
            Map<String, Long> psRssMap = new HashMap<>();
            PackageManager pm = context.getPackageManager();
            try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length != 2) continue;
                    String packageName = parts[1].trim();
                    if (!packageName.isEmpty() && packageName.contains(".")) {
                        try {
                            pm.getApplicationInfo(packageName, 0);
                            runningPackages.add(packageName);
                            try {
                                long rssKb = Long.parseLong(parts[0].trim());
                                psRssMap.put(packageName, rssKb);
                            } catch (NumberFormatException ignored) {
                            }
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
                            if (scheduler != null && scheduler.isProtected(pkg, RestrictionsScheduler.PROTECT_AUTO_KILL)) {
                                Log.d(TAG, "SKIP (temp protected): " + pkg);
                                return false;
                            }
                            if (containsPackage(dumpOutput, pkg)) {
                                Log.d(TAG, "SKIP (foreground): " + pkg);
                                return false;
                            }
                            if (killMode == 1) {
                                boolean inBlacklist = blacklistedApps.contains(pkg);
                                Log.d(TAG, (inBlacklist ? "KILL (blacklist): " : "SKIP (not in blacklist): ") + pkg);
                                return inBlacklist;
                            } else {
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
                for (String pkg : toKill) {
                    long rssKb = psRssMap.getOrDefault(pkg, 0L);
                    if (rssKb == 0) {

                        for (AppModel app : currentAppsList) {
                            if (app.getPackageName().equals(pkg)) {
                                rssKb = app.getAppRamBytes();
                                break;
                            }
                        }
                    }
                    if (rssKb > 0) {
                        recoveredKbByPackage.put(pkg, rssKb);
                        Log.d(TAG, "RAM for " + pkg + ": " + rssKb + " KB");
                    }
                }
                recordSuccessfulKills(toKill, recoveredKbByPackage);

                String killCommand = toKill.stream()
                        .map(this::buildKillCommand)
                        .collect(Collectors.joining("; "));

                shellManager.runShellCommandAndGetFullOutput(killCommand);

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
        String psOutput = shellManager.runShellCommandAndGetFullOutput("ps -A -o name | grep '\\.'");
        if (psOutput == null)
            return;

        long now = System.currentTimeMillis();
        try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String pkg = line.trim();
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
            Toast.makeText(context, context.getString(R.string.toast_free_up, formatMemorySize(finalTotalKb)), Toast.LENGTH_LONG).show();
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
                Toast.makeText(context, context.getString(R.string.toast_free_up, formatMemorySize(finalAppRamBytes)), Toast.LENGTH_LONG).show();
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }, () -> {
            Toast.makeText(context, context.getString(R.string.toast_failed_stop_app, packageName), Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

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
            Toast.makeText(context, context.getString(R.string.toast_uninstall_sent, packageName), Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                onComplete.run();
            }
        }, () -> {
            Toast.makeText(context, context.getString(R.string.toast_failed_uninstall, packageName), Toast.LENGTH_SHORT).show();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private String buildKillCommand(String packageName) {
        String cmd = (getAutoKillType() == 1 ? "am kill " : "am force-stop ") + packageName;
        Log.d(TAG, "buildKillCommand: " + cmd + " (type=" + getAutoKillType() + ")");
        return cmd;
    }

    private void sendKillNotification(int count) {
        ShappkyService.updateNotification(context,
                context.getString(R.string.bg_manager_auto_kill_active),
                context.getString(R.string.bg_manager_stopped_apps, count));
    }

    private void updateWidget() {
        try {
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName widgetComponent = new ComponentName(context, AppzukuWidget.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent);
            for (int appWidgetId : appWidgetIds) {
                AppzukuWidget.updateAppWidget(context, appWidgetManager, appWidgetId);
            }
        } catch (Exception ignored) {
        }
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
        int newEntries = 0;
        for (String pkg : uniquePackages) {
            if (pkg != null && !pkg.isEmpty() && appStatsDao.getStats(pkg) == null) {
                newEntries++;
            }
        }
        if (newEntries > 0) {
            int currentCount = appStatsDao.getCount();
            int excess = (currentCount + newEntries) - STATS_MAX_COUNT;
            if (excess > 0) {
                appStatsDao.deleteOldestStats(excess);
            }
        }
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

    private String formatMemorySize(long kb) {
        if (kb < 1024)
            return kb + " KB";
        else if (kb < 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2f MB", kb / 1024f);
        else
            return String.format(java.util.Locale.US, "%.2f GB", kb / (1024f * 1024f));
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
}
