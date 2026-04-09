package com.gree1d.reappzuku;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;

/**
 * ADB client using libadb-android.
 *
 * <p>Key pair is generated once via BouncyCastle and stored in a PKCS12
 * keystore in the app's private filesDir. The same keys are reused for
 * both pairing ({@link PairingConnectionCtx}) and TLS connection
 * ({@link AdbConnection.Builder}).</p>
 */
public class LocalAdbClient {

    private static final String TAG = "LocalAdbClient";

    private static final String KEYSTORE_FILE     = "reappzuku_adb.p12";
    private static final String KEYSTORE_ALIAS    = "reappzuku_adb";
    private static final char[] KEYSTORE_PASSWORD = "reappzuku".toCharArray();

    private static final int CONNECT_TIMEOUT_SEC = 10;

    private final Context context;

    private AdbConnection connection;

    public LocalAdbClient(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Pairs with adbd using the 6-digit code shown in Wireless Debugging settings.
     *
     * @param host        "127.0.0.1"
     * @param pairingPort port shown next to the code in the Android dialog
     * @param code        6-digit pairing code
     * @return true on success
     */
    public boolean pair(String host, int pairingPort, String code) {
        try {
            PrivateKey privateKey = loadPrivateKey();
            Certificate certificate = loadCertificate();

            Log.d(TAG, "Pairing with " + host + ":" + pairingPort);
            PairingConnectionCtx ctx = new PairingConnectionCtx(
                    host,
                    pairingPort,
                    code.getBytes("UTF-8"),
                    privateKey,
                    certificate,
                    "ReAppzuku");
            ctx.start();
            ctx.close();
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
     * @param host    "127.0.0.1"
     * @param tlsPort from {@code getprop service.adb.tls.port}
     * @return true on success
     */
    public boolean connect(String host, int tlsPort) {
        try {
            disconnect();
            PrivateKey privateKey = loadPrivateKey();
            Certificate certificate = loadCertificate();

            Log.d(TAG, "Connecting to " + host + ":" + tlsPort);
            connection = new AdbConnection.Builder(host, tlsPort)
                    .setPrivateKey(privateKey)
                    .setCertificate(certificate)
                    .setApi(Build.VERSION.SDK_INT)
                    .setDeviceName("ReAppzuku")
                    .connect(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS, true);

            Log.d(TAG, "Connected");
            return true;
        } catch (AdbPairingRequiredException e) {
            Log.e(TAG, "Pairing required before connecting");
            connection = null;
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
            connection = null;
            return false;
        }
    }

    /** Closes the active connection if any. */
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
     * Runs the ps command via ADB shell context.
     * Must be called from a background thread.
     *
     * @return stdout, or null on failure
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
            stream = connection.openStream("shell:" + command);
            StringBuilder sb = new StringBuilder();
            InputStream is = stream.openInputStream();
            byte[] buf = new byte[4096];
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

    private synchronized PrivateKey loadPrivateKey() throws Exception {
        return (PrivateKey) loadKeyStore().getKey(KEYSTORE_ALIAS, KEYSTORE_PASSWORD);
    }

    private synchronized Certificate loadCertificate() throws Exception {
        return loadKeyStore().getCertificate(KEYSTORE_ALIAS);
    }

    /**
     * Loads the PKCS12 keystore from filesDir, generating a new RSA-2048 key
     * pair + self-signed X.509 certificate if it doesn't exist yet.
     * Uses BouncyCastle — already present as a project dependency.
     */
    private synchronized KeyStore loadKeyStore() throws Exception {
        File ksFile = new File(context.getFilesDir(), KEYSTORE_FILE);
        KeyStore ks = KeyStore.getInstance("PKCS12");

        if (ksFile.exists()) {
            try (FileInputStream fis = new FileInputStream(ksFile)) {
                ks.load(fis, KEYSTORE_PASSWORD);
                if (ks.containsAlias(KEYSTORE_ALIAS)) {
                    return ks;
                }
            } catch (Exception e) {
                Log.w(TAG, "Keystore corrupted, regenerating: " + e.getMessage());
            }
        }

        // Generate RSA-2048 key pair
        Log.d(TAG, "Generating new RSA-2048 key pair");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Self-signed X.509 certificate via BouncyCastle
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=ReAppzuku");
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.ONE,
                new Date(now),
                new Date(now + 10L * 365 * 24 * 60 * 60 * 1000), // 10 years
                subject,
                kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        // Store in PKCS12
        ks.load(null, KEYSTORE_PASSWORD);
        ks.setKeyEntry(KEYSTORE_ALIAS, kp.getPrivate(), KEYSTORE_PASSWORD,
                new Certificate[]{cert});
        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            ks.store(fos, KEYSTORE_PASSWORD);
        }

        Log.d(TAG, "Key pair generated and stored");
        return ks;
    }
}
