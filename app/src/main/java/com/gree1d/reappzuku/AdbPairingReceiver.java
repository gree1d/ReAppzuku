package com.gree1d.reappzuku;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.RemoteInput;

import java.util.concurrent.Executors;

/**
 * BroadcastReceiver that handles the pairing code submission from the
 * Wireless Debugging notification.
 *
 * <p>Expected input format: {@code PORT:CODE}, e.g. {@code 37849:123456}.</p>
 *
 * <p>Register in AndroidManifest.xml:
 * <pre>
 * {@code
 * <receiver
 *     android:name=".AdbPairingReceiver"
 *     android:exported="false" />
 * }
 * </pre>
 * </p>
 */
public class AdbPairingReceiver extends BroadcastReceiver {

    private static final String TAG = "AdbPairingReceiver";

    /** Action used for the notification reply intent. */
    public static final String ACTION_PAIR = "com.gree1d.reappzuku.ACTION_ADB_PAIR";

    /** RemoteInput result key. */
    public static final String KEY_CODE_INPUT = "adb_pair_code";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_PAIR.equals(intent.getAction())) return;

        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;

        CharSequence input = results.getCharSequence(KEY_CODE_INPUT);
        if (input == null || input.toString().trim().isEmpty()) return;

        String raw = input.toString().trim();
        Log.d(TAG, "Received pairing input: " + raw);

        // Update notification to show "Подключение…" state immediately
        RootHelper rootHelper = RootHelper.getInstance(context);
        if (rootHelper != null) {
            rootHelper.showPairingProgressNotification();
        }

        // Parse "port:code"
        String[] parts = raw.split(":");
        if (parts.length != 2) {
            Log.w(TAG, "Invalid format, expected PORT:CODE");
            if (rootHelper != null) {
                rootHelper.showPairingNotification(
                        context.getString(R.string.adb_error_invalid_format));
            }
            return;
        }

        int pairingPort;
        String code;
        try {
            pairingPort = Integer.parseInt(parts[0].trim());
            code = parts[1].trim();
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid port number");
            if (rootHelper != null) {
                rootHelper.showPairingNotification(
                        context.getString(R.string.adb_error_invalid_format));
            }
            return;
        }

        // Run pairing + connect off main thread
        final int finalPort = pairingPort;
        final String finalCode = code;
        Executors.newSingleThreadExecutor().execute(() -> {
            if (rootHelper == null) return;
            rootHelper.pairAndConnect("127.0.0.1", finalPort, finalCode);
        });
    }
}
