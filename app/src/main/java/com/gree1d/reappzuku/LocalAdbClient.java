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

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

public class LocalAdbClient extends AbsAdbConnectionManager {

    private static final String TAG = "LocalAdbClient";

    private static final String KEYSTORE_FILE     = "reappzuku_adb.p12";
    private static final String KEYSTORE_ALIAS    = "reappzuku_adb";
    private static final char[] KEYSTORE_PASSWORD = "reappzuku".toCharArray();

    private static volatile LocalAdbClient sInstance;

    private final Context context;
    private PrivateKey  mPrivateKey;
    private Certificate mCertificate;

    public static LocalAdbClient getInstance(Context context) throws Exception {
        if (sInstance == null) {
            synchronized (LocalAdbClient.class) {
                if (sInstance == null) {
                    sInstance = new LocalAdbClient(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private LocalAdbClient(Context context) throws Exception {
        this.context = context;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Lcom/android/org/conscrypt/",
                    "Landroid/net/ssl/"
            );
        }

        setApi(Build.VERSION.SDK_INT);
        loadOrGenerateKeyPair();
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @Override
    protected String getDeviceName() {
        return "ReAppzuku";
    }

    public boolean pair(String host, int pairingPort, String code) {
        try {
            Log.d(TAG, "Pairing " + host + ":" + pairingPort);
            super.pair(host, pairingPort, code);
            Log.d(TAG, "Pairing successful");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pairing failed: " + e.getMessage(), e);
            return false;
        }
    }

    private volatile int lastConnectedPort = 0;
    private volatile String lastConnectedHost = "127.0.0.1";

    public boolean connectTls(String host, int tlsPort) {
        try {
            disconnect();
            Log.d(TAG, "Connecting TLS " + host + ":" + tlsPort);
            setTimeout(10, TimeUnit.SECONDS);
            super.connect(host, tlsPort);
            if (!waitForHandshake(5000)) {
                Log.e(TAG, "TLS handshake timed out");
                return false;
            }
            lastConnectedHost = host;
            lastConnectedPort = tlsPort;
            Log.d(TAG, "Connected TLS");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "TLS connect failed: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean connect(String host, int port) {
        return connectTls(host, port);
    }

    private boolean waitForHandshake(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                AdbStream stream = openStream("shell:echo ok");
                InputStream is = stream.openInputStream();
                byte[] buf = new byte[16];
                int n = is.read(buf);
                stream.close();
                if (n > 0) {
                    Log.d(TAG, "waitForHandshake: success");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "waitForHandshake: " + e.getMessage());
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        Log.e(TAG, "waitForHandshake: timed out — shell:echo ok never succeeded");
        return false;
    }

    public String runShellCommand(String command) {
        String result = runShellCommandOnce(command);
        if (result != null) return result;

        if (lastConnectedPort > 0) {
            Log.w(TAG, "Shell failed — attempting reconnect on " + lastConnectedHost + ":" + lastConnectedPort);
            boolean reconnected = connect(lastConnectedHost, lastConnectedPort);
            if (reconnected) {
                Log.d(TAG, "Reconnected — retrying command");
                return runShellCommandOnce(command);
            }
        }
        return null;
    }

    private String runShellCommandOnce(String command) {
        AdbStream stream = null;
        try {
            stream = openStream("shell:" + command);
            StringBuilder sb = new StringBuilder();
            InputStream is = stream.openInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Shell command failed: " + e.getMessage(), e);
            return null;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) {}
            }
        }
    }

    public String runPsCommand() {
        return runShellCommand(
                "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
    }

    private synchronized void loadOrGenerateKeyPair() throws Exception {
        File ksFile = new File(context.getFilesDir(), KEYSTORE_FILE);
        KeyStore ks = KeyStore.getInstance("PKCS12");

        if (ksFile.exists()) {
            try (FileInputStream fis = new FileInputStream(ksFile)) {
                ks.load(fis, KEYSTORE_PASSWORD);
                if (ks.containsAlias(KEYSTORE_ALIAS)) {
                    mPrivateKey  = (PrivateKey) ks.getKey(KEYSTORE_ALIAS, KEYSTORE_PASSWORD);
                    mCertificate = ks.getCertificate(KEYSTORE_ALIAS);
                    Log.d(TAG, "Key pair loaded from keystore");
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Keystore corrupted, regenerating: " + e.getMessage());
            }
        }

        Log.d(TAG, "Generating new RSA-2048 key pair");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=ReAppzuku");
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.ONE,
                new Date(now),
                new Date(now + 10L * 365 * 24 * 60 * 60 * 1000),
                subject,
                kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        ks.load(null, KEYSTORE_PASSWORD);
        ks.setKeyEntry(KEYSTORE_ALIAS, kp.getPrivate(), KEYSTORE_PASSWORD,
                new Certificate[]{cert});
        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            ks.store(fos, KEYSTORE_PASSWORD);
        }

        mPrivateKey  = kp.getPrivate();
        mCertificate = cert;
        Log.d(TAG, "Key pair generated and stored");
    }
}
