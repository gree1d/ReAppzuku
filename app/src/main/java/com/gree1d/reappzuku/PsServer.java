package com.gree1d.reappzuku.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class PsServer {

    public static final String SOCKET_NAME = "reappzuku_ps";
    public static final String END_MARKER = "__END__";
    public static final String LOG_FILENAME = "psserver.log";

    private static FileOutputStream logOut;

    // Самое примитивное логирование — просто байты в файл.
    // Никаких BufferedWriter, никакого SimpleDateFormat до открытия файла.
    private static void log(String msg) {
        String line = msg + "\n";
        if (logOut != null) {
            try {
                logOut.write(line.getBytes("UTF-8"));
                logOut.flush();
            } catch (IOException ignored) {}
        }
        // Дублируем в stderr на случай ручного запуска
        System.err.print(line);
    }

    private static void log(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(msg + "\n" + sw.toString());
    }

    // ----------------------------------------------------------------

    public static void main(String[] args) {
        // args[0] — путь к files/ папке приложения, передаётся из RootServiceManager
        // Открываем файл как самое первое действие, до любой другой логики
        if (args != null && args.length > 0) {
            try {
                File logFile = new File(args[0], LOG_FILENAME);
                // Создаём папку если вдруг нет
                logFile.getParentFile().mkdirs();
                // Перезаписываем при каждом старте
                logOut = new FileOutputStream(logFile, false);
            } catch (Exception e) {
                System.err.println("FATAL: cannot open log file: " + e);
            }
        } else {
            System.err.println("WARN: no log dir arg, file logging disabled");
        }

        log("=== PsServer start ===");
        log("args.length=" + (args == null ? "null" : args.length));
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                log("args[" + i + "]=" + args[i]);
            }
        }

        // pid без android.os.Process
        log("pid=" + getPid());

        LocalServerSocket serverSocket = null;
        try {
            log("creating LocalServerSocket(\"" + SOCKET_NAME + "\")...");
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            log("socket bound OK");

            int n = 0;
            while (true) {
                log("accept() waiting (served=" + n + ")");
                LocalSocket client = serverSocket.accept();
                n++;
                log("client #" + n + " accepted");
                handleClient(client, n);
            }

        } catch (Throwable e) {
            log("FATAL: " + e.getMessage(), e);
            System.exit(1);
        } finally {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignored) {}
                log("serverSocket closed");
            }
            if (logOut != null) {
                try { logOut.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void handleClient(LocalSocket client, int id) {
        log("[#" + id + "] handleClient");
        try {
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

            String command = reader.readLine();
            if (command == null) {
                log("[#" + id + "] null command — drop");
                return;
            }
            log("[#" + id + "] command=\"" + command + "\"");

            long t0 = System.currentTimeMillis();
            String psOutput = runPs();
            int lines = psOutput.isEmpty() ? 0 : psOutput.split("\n").length;
            log("[#" + id + "] ps done in " + (System.currentTimeMillis() - t0) + "ms, lines=" + lines);

            os.write(psOutput.getBytes("UTF-8"));
            os.write((END_MARKER + "\n").getBytes("UTF-8"));
            os.flush();
            log("[#" + id + "] response sent");

        } catch (IOException e) {
            log("[#" + id + "] IOException: " + e.getMessage(), e);
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            log("[#" + id + "] closed");
        }
    }

    private static String runPs() {
        log("runPs() start");
        StringBuilder sb = new StringBuilder();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"ps", "-A"});

            // Дренируем stderr ps
            final Process pRef = process;
            Thread drain = new Thread(() -> {
                try {
                    BufferedReader err = new BufferedReader(
                            new InputStreamReader(pRef.getErrorStream(), "UTF-8"));
                    String line;
                    while ((line = err.readLine()) != null) {
                        log("ps stderr: " + line);
                    }
                } catch (IOException ignored) {}
            });
            drain.setDaemon(true);
            drain.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            int n = 0;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                n++;
            }
            int exit = process.waitFor();
            log("runPs() exit=" + exit + " lines=" + n);

        } catch (Exception e) {
            log("runPs() error: " + e.getMessage(), e);
        } finally {
            if (process != null) process.destroy();
        }
        return sb.toString();
    }

    private static int getPid() {
        try {
            return Integer.parseInt(new File("/proc/self").getCanonicalFile().getName());
        } catch (Exception e) {
            return -1;
        }
    }
}
