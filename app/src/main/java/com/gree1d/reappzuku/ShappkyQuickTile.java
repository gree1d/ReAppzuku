package com.gree1d.reappzuku;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShappkyQuickTile extends TileService {

    private ShellManager shellManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        TileService.requestListeningState(this, new ComponentName(this, ShappkyQuickTile.class));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_force_stop));
        tile.setLabel(getString(R.string.tile_kill_app_label));
        tile.setContentDescription(getString(R.string.tile_kill_app_subtitle));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getString(R.string.tile_kill_app_subtitle));
        }

        tile.setState(Tile.STATE_ACTIVE);
        tile.updateTile();
    }
    
    @Override
    public void onClick() {
        super.onClick();
        if (shellManager == null) {
            shellManager = new ShellManager(this, handler, executor);
        }

        executor.execute(() -> {
            if (!shellManager.resolveAnyShellPermission()) {
                handler.post(() -> {
                    shellManager.checkShellPermissions();
                    Toast.makeText(this, "Shizuku or Root permission required", Toast.LENGTH_SHORT).show();
                    updateTileState();
                });
                return;
            }

            String packageName = null;

            String dumpOutput = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
            if (dumpOutput != null && !dumpOutput.isEmpty()) {
                packageName = extractPackageFromActivityDump(dumpOutput);
            }

            if (packageName == null) {
                String windowOutput = shellManager.runShellCommandAndGetFullOutput(
                        "dumpsys window | grep mCurrentFocus");
                if (windowOutput != null && !windowOutput.isEmpty()) {
                    packageName = extractPackageFromWindowDump(windowOutput);
                }
            }

            if (packageName == null) {
                String topOutput = shellManager.runShellCommandAndGetFullOutput("cmd activity get-top-activity");
                if (topOutput != null && topOutput.contains("ActivityRecord")) {
                    int start = topOutput.indexOf("u0 ");
                    if (start != -1) {
                        String sub = topOutput.substring(start + 3);
                        int slash = sub.indexOf("/");
                        if (slash != -1) {
                            packageName = sub.substring(0, slash).trim();
                        }
                    }
                }
            }

            if (packageName != null && !packageName.equals(getPackageName()) && !packageName.equals("com.android.systemui")) {
                shellManager.runShellCommand("cmd statusbar collapse", null);

                final String killedPackage = packageName;
                String cmd = "am force-stop " + killedPackage;
                shellManager.runShellCommand(cmd, () -> {
                    logKilledPackage(killedPackage);
                    handler.post(() -> {
                        Toast.makeText(this, "Killed: " + killedPackage, Toast.LENGTH_SHORT).show();
                        updateTileState();
                    });
                }, () -> {
                    handler.post(() -> {
                        Toast.makeText(this, "Failed to kill: " + killedPackage, Toast.LENGTH_SHORT).show();
                        updateTileState();
                    });
                });
            } else {
                handler.post(() -> {
                    Toast.makeText(this, "No killable foreground app found", Toast.LENGTH_SHORT).show();
                    updateTileState();
                });
            }
        });
    }
    
    private String extractPackageFromActivityDump(String output) {
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.contains("com.android.systemui")) continue;

            String[] parts = line.trim().split("\\s+");
            for (String part : parts) {
                if (part.contains("/")) {
                    String potentialPkg = part.split("/")[0];
                    if (potentialPkg.contains(".") && Character.isLetter(potentialPkg.charAt(0))) {
                        return potentialPkg;
                    }
                }
            }
        }
        return null;
    }

    private String extractPackageFromWindowDump(String output) {
        if (output.contains("com.android.systemui")) return null;

        int slashIndex = output.indexOf("/");
        if (slashIndex > 0) {
            int start = slashIndex - 1;
            while (start > 0 && (Character.isLetterOrDigit(output.charAt(start - 1)) || output.charAt(start - 1) == '.')) {
                start--;
            }
            String potentialPkg = output.substring(start, slashIndex);
            if (potentialPkg.contains(".") && Character.isLetter(potentialPkg.charAt(0))) {
                return potentialPkg;
            }
        }
        return null;
    }

    private void logKilledPackage(String packageName) {
        executor.execute(() -> {
            try {
                com.gree1d.reappzuku.db.AppStatsDao appStatsDao =
                        com.gree1d.reappzuku.db.AppDatabase.getInstance(getApplicationContext()).appStatsDao();
                com.gree1d.reappzuku.db.AppStats stats = appStatsDao.getStats(packageName);
                String appName = resolveInstalledAppName(packageName);

                if (stats == null) {
                    stats = new com.gree1d.reappzuku.db.AppStats(packageName);
                    stats.appName = appName;
                    appStatsDao.insert(stats);
                } else if ((stats.appName == null || stats.appName.trim().isEmpty())
                        && appName != null && !appName.trim().isEmpty()) {
                    appStatsDao.updateAppName(packageName, appName);
                }

                appStatsDao.incrementKill(packageName, System.currentTimeMillis());
            } catch (Exception ignored) {
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
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
