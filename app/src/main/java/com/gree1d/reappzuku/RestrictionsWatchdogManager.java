package com.gree1d.reappzuku;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.util.Set;

public class RestrictionsWatchdogManager {

    private static final String TAG = "RestrictionsWatchdog";
    private static final long WATCHDOG_INTERVAL_MS = 35 * 60 * 1000L; // 35 minutes

    private final Context context;
    private final Handler handler;
    private final BackgroundAppManager appManager;
    private final ShellManager shellManager;
    private final RestrictionsScheduler scheduler;

    private boolean running = false;

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            runCheck();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    public RestrictionsWatchdogManager(Context context, Handler handler,
            BackgroundAppManager appManager, ShellManager shellManager,
            RestrictionsScheduler scheduler) {
        this.context      = context;
        this.handler      = handler;
        this.appManager   = appManager;
        this.shellManager = shellManager;
        this.scheduler    = scheduler;
    }

    public void startIfNeeded() {
        if (running) return;
        if (!appManager.supportsBackgroundRestriction()) return;
        if (!shellManager.hasAnyShellPermission()) return;
        if (appManager.getBackgroundRestrictedApps().isEmpty()) {
            Log.d(TAG, "No restricted apps, watchdog not started");
            return;
        }
        running = true;
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
        Log.d(TAG, "Watchdog started, interval=" + (WATCHDOG_INTERVAL_MS / 60000) + " min");
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(watchdogRunnable);
        Log.d(TAG, "Watchdog stopped");
    }


    private void runCheck() {
        if (!appManager.supportsBackgroundRestriction()
                || !shellManager.hasAnyShellPermission()) {
            return;
        }

        Set<String> desired = appManager.sanitizeBackgroundRestrictionTargets(
                appManager.getBackgroundRestrictedApps());

        if (desired.isEmpty()) {
            stop();
            Log.d(TAG, "Watchdog stopped — no more restricted apps");
            return;
        }

        appManager.checkAndRepairRestrictions(desired, scheduler);
    }
}
