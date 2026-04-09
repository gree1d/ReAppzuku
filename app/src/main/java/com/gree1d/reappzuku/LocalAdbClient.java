package com.gree1d.reappzuku;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbKeyPair;
import io.github.muntashirakon.adb.AdbPairingClient;
import io.github.muntashirakon.adb.AdbStream;

/**
 * ADB client using libadb-android for pairing (SPAKE2) and TLS connection.
 *
 * <p>Flow:
 * <ol>
 *   <li>User enables Wireless Debugging in Developer Options.</li>
 *   <li>User taps "Pair device with pairing code" — Android shows port + 6-digit code.</li>
 *   <li>{@link #pair(String, int, String)} is called with that port and code.</li>
 *   <li>{@link #connect(int)} connects to the main Wireless Debugging TLS port.</li>
 *   <li>{@link #runPsCommand()} executes ps in the ADB shell SELinux context.</li>
 * </ol>
 * </p>
 */
public class LocalAdbClient {

    private static final String TAG = "LocalAdbClient";

    private static final String KEY_PRIVATE_FILE = "adb_rsa";
    private static final String KEY_PUBLIC_FILE  = "adb_rsa.pub";

    private static final int CONNECT_TIMEOUT_SEC = 10;

    private final Context context;

    /** Active ADB connection, or null if not connected. */
    private AdbConnection connection;

    public LocalAdbClient(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Pairs with adbd using the 6-digit code shown in Wireless Debugging settings.
     *
     * @param host        typically "127.0.0.1"
     * @param pairingPort the port shown next to the code in the dialog
     * @param code        the 6-digit pairing code
     * @return true on success
     */
    public boolean pair(String host, int pairingPort, String code) {
        try {
            AdbKeyPair keyPair = getOrCreateKeyPair();
            Log.d(TAG, "Pairing with " + host + ":" + pairingPort);
            AdbPairingClient pairingClient =
                    new AdbPairingClient(host, pairingPort, code, keyPair);
            pairingClient.start();
            Log.d(TAG, "Pairing successful");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pairing failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Connects to the Wireless Debugging TLS port.
     *
     * @param tlsPort from {@code getprop service.adb.tls.port}
     * @return true on success
     */
    public boolean connect(int tlsPort) {
        try {
            disconnect();
            AdbKeyPair keyPair = getOrCreateKeyPair();
            Log.d(TAG, "Connecting to TLS port " + tlsPort);
            connection = AdbConnection.create("127.0.0.1", tlsPort, keyPair);
            connection.connect(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS, false);
            Log.d(TAG, "Connected");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
            connection = null;
            return false;
        }
    }

    /** Disconnects from adbd. */
    public void disconnect() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
    }

    /** Returns true if there is an active connection. */
    public boolean isConnected() {
        return connection != null;
    }

    /**
     * Runs {@code ps -A -o rss,name | grep '\.' | grep -v '[-:@]' | awk '{print $2}'}
     * via the ADB shell context and returns stdout.
     * Must be called from a background thread.
     *
     * @return output string, or null on failure
     */
    public String runPsCommand() {
        return runShellCommand(
                "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
    }

    /**
     * Runs an arbitrary shell command and returns stdout.
     * Must be called from a background thread.
     */
    public String runShellCommand(String command) {
        if (connection == null) {
            Log.w(TAG, "runShellCommand: not connected");
            return null;
        }
        AdbStream stream = null;
        try {
            stream = connection.open("shell:" + command);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            InputStream is = stream.openInputStream();
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Shell command failed: " + e.getMessage());
            return null;
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) {}
        }
    }

    // ── Key management ────────────────────────────────────────────────────────

    /**
     * Loads or generates the RSA key pair stored in app-private files.
     * libadb-android persists keys in two files: private key + public key.
     */
    private synchronized AdbKeyPair getOrCreateKeyPair() throws Exception {
        File privFile = new File(context.getFilesDir(), KEY_PRIVATE_FILE);
        File pubFile  = new File(context.getFilesDir(), KEY_PUBLIC_FILE);

        if (privFile.exists() && pubFile.exists()) {
            try {
                return AdbKeyPair.load(privFile, pubFile);
            } catch (Exception e) {
                Log.w(TAG, "Key load failed, regenerating: " + e.getMessage());
            }
        }

        Log.d(TAG, "Generating new ADB key pair");
        AdbKeyPair kp = AdbKeyPair.generate();
        kp.savePrivateKey(privFile);
        kp.savePublicKey(pubFile);
        return kp;
    }
}
