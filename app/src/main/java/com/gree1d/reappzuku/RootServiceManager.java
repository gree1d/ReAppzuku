package com.gree1d.reappzuku;

import android.content.Context;
import android.util.Log;

import com.gree1d.reappzuku.server.PsClient;

import java.io.DataOutputStream;
import java.io.IOException;

public class RootServiceManager {

    private static final String TAG = "RootServiceManager";
    private static final String SERVER_CLASS = "com.gree1d.reappzuku.server.PsServer";

    private final Context context;
    private volatile int serverPid = -1;

    public RootServiceManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (isRunning()) return;
        String apkPath = context.getPackageCodePath();
        String cmd = "CLASSPATH=" + apkPath +
                " app_process /system/bin " + SERVER_CLASS +
                " 2>&1 | logwrapper -t PsServer &\necho $!";
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(su.getInputStream()));
            String pidLine = reader.readLine();
            su.waitFor();

            if (pidLine != null && !pidLine.trim().isEmpty()) {
                serverPid = Integer.parseInt(pidLine.trim());
                Log.d(TAG, "PsServer started, pid=" + serverPid);
            } else {
                Log.e(TAG, "PsServer start failed — no pid returned");
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            Log.e(TAG, "start failed: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (serverPid <= 0) return;
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("kill " + serverPid + "\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
            Log.d(TAG, "PsServer stopped, pid=" + serverPid);
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "stop failed: " + e.getMessage(), e);
        } finally {
            serverPid = -1;
        }
    }

    public boolean isRunning() {
        if (serverPid <= 0) return false;
        try {
            Process p = Runtime.getRuntime().exec("kill -0 " + serverPid);
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public void ensureRunning() {
        if (!isRunning()) {
            Log.w(TAG, "PsServer not running — restarting");
            serverPid = -1;
            // Small delay to avoid socket name collision if process just died
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            start();
            // Wait for server to bind socket
            waitForSocket(3000);
        }
    }

    private void waitForSocket(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String result = PsClient.execute();
            if (result != null) {
                Log.d(TAG, "PsServer socket ready");
                return;
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        Log.e(TAG, "PsServer socket not ready after " + timeoutMs + "ms");
    }
}
