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
        return new ServiceImpl();
    }

    static class ServiceImpl extends IPrivilegedService.Stub {

        @Override
        public int execute(String command) {
            try {
                // Здесь мы уже в Magisk-процессе — su не нужен
                Process process = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c", command}
                );
                // Дочитываем stdout/stderr чтобы не блокировать процесс
                drain(process.getInputStream());
                drain(process.getErrorStream());
                return process.waitFor();
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "execute failed: " + command, e);
                return -1;
            }
        }

        @Override
        public String executeForOutput(String command) {
            StringBuilder output = new StringBuilder();
            try {
                Process process = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c", command}
                );
                try (BufferedReader stdout = new BufferedReader(
                             new InputStreamReader(process.getInputStream()));
                     BufferedReader stderr = new BufferedReader(
                             new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = stdout.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                    while ((line = stderr.readLine()) != null) {
                        output.append("ERROR: ").append(line).append("\n");
                    }
                }
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "executeForOutput failed: " + command, e);
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
