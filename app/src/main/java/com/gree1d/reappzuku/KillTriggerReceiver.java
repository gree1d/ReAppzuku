package com.gree1d.reappzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import static com.gree1d.reappzuku.PreferenceKeys.*;

public class KillTriggerReceiver extends BroadcastReceiver {
    private static final String TAG = "KillTriggerReceiver";
    private static final String WAKELOCK_TAG = "reappzuku:AutoKillWakeLock";
    private static final long WAKELOCK_TIMEOUT_MS = 10_000L;

    // Static — чтобы ShappkyService мог освободить его после завершения kill
    static volatile PowerManager.WakeLock autoKillWakeLock;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_KILL_ON_SCREEN_OFF, false)) {
                Log.d(TAG, "Screen off: acquiring WakeLock and starting kill cycle");
                acquireAutoKillWakeLock(context);
                Intent serviceIntent = new Intent(context, ShappkyService.class);
                serviceIntent.setAction("TRIGGER_KILL");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }

            // Notify service to start idle freeze timer regardless of kill setting
            Intent freezeIntent = new Intent(context, ShappkyService.class);
            freezeIntent.setAction("SCREEN_OFF");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(freezeIntent);
            } else {
                context.startService(freezeIntent);
            }

        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            // Notify service — it will check keyguard state after a short delay
            Intent unfreezeIntent = new Intent(context, ShappkyService.class);
            unfreezeIntent.setAction("SCREEN_ON");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(unfreezeIntent);
            } else {
                context.startService(unfreezeIntent);
            }
        } else if (ShappkyService.ACTION_IDLE_FREEZE.equals(action)) {
            Log.d(TAG, "Idle freeze alarm received, forwarding to service");
            // Triggered by AlarmManager after idle threshold — forward to service
            Log.d(TAG, "Idle freeze alarm received, forwarding to service");
            Intent freezeIntent = new Intent(context, ShappkyService.class);
            freezeIntent.setAction("IDLE_FREEZE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(freezeIntent);
            } else {
                context.startService(freezeIntent);
            }
        }
    }

    static void acquireAutoKillWakeLock(Context context) {
        // Освобождаем предыдущий если вдруг остался (защита от двойного acquire)
        releaseAutoKillWakeLock();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        wl.setReferenceCounted(false);
        // Таймаут 15 сек — auto-release если kill зависнет
        wl.acquire(WAKELOCK_TIMEOUT_MS);
        autoKillWakeLock = wl;
        Log.d(TAG, "AutoKill WakeLock acquired (timeout 15s)");
    }

    static void releaseAutoKillWakeLock() {
        PowerManager.WakeLock wl = autoKillWakeLock;
        if (wl != null && wl.isHeld()) {
            wl.release();
            Log.d(TAG, "AutoKill WakeLock released");
        }
        autoKillWakeLock = null;
    }
}
