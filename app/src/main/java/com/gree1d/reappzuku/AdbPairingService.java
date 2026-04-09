package com.gree1d.reappzuku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import static com.gree1d.reappzuku.AppConstants.*;

/**
 * Lightweight foreground service that keeps mDNS discovery alive
 * while the user is in the Wireless Debugging settings screen.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Started by {@link RootHelper#startServiceFlow} before opening WD settings.</li>
 *   <li>Runs NsdManager discovery for {@code _adb-tls-pairing._tcp}.</li>
 *   <li>On port found → stores it in {@link RootHelper}, updates notification
 *       to show the code-input field, stops itself.</li>
 *   <li>On timeout or stop → stops itself.</li>
 * </ol>
 *
 * <p>Declare in AndroidManifest.xml:</p>
 * <pre>
 * {@code
 * <service
 *     android:name=".AdbPairingService"
 *     android:foregroundServiceType="connectedDevice"
 *     android:exported="false" />
 * }
 * </pre>
 * And add permission:
 * <pre>
 * {@code <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" /> }
 * </pre>
 */
public class AdbPairingService extends Service {

    private static final String TAG = "AdbPairingService";

    public static final String ACTION_START = "com.gree1d.reappzuku.ACTION_ADB_PAIRING_START";
    public static final String ACTION_STOP  = "com.gree1d.reappzuku.ACTION_ADB_PAIRING_STOP";

    private static final String SERVICE_TYPE = "_adb-tls-pairing._tcp.";
    private static final long   TIMEOUT_MS   = 20_000;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean done = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID_ADB_SERVICE, buildSearchingNotification());
        startDiscovery();
        scheduleTimeout();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── mDNS Discovery ───────────────────────────────────────────────────────

    private void startDiscovery() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available");
            onPortResult(-1);
            return;
        }

        discoveryListener = new NsdManager.DiscoveryListener() {

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCode);
                onPortResult(-1);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Discovery stop failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "mDNS discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "mDNS discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                Log.d(TAG, "Pairing service found: " + info.getServiceName());
                if (!done) {
                    // Must resolve to get the port number
                    nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                            Log.w(TAG, "Resolve failed: " + errorCode);
                            // Try again on next onServiceFound
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo i) {
                            int port = i.getPort();
                            Log.d(TAG, "Pairing port resolved: " + port);
                            onPortResult(port);
                        }
                    });
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                Log.d(TAG, "Pairing service lost");
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void stopDiscovery() {
        if (discoveryListener != null && nsdManager != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.w(TAG, "stopServiceDiscovery: " + e.getMessage());
            }
            discoveryListener = null;
        }
    }

    // ── Result handling ───────────────────────────────────────────────────────

    private synchronized void onPortResult(int port) {
        if (done) return;
        done = true;

        RootHelper rootHelper = RootHelper.getInstance(null);
        if (rootHelper == null) {
            Log.e(TAG, "RootHelper instance is null");
            stopSelf();
            return;
        }

        if (port > 0) {
            // Port found — stay alive so notification persists while user enters code.
            // Service will be stopped by RootHelper after pairing succeeds or fails.
            Log.d(TAG, "Port found: " + port + " — waiting for code input");
            rootHelper.onPairingPortDiscovered(port);
            // DO NOT call stopSelf() here
        } else {
            Log.e(TAG, "Port not found — showing error");
            rootHelper.showPairingNotification(
                    getString(R.string.adb_error_wd_not_enabled));
            stopSelf();
        }
    }

    // ── Timeout ───────────────────────────────────────────────────────────────

    private void scheduleTimeout() {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    if (!done) {
                        Log.w(TAG, "mDNS discovery timed out after " + TIMEOUT_MS + "ms");
                        onPortResult(-1);
                    }
                }, TIMEOUT_MS);
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private Notification buildSearchingNotification() {
        ensureChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID_ADB_SERVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.adb_notif_pair_title))
                .setContentText(getString(R.string.adb_banner_btn_start_loading))
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID_ADB_SERVICE,
                        getString(R.string.adb_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    public static void start(Context context) {
        Intent intent = new Intent(context, AdbPairingService.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AdbPairingService.class)
                .setAction(ACTION_STOP);
        context.startService(intent);
    }
}
