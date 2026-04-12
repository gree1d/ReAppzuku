package com.gree1d.reappzuku;

import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.topjohnwu.superuser.ipc.RootService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Runs in a privileged Magisk process (u:r:magisk:s0 context).
 * Commands executed here bypass the SELinux restrictions that block
 * ps -A and dumpsys activity when running via plain "su" on MIUI/HyperOS Android 14+.
 */
public class PrivilegedService extends RootService {

    private static final String TAG = "PrivilegedService";

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: PrivilegedService starting in root process");
        logSelinuxContext();
        return new ServiceImpl();
    }

    /** Логируем SELinux контекст процесса — ключевая информация для отладки */
    private void logSelinuxContext() {
        try {
            Process p = Runtime.getRuntime().exec("cat /proc/self/attr/current");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ctx = r.readLine();
            p.waitFor();
            Log.i(TAG, "SELinux context: " + ctx);
        } catch (Exception e) {
            Log.w(TAG, "Could not read SELinux context: " + e.getMessage());
        }
    }

    static class ServiceImpl extends IPrivilegedService.Stub {

        @Override
        public int execute(String command) {
            Log.d(TAG, "execute() cmd: " + command);
            long t = System.currentTimeMillis();
            try {
                Process process = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c", command}
                );
                drain(process.getInputStream());
                drain(process.getErrorStream());
                int exit = process.waitFor();
                Log.d(TAG, "execute() exit=" + exit + " (" + (System.currentTimeMillis() - t) + "ms) cmd: " + command);
                return exit;
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "execute() FAILED cmd: " + command, e);
                return -1;
            }
        }

        @Override
        public String executeForOutput(String command) {
            Log.d(TAG, "executeForOutput() cmd: " + command);
            long t = System.currentTimeMillis();
            StringBuilder output = new StringBuilder();
            StringBuilder errors = new StringBuilder();
            try {
                Process process = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c", command}
                );
                try (BufferedReader stdout = new BufferedReader(
                             new InputStreamReader(process.getInputStream()));
                     BufferedReader stderr = new BufferedReader(
                             new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    int lines = 0;
                    while ((line = stdout.readLine()) != null) {
                        output.append(line).append("\n");
                        lines++;
                    }
                    while ((line = stderr.readLine()) != null) {
                        errors.append(line).append("\n");
                        output.append("ERROR: ").append(line).append("\n");
                    }
                    Log.d(TAG, "executeForOutput() stdout_lines=" + lines
                            + " stderr_empty=" + (errors.length() == 0)
                            + " (" + (System.currentTimeMillis() - t) + "ms)"
                            + " cmd: " + command);
                    if (errors.length() > 0) {
                        Log.w(TAG, "executeForOutput() stderr: " + errors.toString().trim());
                    }
                }
                int exit = process.waitFor();
                Log.d(TAG, "executeForOutput() exit=" + exit);
                if (output.length() == 0) {
                    Log.w(TAG, "executeForOutput() output is EMPTY for cmd: " + command);
                }
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "executeForOutput() FAILED cmd: " + command, e);
                return null;
            }
            return output.toString();
        }

        private void drain(java.io.InputStream stream) throws IOException {
            byte[] buf = new byte[4096];
            while (stream.read(buf) != -1) { /* consume */ }
        }
    }
}
