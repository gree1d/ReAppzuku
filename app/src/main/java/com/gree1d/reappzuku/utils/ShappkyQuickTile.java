package com.gree1d.reappzuku.utils;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;

import com.gree1d.reappzuku.core.AppDebugManager;
import com.gree1d.reappzuku.core.AppDebugManager.Category;
import com.gree1d.reappzuku.core.App;
import com.gree1d.reappzuku.core.ShellManager;
import com.gree1d.reappzuku.manager.AutoKillManager;
import com.gree1d.reappzuku.manager.BackgroundAppManager;
import com.gree1d.reappzuku.R;

public class ShappkyQuickTile extends TileService {

    private ShellManager shellManager;
    private AutoKillManager autoKillManager;
    private Handler handler;
    private ExecutorService executor;
    private ExecutorService shellExecutor;

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onTileAdded");
        TileService.requestListeningState(this, new ComponentName(this, ShappkyQuickTile.class));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onStartListening");
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) {
            AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: updateTileState tile is null, skipping");
            return;
        }

        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_force_stop));
        tile.setLabel(getString(R.string.tile_kill_app_label));
        tile.setContentDescription(getString(R.string.tile_kill_app_subtitle));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getString(R.string.tile_kill_app_subtitle));
        }

        tile.setState(Tile.STATE_ACTIVE);
        tile.updateTile();
        AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: updateTileState tile updated to STATE_ACTIVE");
    }

    @Override
    public void onClick() {
        super.onClick();
        AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick");
        if (shellManager == null) {
            App app = (App) getApplicationContext();
            handler = app.getSharedHandler();
            executor = app.getSharedExecutor();
            shellExecutor = app.getShellExecutor();
            shellManager = app.getShellManager();
            AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick ShellManager initialized");
        }
        if (autoKillManager == null) {
            BackgroundAppManager appManager = new BackgroundAppManager(this, handler, executor, shellExecutor, shellManager);
            autoKillManager = new AutoKillManager(this, handler, executor, shellManager, appManager.getCurrentAppsList());
            AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick AutoKillManager initialized");
        }

        shellExecutor.execute(() -> {
            if (!shellManager.resolveAnyShellPermission()) {
                AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick no shell permission available");
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
                AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick activity dump resolved pkg=" + packageName);
            }

            if (packageName == null) {
                String windowOutput = shellManager.runShellCommandAndGetFullOutput(
                        "dumpsys window | grep mCurrentFocus");
                if (windowOutput != null && !windowOutput.isEmpty()) {
                    packageName = extractPackageFromWindowDump(windowOutput);
                    AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick window dump resolved pkg=" + packageName);
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
                AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick get-top-activity resolved pkg=" + packageName);
            }

            if (packageName != null && !packageName.equals(getPackageName()) && !packageName.equals("com.android.systemui")) {
                shellManager.runShellCommand("cmd statusbar collapse", null);

                final String killedPackage = packageName;
                AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick killing foreground pkg=" + killedPackage);

                // Resolve the recents taskId for this package BEFORE killing it —
                // the recents entry stays even after force-stop, so we need the id now.
                Integer taskId = resolveTaskIdForPackage(killedPackage);

                String cmd = "am force-stop " + killedPackage;
                shellManager.runShellCommand(cmd, () -> {
                    AppDebugManager.i(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick kill success pkg=" + killedPackage);
                    executor.execute(() -> autoKillManager.recordQuickTileKill(killedPackage));

                    if (taskId != null) {
                        removeFromRecents(taskId, killedPackage);
                    } else {
                        AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick no taskId resolved, skipping recents removal for pkg=" + killedPackage);
                    }

                    handler.post(() -> {
                        Toast.makeText(this, "Killed: " + killedPackage, Toast.LENGTH_SHORT).show();
                        updateTileState();
                    });
                }, () -> {
                    AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick kill failed pkg=" + killedPackage);
                    handler.post(() -> {
                        Toast.makeText(this, "Failed to kill: " + killedPackage, Toast.LENGTH_SHORT).show();
                        updateTileState();
                    });
                });
            } else {
                AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onClick no killable foreground app found, resolved pkg=" + packageName);
                handler.post(() -> {
                    Toast.makeText(this, "No killable foreground app found", Toast.LENGTH_SHORT).show();
                    updateTileState();
                });
            }
        });
    }

    /**
     * Finds the recents taskId that currently hosts the given package,
     * by parsing "dumpsys activity recents". Must be called BEFORE
     * force-stopping the app — some devices drop the task's baseIntent
     * info once the process is gone.
     */
    private Integer resolveTaskIdForPackage(String packageName) {
        String recentsOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys activity recents");
        if (recentsOutput == null || recentsOutput.isEmpty()) {
            AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: resolveTaskIdForPackage dumpsys recents returned empty");
            return null;
        }

        Integer lastMatchedTaskId = null;
        String[] lines = recentsOutput.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();

            // MIUI/HyperOS format: "Recent #0: Task{abc123#3049 ..."
            // Standard format: "* TaskRecord{... #1234 A=com.example.app U=0 ...}"
            // We need the number after "Task{...#" (the task ID), NOT "Recent #" (the position)
            
            if (trimmed.contains("Task{") && trimmed.contains(packageName)) {
                // Find "Task{" position
                int taskStart = trimmed.indexOf("Task{");
                if (taskStart == -1) continue;
                
                // Find the # after "Task{"
                int hashIdx = trimmed.indexOf('#', taskStart);
                if (hashIdx == -1) continue;
                
                // Extract digits after #
                int idStart = hashIdx + 1;
                int idEnd = idStart;
                while (idEnd < trimmed.length() && Character.isDigit(trimmed.charAt(idEnd))) {
                    idEnd++;
                }
                if (idEnd == idStart) continue;

                try {
                    int candidateId = Integer.parseInt(trimmed.substring(idStart, idEnd));
                    lastMatchedTaskId = candidateId;
                    AppDebugManager.i(Category.SHORTCUTS_WIDGETS,
                            "ShappkyQuickTile: resolveTaskIdForPackage matched taskId=" + candidateId + " for pkg=" + packageName + " in line: " + trimmed);
                    break; // Found it, stop searching
                } catch (NumberFormatException e) {
                    AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: failed to parse taskId from: " + trimmed);
                }
            }
        }

        if (lastMatchedTaskId == null) {
            AppDebugManager.w(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: resolveTaskIdForPackage no taskId found for pkg=" + packageName);
        }
        return lastMatchedTaskId;
    }

    /**
     * Removes the given task from the Recents (overview) screen.
     * Uses "am stack remove" which is confirmed working on MIUI/HyperOS.
     */
    private void removeFromRecents(int taskId, String packageName) {
        String cmd = "am stack remove " + taskId;
        AppDebugManager.i(Category.SHORTCUTS_WIDGETS,
                "ShappkyQuickTile: removeFromRecents executing: " + cmd + " for pkg=" + packageName);
        
        shellManager.runShellCommand(cmd, () -> {
            AppDebugManager.i(Category.SHORTCUTS_WIDGETS,
                    "ShappkyQuickTile: removeFromRecents SUCCESS taskId=" + taskId + " pkg=" + packageName);
        }, () -> {
            AppDebugManager.w(Category.SHORTCUTS_WIDGETS,
                    "ShappkyQuickTile: removeFromRecents FAILED taskId=" + taskId + " pkg=" + packageName);
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppDebugManager.d(Category.SHORTCUTS_WIDGETS, "ShappkyQuickTile: onDestroy");
    }
}
