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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Transparent, no-UI activity invoked via the launcher long-press static shortcut.
// Finds the most-recent non-launcher, non-self task from dumpsys activity recents and
// force-stops it.
public class KillShortcutActivity extends Activity {

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
            String recentsOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity recents");
            String targetPackage = null;

            if (recentsOutput != null && !recentsOutput.isEmpty()) {
                targetPackage = findKillablePackageFromRecents(recentsOutput);
            }

            if (targetPackage != null) {
                final String pkg = targetPackage;
                // onSuccess/onFailure are delivered on the main thread by ShellManager.
                // finish() is called inside each branch so it only runs after the kill completes,
                // preventing shutdownNow() from racing with the am force-stop command.
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

    /**
     * Parses `dumpsys activity recents` output to find the first task that is:
     * - not a home/launcher task (type=home)
     * - not our own package
     * - not a known launcher package
     */
    private String findKillablePackageFromRecents(String output) {
        Set<String> launcherPackages = new HashSet<>();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homeResolvers = getPackageManager().queryIntentActivities(homeIntent, 0);
        for (ResolveInfo ri : homeResolvers) {
            if (ri.activityInfo != null) {
                launcherPackages.add(ri.activityInfo.packageName);
            }
        }

        String myPackage = getPackageName();
        boolean inHomeBlock = false;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();

            // Each task block starts with "* Recent #N:"
            if (trimmed.startsWith("* Recent #")) {
                inHomeBlock = trimmed.contains("type=home");
                continue;
            }

            if (!trimmed.startsWith("affinity=")) continue;

            String affinity = trimmed.substring("affinity=".length()).trim();
            // Strip any trailing tokens after a space
            int spaceIdx = affinity.indexOf(' ');
            if (spaceIdx != -1) affinity = affinity.substring(0, spaceIdx);

            if (inHomeBlock) continue;
            if (affinity.equals(myPackage)) continue;
            if (launcherPackages.contains(affinity)) continue;
            if (!affinity.contains(".")) continue;

            return affinity;
        }

        return null;
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
            if (label != null) return label.toString();
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
