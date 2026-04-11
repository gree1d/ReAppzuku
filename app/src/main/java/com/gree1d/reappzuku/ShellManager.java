package com.gree1d.reappzuku;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.ShizukuRemoteProcess;
import rikka.shizuku.SystemServiceHelper;

/**
 * Manages shell command execution via Root or Shizuku.
 * Prioritizes Root access over Shizuku when both are available.
 */
public class ShellManager {
    private static final String TAG = "ShellManager";

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;

    // Root access state - use AtomicBoolean for thread safety
    private volatile Boolean hasRoot = null;
    private final AtomicBoolean rootCheckInProgress = new AtomicBoolean(false);

    @SuppressWarnings("deprecation")
    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener;

    public ShellManager(Context context, Handler handler, ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.executor = executor;

        // Start root check in background immediately
        initializeRootCheck();
    }

    /**
     * Initialize root access check in background.
     * This prevents blocking the main thread.
     */
    private void initializeRootCheck() {
        if (rootCheckInProgress.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    hasRoot = checkRootAccessBlocking();
                    Log.d(TAG, "Root access check complete: " + hasRoot);
                } finally {
                    rootCheckInProgress.set(false);
                }
            });
        }
    }

    /**
     * Blocking check for root access. Should only be called from background thread.
     * Uses "id -u" to verify the actual UID after su — returns true only if uid=0.
     * This correctly handles cases where su exists but permission was denied by
     * the root manager (Magisk, KernelSU, APatch, etc.).
     */
    private boolean checkRootAccessBlocking() {
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
            process.waitFor();

            return "0".equals(output != null ? output.trim() : "");
        } catch (IOException | InterruptedException e) {
            Log.d(TAG, "Root not available: " + e.getMessage());
            return false;
        } finally {
            try {
                if (os != null)
                    os.close();
                if (process != null)
                    process.destroy();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Set the permission listener for Shizuku.
     */
    public void setShizukuPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        this.shizukuPermissionListener = listener;
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
    }

    /**
     * Remove the Shizuku permission listener.
     */
    public void removeShizukuPermissionListener() {
        if (shizukuPermissionListener != null) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        }
    }

    /**
     * Check if the device has root access.
     * Returns cached result if available, otherwise returns false (check may still
     * be in progress).
     * For blocking check, use from background thread only.
     */
    public boolean hasRootAccess() {
        if (hasRoot == null) {
            // If called before check completes, do blocking check on current thread
            // This should only happen if called from background thread
            if (Looper.myLooper() != Looper.getMainLooper()) {
                hasRoot = checkRootAccessBlocking();
            } else {
                // On main thread, return false and let Shizuku be used
                return false;
            }
        }
        return hasRoot;
    }

    /**
     * Check if Shizuku is available and has necessary permissions.
     */
    public boolean hasShizukuPermission() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.w(TAG, "Error checking Shizuku permission", e);
            return false;
        }
    }

    /**
     * Check for shell permissions and request Shizuku if needed.
     * Only requests Shizuku permission if root access is not available.
     */
    public void checkShellPermissions() {
        // If root access is available, don't request Shizuku
        if (hasRoot != null && hasRoot) {
            Log.d(TAG, "Root access available, skipping Shizuku permission request");
            return;
        }

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

    /**
     * Check if any shell permission (Root or Shizuku) is available.
     * This is non-blocking and will return true if Shizuku is available
     * even if root check hasn't completed yet.
     */
    public boolean hasAnyShellPermission() {
        // Check Shizuku first (non-blocking)
        if (hasShizukuPermission()) {
            return true;
        }
        // Check cached root result (null means check in progress)
        return hasRoot != null && hasRoot;
    }

    /**
     * Resolve shell permission from a background thread.
     * This performs a blocking root check if needed, while leaving Shizuku checks unchanged.
     */
    public boolean resolveAnyShellPermission() {
        if (hasShizukuPermission()) {
            return true;
        }
        return hasRootAccess();
    }

    /**
     * Run a shell command prioritizing Root, then Shizuku.
     * Executes on background thread and posts callback to main handler.
     */
    public void runShellCommand(String command, Runnable onSuccess) {
        runShellCommand(command, onSuccess, null);
    }

    /**
     * Run a shell command with separate success/failure callbacks.
     * Executes on background thread and posts callbacks to main handler.
     */
    public void runShellCommand(String command, Runnable onSuccess, Runnable onFailure) {
        executor.execute(() -> {
            boolean succeeded = runShellCommandBlocking(command);

            if (succeeded) {
                if (onSuccess != null) {
                    handler.post(onSuccess);
                }
            } else if (onFailure != null) {
                handler.post(onFailure);
            }
        });
    }

    /**
     * Run a shell command synchronously and return whether it succeeded.
     * This method is blocking and should only be called from a background thread.
     */
    public boolean runShellCommandBlocking(String command) {
        return runShellCommandForResult(command).succeeded();
    }

    public ShellResult runShellCommandForResult(String command) {
        ShellResult rootResult = null;
        if (hasRootAccess()) {
            rootResult = executeRootCommandForResult(command);
            if (rootResult.succeeded()) {
                return rootResult;
            }
        }
        if (hasShizukuPermission()) {
            ShellResult shizukuResult = executeShizukuCommandForResult(command);
            if (shizukuResult.succeeded() || rootResult == null) {
                return shizukuResult;
            }
        }
        return rootResult != null ? rootResult : new ShellResult(false, -1, "No Root or Shizuku permission available");
    }

    /**
     * Run a shell command and process its output line by line.
     */
    public void runShellCommandWithOutput(String command, Consumer<String> outputProcessor) {
        executor.execute(() -> {
            boolean executed = false;
            if (hasRootAccess()) {
                executed = executeRootCommand(command, outputProcessor);
            }
            if (!executed && hasShizukuPermission()) {
                executed = executeShizukuCommandWithOutput(command, outputProcessor);
            }
        });
    }

    /**
     * Run a shell command and return the full output.
     * This method is blocking and should be called from a background thread.
     */
    public String runShellCommandAndGetFullOutput(String command) {
        if (hasRootAccess()) {
            return executeRootCommandAndGetFullOutput(command);
        } else if (hasShizukuPermission()) {
            return executeShizukuCommandAndGetFullOutput(command);
        }
        return null;
    }

    /**
     * Run a shell command via Shizuku only, even if Root is available.
     * Use for commands blocked by SELinux in root context (e.g. ps -A, dumpsys).
     * This method is blocking and should be called from a background thread.
     */
    public String runShizukuCommandAndGetFullOutput(String command) {
        if (hasShizukuPermission()) {
            return executeShizukuCommandAndGetFullOutput(command);
        }
        Log.w(TAG, "runShizukuCommandAndGetFullOutput: Shizuku not available, falling back to root");
        if (hasRootAccess()) {
            return executeRootCommandAndGetFullOutput(command);
        }
        return null;
    }

    /**
     * Freeze a package via Shizuku Binder API using reflection.
     * Sets COMPONENT_ENABLED_STATE_DISABLED_USER so the app cannot restart until unfrozen.
     * Should only be called from a background thread.
     *
     * @return true if freeze succeeded
     */
    public boolean freezePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return setPackageEnabledState(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
    }

    /**
     * Unfreeze a package via Shizuku Binder API using reflection.
     * Should only be called from a background thread.
     *
     * @return true if unfreeze succeeded
     */
    public boolean unfreezePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return setPackageEnabledState(packageName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
    }

    /**
     * Sets the enabled state of a package via IPackageManager using reflection,
     * mirroring the approach used by Hail (github.com/aistra0528/Hail).
     */
    private boolean setPackageEnabledState(String packageName, int newState) {
        if (!hasShizukuPermission()) {
            Log.w(TAG, "setPackageEnabledState: Shizuku not available");
            return false;
        }
        try {
            IBinder binder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"));
            Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
            Object pm = HiddenApiBypass.invoke(stubClass, null, "asInterface", binder);

            HiddenApiBypass.invoke(
                    pm.getClass(),
                    pm,
                    "setApplicationEnabledSetting",
                    packageName,
                    newState,
                    0,
                    0, // userId
                    context.getPackageName()
            );
            Log.d(TAG, "setPackageEnabledState(" + newState + ") ok: " + packageName);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setPackageEnabledState failed for " + packageName, e);
            return false;
        }
    }

    // --- Private helper methods ---

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
                try (BufferedReader readerInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = readerInput.readLine()) != null) {
                        final String finalLine = line;
                        handler.post(() -> outputProcessor.accept(finalLine));
                    }
                    while ((line = errorReader.readLine()) != null) {
                        final String finalLine = line;
                        handler.post(() -> outputProcessor.accept("ERROR: " + finalLine));
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "Root command exited with code " + exitCode + ": " + command);
            }
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Root command failed", e);
            return false;
        } finally {
            try {
                if (os != null)
                    os.close();
                if (process != null)
                    process.destroy();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean executeShizukuCommand(String command) {
        ShizukuRemoteProcess remote = null;
        try {
            remote = Shizuku.newProcess(new String[] { "sh", "-c", command }, null, "/");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(remote.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Consume output
                }
            }

            int exitCode = remote.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "Shizuku command exited with code " + exitCode + ": " + command);
            }
            return exitCode == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Shizuku command failed", e);
            return false;
        } finally {
            if (remote != null) {
                remote.destroy();
            }
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

            try (BufferedReader readerInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    output.append("ERROR: ").append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "Root command exited with code " + exitCode + ": " + command);
            }
            return new ShellResult(exitCode == 0, exitCode, output.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Root command failed", e);
            return new ShellResult(false, -1, e.getMessage());
        } finally {
            try {
                if (os != null)
                    os.close();
                if (process != null)
                    process.destroy();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean executeShizukuCommandWithOutput(String command, Consumer<String> outputProcessor) {
        ShizukuRemoteProcess remote = null;
        try {
            remote = Shizuku.newProcess(new String[] { "sh", "-c", command }, null, "/");
            try (BufferedReader readerInput = new BufferedReader(new InputStreamReader(remote.getInputStream()));
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(remote.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    final String finalLine = line;
                    handler.post(() -> outputProcessor.accept(finalLine));
                }
                while ((line = errorReader.readLine()) != null) {
                    final String finalLine = line;
                    handler.post(() -> outputProcessor.accept("ERROR: " + finalLine));
                }
            }
            int exitCode = remote.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "Shizuku command with output exited with code " + exitCode + ": " + command);
            }
            return exitCode == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Shizuku command with output failed", e);
            return false;
        } finally {
            if (remote != null) {
                remote.destroy();
            }
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

            try (BufferedReader readerInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
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
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Root command get output failed", e);
            return null;
        } finally {
            try {
                if (os != null)
                    os.close();
                if (process != null)
                    process.destroy();
            } catch (IOException ignored) {
            }
        }
    }

    private String executeShizukuCommandAndGetFullOutput(String command) {
        ShizukuRemoteProcess remote = null;
        StringBuilder output = new StringBuilder();
        try {
            remote = Shizuku.newProcess(new String[] { "sh", "-c", command }, null, "/");
            try (BufferedReader readerInput = new BufferedReader(new InputStreamReader(remote.getInputStream()));
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(remote.getErrorStream()))) {
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
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Shizuku command get output failed", e);
            return null;
        } finally {
            if (remote != null) {
                remote.destroy();
            }
        }
    }

    private ShellResult executeShizukuCommandForResult(String command) {
        ShizukuRemoteProcess remote = null;
        StringBuilder output = new StringBuilder();
        try {
            remote = Shizuku.newProcess(new String[] { "sh", "-c", command }, null, "/");
            try (BufferedReader readerInput = new BufferedReader(new InputStreamReader(remote.getInputStream()));
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(remote.getErrorStream()))) {
                String line;
                while ((line = readerInput.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = errorReader.readLine()) != null) {
                    output.append("ERROR: ").append(line).append("\n");
                }
            }
            int exitCode = remote.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "Shizuku command exited with code " + exitCode + ": " + command);
            }
            return new ShellResult(exitCode == 0, exitCode, output.toString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.e(TAG, "Shizuku command failed", e);
            return new ShellResult(false, -1, e.getMessage());
        } finally {
            if (remote != null) {
                remote.destroy();
            }
        }
    }

    public static final class ShellResult {
        private final boolean succeeded;
        private final int exitCode;
        private final String output;

        private ShellResult(boolean succeeded, int exitCode, String output) {
            this.succeeded = succeeded;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output.trim();
        }

        public boolean succeeded() {
            return succeeded;
        }

        public int exitCode() {
            return exitCode;
        }

        public String output() {
            return output;
        }
    }
}
