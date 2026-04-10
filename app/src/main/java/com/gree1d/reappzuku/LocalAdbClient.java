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

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * ADB client using libadb-android (v3.x).
 *
 * <p>Правильный паттерн: наследоваться от {@link AbsAdbConnectionManager},
 * реализовав getPrivateKey() и getCertificate(). Библиотека сама управляет
 * жизненным циклом соединения — pair/connect/openStream.</p>
 *
 * <p>Ключевая пара генерируется один раз через BouncyCastle и хранится в
 * PKCS12-кейсторе в filesDir приложения.</p>
 */
public class LocalAdbClient extends AbsAdbConnectionManager {

    private static final String TAG = "LocalAdbClient";

    private static final String KEYSTORE_FILE     = "reappzuku_adb.p12";
    private static final String KEYSTORE_ALIAS    = "reappzuku_adb";
    private static final char[] KEYSTORE_PASSWORD = "reappzuku".toCharArray();

    private static volatile LocalAdbClient sInstance;

    private final Context context;
    private PrivateKey  mPrivateKey;
    private Certificate mCertificate;

    // ── Singleton ─────────────────────────────────────────────────────────────

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

    // ── Constructor ───────────────────────────────────────────────────────────

    private LocalAdbClient(Context context) throws Exception {
        this.context = context;

        // libadb-android использует Conscrypt через reflection.
        // На Android 9+ нужно разрешить доступ к скрытому API.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Lcom/android/org/conscrypt/",
                    "Landroid/net/ssl/"
            );
        }

        // Указываем версию API устройства (нужно для ADB handshake)
        setApi(Build.VERSION.SDK_INT);

        // Загружаем (или генерируем) ключевую пару
        loadOrGenerateKeyPair();
    }

    // ── AbsAdbConnectionManager — обязательные методы ─────────────────────────

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

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Паринг с adbd по 6-значному коду из настроек Wireless Debugging.
     *
     * @param host        "127.0.0.1"
     * @param pairingPort порт, показанный рядом с кодом в диалоге Android
     * @param code        6-значный код сопряжения
     * @return true при успехе
     */
    public boolean pair(String host, int pairingPort, String code) {
        try {
            Log.d(TAG, "Pairing " + host + ":" + pairingPort);
            // Метод из AbsAdbConnectionManager — он создаёт PairingConnectionCtx внутри
            super.pair(host, pairingPort, code);
            Log.d(TAG, "Pairing successful");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pairing failed: " + e.getMessage(), e);
            return false;
        }
    }

    /** Порт последнего успешного подключения — для авто-реконнекта. */
    private volatile int lastConnectedPort = 0;
    private volatile String lastConnectedHost = "127.0.0.1";

    /**
     * Подключение к TLS-порту Wireless Debugging (Wireless Debugging, случайный порт).
     * Использует двухаргументный connect(host, port) — TLS-путь.
     *
     * @param host    "127.0.0.1"
     * @param tlsPort из {@code getprop service.adb.tls.port}
     * @return true при успехе
     */
    public boolean connectTls(String host, int tlsPort) {
        try {
            disconnect();
            Log.d(TAG, "Connecting TLS " + host + ":" + tlsPort);
            // Двухаргументный connect — использует TLS (Wireless Debugging протокол)
            super.connect(host, tlsPort);
            lastConnectedHost = host;
            lastConnectedPort = tlsPort;
            Log.d(TAG, "Connected TLS");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "TLS connect failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Подключение к legacy TCP-порту 5555 (plain RSA AUTH, без TLS).
     * Используется после tcpip:5555.
     * Как делает PMX: setTimeout + connect(port) без host.
     *
     * @param host "127.0.0.1"
     * @param port 5555
     * @return true при успехе
     */
    public boolean connectLegacyTcp(String host, int port) {
        try {
            disconnect();
            Log.d(TAG, "Connecting legacy TCP " + host + ":" + port);
            // Одноаргументный connect(port) — legacy TCP путь без TLS
            // Сначала устанавливаем хост через setHostAddress
            setHostAddress(host);
            setTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
            super.connect(port);
            lastConnectedHost = host;
            lastConnectedPort = port;
            Log.d(TAG, "Connected legacy TCP");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Legacy TCP connect failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Универсальный connect — выбирает TLS или legacy TCP по порту.
     * Порт 5555 → legacy TCP, любой другой → TLS.
     */
    public boolean connect(String host, int port) {
        if (port == 5555) {
            return connectLegacyTcp(host, port);
        }
        return connectTls(host, port);
    }

    /**
     * Выполняет команду через ADB shell.
     * При "Stream closed" (adbd перезапустился после tcpip:5555) автоматически
     * переподключается и повторяет команду один раз.
     * Вызывать только из фонового потока.
     *
     * @return stdout или null при ошибке
     */
    public String runShellCommand(String command) {
        String result = runShellCommandOnce(command);
        if (result != null) return result;

        // Null означает ошибку — пробуем реконнект если знаем порт
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

    /**
     * Стандартная ps-команда для получения списка процессов.
     * Вызывать только из фонового потока.
     */
    public String runPsCommand() {
        return runShellCommand(
                "ps -A -o rss,name | grep '\\.' | grep -v '[-:@]' | awk '{print $2}'");
    }

    /**
     * Отправляет transport-команду "tcpip:5555" на adbd.
     * Это переводит adbd из TLS/Wireless Debugging режима в классический TCP-режим
     * на порту 5555 — он начинает слушать 127.0.0.1:5555 независимо от Wi-Fi.
     *
     * Это НЕ shell-команда, а host-transport-сервис ADB-протокола.
     * После вызова adbd перезапускается — текущее соединение закроется.
     * Нужно переподключиться на 127.0.0.1:5555.
     *
     * Вызывать из фонового потока.
     *
     * @return true если команда отправлена успешно
     */
    public boolean switchToTcpIp() {
        AdbStream stream = null;
        try {
            stream = openStream("tcpip:5555");
            Log.d(TAG, "tcpip:5555 sent — adbd restarting in TCP mode");
            // Читаем ответ (обычно "restarting in TCP mode port: 5555")
            try {
                java.io.InputStream is = stream.openInputStream();
                byte[] buf = new byte[256];
                int n = is.read(buf);
                if (n > 0) Log.d(TAG, "tcpip response: " + new String(buf, 0, n, "UTF-8").trim());
            } catch (Exception ignored) {}
            // После tcpip:5555 adbd перезапустится и будет слушать на 5555.
            // Обновляем lastConnectedPort чтобы авто-реконнект в runShellCommand
            // переподключался именно на 5555, а не на старый TLS-порт.
            lastConnectedPort = 5555;
            lastConnectedHost = "127.0.0.1";
            return true;
        } catch (Exception e) {
            Log.e(TAG, "switchToTcpIp failed: " + e.getMessage(), e);
            return false;
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) {}
        }
    }

    // ── Генерация / загрузка ключей ───────────────────────────────────────────

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

        // Генерируем RSA-2048
        Log.d(TAG, "Generating new RSA-2048 key pair");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Self-signed X.509 через BouncyCastle
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=ReAppzuku");
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.ONE,
                new Date(now),
                new Date(now + 10L * 365 * 24 * 60 * 60 * 1000), // 10 лет
                subject,
                kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        // Сохраняем в PKCS12
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
