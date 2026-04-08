package com.gree1d.reappzuku;

import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Quick Settings tile to run the configured whitelist/blacklist background kill
public class ShappkyBackgroundKillTile extends TileService {

    private ShellManager shellManager;
    private BackgroundAppManager backgroundAppManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        TileService.requestListeningState(this, new ComponentName(this, ShappkyBackgroundKillTile.class));
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
        tile.setLabel(getString(R.string.tile_background_kill_label));
        tile.setContentDescription(getString(R.string.tile_background_kill_subtitle));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getString(R.string.tile_background_kill_subtitle));
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
        if (backgroundAppManager == null) {
            backgroundAppManager = new BackgroundAppManager(this, handler, executor, shellManager, null);
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

            backgroundAppManager.performAutoKill(() -> {
                Toast.makeText(this, "Configured background kill finished", Toast.LENGTH_SHORT).show();
                updateTileState();
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
