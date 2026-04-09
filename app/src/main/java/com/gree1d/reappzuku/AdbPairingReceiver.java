package com.gree1d.reappzuku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.RemoteInput;

import java.util.concurrent.Executors;

/**
 * BroadcastReceiver that handles the pairing code submission from the
 * Wireless Debugging notification.
 *
 * <p>Expected input: 6-digit code shown in Android Wireless Debugging dialog.
 * The pairing port is discovered automatically via mDNS in {@link RootHelper#startServiceFlow}.</p>
 */
public class AdbPairingReceiver extends BroadcastReceiver {

    private static final String TAG = "AdbPairingReceiver";

    public static final String ACTION_PAIR = "com.gree1d.reappzuku.ACTION_ADB_PAIR";
    public static final String KEY_CODE_INPUT = "adb_pair_code";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_PAIR.equals(intent.getAction())) return;

        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;

        CharSequence input = results.getCharSequence(KEY_CODE_INPUT);
        if (input == null || input.toString().trim().isEmpty()) return;

        String code = input.toString().trim();

        // Validate: must be exactly 6 digits
        if (!code.matches("\\d{6}")) {
            Log.w(TAG, "Invalid code format: expected 6 digits");
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

        // Show "Подключение…" spinner immediately
        rootHelper.showPairingProgressNotification();

        // Run pairing + connect off main thread
        Executors.newSingleThreadExecutor().execute(() ->
                rootHelper.pairAndConnect(code));
    }
}
