package com.gree1d.reappzuku;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Minimal ADB-over-TCP client for local adbd (127.0.0.1:5555).
 *
 * <p>Only implements what is needed to execute a single shell command and
 * return its stdout. RSA-2048 key pair is generated once and persisted in
 * the app's private files directory; the public key is registered in
 * {@code /data/misc/adb/adb_keys} via root on first use.</p>
 *
 * <p>Used exclusively to run {@code ps -A -o rss,name ...} in the ADB
 * {@code shell} SELinux context, where the command works correctly on
 * Android 10+ with root.</p>
 */
public class LocalAdbClient {

    private static final String TAG = "LocalAdbClient";

    // ADB protocol constants
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;

    private static final int AUTH_TOKEN     = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS    = 8000;

    // Key file names (stored in context.getFilesDir())
    private static final String KEY_PRIVATE = "adb_client.key";
    private static final String KEY_PUBLIC  = "adb_client.pub";

    private final Context context;
    private final ShellManager shellManager;

    public LocalAdbClient(Context context, ShellManager shellManager) {
        this.context = context.getApplicationContext();
        this.shellManager = shellManager;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Executes {@code ps -A -o rss,name | grep '\.' | grep -v '[-:@]' | awk '{print $2}'}
     * via the local ADB shell and returns the raw stdout, or {@code null} on failure.
     *
     * <p>Must be called from a background thread.</p>
     */
    public String runPsCommand() {
        return runShellCommand(
                "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
    }

    /**
     * Ensures our RSA public key is registered in {@code /data/misc/adb/adb_keys}.
     * Must be called from a background thread (uses root via ShellManager).
     * Safe to call multiple times — checks for duplicate before appending.
     */
    public void ensureKeyRegistered() {
        try {
            String pubKeyLine = getAdbPublicKeyLine();
            if (pubKeyLine == null) return;

            // Check if already present to avoid duplicates
            String existing = shellManager.runShellCommandAndGetFullOutput(
                    "cat /data/misc/adb/adb_keys 2>/dev/null");
            if (existing != null && existing.contains(pubKeyLine.trim())) {
                Log.d(TAG, "ADB key already registered");
                return;
            }

            // Append our key — the echo adds a newline automatically
            String escaped = pubKeyLine.trim().replace("'", "'\\''");
            shellManager.runShellCommandAndGetFullOutput(
                    "echo '" + escaped + "' >> /data/misc/adb/adb_keys");
            // Restart adbd so it re-reads the keys file
            shellManager.runShellCommandAndGetFullOutput("stop adbd");
            sleep(400);
            shellManager.runShellCommandAndGetFullOutput("start adbd");
            sleep(800);
            Log.d(TAG, "ADB key registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register ADB key", e);
        }
    }

    // ── Key management ────────────────────────────────────────────────────────

    /**
     * Returns (generating if necessary) the RSA key pair from app-private storage.
     */
    private synchronized KeyPair getOrCreateKeyPair() throws Exception {
        File privFile = new File(context.getFilesDir(), KEY_PRIVATE);
        File pubFile  = new File(context.getFilesDir(), KEY_PUBLIC);

        if (privFile.exists() && pubFile.exists()) {
            byte[] privBytes = readFile(privFile);
            byte[] pubBytes  = readFile(pubFile);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            PublicKey  pub  = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
            return new KeyPair(pub, priv);
        }

        Log.d(TAG, "Generating new RSA-2048 key pair");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        writeFile(privFile, kp.getPrivate().getEncoded());
        writeFile(pubFile,  kp.getPublic().getEncoded());
        return kp;
    }

    /**
     * Returns the ADB-format public key line (base64 + hostname comment).
     * Format mirrors {@code ~/.android/adbkey.pub} used by the standard ADB client.
     */
    private String getAdbPublicKeyLine() {
        try {
            KeyPair kp = getOrCreateKeyPair();
            byte[] adbPubKey = encodeAdbPublicKey((RSAPublicKey) kp.getPublic());
            String b64 = Base64.encodeToString(adbPubKey, Base64.NO_WRAP);
            return b64 + " ReAppzuku\n";
        } catch (Exception e) {
            Log.e(TAG, "Failed to encode ADB public key", e);
            return null;
        }
    }

    /**
     * Encodes an RSA public key in the binary format expected by adbd/adb_keys.
     *
     * <p>ADB uses a custom little-endian structure:</p>
     * <pre>
     *   uint32 modulus_size (in uint32 words = 64 for RSA-2048)
     *   uint32 n0inv        (-1 / modulus[0]) mod 2^32
     *   uint32[64] n        modulus, little-endian words
     *   uint32[64] rr       Montgomery R^2 mod n, little-endian words
     *   uint32 exponent     public exponent (65537)
     * </pre>
     */
    private byte[] encodeAdbPublicKey(RSAPublicKey pub) throws Exception {
        final int KEY_LENGTH_WORDS = 64; // 2048 bits / 32 bits per word
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();

        // n0inv = -1 / n[0] mod 2^32
        BigInteger n0 = n.mod(BigInteger.TWO.pow(32));
        BigInteger n0inv = n0.modInverse(BigInteger.TWO.pow(32))
                            .negate().mod(BigInteger.TWO.pow(32));

        // rr = 2^(2*2048) mod n  (Montgomery constant R^2)
        BigInteger rr = BigInteger.TWO.pow(2 * 2048).mod(n);

        ByteBuffer buf = ByteBuffer.allocate(
                (3 + KEY_LENGTH_WORDS * 2) * 4   // header + n + rr
        ).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(KEY_LENGTH_WORDS);
        buf.putInt((int) (n0inv.longValue() & 0xFFFFFFFFL));

        // modulus — 64 little-endian uint32 words
        putBigIntLE(buf, n, KEY_LENGTH_WORDS);
        // rr — 64 little-endian uint32 words
        putBigIntLE(buf, rr, KEY_LENGTH_WORDS);

        buf.putInt(e.intValue());
        return buf.array();
    }

    /** Writes {@code num} little-endian uint32 words from {@code v} into {@code buf}. */
    private void putBigIntLE(ByteBuffer buf, BigInteger v, int numWords) {
        byte[] bytes = v.toByteArray();
        // BigInteger bytes are big-endian; convert to LE uint32 words
        byte[] padded = new byte[numWords * 4];
        // Copy right-aligned (big-endian) bytes into padded array
        int copyLen = Math.min(bytes.length, padded.length);
        int srcStart = bytes.length > padded.length ? bytes.length - padded.length : 0;
        int dstStart = padded.length - (bytes.length - srcStart);
        System.arraycopy(bytes, srcStart, padded, dstStart, copyLen);
        // Reverse to little-endian
        for (int i = 0; i < numWords * 4; i += 4) {
            buf.put(padded[numWords * 4 - i - 1]);
            buf.put(padded[numWords * 4 - i - 2]);
            buf.put(padded[numWords * 4 - i - 3]);
            buf.put(padded[numWords * 4 - i - 4]);
        }
    }

    // ── ADB protocol ──────────────────────────────────────────────────────────

    /**
     * Connects to local adbd, authenticates, runs the shell command,
     * collects stdout and returns it as a String.
     */
    private String runShellCommand(String command) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", AppConstants.ADB_WIFI_PORT),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 1. Send CNXN
            sendMessage(out, A_CNXN, A_VERSION, MAX_PAYLOAD,
                    "host::ReAppzuku".getBytes("UTF-8"));

            // 2. Authenticate
            if (!authenticate(in, out)) {
                Log.e(TAG, "ADB authentication failed");
                return null;
            }

            // 3. Open shell stream
            final int localId = 1;
            String shellCmd = "shell:" + command;
            sendMessage(out, A_OPEN, localId, 0, shellCmd.getBytes("UTF-8"));

            // 4. Wait for OKAY (remote assigns remoteId)
            int[] header = readHeader(in);
            if (header[0] != A_OKAY) {
                Log.e(TAG, "Expected OKAY after OPEN, got: " + Integer.toHexString(header[0]));
                return null;
            }
            int remoteId = header[1];

            // 5. Acknowledge OKAY
            sendMessage(out, A_OKAY, localId, remoteId, null);

            // 6. Read WRTE packets until CLSE
            StringBuilder sb = new StringBuilder();
            while (true) {
                int[] h = readHeader(in);
                int cmd = h[0];
                int dataLen = h[3];

                if (cmd == A_CLSE) break;

                byte[] data = new byte[dataLen];
                if (dataLen > 0) in.readFully(data);

                if (cmd == A_WRTE) {
                    sb.append(new String(data, "UTF-8"));
                    // Acknowledge each WRTE
                    sendMessage(out, A_OKAY, localId, remoteId, null);
                } else if (cmd == A_OKAY) {
                    // flow control — ignore
                }
            }

            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "ADB shell command failed", e);
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Handles the ADB AUTH handshake.
     * adbd sends AUTH(TOKEN), we sign it and reply AUTH(SIGNATURE).
     * If adbd doesn't recognise our key it sends another AUTH(TOKEN);
     * we then send our public key as AUTH(RSAPUBLICKEY).
     */
    private boolean authenticate(DataInputStream in, OutputStream out) throws Exception {
        KeyPair kp = getOrCreateKeyPair();

        int[] h = readHeader(in);
        if (h[0] != A_AUTH || h[1] != AUTH_TOKEN) {
            Log.e(TAG, "Expected AUTH TOKEN, got cmd=" + Integer.toHexString(h[0]));
            return false;
        }
        byte[] token = new byte[h[3]];
        if (h[3] > 0) in.readFully(token);

        // Sign the token with our private key (SHA-1withRSA as ADB expects)
        java.security.Signature sig = java.security.Signature.getInstance("SHA1withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(token);
        byte[] signature = sig.sign();

        sendMessage(out, A_AUTH, AUTH_SIGNATURE, 0, signature);

        // adbd either responds CNXN (success) or another AUTH TOKEN (key unknown)
        int[] h2 = readHeader(in);
        if (h2[0] == A_CNXN) {
            // Drain the CNXN data
            if (h2[3] > 0) {
                byte[] dummy = new byte[h2[3]];
                in.readFully(dummy);
            }
            return true;
        }

        if (h2[0] == A_AUTH && h2[1] == AUTH_TOKEN) {
            // Key not recognised — drain second token and send our public key
            byte[] token2 = new byte[h2[3]];
            if (h2[3] > 0) in.readFully(token2);

            String pubKeyLine = getAdbPublicKeyLine();
            if (pubKeyLine == null) return false;

            sendMessage(out, A_AUTH, AUTH_RSAPUBLICKEY, 0,
                    pubKeyLine.getBytes("UTF-8"));

            // adbd must now accept (it's localhost + we already added to adb_keys)
            int[] h3 = readHeader(in);
            if (h3[0] == A_CNXN) {
                if (h3[3] > 0) {
                    byte[] dummy = new byte[h3[3]];
                    in.readFully(dummy);
                }
                return true;
            }
        }

        return false;
    }

    // ── Wire format helpers ───────────────────────────────────────────────────

    /**
     * Sends an ADB message.
     * Format: [cmd, arg0, arg1, data_len, data_crc32, ~cmd] + data
     */
    private void sendMessage(OutputStream out, int cmd, int arg0, int arg1, byte[] data)
            throws IOException {
        byte[] payload = data != null ? data : new byte[0];
        int crc = crc32(payload);

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

    /**
     * Reads a 6-int (24-byte) ADB message header.
     * Returns int[]: [cmd, arg0, arg1, data_len, data_crc, magic]
     */
    private int[] readHeader(DataInputStream in) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        in.readFully(buf.array());
        return new int[]{
                buf.getInt(), buf.getInt(), buf.getInt(),
                buf.getInt(), buf.getInt(), buf.getInt()
        };
    }

    private static int crc32(byte[] data) {
        if (data == null || data.length == 0) return 0;
        int crc = 0;
        for (byte b : data) crc += (b & 0xFF);
        return crc;
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            return buf;
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
