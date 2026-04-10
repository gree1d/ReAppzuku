package com.gree1d.reappzuku.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class PsServer {

    private static final String TAG = "PsServer";
    public static final String SOCKET_NAME = "reappzuku_ps";
    public static final String END_MARKER = "__END__";

    public static void main(String[] args) {
        Log.i(TAG, "PsServer starting");
        try {
            LocalServerSocket serverSocket = new LocalServerSocket(SOCKET_NAME);
            Log.i(TAG, "Listening on " + SOCKET_NAME);
            while (true) {
                LocalSocket client = serverSocket.accept();
                handleClient(client);
            }
        } catch (IOException e) {
            Log.e(TAG, "Server error: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void handleClient(LocalSocket client) {
        try {
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

            String command = reader.readLine();
            if (command == null) return;

            String psOutput = runPs();
            os.write(psOutput.getBytes("UTF-8"));
            os.write((END_MARKER + "\n").getBytes("UTF-8"));
            os.flush();
        } catch (IOException e) {
            Log.e(TAG, "Client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private static String runPs() {
        StringBuilder sb = new StringBuilder();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"ps", "-A"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "ps failed: " + e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
        return sb.toString();
    }
}
