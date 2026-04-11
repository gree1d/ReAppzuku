package com.gree1d.reappzuku;

import android.content.Context;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

public class RootServiceManager {

    private static final String TAG = "RootServiceManager";

    private final Context context;

    public RootServiceManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        String libPath = getLibPath();
        if (libPath == null) {
            Log.e(TAG, "libshizuku.so not found");
            return;
        }
        String apkPath = context.getPackageCodePath();
        String cmd = libPath + " --apk=" + apkPath + " &";
        Log.d(TAG, "Starting Shizuku server: " + cmd);
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
            Log.d(TAG, "Shizuku server start command sent");
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "start failed: " + e.getMessage(), e);
        }
    }

    public void stop() {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("pkill -f libshizuku.so\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();
            Log.d(TAG, "Shizuku server stopped");
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "stop failed: " + e.getMessage(), e);
        }
    }

    private String getLibPath() {
        File lib = new File(context.getApplicationInfo().nativeLibraryDir, "libshizuku.so");
        if (lib.exists()) {
            return lib.getAbsolutePath();
        }
        Log.e(TAG, "libshizuku.so not found at " + lib.getAbsolutePath());
        return null;
    }
}
