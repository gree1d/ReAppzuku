package com.gree1d.reappzuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.topjohnwu.superuser.ipc.RootService;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.ShizukuRemoteProcess;
import rikka.shizuku.SystemServiceHelper;

/**
 * Manages shell command execution via Root, RootService (libsu), or Shizuku.
 *
 * Priority:
 *   1. Plain Root (su) — для большинства команд
 *   2. RootService (libsu) — когда su заблокирован SELinux (MIUI/HyperOS Android 14+)
 *   3. Shizuku — если root недоступен
 */
public class ShellManager {
    private static final String TAG = "ShellManager";

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;

    // Root access state
    private volatile Boolean hasRoot = null;
    private final AtomicBoolean rootCheckInProgress = new AtomicBoolean(false);

    // RootService (libsu) state
    private volatile IPrivilegedService privilegedService = null;
    private final AtomicBoolean rootServiceBindInProgress = new AtomicBoolean(false);

    @SuppressWarnings("deprecation")
    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener;

    public ShellManager(Context context, Handler handler, ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.executor = executor;
        initializeRootCheck();
    }

    // -------------------------------------------------------------------------
    // Root check
    // -------------------------------------------------------------------------

    private void initializeRootCheck() {
        if (rootCheckInProgress.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    hasRoot = checkRootAccessBlocking();
                    Log.d(TAG, "Root access check complete: " + hasRoot);
                    // Если root есть — заранее поднимаем RootService
                    if (hasRoot) {
                        bindRootService();
                    }
                } finally {
                    rootCheckInProgress.set(false);
                }
            });
        }
    }

    private boolean checkRootAccessBlocking() {
        Log.d(TAG, "checkRootAccessBlocking: starting su check");
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id -u\n");
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            int exitCode = process.waitFor();
            boolean result = "0".equals(output != null ? output.trim() : "");
            Log.d(TAG, "checkRootAccessBlocking: output='" + output + "' exitCode=" + exitCode + " result=" + result);
            return result;
        } catch (IOException | InterruptedException e) {
            Log.d(TAG, "checkRootAccessBlocking: exception — " + e.getMessage());
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // RootService (libsu) binding
    // -------------------------------------------------------------------------

    /**
     * Запускает и привязывает PrivilegedService асинхронно.
     * Повторный вызов игнорируется если сервис уже привязан или в процессе.
     */
    public void bindRootService() {
        if (privilegedService != null) {
            Log.d(TAG, "bindRootService: already connected, skip");
            return;
        }
        if (!rootServiceBindInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "bindRootService: bind already in progress, skip");
            return;
        }
        Log.d(TAG, "bindRootService: posting bind to main thread");
        handler.post(() -> {
            Log.d(TAG, "bindRootService: calling RootService.bind()");
            Intent intent = new Intent(context, PrivilegedService.class);
            RootService.bind(intent, rootServiceConnection);
        });
    }

    /**
     * Отвязать RootService при уничтожении компонента.
     */
    public void unbindRootService() {
        if (privilegedService != null) {
            Log.d(TAG, "unbindRootService: unbinding");
            Intent intent = new Intent(context, PrivilegedService.class);
            RootService.unbind(rootServiceConnection);
            privilegedService = null;
        } else {
            Log.d(TAG, "unbindRootService: was not connected");
        }
    }

    private final ServiceConnection rootServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            privilegedService = IPrivilegedService.Stub.asInterface(service);
            rootServiceBindInProgress.set(false);
            Log.d(TAG, "RootService connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            privilegedService = null;
            rootServiceBindInProgress.set(false);
            Log.w(TAG, "RootService disconnected");
        }
    };

    /**
     * Синхронно ждёт подключения RootService (макс. 5 секунд).
     * Используется когда нужен сервис прямо сейчас из фонового потока.
     */
    private IPrivilegedService awaitRootService() {
        if (privilegedService != null) {
            Log.d(TAG, "awaitRootService: already available");
            return privilegedService;
        }
        if (!hasRootAccess()) {
            Log.d(TAG, "awaitRootService: no root, skip");
            return null;
        }

        bindRootService();

        Log.d(TAG, "awaitRootService: waiting up to 5s for RootService...");
        long deadline = System.currentTimeMillis() + 5000;
        while (privilegedService == null && System.currentTimeMillis() < deadline) {
            try {
                //noinspection BusyWait
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "awaitRootService: interrupted while waiting");
                return null;
            }
        }
        if (privilegedService != null) {
            Log.d(TAG, "awaitRootService: connected after " + (5000 - (deadline - System.currentTimeMillis())) + "ms");
        } else {
            Log.w(TAG, "awaitRootService: TIMEOUT — RootService did not connect within 5s");
        }
        return privilegedService;
    }

    public boolean hasRootServiceAvailable() {
        return privilegedService != null;
    }

    // -------------------------------------------------------------------------
    // Public API (без изменений для обратной совместимости)
    // -------------------------------------------------------------------------

    public void setShizukuPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        this.shizukuPermissionListener = listener;
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
    }

    public void removeShizukuPermissionListener() {
        if (shizukuPermissionListener != null) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        }
    }

    public boolean hasRootAccess() {
        if (hasRoot == null) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                hasRoot = checkRootAccessBlocking();
            } else {
                return false;
            }
        }
        return hasRoot;
    }

    public boolean hasShizukuPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.w(TAG, "Error checking Shizuku permission", e);
            return false;
        }
    }

    public void checkShellPermissions() {
        if (hasRoot != null && hasRoot) return;
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking shell permissions", e);
        }
    }

    public boolean hasAnyShellPermission() {
        if (hasShizukuPermission()) return true;
        return hasRoot != null && hasRoot;
    }

    public boolean resolveAnyShellPermission() {
        if (hasShizukuPermission()) return true;
        return hasRootAccess();
    }

    public void runShellCommand(String command, Runnable onSuccess) {
        runShellCommand(command, onSuccess, null);
    }

    public void runShellCommand(String command, Runnable onSuccess, Runnable onFailure) {
        executor.execute(() -> {
            boolean succeeded = runShellCommandBlocking(command);
            if (succeeded) {
                if (onSuccess != null) handler.post(onSuccess);
            } else if (onFailure != null) {
                handler.post(onFailure);
            }
        });
    }

    public boolean runShellCommandBlocking(String command) {
        return runShellCommandForResult(command).succeeded();
    }

    /**
     * Основной метод выполнения команды.
     *
     * Приоритет:
     * 1. Root (su) — пробуем первым
     * 2. RootService (libsu) — если su вернул ошибку (SELinux блокировка)
     * 3. Shizuku — если root вообще недоступен
     */
    public ShellResult runShellCommandForResult(String command) {
        Log.d(TAG, "runShellCommandForResult: hasRoot=" + hasRoot
                + " rootServiceAvail=" + (privilegedService != null)
                + " shizuku=" + hasShizukuPermission()
                + " cmd: " + command);

        if (hasRootAccess()) {
            ShellResult rootResult = executeRootCommandForResult(command);
            Log.d(TAG, "runShellCommandForResult: su result — succeeded=" + rootResult.succeeded()
                    + " exit=" + rootResult.exitCode()
                    + " outputLen=" + rootResult.output().length());

            if (rootResult.succeeded()) {
                return rootResult;
            }

            Log.w(TAG, "runShellCommandForResult: su failed (SELinux?), trying RootService for: " + command);
            IPrivilegedService service = awaitRootService();
            if (service != null) {
                ShellResult rsResult = executeRootServiceCommandForResult(service, command);
                Log.d(TAG, "runShellCommandForResult: RootService result — succeeded=" + rsResult.succeeded()
                        + " exit=" + rsResult.exitCode()
                        + " outputLen=" + rsResult.output().length());
                if (rsResult.succeeded()) {
                    Log.i(TAG, "runShellCommandForResult: RootService bypassed SELinux for: " + command);
                    return rsResult;
                }
                Log.w(TAG, "runShellCommandForResult: RootService also failed for: " + command);
            } else {
                Log.w(TAG, "runShellCommandForResult: RootService unavailable, no fallback");
            }

            return rootResult;
        }

        if (hasShizukuPermission()) {
            Log.d(TAG, "runShellCommandForResult: using Shizuku for: " + command);
            return executeShizukuCommandForResult(command);
        }

        Log.e(TAG, "runShellCommandForResult: NO permission available for: " + command);
        return new ShellResult(false, -1, "No Root or Shizuku permission available");
    }

    public void runShellCommandWithOutput(String command, Consumer<String> outputProcessor) {
        executor.execute(() -> {
            boolean executed = false;
            if (hasRootAccess()) {
                executed = executeRootCommand(command, outputProcessor);
                if (!executed) {
                    // Пробуем RootService
                    IPrivilegedService service = awaitRootService();
                    if (service != null) {
                        executed = executeRootServiceCommandWithOutput(service, command, outputProcessor);
                    }
                }
            }
            if (!executed && hasShizukuPermission()) {
                executeShizukuCommandWithOutput(command, outputProcessor);
            }
        });
    }

    public String runShellCommandAndGetFullOutput(String command) {
        Log.d(TAG, "runShellCommandAndGetFullOutput: cmd: " + command);
        if (hasRootAccess()) {
            String output = executeRootCommandAndGetFullOutput(command);
            Log.d(TAG, "runShellCommandAndGetFullOutput: su output "
                    + (output == null ? "NULL" : "len=" + output.length()
                    + (output.startsWith("ERROR:") ? " [starts with ERROR]" : "")));
            if (output != null && !output.startsWith("ERROR:")) {
                return output;
            }
            Log.w(TAG, "runShellCommandAndGetFullOutput: su failed, trying RootService for: " + command);
            IPrivilegedService service = awaitRootService();
            if (service != null) {
                try {
                    String rsOutput = service.executeForOutput(command);
                    Log.d(TAG, "runShellCommandAndGetFullOutput: RootService output "
                            + (rsOutput == null ? "NULL" : "len=" + rsOutput.length()));
                    return rsOutput;
                } catch (RemoteException e) {
                    Log.w(TAG, "runShellCommandAndGetFullOutput: RootService failed", e);
                }
            } else {
                Log.w(TAG, "runShellCommandAndGetFullOutput: RootService unavailable");
            }
            return output;
        }
        if (hasShizukuPermission()) {
            Log.d(TAG, "runShellCommandAndGetFullOutput: using Shizuku");
            return executeShizukuCommandAndGetFullOutput(command);
        }
        Log.e(TAG, "runShellCommandAndGetFullOutput: NO permission for: " + command);
        return null;
    }

    // -------------------------------------------------------------------------
    // freeze / unfreeze (без изменений)
    // -------------------------------------------------------------------------

    public boolean freezePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return setPackageEnabledState(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
    }

    public boolean unfreezePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return setPackageEnabledState(packageName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
    }

    private boolean setPackageEnabledState(String packageName, int newState) {
        if (!hasShizukuPermission()) {
            Log.w(TAG, "setPackageEnabledState: Shizuku not available");
            return false;
        }
        try {
            IBinder binder = new ShizukuBinderWrapper(
                    SystemServiceHelper.getSystemService("package"));
            Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
            Object pm = HiddenApiBypass.invoke(stubClass, null, "asInterface", binder);
            HiddenApiBypass.invoke(
                    pm.getClass(), pm,
                    "setApplicationEnabledSetting",
                    packageName, newState, 0, 0,
                    context.getPackageName()
            );
            Log.d(TAG, "setPackageEnabledState(" + newState + ") ok: " + packageName);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setPackageEnabledState failed for " + packageName, e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private — RootService helpers
    // -------------------------------------------------------------------------

    private ShellResult executeRootServiceCommandForResult(
            IPrivilegedService service, String command) {
        try {
            String output = service.executeForOutput(command);
            boolean ok = output != null && !output.contains("ERROR:");
            return new ShellResult(ok, ok ? 0 : 1, output != null ? output : "");
        } catch (RemoteException e) {
            Log.w(TAG, "RootService command failed: " + command, e);
            return new ShellResult(false, -1, e.getMessage());
        }
    }

    private boolean executeRootServiceCommandWithOutput(
            IPrivilegedService service, String command, Consumer<String> outputProcessor) {
        try {
            String output = service.executeForOutput(command);
            if (output == null) return false;
            for (String line : output.split("\n")) {
                final String l = line;
                handler.post(() -> outputProcessor.accept(l));
            }
            return !output.contains("ERROR:");
        } catch (RemoteException e) {
            Log.w(TAG, "RootService command with output failed", e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private — Root (su) helpers (без изменений)
    // -------------------------------------------------------------------------

    private boolean executeRootCommand(String command, Consumer<String> outputProcessor) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            if (outputProcessor != null) {
                try (BufferedReader readerInput = new BufferedReader(
                             new InputStreamReader(process.getInputStream()));
                     BufferedReader errorReader = new BufferedReader(
                             new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = readerInput.readLine()) != null) {
                        final String l = line;
                        handler.post(() -> outputProcessor.accept(l));
                    }
                    while ((line = errorReader.readLine()) != null) {
                        final String l = line;
                        handler.post(() -> outputProcessor.accept("ERROR: " + l));
                    }
                }
            }
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "Root command failed", e);
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (IOException ignored) {}
        }
    }

    private ShellResult executeRootCommandForResult(String command) {
        Process process = null;
        DataOutputStream os = null;
        StringBuilder output = new StringBuilder();
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            try (BufferedReader readerInput = new BufferedReader(
                         new InputStreamReader(process.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(
                         new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    output.append("ERROR: ").append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            return new ShellResult(exitCode == 0, exitCode, output.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "Root command failed", e);
            return new ShellResult(false, -1, e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (IOException ignored) {}
        }
    }

    private String executeRootCommandAndGetFullOutput(String command) {
        Process process = null;
        DataOutputStream os = null;
        StringBuilder output = new StringBuilder();
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            try (BufferedReader readerInput = new BufferedReader(
                         new InputStreamReader(process.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(
                         new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    output.append("ERROR: ").append(line).append("\n");
                }
            }
            process.waitFor();
            return output.toString();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "Root command get output failed", e);
            return null;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Private — Shizuku helpers (без изменений)
    // -------------------------------------------------------------------------

    private boolean executeShizukuCommand(String command) {
        ShizukuRemoteProcess remote = null;
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(remote.getInputStream()))) {
                while (reader.readLine() != null) { /* consume */ }
            }
            int exitCode = remote.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "Shizuku command failed", e);
            return false;
        } finally {
            if (remote != null) remote.destroy();
        }
    }

    private ShellResult executeShizukuCommandForResult(String command) {
        ShizukuRemoteProcess remote = null;
        StringBuilder output = new StringBuilder();
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            try (BufferedReader readerInput = new BufferedReader(
                         new InputStreamReader(remote.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(
                         new InputStreamReader(remote.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    output.append("ERROR: ").append(line).append("\n");
                }
            }
            int exitCode = remote.waitFor();
            return new ShellResult(exitCode == 0, exitCode, output.toString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "Shizuku command failed", e);
            return new ShellResult(false, -1, e.getMessage());
        } finally {
            if (remote != null) remote.destroy();
        }
    }

    private boolean executeShizukuCommandWithOutput(String command, Consumer<String> outputProcessor) {
        ShizukuRemoteProcess remote = null;
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            try (BufferedReader readerInput = new BufferedReader(
                         new InputStreamReader(remote.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(
                         new InputStreamReader(remote.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    final String l = line;
                    handler.post(() -> outputProcessor.accept(l));
                }
                while ((line = errorReader.readLine()) != null) {
                    final String l = line;
                    handler.post(() -> outputProcessor.accept("ERROR: " + l));
                }
            }
            int exitCode = remote.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "Shizuku command with output failed", e);
            return false;
        } finally {
            if (remote != null) remote.destroy();
        }
    }

    private String executeShizukuCommandAndGetFullOutput(String command) {
        ShizukuRemoteProcess remote = null;
        StringBuilder output = new StringBuilder();
        try {
            remote = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, "/");
            try (BufferedReader readerInput = new BufferedReader(
                         new InputStreamReader(remote.getInputStream()));
                 BufferedReader errorReader = new BufferedReader(
                         new InputStreamReader(remote.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    output.append("ERROR: ").append(line).append("\n");
                }
            }
            remote.waitFor();
            return output.toString();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "Shizuku command get output failed", e);
            return null;
        } finally {
            if (remote != null) remote.destroy();
        }
    }

    // -------------------------------------------------------------------------
    // ShellResult (без изменений)
    // -------------------------------------------------------------------------

    public static final class ShellResult {
        private final boolean succeeded;
        private final int exitCode;
        private final String output;

        private ShellResult(boolean succeeded, int exitCode, String output) {
            this.succeeded = succeeded;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output.trim();
        }

        public boolean succeeded() { return succeeded; }
        public int exitCode()      { return exitCode; }
        public String output()     { return output; }
    }
}
