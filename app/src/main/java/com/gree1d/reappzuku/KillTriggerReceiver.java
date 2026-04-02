package com.gree1d.reappzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import static com.gree1d.reappzuku.PreferenceKeys.*;

public class KillTriggerReceiver extends BroadcastReceiver {
    private static final String TAG = "KillTriggerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_KILL_ON_SCREEN_OFF, false)) {
                Log.d(TAG, "Screen off detected, starting kill cycle");
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
}
