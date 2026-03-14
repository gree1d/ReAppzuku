package com.northmendo.Appzuku;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Transparent, no-UI activity invoked via the launcher long-press static shortcut.
// Resolves the previously active app and force-stops it.
public class KillShortcutActivity extends Activity {
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private ShellManager shellManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        shellManager = new ShellManager(this, handler, executor);

        if (!shellManager.hasAnyShellPermission()) {
            Toast.makeText(getApplicationContext(), "Shizuku or Root permission required", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        executor.execute(() -> {
            String targetPackage = findKillablePackage();
            if (targetPackage != null) {
                final String pkg = targetPackage;
                shellManager.runShellCommand("am force-stop " + pkg,
                        () -> {
                            logKilledPackage(pkg);
                            Toast.makeText(getApplicationContext(), "Killed: " + pkg, Toast.LENGTH_SHORT).show();
                            finish();
                        },
                        () -> {
                            Toast.makeText(getApplicationContext(), "Failed to kill: " + pkg, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                );
            } else {
                handler.post(() -> {
                    Toast.makeText(getApplicationContext(), "No killable foreground app found", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private String findKillablePackage() {
        Set<String> launcherPackages = getLauncherPackages();

        String recentsOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity recents");
        String targetPackage = findKillablePackageFromRecents(recentsOutput, launcherPackages);
        if (targetPackage != null) {
            return targetPackage;
        }

        String activitiesOutput = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ActivityRecord'");
        return findKillablePackageFromActivities(activitiesOutput, launcherPackages);
    }

    private Set<String> getLauncherPackages() {
        Set<String> launcherPackages = new HashSet<>();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homeResolvers = getPackageManager().queryIntentActivities(homeIntent, 0);
        for (ResolveInfo ri : homeResolvers) {
            if (ri.activityInfo != null) {
                launcherPackages.add(ri.activityInfo.packageName);
            }
        }
        launcherPackages.add(getPackageName());
        launcherPackages.add(SYSTEM_UI_PACKAGE);
        return launcherPackages;
    }

    private String findKillablePackageFromRecents(String output, Set<String> excludedPackages) {
        if (output == null || output.isEmpty()) {
            return null;
        }

        boolean inHomeBlock = false;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();

            if (trimmed.startsWith("* Recent #")) {
                inHomeBlock = trimmed.contains("type=home");
                continue;
            }
            if (inHomeBlock) {
                continue;
            }

            String candidate = extractRecentPackage(trimmed);
            if (isKillablePackage(candidate, excludedPackages)) {
                return candidate;
            }
        }

        return null;
    }

    private String extractRecentPackage(String line) {
        if (line.startsWith("realActivity=") || line.startsWith("origActivity=") || line.startsWith("affinity=")) {
            return normalizePackageToken(line.substring(line.indexOf('=') + 1));
        }

        int cmpIndex = line.indexOf("cmp=");
        if (cmpIndex != -1) {
            return normalizePackageToken(line.substring(cmpIndex + 4));
        }

        int activityIndex = line.indexOf("A=");
        if (activityIndex != -1) {
            return normalizePackageToken(line.substring(activityIndex + 2));
        }

        return null;
    }

    private String findKillablePackageFromActivities(String output, Set<String> excludedPackages) {
        if (output == null || output.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String line : output.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            for (String part : parts) {
                if (!part.contains("/")) {
                    continue;
                }

                String candidate = normalizePackageToken(part);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        for (String candidate : candidates) {
            if (isKillablePackage(candidate, excludedPackages)) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizePackageToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        String normalized = token.trim();
        int spaceIdx = normalized.indexOf(' ');
        if (spaceIdx != -1) {
            normalized = normalized.substring(0, spaceIdx);
        }
        int braceIdx = normalized.indexOf('}');
        if (braceIdx != -1) {
            normalized = normalized.substring(0, braceIdx);
        }
        int slashIdx = normalized.indexOf('/');
        if (slashIdx != -1) {
            normalized = normalized.substring(0, slashIdx);
        }
        while (!normalized.isEmpty() && !Character.isLetterOrDigit(normalized.charAt(normalized.length() - 1))) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.contains(".") && Character.isLetter(normalized.charAt(0))) {
            return normalized;
        }
        return null;
    }

    private boolean isKillablePackage(String packageName, Set<String> excludedPackages) {
        return packageName != null && !excludedPackages.contains(packageName);
    }

    private void logKilledPackage(String packageName) {
        executor.execute(() -> {
            try {
                com.northmendo.Appzuku.db.AppStatsDao appStatsDao =
                        com.northmendo.Appzuku.db.AppDatabase.getInstance(getApplicationContext()).appStatsDao();
                com.northmendo.Appzuku.db.AppStats stats = appStatsDao.getStats(packageName);
                String appName = resolveInstalledAppName(packageName);

                if (stats == null) {
                    stats = new com.northmendo.Appzuku.db.AppStats(packageName);
                    stats.appName = appName;
                    appStatsDao.insert(stats);
                } else if ((stats.appName == null || stats.appName.trim().isEmpty())
                        && appName != null && !appName.trim().isEmpty()) {
                    appStatsDao.updateAppName(packageName, appName);
                }

                appStatsDao.incrementKill(packageName, System.currentTimeMillis());
            } catch (Exception ignored) {
                // Keep shortcut kill flow non-blocking even if stats logging fails.
            }
        });
    }

    private String resolveInstalledAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(appInfo);
            if (label != null) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // shutdown() (not shutdownNow()) lets the logKilledPackage DB task finish gracefully.
        executor.shutdown();
    }
}