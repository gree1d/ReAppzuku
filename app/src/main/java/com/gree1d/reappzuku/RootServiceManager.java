package com.gree1d.reappzuku;

import android.content.Context;
import android.util.Log;

import com.gree1d.reappzuku.server.PsClient;
import com.gree1d.reappzuku.server.PsServer;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class RootServiceManager {

    private static final String TAG = "RootServiceManager";
    private static final String SERVER_CLASS = "com.gree1d.reappzuku.server.PsServer";

    private final Context context;
    private volatile int serverPid = -1;

    // Последняя позиция в лог-файле — чтобы dumpServerLog показывал только новые строки
    private long logFileOffset = 0;

    public RootServiceManager(Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, "created");
        Log.d(TAG, "logFile=" + getLogFile().getAbsolutePath());
        Log.d(TAG, "filesDir exists=" + context.getFilesDir().exists());
    }

    private File getLogFile() {
        return new File(context.getFilesDir(), PsServer.LOG_FILENAME);
    }

    // ----------------------------------------------------------------
    // Публичный API
    // ----------------------------------------------------------------

    public void start() {
        Log.d(TAG, "▶ start() BEGIN serverPid=" + serverPid);

        if (isRunning()) {
            Log.d(TAG, "start() skipped — already running");
            return;
        }

        // Сбрасываем offset — читаем лог с начала
        logFileOffset = 0;

        File logFile = getLogFile();
        if (logFile.exists()) {
            boolean deleted = logFile.delete();
            Log.d(TAG, "old log deleted=" + deleted);
        }

        String apkPath = context.getPackageCodePath();
        String logDir  = context.getFilesDir().getAbsolutePath();

        Log.d(TAG, "apkPath=" + apkPath);
        Log.d(TAG, "logDir=" + logDir);
        Log.d(TAG, "apk exists=" + new File(apkPath).exists());
        Log.d(TAG, "logDir writable=" + new File(logDir).canWrite());

        // argv[0]=PsServer (имя процесса), argv[1]=logDir
        String cmd = "CLASSPATH=" + apkPath
                + " app_process /system/bin " + SERVER_CLASS
                + " PsServer " + logDir
                + " &\necho $!";

        Log.d(TAG, "shell cmd:\n" + cmd);

        try {
            Log.d(TAG, "exec su...");
            Process su = Runtime.getRuntime().exec("su");

            // Дренируем stderr su
            final Process suRef = su;
            Thread stderrDrain = new Thread(() -> {
                try {
                    BufferedReader err = new BufferedReader(
                            new InputStreamReader(suRef.getErrorStream()));
                    String line;
                    while ((line = err.readLine()) != null) {
                        Log.w(TAG, "su stderr: " + line);
                    }
                } catch (IOException ignored) {}
            }, "su-stderr");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            Log.d(TAG, "cmd flushed to su");

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(su.getInputStream()));

            String pidLine = reader.readLine();
            Log.d(TAG, "pidLine=\"" + pidLine + "\"");

            String extra;
            while ((extra = reader.readLine()) != null) {
                Log.d(TAG, "su stdout extra: \"" + extra + "\"");
            }

            int suExit = su.waitFor();
            Log.d(TAG, "su exited code=" + suExit);

            if (pidLine != null && !pidLine.trim().isEmpty()) {
                serverPid = Integer.parseInt(pidLine.trim());
                Log.d(TAG, "serverPid=" + serverPid);
            } else {
                Log.e(TAG, "no pid — server may have failed immediately");
            }

        } catch (IOException e) {
            Log.e(TAG, "start() IOException: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Log.e(TAG, "start() interrupted", e);
            Thread.currentThread().interrupt();
        } catch (NumberFormatException e) {
            Log.e(TAG, "start() bad pid: " + e.getMessage(), e);
        }

        // Даём серверу 500мс записать хоть что-нибудь в лог
        Log.d(TAG, "sleeping 500ms for server to write first log lines...");
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        Log.d(TAG, "◀ start() END serverPid=" + serverPid);
        dumpServerLog("after start()");
    }

    public void stop() {
        Log.d(TAG, "▶ stop() serverPid=" + serverPid);
        dumpServerLog("before stop()");

        if (serverPid <= 0) {
            Log.w(TAG, "stop() — no pid, nothing to kill");
            return;
        }
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("kill " + serverPid + "\n");
            os.writeBytes("exit\n");
            os.flush();
            int exit = su.waitFor();
            Log.d(TAG, "◀ stop() kill sent, su exit=" + exit);
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "stop() error: " + e.getMessage(), e);
        } finally {
            serverPid = -1;
        }
    }

    public boolean isRunning() {
        if (serverPid <= 0) {
            Log.v(TAG, "isRunning() → false (no pid)");
            return false;
        }
        try {
            Process p = Runtime.getRuntime().exec("kill -0 " + serverPid);
            int exit = p.waitFor();
            boolean running = (exit == 0);
            Log.v(TAG, "isRunning() kill -0 " + serverPid + " exit=" + exit + " → " + running);
            return running;
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "isRunning() error: " + e.getMessage());
            return false;
        }
    }

    public void ensureRunning() {
        Log.d(TAG, "▶ ensureRunning() serverPid=" + serverPid);
        if (!isRunning()) {
            Log.w(TAG, "server not running — restarting");
            dumpServerLog("ensureRunning() — before restart");
            serverPid = -1;
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            start();
            waitForSocket(3000);
        } else {
            Log.d(TAG, "◀ ensureRunning() — server alive");
        }
    }

    private void waitForSocket(long timeoutMs) {
        Log.d(TAG, "▶ waitForSocket() timeout=" + timeoutMs + "ms");
        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            long remaining = deadline - System.currentTimeMillis();
            Log.d(TAG, "socket probe #" + attempt + " remaining=" + remaining + "ms");

            String result = PsClient.execute();
            if (result != null) {
                Log.d(TAG, "◀ waitForSocket() READY after " + attempt + " probe(s)");
                dumpServerLog("socket ready");
                return;
            }

            // После каждых 5 попыток — дамп лога чтобы видеть прогресс сервера
            if (attempt % 5 == 0) {
                dumpServerLog("probe #" + attempt + " still waiting");
            }

            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        Log.e(TAG, "◀ waitForSocket() TIMEOUT after " + timeoutMs + "ms (" + attempt + " probes)");
        dumpServerLog("socket TIMEOUT");
    }

    // ----------------------------------------------------------------
    // Дамп лога сервера — читает только новые строки с момента последнего вызова
    // ----------------------------------------------------------------

    /**
     * Читает psserver.log и выводит каждую строку через Log.d с тегом PsServer.
     * Вызывается автоматически во всех ключевых точках.
     * Можно также вызвать вручную из UI/Activity для мгновенной диагностики.
     */
    public void dumpServerLog(String reason) {
        File f = getLogFile();
        Log.d(TAG, "=== SERVER LOG [" + reason + "] ==="
                + " exists=" + f.exists()
                + " size=" + f.length()
                + " offset=" + logFileOffset);

        if (!f.exists() || f.length() == 0) {
            Log.e(TAG, "  (log file missing or empty — server crashed before first write)");
            Log.e(TAG, "  Check: does su work? Is apkPath correct? Is logDir writable?");
            return;
        }

        if (f.length() <= logFileOffset) {
            Log.d(TAG, "  (no new lines since last dump)");
            return;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            // Пропускаем уже прочитанное
            br.skip(logFileOffset);

            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                Log.d("PsServer", line);
                count++;
            }
            logFileOffset = f.length();
            Log.d(TAG, "  (dumped " + count + " new lines, offset now=" + logFileOffset + ")");

        } catch (IOException e) {
            Log.e(TAG, "dumpServerLog read error: " + e.getMessage(), e);
        }

        Log.d(TAG, "=== SERVER LOG END ===");
    }
}
