package com.northmendo.Appzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

import static com.northmendo.Appzuku.PreferenceKeys.KEY_AUTO_KILL_ENABLED;
import static com.northmendo.Appzuku.PreferenceKeys.PREFERENCES_NAME;

/**
 * Broadcast receiver for Boot Completed.
 * Restores automation state after reboot.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            boolean automationEnabled = prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false);

            if (automationEnabled) {
                Intent serviceIntent = new Intent(context, ShappkyService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
                AutoKillWorker.schedule(context);
                Log.d(TAG, "Boot restore complete: automation resumed");
            } else {
                AutoKillWorker.cancel(context);
                Log.d(TAG, "Boot restore skipped: automation disabled");
            }
        }
    }
}
