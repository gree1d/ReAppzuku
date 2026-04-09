package com.gree1d.reappzuku;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

/**
 * Discovers the ADB wireless pairing port via mDNS.
 *
 * Android 11+ broadcasts the pairing service as _adb-tls-pairing._tcp over mDNS.
 * We listen for it, resolve the port, then stop immediately.
 *
 * Usage:
 *   AdbMdnsDiscovery.discover(context, port -> { ... }, timeoutMs);
 */
public class AdbMdnsDiscovery {

    private static final String TAG = "AdbMdnsDiscovery";
    private static final String SERVICE_TYPE = "_adb-tls-pairing._tcp.";

    public interface PortCallback {
        /** Called on a background thread when port is found, or with -1 on timeout/failure. */
        void onResult(int port);
    }

    /**
     * Starts mDNS discovery for the ADB pairing service.
     * Calls {@code callback} once with the port number, then cleans up.
     *
     * @param context    application context
     * @param callback   receives port > 0 on success, -1 on failure/timeout
     * @param timeoutMs  how long to wait before giving up (e.g. 10000)
     */
    public static void discover(Context context, PortCallback callback, long timeoutMs) {
        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available");
            callback.onResult(-1);
            return;
        }

        // Wrapper so we can stop discovery after first result
        final boolean[] done = {false};
        final NsdManager.DiscoveryListener[] discoveryListenerRef = {null};

        NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "Resolve failed: " + errorCode);
                if (!done[0]) {
                    done[0] = true;
                    stopDiscovery(nsdManager, discoveryListenerRef[0]);
                    callback.onResult(-1);
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                int port = info.getPort();
                Log.d(TAG, "Pairing port resolved: " + port);
                if (!done[0]) {
                    done[0] = true;
                    stopDiscovery(nsdManager, discoveryListenerRef[0]);
                    callback.onResult(port);
                }
            }
        };

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCode);
                if (!done[0]) {
                    done[0] = true;
                    callback.onResult(-1);
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Discovery stop failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "mDNS discovery started for " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "mDNS discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                Log.d(TAG, "Pairing service found: " + info.getServiceName());
                if (!done[0]) {
                    nsdManager.resolveService(info, resolveListener);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                Log.d(TAG, "Pairing service lost: " + info.getServiceName());
            }
        };

        discoveryListenerRef[0] = discoveryListener;

        // Timeout watchdog
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                Log.w(TAG, "mDNS discovery timed out");
                stopDiscovery(nsdManager, discoveryListener);
                callback.onResult(-1);
            }
        }, timeoutMs);

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private static void stopDiscovery(NsdManager nsdManager,
                                      NsdManager.DiscoveryListener listener) {
        if (listener == null) return;
        try {
            nsdManager.stopServiceDiscovery(listener);
        } catch (Exception e) {
            Log.w(TAG, "stopServiceDiscovery: " + e.getMessage());
        }
    }
}
