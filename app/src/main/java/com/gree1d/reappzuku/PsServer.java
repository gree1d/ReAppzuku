package com.gree1d.reappzuku.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PsServer {

    private static final String TAG = "PsServer";
    public static final String SOCKET_NAME = "reappzuku_ps";
    public static final String END_MARKER = "__END__";

    // Файл лога — читается RootServiceManager и ретранслируется в logcat
    public static final String LOG_FILE = "/data/local/tmp/psserver_debug.log";

    private static FileWriter logWriter;
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static synchronized void log(char level, String msg) {
        String line = DATE_FMT.format(new Date()) + " " + level + "/" + TAG + ": " + msg;
        // stderr — подхватывается при ручном запуске
        System.err.println(line);
        // файл — основной канал для RootServiceManager
        try {
            if (logWriter == null) {
                logWriter = new FileWriter(LOG_FILE, /*append=*/true);
            }
            logWriter.write(line + "\n");
            logWriter.flush();
        } catch (IOException e) {
            System.err.println("LOG_WRITE_FAILED: " + e.getMessage());
        }
    }

    private static void logV(String msg) { log('V', msg); }
    private static void logD(String msg) { log('D', msg); }
    private static void logW(String msg) { log('W', msg); }
    private static void logE(String msg) { log('E', msg); }
    private static void logE(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log('E', msg + "\n" + sw);
    }

    // ----------------------------------------------------------------

    public static void main(String[] args) {
        // Очищаем старый лог при каждом старте
        try { new File(LOG_FILE).delete(); } catch (Exception ignored) {}

        logD("main() entered, pid=" + getPid() + " uid=" + getUid());
        logD("java.version=" + System.getProperty("java.version"));

        if (args != null && args.length > 0) {
            StringBuilder sb = new StringBuilder("args:");
            for (String a : args) sb.append(" [").append(a).append("]");
            logV(sb.toString());
        } else {
            logV("no args");
        }

        LocalServerSocket serverSocket = null;
        try {
            logD("creating LocalServerSocket(\"" + SOCKET_NAME + "\")");
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            logD("socket bound OK — entering accept loop");

            int n = 0;
            while (true) {
                logV("accept() waiting (served=" + n + ")");
                LocalSocket client = serverSocket.accept();
                n++;
                logD("client #" + n + " connected");
                handleClient(client, n);
            }
        } catch (Throwable e) {
            logE("fatal: " + e.getMessage(), e);
            System.exit(1);
        } finally {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignored) {}
                logD("serverSocket closed");
            }
            if (logWriter != null) {
                try { logWriter.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void handleClient(LocalSocket client, int id) {
        logV("[#" + id + "] handleClient start");
        try {
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

            String command = reader.readLine();
            if (command == null) {
                logW("[#" + id + "] null command — dropping");
                return;
            }
            logD("[#" + id + "] command=\"" + command + "\"");

            long t0 = System.currentTimeMillis();
            String psOutput = runPs();
            long ms = System.currentTimeMillis() - t0;
            int lines = psOutput.isEmpty() ? 0 : psOutput.split("\n").length;
            logD("[#" + id + "] ps done in " + ms + "ms, lines=" + lines);

            byte[] data = psOutput.getBytes("UTF-8");
            byte[] marker = (END_MARKER + "\n").getBytes("UTF-8");
            os.write(data);
            os.write(marker);
            os.flush();
            logV("[#" + id + "] sent " + data.length + " bytes + END_MARKER");

        } catch (IOException e) {
            logE("[#" + id + "] IOException: " + e.getMessage(), e);
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            logV("[#" + id + "] socket closed");
        }
    }

    private static String runPs() {
        logV("runPs() exec [ps, -A]");
        StringBuilder sb = new StringBuilder();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"ps", "-A"});

            final Process pRef = process;
            Thread stderrDrain = new Thread(() -> {
                try {
                    BufferedReader err = new BufferedReader(
                            new InputStreamReader(pRef.getErrorStream(), "UTF-8"));
                    String line;
                    while ((line = err.readLine()) != null) {
                        logW("runPs stderr: " + line);
                    }
                } catch (IOException ignored) {}
            }, "ps-stderr");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            int n = 0;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                n++;
            }
            int exit = process.waitFor();
            logD("runPs() exit=" + exit + " lines=" + n);

        } catch (IOException e) {
            logE("runPs() IOException: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            logE("runPs() interrupted", e);
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) process.destroy();
        }
        return sb.toString();
    }

    private static int getPid() {
        try { return Integer.parseInt(new File("/proc/self").getCanonicalFile().getName()); }
        catch (Exception e) { return -1; }
    }

    private static int getUid() {
        try {
            Process p = Runtime.getRuntime().exec("id -u");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) { return -1; }
    }
}
