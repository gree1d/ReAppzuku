package com.gree1d.reappzuku.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;
import com.gree1d.reappzuku.core.AppDebugManager;
import com.gree1d.reappzuku.core.AppDebugManager.Category;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

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

import com.gree1d.reappzuku.core.ShellManager;
import com.gree1d.reappzuku.utils.AppModel;
import com.gree1d.reappzuku.R;
import com.gree1d.reappzuku.service.ShappkyService;
import com.gree1d.reappzuku.core.ProtectedApps;

import static com.gree1d.reappzuku.core.PreferenceKeys.*;
import static com.gree1d.reappzuku.core.AppConstants.*;

public class AutoKillManager {
    private static final String TAG = "AutoKillManager";
  
    private static final int STATS_LIMIT = 15_000;

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

    public void performAutoKill(Runnable onComplete, String source) {
        performAutoKill(onComplete, null, source);
    }

    public void performAutoKill(Runnable onComplete, Set<String> extraWhitelist, String source) {
        performAutoKillWithResult(onComplete, extraWhitelist, null, source);
    }

    public void performAutoKillWithResult(Runnable onComplete, Set<String> extraWhitelist,
            java.util.function.BiConsumer<Integer, Long> onResult, String source) {
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

            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: === performAutoKill start ===");
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: killMode=" + (killMode == 1 ? "BLACKLIST" : "WHITELIST"));
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: whitelistedApps=" + whitelistedApps);
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: blacklistedApps=" + blacklistedApps);
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: hiddenApps=" + hiddenApps);

            long meminfoStart = System.currentTimeMillis();

            Set<String> runningPackages = new HashSet<>();
            Map<String, Long> psRssMap = new HashMap<>();
            Map<Integer, String> pidToPackage = new HashMap<>();
            Map<String, String> packageMemorySource = new HashMap<>();
            PackageManager pm = context.getPackageManager();
            String memorySource = "PSS";

            Map<String, List<Integer>> pidsByPackage = new HashMap<>();
            Map<Integer, Long> psRssByPid = new HashMap<>();
            String pidListOutput = shellManager.runShellCommandAndGetFullOutput("ps -A -o pid,rss,name | grep '\\.'");
            if (pidListOutput != null) {
                try (BufferedReader reader = new BufferedReader(new StringReader(pidListOutput))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+", 3);
                        if (parts.length < 3) continue;
                        String packageName = parts[2].trim();
                        if (packageName.contains(":")) {
                            packageName = packageName.substring(0, packageName.indexOf(":"));
                        }
                        if (packageName.isEmpty() || !packageName.contains(".")) continue;
                        try {
                            int pid = Integer.parseInt(parts[0].trim());
                            pidsByPackage.computeIfAbsent(packageName, k -> new ArrayList<>()).add(pid);
                            try {
                                psRssByPid.put(pid, Long.parseLong(parts[1].trim()));
                            } catch (NumberFormatException ignored) {
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            }

            if (!pidsByPackage.isEmpty()) {
                int[] allPids = pidsByPackage.values().stream()
                        .flatMap(List::stream)
                        .mapToInt(Integer::intValue)
                        .toArray();

                List<com.gree1d.reappzuku.core.shell.ProcessMemoryInfo> memInfos =
                        shellManager.getProcessMemoryInfo(allPids);

                AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: getProcessMemoryInfo returned " + memInfos.size()
                        + " entries for " + allPids.length + " pids (took " + (System.currentTimeMillis() - meminfoStart) + "ms)");

                if (!memInfos.isEmpty()) {
                    Map<Integer, Long> pssByPid = new HashMap<>();
                    for (com.gree1d.reappzuku.core.shell.ProcessMemoryInfo info : memInfos) {
                        pssByPid.put(info.pid, info.totalPssKb);
                    }
                    for (Map.Entry<String, List<Integer>> entry : pidsByPackage.entrySet()) {
                        String packageName = entry.getKey();
                        try {
                            pm.getApplicationInfo(packageName, 0);
                        } catch (PackageManager.NameNotFoundException ignored) {
                            continue;
                        }
                        long total = 0;
                        boolean anyResolved = false;
                        boolean anyPss = false;
                        boolean anyRssFallback = false;
                        for (int pid : entry.getValue()) {
                            Long pss = pssByPid.get(pid);
                            if (pss != null) {
                                total += pss;
                                anyResolved = true;
                                anyPss = true;
                                pidToPackage.put(pid, packageName);
                            } else {
                                Long rss = psRssByPid.get(pid);
                                if (rss != null) {
                                    total += rss;
                                    anyResolved = true;
                                    anyRssFallback = true;
                                    pidToPackage.put(pid, packageName);
                                    AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: pid " + pid
                                            + " (" + packageName + ") missing from getProcessMemoryInfo — using ps RSS fallback: " + rss + " KB");
                                }
                            }
                        }
                        if (anyResolved) {
                            runningPackages.add(packageName);
                            psRssMap.put(packageName, total);
                            packageMemorySource.put(packageName, anyPss && anyRssFallback ? "PSS+RSS" : anyPss ? "PSS" : "RSS");
                        }
                    }
                }
            }

            if (runningPackages.isEmpty()) {
                memorySource = "RSS";
                AppDebugManager.w(Category.AUTO_KILL_BASE,
                        "AutoKillManager: getProcessMemoryInfo yielded no packages — falling back to ps/rss");
                String psOutput = shellManager.runShellCommandAndGetFullOutput(
                        "ps -A -o rss,name | grep '\\.'");
                if (psOutput == null || psOutput.trim().isEmpty()) {
                    AppDebugManager.w(Category.AUTO_KILL_BASE, "AutoKillManager: ps fallback also empty — aborting kill");
                    if (onComplete != null)
                        handler.post(onComplete);
                    return;
                }
                try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+", 2);
                        if (parts.length < 2) continue;
                        String rssStr = parts[0].trim();
                        String fullName = parts[1].trim();
                        String packageName = fullName.contains(":")
                                ? fullName.substring(0, fullName.indexOf(":"))
                                : fullName;
                        if (packageName.isEmpty() || !packageName.contains(".")) continue;
                        try {
                            pm.getApplicationInfo(packageName, 0);
                            runningPackages.add(packageName);
                            packageMemorySource.put(packageName, "RSS");
                            try {
                                long rssKb = Long.parseLong(rssStr);
                                psRssMap.put(packageName, rssKb);
                            } catch (NumberFormatException ignored) {
                            }
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            }

            long pssCount = packageMemorySource.values().stream().filter(s -> s.equals("PSS")).count();
            long rssCount = packageMemorySource.values().stream().filter(s -> s.equals("RSS")).count();
            long mixedCount = packageMemorySource.values().stream().filter(s -> s.equals("PSS+RSS")).count();
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: memorySource=" + memorySource
                    + " for this cycle (per-package: PSS=" + pssCount + ", RSS=" + rssCount + ", PSS+RSS=" + mixedCount + ")");

            killOrphanShellProcesses(null);

            boolean presetActive = new PresetManager(context).getActivePresetNumber() != 0;

            String currentKeyboard = ProtectedApps.getCurrentKeyboardPackage(context);
            String currentLauncher = ProtectedApps.getCurrentLauncherPackage(context);

            List<String> candidates = runningPackages.stream()
                    .filter(pkg -> {
                        try {
                            if (hiddenApps.contains(pkg)) {
                                AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: SKIP (hidden): " + pkg);
                                return false;
                            }
                            if (ProtectedApps.isProtected(pkg, currentKeyboard, currentLauncher)) {
                                AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: SKIP (protected): " + pkg);
                                return false;
                            }
                            if (extraWhitelist != null && extraWhitelist.contains(pkg)) {
                                AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: SKIP (extra whitelist): " + pkg);
                                return false;
                            }
                            if (!presetActive && scheduler != null && scheduler.isProtected(pkg, RestrictionsScheduler.PROTECT_AUTO_KILL)) {
                                AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: SKIP (temp protected): " + pkg);
                                return false;
                            }
                            if (killMode == 1) {
                                boolean inBlacklist = blacklistedApps.contains(pkg);
                                AppDebugManager.d(Category.AUTO_KILL_BASE, (inBlacklist ? "AutoKillManager: KILL (blacklist): " : "AutoKillManager: SKIP (not in blacklist): ") + pkg);
                                return inBlacklist;
                            } else {
                                if (whitelistedApps.contains(pkg)) {
                                    AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: SKIP (whitelisted): " + pkg);
                                    return false;
                                }
                                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                                boolean persistent = (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
                                AppDebugManager.d(Category.AUTO_KILL_BASE, (persistent ? "AutoKillManager: SKIP (persistent): " : "AutoKillManager: KILL (whitelist mode): ") + pkg);
                                return !persistent;
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: SKIP (not found): " + pkg);
                            return false;
                        }
                    })
                    .collect(Collectors.toList());

            Map<String, String> protectedReasons = Collections.emptyMap();
            if (!candidates.isEmpty()) {
                protectedReasons = collectProtectedForeground(candidates);
                if (protectedReasons == null) {
                    AppDebugManager.w(Category.AUTO_KILL_BASE, "AutoKillManager: foreground detection failed — aborting kill");
                    if (onComplete != null)
                        handler.post(onComplete);
                    return;
                }
            }
            List<String> toKill = new ArrayList<>();
            for (String pkg : candidates) {
                String reason = protectedReasons.get(pkg);
                if (reason != null) {
                    AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: SKIP (" + reason + "): " + pkg);
                } else {
                    toKill.add(pkg);
                }
            }

            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: toKill list (" + toKill.size() + "): " + toKill);

            Map<String, Long> pendingRss = loadPendingRss();
            Map<String, Long> confirmedFreedKb = new HashMap<>();
            for (Map.Entry<String, Long> entry : pendingRss.entrySet()) {
                String pkg = entry.getKey();
                if (!psRssMap.containsKey(pkg)) {
                    confirmedFreedKb.put(pkg, entry.getValue());
                    AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: Confirmed freed RAM for " + pkg + ": " + entry.getValue() + " KB [" + memorySource + "]");
                } else {
                    AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: Skipped RAM (relaunched): " + pkg);
                }
            }
            if (!confirmedFreedKb.isEmpty()) {
                recordConfirmedRam(confirmedFreedKb);
            }

            if (!toKill.isEmpty()) {
                recordSuccessfulKills(toKill, null, source);

                Map<String, Long> newPendingRss = new HashMap<>();
                for (String pkg : toKill) {
                    long rssKb = psRssMap.getOrDefault(pkg, 0L);
                    if (rssKb > 0) {
                        newPendingRss.put(pkg, rssKb);
                        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: Pending " + packageMemorySource.getOrDefault(pkg, memorySource) + " for " + pkg + ": " + rssKb + " KB");
                    }
                }
                savePendingRss(newPendingRss);

                String killCommand = toKill.stream()
                        .map(this::buildKillCommand)
                        .collect(Collectors.joining("; "));

                shellManager.runShellCommandAndGetFullOutput(killCommand);

                sendKillNotification(toKill.size());

                long totalRssKb = 0;
                for (String pkg : toKill) {
                    totalRssKb += psRssMap.getOrDefault(pkg, 0L);
                }
                final long finalTotalRssKb = totalRssKb;
                final int finalKillCount = toKill.size();
                if (onResult != null) {
                    handler.post(() -> onResult.accept(finalKillCount, finalTotalRssKb));
                }

                try {
                    Thread.sleep(RELAUNCH_CHECK_DELAY_MS);
                } catch (InterruptedException ignored) {
                }
                com.gree1d.reappzuku.db.AppDatabase db = com.gree1d.reappzuku.db.AppDatabase.getInstance(context);
                checkRelaunches(toKill, db);
            } else {
                savePendingRss(new HashMap<>());
                if (onResult != null) {
                    handler.post(() -> onResult.accept(0, 0L));
                }
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
            executor.execute(() -> {
                recordSuccessfulKills(packagesToLog, recoveredToLog, "Manual Kill");
                killOrphanShellProcesses(new HashSet<>(packagesToLog));
            });
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
        killApp(packageName, "Manual Kill", onComplete);
    }

    public void killApp(String packageName, String source, Runnable onComplete) {
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
            executor.execute(() -> {
                recordSuccessfulKills(Collections.singletonList(packageToKill), recoveredKbByPackage, source);
                killOrphanShellProcesses(Collections.singleton(packageToKill));
            });
            if ("Shortcut Kill".equals(source)) {
                String appLabel = resolveInstalledAppName(context.getPackageManager(), packageToKill);
                String displayName = appLabel != null ? appLabel : packageToKill;
                Toast.makeText(context, context.getString(R.string.toast_shortcut_killed, displayName), Toast.LENGTH_LONG).show();
            } else if (finalAppRamBytes > 0) {
                Toast.makeText(context, context.getString(R.string.toast_free_up, formatMemorySize(finalAppRamBytes)), Toast.LENGTH_LONG).show();
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }, () -> {
            Toast.makeText(context, context.getString(R.string.toast_failed_stop_app, packageToKill), Toast.LENGTH_SHORT).show();
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

    private static final java.util.regex.Pattern PSS_PROCESS_LINE =
            java.util.regex.Pattern.compile("^\\s*([\\d,]+)K:\\s*(\\S+)\\s*\\(pid\\s+(\\d+)");

    private static final java.util.regex.Pattern SECTION_HEADER_LINE =
            java.util.regex.Pattern.compile("^[A-Za-z].*:\\s*$");


    private void parseTotalPssByProcess(String meminfoOutput, PackageManager pm,
            Set<String> runningPackages, Map<String, Long> psRssMap,
            Map<Integer, String> pidToPackage) {
        try (BufferedReader reader = new BufferedReader(new StringReader(meminfoOutput))) {
            String line;
            boolean inSection = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (!inSection) {
                    if (trimmed.equalsIgnoreCase("Total PSS by process:")) {
                        inSection = true;
                    }
                    continue;
                }

                java.util.regex.Matcher procMatch = PSS_PROCESS_LINE.matcher(line);
                if (!procMatch.find()) {
                    if (trimmed.isEmpty() || SECTION_HEADER_LINE.matcher(trimmed).matches()) {
                        break;
                    }
                    continue;
                }

                String pssStr = procMatch.group(1).replace(",", "");
                String rawName = procMatch.group(2);
                String pidStr = procMatch.group(3);
                String packageName = rawName.contains(":")
                        ? rawName.substring(0, rawName.indexOf(":"))
                        : rawName;

                if (packageName.isEmpty() || !packageName.contains(".")) continue;

                try {
                    long pssKb = Long.parseLong(pssStr);
                    pm.getApplicationInfo(packageName, 0);
                    runningPackages.add(packageName);
                    long existing = psRssMap.getOrDefault(packageName, 0L);
                    psRssMap.put(packageName, existing + pssKb);
                    try {
                        pidToPackage.put(Integer.parseInt(pidStr), packageName);
                    } catch (NumberFormatException ignored) {
                    }
                } catch (NumberFormatException | PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String buildKillCommand(String packageName) {
        String cmd = (getAutoKillType() == 1 ? "am kill " : "am force-stop ") + packageName;
        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: buildKillCommand: " + cmd + " (type=" + getAutoKillType() + ")");
        return cmd;
    }

    public void killPackageSync(String packageName) {
        killPackageSync(packageName, "Manual Kill");
    }

    public void killPackageSync(String packageName, String source) {
        if (packageName == null || packageName.isEmpty()) return;
        String cmd = buildKillCommand(packageName);
        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: killPackageSync: " + cmd + " source=" + source);
        shellManager.runShellCommandAndGetFullOutput(cmd);

        long appRamBytes = 0;
        for (AppModel app : currentAppsList) {
            if (app.getPackageName().equals(packageName)) {
                appRamBytes = app.getAppRamBytes();
                break;
            }
        }
        final Map<String, Long> recoveredKbByPackage = new HashMap<>();
        if (appRamBytes > 0) {
            recoveredKbByPackage.put(packageName, appRamBytes);
        }
        recordSuccessfulKills(Collections.singletonList(packageName), recoveredKbByPackage, source);
        killOrphanShellProcesses(Collections.singleton(packageName));
    }

    private void sendKillNotification(int count) {
        ShappkyService.updateNotification(context,
                context.getString(R.string.bg_manager_auto_kill_active),
                context.getString(R.string.bg_manager_stopped_apps, count));
    }

    public void recordQuickTileKill(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        recordSuccessfulKills(Collections.singletonList(packageName), null, "Quick Tile");
    }

    private void recordSuccessfulKills(List<String> packageNames,
            Map<String, Long> recoveredKbByPackage, String source) {
        if (packageNames == null || packageNames.isEmpty()) {
            return;
        }

        com.gree1d.reappzuku.db.AppStatsDao appStatsDao =
                com.gree1d.reappzuku.db.AppDatabase.getInstance(context).appStatsDao();
        PackageManager packageManager = context.getPackageManager();
        long now = System.currentTimeMillis();

        Set<String> uniquePackages = new HashSet<>(packageNames);
        int newEntries = uniquePackages.size();

        int currentCount = appStatsDao.getCount();
        int excess = (currentCount + newEntries) - STATS_LIMIT;
        if (excess > 0) {
            appStatsDao.deleteOldestStats(excess);
            AppDebugManager.d(Category.AUTO_KILL_BASE,
                    "AutoKillManager: DB limit reached, deleted " + excess + " oldest records");
        }

        for (String packageName : uniquePackages) {
            if (packageName == null || packageName.isEmpty()) {
                continue;
            }

            String appName = resolveInstalledAppName(packageManager, packageName);

            if (appName != null && !appName.trim().isEmpty()) {
                appStatsDao.updateAppName(packageName, appName);
            }

            com.gree1d.reappzuku.db.AppStats stats =
                    new com.gree1d.reappzuku.db.AppStats(packageName);
            stats.appName = appName;
            stats.lastKillTime = now;
            stats.lastKillSource = source;
            stats.relaunchCount = 0;
            stats.lastRelaunchTime = 0;

            long recoveredKb = recoveredKbByPackage != null
                    ? recoveredKbByPackage.getOrDefault(packageName, 0L)
                    : 0L;
            stats.totalRecoveredKb = recoveredKb;

            appStatsDao.insert(stats);

            AppDebugManager.d(Category.AUTO_KILL_BASE,
                    "AutoKillManager: Inserted kill record for " + packageName
                    + " recoveredKb=" + recoveredKb + " source=" + source);
        }
    }

    private void recordConfirmedRam(Map<String, Long> confirmedFreedKb) {
        if (confirmedFreedKb == null || confirmedFreedKb.isEmpty()) return;
        com.gree1d.reappzuku.db.AppStatsDao appStatsDao =
                com.gree1d.reappzuku.db.AppDatabase.getInstance(context).appStatsDao();
        for (Map.Entry<String, Long> entry : confirmedFreedKb.entrySet()) {
            appStatsDao.addRecoveredKb(entry.getKey(), entry.getValue());
            AppDebugManager.d(Category.AUTO_KILL_BASE,
                    "AutoKillManager: Confirmed RAM added for " + entry.getKey()
                    + ": " + entry.getValue() + " KB");
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

    private void killOrphanShellProcesses(Set<String> targetPackages) {
        String psOutput = shellManager.runShellCommandAndGetFullOutput(
                "ps -A -o user,pid,ppid,name | grep '\\.'");
        if (psOutput == null || psOutput.trim().isEmpty()) return;

        Map<String, String> appProcessPids = new HashMap<>();
        Map<String, List<String>> orphanCandidatePids = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(psOutput))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                String user = parts[0].trim();
                String pid = parts[1].trim();
                String ppid = parts[2].trim();
                String fullName = parts[3].trim();
                String packageName = fullName.contains(":")
                        ? fullName.substring(0, fullName.indexOf(":"))
                        : fullName;
                if (packageName.isEmpty() || !packageName.contains(".")) continue;
                if (targetPackages != null && !targetPackages.contains(packageName)) continue;
                if (user.equals("shell") && ppid.equals("1") && fullName.contains(":")) {
                    orphanCandidatePids.computeIfAbsent(packageName, k -> new ArrayList<>()).add(pid);
                } else if (!user.equals("shell")) {
                    appProcessPids.put(packageName, pid);
                }
            }
        } catch (IOException ignored) {
        }

        List<String> toKill = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : orphanCandidatePids.entrySet()) {
            if (!appProcessPids.containsKey(entry.getKey())) {
                toKill.addAll(entry.getValue());
                AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: Orphan shell PIDs for " + entry.getKey() + ": " + entry.getValue());
            }
        }
        if (!toKill.isEmpty()) {
            shellManager.runShellCommandAndGetFullOutput("kill -9 " + String.join(" ", toKill));
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: Killed orphan shell PIDs: " + toKill);
        }
    }

    private static final String FG_SECTION_PREFIX = "===RAZ_";
    private static final String FG_SECTION_SUFFIX = "===";
    private static final String FG_SEC_RESUMED = "RESUMED";
    private static final String FG_SEC_MEDIA = "MEDIA";
    private static final String FG_SEC_FGS = "FGS";
    private static final String FG_SEC_RECENTS = "RECENTS";

    private static final String FG_COMBINED_COMMAND =
            "echo " + FG_SECTION_PREFIX + FG_SEC_RESUMED + FG_SECTION_SUFFIX + "; "
            + "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|mFocusedActivity|ResumedActivity'; "
            + "echo " + FG_SECTION_PREFIX + FG_SEC_MEDIA + FG_SECTION_SUFFIX + "; "
            + "dumpsys media_session | grep -E '^ *package=|state=PlaybackState'; "
            + "echo " + FG_SECTION_PREFIX + FG_SEC_FGS + FG_SECTION_SUFFIX + "; "
            + "dumpsys activity services | grep -E '^ *packageName=|isForeground=true'; "
            + "echo " + FG_SECTION_PREFIX + FG_SEC_RECENTS + FG_SECTION_SUFFIX + "; "
            + "dumpsys activity recents | grep -E 'Recent #|realActivity=|cmp='";

    private static final java.util.regex.Pattern FG_RESUMED_PATTERN = java.util.regex.Pattern.compile(
            "(?:topResumedActivity|mResumedActivity|mFocusedActivity|ResumedActivity)[=:]\\s*ActivityRecord\\{\\S+ u\\d+ ([A-Za-z0-9_.]+)/");
    private static final java.util.regex.Pattern FG_RECENT_AFFINITY_PATTERN = java.util.regex.Pattern.compile(
            "Recent #\\d+:.*?\\bA=([A-Za-z0-9_.]+)");
    private static final java.util.regex.Pattern FG_COMPONENT_PATTERN = java.util.regex.Pattern.compile(
            "(?:realActivity|cmp)=([A-Za-z0-9_.]+)/");
    private static final java.util.regex.Pattern FG_MEDIA_PACKAGE_PATTERN = java.util.regex.Pattern.compile(
            "^\\s*package=([A-Za-z0-9_.]+)\\s*$");
    private static final java.util.regex.Pattern FG_MEDIA_STATE_PATTERN = java.util.regex.Pattern.compile(
            "state=PlaybackState \\{state=(\\d+)");
    private static final java.util.regex.Pattern FG_SERVICE_PACKAGE_PATTERN = java.util.regex.Pattern.compile(
            "^\\s*packageName=([A-Za-z0-9_.]+)\\s*$");

    private Map<String, String> collectProtectedForeground(List<String> candidates) {
        long start = System.currentTimeMillis();
        String output = shellManager.runShellCommandAndGetFullOutput(FG_COMBINED_COMMAND);
        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: foreground combined output length: "
                + (output == null ? "null" : output.length()) + " (took " + (System.currentTimeMillis() - start) + "ms)");
        if (output == null) {
            return null;
        }

        Map<String, StringBuilder> sections = splitForegroundSections(output);
        Set<String> resumed = parseResumedPackages(sections.get(FG_SEC_RESUMED));
        Set<String> media = parseMediaPackages(sections.get(FG_SEC_MEDIA));
        Set<String> services = parseForegroundServicePackages(sections.get(FG_SEC_FGS));
        Set<String> recents = parseRecentPackages(sections.get(FG_SEC_RECENTS));

        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: foreground resumed=" + resumed);
        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: foreground media=" + media);
        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: foreground services=" + services);
        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: foreground recents=" + recents);

        Map<String, String> reasons = new HashMap<>();
        if (resumed.isEmpty()) {
            AppDebugManager.w(Category.AUTO_KILL_BASE,
                    "AutoKillManager: no resumed activity parsed — falling back to full dumpsys activity activities");
            String dumpOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity activities");
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: fallback dumpsys output length: "
                    + (dumpOutput == null ? "null" : dumpOutput.length()));
            if (dumpOutput == null) {
                return null;
            }
            for (String pkg : candidates) {
                if (containsPackage(dumpOutput, pkg)) {
                    reasons.put(pkg, "foreground");
                }
            }
        } else {
            for (String pkg : candidates) {
                if (resumed.contains(pkg)) {
                    reasons.put(pkg, "foreground");
                }
            }
        }
        for (String pkg : candidates) {
            if (reasons.containsKey(pkg)) continue;
            if (media.contains(pkg)) {
                reasons.put(pkg, "media playing");
            } else if (services.contains(pkg)) {
                reasons.put(pkg, "foreground service");
            } else if (recents.contains(pkg)) {
                reasons.put(pkg, "recents");
            }
        }

        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: foreground protection resolved " + reasons.size()
                + " of " + candidates.size() + " candidates (total " + (System.currentTimeMillis() - start) + "ms)");
        return reasons;
    }

    private Map<String, StringBuilder> splitForegroundSections(String output) {
        Map<String, StringBuilder> sections = new HashMap<>();
        StringBuilder current = null;
        try (BufferedReader reader = new BufferedReader(new StringReader(output))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith(FG_SECTION_PREFIX) && trimmed.endsWith(FG_SECTION_SUFFIX)) {
                    String name = trimmed.substring(FG_SECTION_PREFIX.length(), trimmed.length() - FG_SECTION_SUFFIX.length());
                    current = new StringBuilder();
                    sections.put(name, current);
                } else if (current != null) {
                    current.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
        }
        return sections;
    }

    private Set<String> parseResumedPackages(StringBuilder section) {
        Set<String> result = new HashSet<>();
        if (section == null) return result;
        java.util.regex.Matcher matcher = FG_RESUMED_PATTERN.matcher(section);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private Set<String> parseRecentPackages(StringBuilder section) {
        Set<String> result = new HashSet<>();
        if (section == null) return result;
        try (BufferedReader reader = new BufferedReader(new StringReader(section.toString()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher affinity = FG_RECENT_AFFINITY_PATTERN.matcher(line);
                if (affinity.find()) {
                    result.add(affinity.group(1));
                }
                java.util.regex.Matcher component = FG_COMPONENT_PATTERN.matcher(line);
                if (component.find()) {
                    result.add(component.group(1));
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    private Set<String> parseMediaPackages(StringBuilder section) {
        Set<String> result = new HashSet<>();
        if (section == null) return result;
        try (BufferedReader reader = new BufferedReader(new StringReader(section.toString()))) {
            String line;
            String currentPackage = null;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher pkgMatcher = FG_MEDIA_PACKAGE_PATTERN.matcher(line);
                if (pkgMatcher.matches()) {
                    currentPackage = pkgMatcher.group(1);
                    continue;
                }
                java.util.regex.Matcher stateMatcher = FG_MEDIA_STATE_PATTERN.matcher(line);
                if (currentPackage != null && stateMatcher.find()) {
                    int state = Integer.parseInt(stateMatcher.group(1));
                    if (state == 2 || state == 3 || state == 6) {
                        result.add(currentPackage);
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return result;
    }

    private Set<String> parseForegroundServicePackages(StringBuilder section) {
        Set<String> result = new HashSet<>();
        if (section == null) return result;
        try (BufferedReader reader = new BufferedReader(new StringReader(section.toString()))) {
            String line;
            String currentPackage = null;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher pkgMatcher = FG_SERVICE_PACKAGE_PATTERN.matcher(line);
                if (pkgMatcher.matches()) {
                    currentPackage = pkgMatcher.group(1);
                    continue;
                }
                if (currentPackage != null && line.contains("isForeground=true")) {
                    result.add(currentPackage);
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    private static boolean containsPackage(String output, String packageName) {
        if (output == null || packageName == null) return false;

        int idx = output.indexOf(packageName);
        if (idx == -1) {
            AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: containsPackage: NOT FOUND in dumpsys: " + packageName);
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
                AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: containsPackage: FOUND " + packageName
                        + " | context: [" + output.substring(from, to).replace("\n", "↵") + "]");
                return true;
            }
            idx = output.indexOf(packageName, idx + 1);
        }

        AppDebugManager.d(Category.AUTO_KILL_BASE, "AutoKillManager: containsPackage: found as substring but boundaries failed: " + packageName);
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

    private Map<String, Long> loadPendingRss() {
        Map<String, Long> result = new HashMap<>();
        String json = sharedpreferences.getString(KEY_AUTO_KILL_PENDING_RSS, null);
        if (json == null) return result;
        try {
            JSONObject obj = new JSONObject(json);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                result.put(pkg, obj.getLong(pkg));
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    private void savePendingRss(Map<String, Long> pendingRss) {
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, Long> entry : pendingRss.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
        } catch (JSONException ignored) {
        }
        sharedpreferences.edit().putString(KEY_AUTO_KILL_PENDING_RSS, obj.toString()).apply();
    }

    public void clearPendingRss() {
        sharedpreferences.edit().remove(KEY_AUTO_KILL_PENDING_RSS).apply();
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
