package com.gree1d.reappzuku;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * ADB client supporting both classic TCP and TLS (Wireless Debugging, Android 11+).
 *
 * <p>Key management uses Android KeyStore — a self-signed X.509 certificate is
 * generated automatically alongside the RSA-2048 key. The public key is registered
 * in {@code /data/misc/adb/adb_keys} via root, bypassing the pairing flow entirely.
 * adbd verifies TLS connections by extracting the RSA public key from the client
 * certificate and checking it against {@code adb_keys}.</p>
 */
public class LocalAdbClient {

    private static final String TAG = "LocalAdbClient";

    private static final String KEYSTORE_ALIAS = "reappzuku_adb";

    // ADB protocol constants
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;

    private static final int AUTH_TOKEN        = 1;
    private static final int AUTH_SIGNATURE    = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS    = 10000;

    private final Context context;
    private final ShellManager shellManager;

    /** Port of the active Wireless Debugging TLS endpoint (0 = not set). */
    private volatile int tlsPort = 0;

    public LocalAdbClient(Context context, ShellManager shellManager) {
        this.context = context.getApplicationContext();
        this.shellManager = shellManager;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setTlsPort(int port) { this.tlsPort = port; }
    public int getTlsPort() { return tlsPort; }

    /**
     * Runs the ps command via TLS if a port is set, classic TCP otherwise.
     * Must be called from a background thread.
     */
    public String runPsCommand() {
        final String cmd =
                "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'";
        if (tlsPort > 0) {
            Log.d(TAG, "runPsCommand via TLS port " + tlsPort);
            return runShellCommandTls(tlsPort, cmd);
        }
        Log.d(TAG, "runPsCommand via classic TCP port " + AppConstants.ADB_WIFI_PORT);
        return runShellCommandClassic("127.0.0.1", AppConstants.ADB_WIFI_PORT, cmd);
    }

    /**
     * Tests whether a TLS connection to the given port succeeds.
     * Must be called from a background thread.
     */
    public boolean testTlsConnection(int port) {
        try {
            SSLContext ctx = createSslContext();
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
            s.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.startHandshake();
            s.close();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "testTlsConnection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Writes our RSA public key to {@code /data/misc/adb/adb_keys} via root.
     * Safe to call multiple times — skips if already present.
     * Must be called from a background thread.
     */
    public void ensureKeyRegistered() {
        try {
            String pubKeyLine = getAdbPublicKeyLine();
            if (pubKeyLine == null) return;

            String existing = shellManager.runShellCommandAndGetFullOutput(
                    "cat /data/misc/adb/adb_keys 2>/dev/null");
            if (existing != null && existing.contains(pubKeyLine.trim())) {
                Log.d(TAG, "ADB key already in adb_keys");
                return;
            }

            String escaped = pubKeyLine.trim().replace("'", "'\\''");
            shellManager.runShellCommandAndGetFullOutput(
                    "echo '" + escaped + "' >> /data/misc/adb/adb_keys");
            Log.d(TAG, "ADB key written to adb_keys");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register ADB key", e);
        }
    }

    // ── Key management (Android KeyStore) ────────────────────────────────────

    private void ensureKeyGenerated() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) return;

        Log.d(TAG, "Generating RSA-2048 key in AndroidKeyStore");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        kpg.initialize(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(new X500Principal("CN=ReAppzuku"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(new Date(0))
                .setCertificateNotAfter(new Date(Long.MAX_VALUE / 2))
                .build());
        kpg.generateKeyPair();
    }

    private String getAdbPublicKeyLine() throws Exception {
        ensureKeyGenerated();
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        RSAPublicKey pub = (RSAPublicKey)
                ks.getCertificate(KEYSTORE_ALIAS).getPublicKey();
        byte[] encoded = encodeAdbPublicKey(pub);
        return Base64.encodeToString(encoded, Base64.NO_WRAP) + " ReAppzuku\n";
    }

    /** Encodes RSA public key in ADB's little-endian Montgomery binary format. */
    private byte[] encodeAdbPublicKey(RSAPublicKey pub) {
        final int WORDS = 64; // 2048 / 32
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();

        BigInteger n0 = n.mod(BigInteger.TWO.pow(32));
        BigInteger n0inv = n0.modInverse(BigInteger.TWO.pow(32))
                .negate().mod(BigInteger.TWO.pow(32));
        BigInteger rr = BigInteger.TWO.pow(2 * 2048).mod(n);

        ByteBuffer buf = ByteBuffer.allocate((3 + WORDS * 2) * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(WORDS);
        buf.putInt((int) (n0inv.longValue() & 0xFFFFFFFFL));
        putBigIntLE(buf, n, WORDS);
        putBigIntLE(buf, rr, WORDS);
        buf.putInt(e.intValue());
        return buf.array();
    }

    private void putBigIntLE(ByteBuffer buf, BigInteger v, int numWords) {
        byte[] bytes = v.toByteArray();
        byte[] padded = new byte[numWords * 4];
        int srcStart = bytes.length > padded.length ? bytes.length - padded.length : 0;
        int dstStart = padded.length - (bytes.length - srcStart);
        int copyLen = Math.min(bytes.length, padded.length);
        System.arraycopy(bytes, srcStart, padded, dstStart, copyLen);
        for (int i = 0; i < numWords * 4; i += 4) {
            buf.put(padded[numWords * 4 - i - 1]);
            buf.put(padded[numWords * 4 - i - 2]);
            buf.put(padded[numWords * 4 - i - 3]);
            buf.put(padded[numWords * 4 - i - 4]);
        }
    }

    // ── TLS connection (Android 11+ Wireless Debugging) ───────────────────────

    private SSLContext createSslContext() throws Exception {
        ensureKeyGenerated();
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("X509");
        kmf.init(ks, null);

        // Trust all — adbd trusts us based on adb_keys, not cert chain
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trustAll, null);
        return ctx;
    }

    private String runShellCommandTls(int port, String command) {
        SSLSocket socket = null;
        try {
            SSLContext ctx = createSslContext();
            socket = (SSLSocket) ctx.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.startHandshake();

            return runAdbProtocol(
                    new DataInputStream(socket.getInputStream()),
                    socket.getOutputStream(),
                    command);
        } catch (Exception e) {
            Log.e(TAG, "TLS command failed: " + e.getMessage());
            return null;
        } finally {
            if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── Classic TCP connection (fallback) ─────────────────────────────────────

    private String runShellCommandClassic(String host, int port, String command) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            return runAdbProtocol(
                    new DataInputStream(socket.getInputStream()),
                    socket.getOutputStream(),
                    command);
        } catch (Exception e) {
            Log.e(TAG, "Classic ADB command failed: " + e.getMessage());
            return null;
        } finally {
            if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── ADB protocol ──────────────────────────────────────────────────────────

    /**
     * Full ADB handshake + shell command over an already-connected stream.
     * Handles "adbd sends CNXN first" (trusted key, TLS) and
     * "adbd sends AUTH TOKEN" (classic auth challenge) flows.
     */
    private String runAdbProtocol(DataInputStream in, OutputStream out, String command)
            throws Exception {
        sendMessage(out, A_CNXN, A_VERSION, MAX_PAYLOAD,
                "host::ReAppzuku".getBytes("UTF-8"));

        int[] h = readHeader(in);

        if (h[0] == A_AUTH && h[1] == AUTH_TOKEN) {
            byte[] token = readData(in, h[3]);
            if (!respondToAuthChallenge(in, out, token)) {
                Log.e(TAG, "AUTH challenge failed");
                return null;
            }
        } else if (h[0] == A_CNXN) {
            readData(in, h[3]); // drain CNXN payload
        } else {
            Log.e(TAG, "Unexpected message: " + Integer.toHexString(h[0]));
            return null;
        }

        return executeShell(in, out, command);
    }

    private boolean respondToAuthChallenge(DataInputStream in, OutputStream out,
            byte[] token) throws Exception {
        ensureKeyGenerated();
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        PrivateKey priv = (PrivateKey) ks.getKey(KEYSTORE_ALIAS, null);

        java.security.Signature sig =
                java.security.Signature.getInstance("SHA1withRSA");
        sig.initSign(priv);
        sig.update(token);
        sendMessage(out, A_AUTH, AUTH_SIGNATURE, 0, sig.sign());

        int[] h2 = readHeader(in);
        if (h2[0] == A_CNXN) {
            readData(in, h2[3]);
            return true;
        }
        if (h2[0] == A_AUTH && h2[1] == AUTH_TOKEN) {
            readData(in, h2[3]);
            String pubKeyLine = getAdbPublicKeyLine();
            if (pubKeyLine == null) return false;
            sendMessage(out, A_AUTH, AUTH_RSAPUBLICKEY, 0,
                    pubKeyLine.getBytes("UTF-8"));
            int[] h3 = readHeader(in);
            if (h3[0] == A_CNXN) {
                readData(in, h3[3]);
                return true;
            }
        }
        return false;
    }

    private String executeShell(DataInputStream in, OutputStream out, String command)
            throws Exception {
        final int localId = 1;
        sendMessage(out, A_OPEN, localId, 0,
                ("shell:" + command).getBytes("UTF-8"));

        int[] h = readHeader(in);
        if (h[0] != A_OKAY) {
            Log.e(TAG, "Expected OKAY after OPEN, got: " + Integer.toHexString(h[0]));
            return null;
        }
        int remoteId = h[1];
        sendMessage(out, A_OKAY, localId, remoteId, null);

        StringBuilder sb = new StringBuilder();
        while (true) {
            int[] hh = readHeader(in);
            if (hh[0] == A_CLSE) break;
            byte[] data = readData(in, hh[3]);
            if (hh[0] == A_WRTE) {
                sb.append(new String(data, "UTF-8"));
                sendMessage(out, A_OKAY, localId, remoteId, null);
            }
        }
        return sb.toString();
    }

    // ── Wire helpers ──────────────────────────────────────────────────────────

    private void sendMessage(OutputStream out, int cmd, int arg0, int arg1, byte[] data)
            throws IOException {
        byte[] payload = data != null ? data : new byte[0];
        int crc = 0;
        for (byte b : payload) crc += (b & 0xFF);

        ByteBuffer buf = ByteBuffer.allocate(24 + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(payload.length);
        buf.putInt(crc);
        buf.putInt(cmd ^ 0xFFFFFFFF);
        buf.put(payload);
        out.write(buf.array());
        out.flush();
    }

    private int[] readHeader(DataInputStream in) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        in.readFully(buf.array());
        return new int[]{
                buf.getInt(), buf.getInt(), buf.getInt(),
                buf.getInt(), buf.getInt(), buf.getInt()
        };
    }

    private byte[] readData(DataInputStream in, int len) throws IOException {
        if (len <= 0) return new byte[0];
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }
}
