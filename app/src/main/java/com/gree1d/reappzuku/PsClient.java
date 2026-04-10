package com.gree1d.reappzuku.server;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class PsClient {

    private static final String TAG = "PsClient";
    private static final int TIMEOUT_MS = 10_000;

    public static String execute() {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(
                    PsServer.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(TIMEOUT_MS);

            OutputStream os = socket.getOutputStream();
            os.write("ps-a\n".getBytes("UTF-8"));
            os.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (PsServer.END_MARKER.equals(line)) break;
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "execute failed: " + e.getMessage());
            return null;
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
