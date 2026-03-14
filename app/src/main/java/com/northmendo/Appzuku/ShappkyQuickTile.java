package com.northmendo.Appzuku;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Quick Settings tile to kill the current foreground application
public class ShappkyQuickTile extends TileService {

    private ShellManager shellManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        // Request listening state to ensure we can update the tile
        TileService.requestListeningState(this, new ComponentName(this, ShappkyQuickTile.class));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_force_stop));
        tile.setLabel(getString(R.string.tile_kill_app_label));
        tile.setContentDescription(getString(R.string.tile_kill_app_subtitle));

        // Set subtitle on Android 10+ (API 29+)
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

        if (!shellManager.hasAnyShellPermission()) {
            Toast.makeText(this, "Shizuku or Root permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            String packageName = ForegroundAppResolver.resolveKillableForegroundPackage(shellManager, getPackageName());
            if (packageName != null) {
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
                // Keep tile kill flow non-blocking even if stats logging fails.
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