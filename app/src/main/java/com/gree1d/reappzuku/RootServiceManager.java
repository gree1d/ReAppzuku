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

    public RootServiceManager(Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, "RootServiceManager created");
    }

    public void start() {
        Log.d(TAG, "start() called, serverPid=" + serverPid);
        if (isRunning()) {
            Log.d(TAG, "start() skipped — already running (pid=" + serverPid + ")");
            return;
        }

        String apkPath = context.getPackageCodePath();
        Log.d(TAG, "apkPath=" + apkPath);

        // Запускаем без logwrapper — PsServer логирует в файл сам
        String cmd = "CLASSPATH=" + apkPath +
                " app_process /system/bin " + SERVER_CLASS + " &\necho $!";
        Log.d(TAG, "launch cmd:\n" + cmd);

        try {
            Process su = Runtime.getRuntime().exec("su");

            // Дренируем stderr su в фоне
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
            Log.v(TAG, "command written to su stdin");

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(su.getInputStream()));
            String pidLine = reader.readLine();
            Log.v(TAG, "pidLine=\"" + pidLine + "\"");

            String extra;
            while ((extra = reader.readLine()) != null) {
                Log.v(TAG, "su stdout extra: \"" + extra + "\"");
            }

            int exitCode = su.waitFor();
            Log.d(TAG, "su exited, code=" + exitCode);

            if (pidLine != null && !pidLine.trim().isEmpty()) {
                serverPid = Integer.parseInt(pidLine.trim());
                Log.d(TAG, "PsServer launched, pid=" + serverPid);
            } else {
                Log.e(TAG, "no pid returned — PsServer may have failed to start");
            }

        } catch (IOException e) {
            Log.e(TAG, "start() IOException: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Log.e(TAG, "start() interrupted", e);
            Thread.currentThread().interrupt();
        } catch (NumberFormatException e) {
            Log.e(TAG, "start() bad pid: " + e.getMessage(), e);
        }

        Log.d(TAG, "start() done, serverPid=" + serverPid);
    }

    public void stop() {
        Log.d(TAG, "stop() called, serverPid=" + serverPid);
        if (serverPid <= 0) {
            Log.w(TAG, "stop() skipped — no pid");
            return;
        }
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("kill " + serverPid + "\n");
            os.writeBytes("exit\n");
            os.flush();
            int exit = su.waitFor();
            Log.d(TAG, "kill sent, su exit=" + exit + ", pid was=" + serverPid);
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "stop() failed: " + e.getMessage(), e);
        } finally {
            serverPid = -1;
        }
    }

    public boolean isRunning() {
        if (serverPid <= 0) {
            Log.v(TAG, "isRunning() -> false (no pid)");
            return false;
        }
        try {
            Process p = Runtime.getRuntime().exec("kill -0 " + serverPid);
            int exit = p.waitFor();
            Log.v(TAG, "isRunning() kill -0 " + serverPid + " -> " + exit);
            return exit == 0;
        } catch (IOException | InterruptedException e) {
            Log.w(TAG, "isRunning() check error: " + e.getMessage());
            return false;
        }
    }

    public void ensureRunning() {
        Log.d(TAG, "ensureRunning() serverPid=" + serverPid);
        if (!isRunning()) {
            Log.w(TAG, "server not running — restarting");
            serverPid = -1;
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            start();
            waitForSocket(3000);
        } else {
            Log.d(TAG, "ensureRunning() — server OK");
        }
    }

    private void waitForSocket(long timeoutMs) {
        Log.d(TAG, "waitForSocket() timeout=" + timeoutMs + "ms");
        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            Log.v(TAG, "socket probe #" + attempt
                    + " remaining=" + (deadline - System.currentTimeMillis()) + "ms");
            String result = PsClient.execute();
            if (result != null) {
                Log.d(TAG, "socket ready after " + attempt + " probe(s)");
                dumpServerLog("after socket ready");
                return;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        Log.e(TAG, "socket NOT ready after " + timeoutMs + "ms (" + attempt + " probes)");
        // Самое важное — дамп лога сервера после неудачи
        dumpServerLog("after timeout");
    }

    /**
     * Читает лог-файл PsServer и ретранслирует каждую строку через Log.d/e
     * с тегом "PsServer", чтобы они появились в logcat.
     */
    public void dumpServerLog(String reason) {
        File f = new File(PsServer.LOG_FILE);
        Log.d(TAG, "=== dumpServerLog [" + reason + "] file=" + f.getAbsolutePath()
                + " exists=" + f.exists() + " size=" + f.length() + " ===");
        if (!f.exists() || f.length() == 0) {
            Log.w(TAG, "log file empty or missing — server may have crashed before first write");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Уровень определяем по символу из формата PsServer: "E/PsServer:", "D/PsServer:" и т.д.
                if (line.contains("E/PsServer")) {
                    Log.e("PsServer", line);
                } else if (line.contains("W/PsServer")) {
                    Log.w("PsServer", line);
                } else {
                    Log.d("PsServer", line);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "dumpServerLog() read failed: " + e.getMessage(), e);
        }
        Log.d(TAG, "=== dumpServerLog end ===");
    }
}
