package com.gree1d.reappzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.RemoteInput;

import java.util.concurrent.Executors;

/**
 * Handles the 6-digit pairing code submitted from the notification.
 * The pairing port is discovered by {@link AdbPairingService} via mDNS
 * and stored in {@link RootHelper#discoveredPairingPort}.
 */
public class AdbPairingReceiver extends BroadcastReceiver {

    private static final String TAG = "AdbPairingReceiver";

    public static final String ACTION_PAIR   = "com.gree1d.reappzuku.ACTION_ADB_PAIR";
    public static final String KEY_CODE_INPUT = "adb_pair_code";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_PAIR.equals(intent.getAction())) return;

        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;

        CharSequence input = results.getCharSequence(KEY_CODE_INPUT);
        if (input == null || input.toString().trim().isEmpty()) return;

        String code = input.toString().trim();

        // Must be exactly 6 digits
        if (!code.matches("\\d{6}")) {
            Log.w(TAG, "Invalid code: expected 6 digits, got: " + code.length() + " chars");
            RootHelper rootHelper = RootHelper.getInstance(context);
            if (rootHelper != null) {
                rootHelper.showPairingNotification(
                        context.getString(R.string.adb_error_invalid_format));
            }
            return;
        }

        Log.d(TAG, "Received pairing code");

        RootHelper rootHelper = RootHelper.getInstance(context);
        if (rootHelper == null) {
            Log.e(TAG, "RootHelper instance is null");
            return;
        }

        rootHelper.showPairingProgressNotification();

        final String finalCode = code;
        Executors.newSingleThreadExecutor().execute(() ->
                rootHelper.pairAndConnect(finalCode));
    }
}
