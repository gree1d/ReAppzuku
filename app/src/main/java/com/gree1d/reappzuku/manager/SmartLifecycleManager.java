package com.gree1d.reappzuku.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

import com.gree1d.reappzuku.core.AppConstants;
import com.gree1d.reappzuku.core.AppPolicyLegacyMigrator;
import com.gree1d.reappzuku.core.AppPolicyResolver;
import com.gree1d.reappzuku.core.ProtectedApps;
import com.gree1d.reappzuku.core.PrivilegedShell;
import com.gree1d.reappzuku.core.ShellManager;
import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.AppPolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.gree1d.reappzuku.core.PreferenceKeys.*;

/**
 * Conservative lifecycle management inspired by Brevent's "standby first,
 * force-stop later" model, but using ReAppzuku/Shizuku as the privilege layer.
 *
 * During Phase 9 explicit Room-backed policies own per-app behavior. Packages
 * without an explicit policy keep the bounded legacy blacklist fallback only
 * while compatibility migration is still required.
 */
public final class SmartLifecycleManager {

    public static final int PROFILE_GENTLE = 0;
    public static final int PROFILE_BALANCED = 1;
    public static final int PROFILE_AGGRESSIVE = 2;

    private static final long MINUTE = 60_000L;
    private final Context context;
    private final ShellManager shellManager;
    private final PrivilegedShell privilegedShell;
    private final SharedPreferences prefs;
    private final PackageManager packageManager;

    public SmartLifecycleManager(Context context, ShellManager shellManager) {
        this.context = context.getApplicationContext();
        this.shellManager = shellManager;
        this.privilegedShell = new PrivilegedShell(shellManager);
        this.prefs = this.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.packageManager = this.context.getPackageManager();
    }

    public static void recordForeground(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(KEY_SMART_LAST_FOREGROUND_PREFIX + packageName, System.currentTimeMillis())
                .remove(KEY_SMART_BACKGROUND_SINCE_PREFIX + packageName)
                .apply();
    }

    public static int getBootGraceMinutes(SharedPreferences prefs) {
        switch (prefs.getInt(KEY_SMART_LIFECYCLE_PROFILE, PROFILE_BALANCED)) {
            case PROFILE_GENTLE: return 10;
            case PROFILE_AGGRESSIVE: return 2;
            default: return 5;
        }
    }

    public static int getStandbyDelayMinutes(SharedPreferences prefs) {
        switch (prefs.getInt(KEY_SMART_LIFECYCLE_PROFILE, PROFILE_BALANCED)) {
            case PROFILE_GENTLE: return 120;
            case PROFILE_AGGRESSIVE: return 30;
            default: return 60;
        }
    }

    public static int getForceStopDelayMinutes(SharedPreferences prefs) {
        switch (prefs.getInt(KEY_SMART_LIFECYCLE_PROFILE, PROFILE_BALANCED)) {
            case PROFILE_GENTLE: return 720;
            case PROFILE_AGGRESSIVE: return 120;
            default: return 360;
        }
    }

    public boolean runPass(boolean bootPass) {
        boolean legacySmartEnabled = prefs.getBoolean(KEY_SMART_LIFECYCLE_ENABLED, false);

        Set<String> blacklisted = new HashSet<>(
                prefs.getStringSet(KEY_BLACKLISTED_APPS, Collections.emptySet()));
        Set<String> whitelisted = new HashSet<>(
                prefs.getStringSet(KEY_WHITELISTED_APPS, Collections.emptySet()));
        boolean legacyAutoKillEnabled = prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false)
                || prefs.getInt(KEY_ACTIVE_PRESET, 0) != 0;
        boolean whitelistMode = prefs.getInt(KEY_KILL_MODE, AppConstants.DEFAULT_KILL_MODE) == 0;

        boolean migrationSnapshotCurrent =
                AppPolicyLegacyMigrator.isMigrationSnapshotCurrent(prefs);
        Map<String, AppPolicy> explicitPolicies = new HashMap<>();
        for (AppPolicy policy : AppDatabase.getInstance(context).appPolicyDao().getAll()) {
            AppPolicy effectivePolicy = AppPolicyLegacyMigrator.resolveEffectivePolicy(
                    policy, migrationSnapshotCurrent);
            if (effectivePolicy != null && effectivePolicy.packageName != null) {
                explicitPolicies.put(effectivePolicy.packageName, effectivePolicy);
            }
        }

        Set<String> managed = new HashSet<>(blacklisted);
        // Include every explicit row so switching a formerly-SMART package to
        // PROTECTED/UNMANAGED/IMMEDIATE also clears stale Smart timing state.
        managed.addAll(explicitPolicies.keySet());
        if (managed.isEmpty()) {
            return true;
        }

        Set<String> smartManaged = new HashSet<>();
        for (String pkg : managed) {
            AppPolicy explicitPolicy = explicitPolicies.get(pkg);
            AppPolicyResolver.LegacyState legacyState = new AppPolicyResolver.LegacyState(
                    legacyAutoKillEnabled,
                    legacySmartEnabled,
                    whitelistMode,
                    whitelisted.contains(pkg),
                    blacklisted.contains(pkg));
            if (AppPolicyResolver.shouldExecuteSmart(
                    explicitPolicy,
                    migrationSnapshotCurrent ? null : legacyState,
                    bootPass)) {
                smartManaged.add(pkg);
            } else {
                clearBackgroundState(pkg);
            }
        }
        if (smartManaged.isEmpty()) {
            return true;
        }
        if (!shellManager.resolveAnyShellPermission()) {
            return false;
        }

        String currentForeground = getCurrentForegroundPackage();
        if (currentForeground != null) recordForeground(context, currentForeground);

        Set<String> running = getRunningPackages();
        String mediaDump = safeShell("dumpsys media_session");
        String widgetDump = safeShell("dumpsys appwidget");
        String servicesDump = safeShell("dumpsys activity services");
        String wallpaperDump = safeShell("dumpsys wallpaper");
        String devicePolicyDump = safeShell("dumpsys device_policy");
        String connectivityDump = safeShell("dumpsys connectivity");

        long now = System.currentTimeMillis();
        long bootEpoch = prefs.getLong(KEY_SMART_BOOT_EPOCH_MS, 0L);
        long legacyStandbyDelay = getStandbyDelayMinutes(prefs) * MINUTE;
        long legacyForceStopDelay = getForceStopDelayMinutes(prefs) * MINUTE;
        boolean legacyBootCleanupEnabled =
                prefs.getBoolean(KEY_SMART_BOOT_CLEANUP_ENABLED, true);
        boolean retryRequired = false;

        for (String pkg : smartManaged) {
            AppPolicy explicitPolicy = explicitPolicies.get(pkg);

            if (!isEligiblePackage(pkg)) {
                clearBackgroundState(pkg);
                continue;
            }

            if (pkg.equals(currentForeground)) {
                clearBackgroundState(pkg);
                continue;
            }

            if (!running.contains(pkg)) {
                clearBackgroundState(pkg);
                continue;
            }

            String protectionReason = getProtectionReason(pkg, mediaDump, widgetDump, servicesDump,
                    wallpaperDump, devicePolicyDump, connectivityDump);
            if (protectionReason != null) {
                clearBackgroundState(pkg);
                continue;
            }

            if (bootPass) {
                // Explicit SMART policies own their boot-cleanup decision. Legacy-owned
                // packages keep the old global switch until that compatibility path is retired.
                if (explicitPolicy == null && !legacyBootCleanupEnabled) continue;

                long lastForeground = prefs.getLong(KEY_SMART_LAST_FOREGROUND_PREFIX + pkg, 0L);
                if (bootEpoch > 0 && lastForeground >= bootEpoch) {
                    continue;
                }
                SmartLifecycleRecoveryPolicy.ForceStopOutcome outcome =
                        SmartLifecycleRecoveryPolicy.onForceStopResult(
                                true, forceStop(pkg, "boot cleanup"));
                if (outcome == SmartLifecycleRecoveryPolicy.ForceStopOutcome.CLEAR_STATE) {
                    clearBackgroundState(pkg);
                } else if (outcome == SmartLifecycleRecoveryPolicy.ForceStopOutcome.KEEP_STATE_AND_RETRY) {
                    retryRequired = true;
                }
                continue;
            }

            long standbyDelay = AppPolicyResolver.resolveSmartStandbyDelayMs(
                    explicitPolicy, legacyStandbyDelay);
            long forceStopDelay = AppPolicyResolver.resolveSmartForceStopDelayMs(
                    explicitPolicy, legacyForceStopDelay, standbyDelay);

            String sinceKey = KEY_SMART_BACKGROUND_SINCE_PREFIX + pkg;
            long backgroundSince = prefs.getLong(sinceKey, 0L);
            if (backgroundSince <= 0L || backgroundSince > now) {
                prefs.edit().putLong(sinceKey, now).apply();
                continue;
            }

            long idle = now - backgroundSince;
            if (idle >= standbyDelay
                    && !prefs.getBoolean(KEY_SMART_STANDBY_APPLIED_PREFIX + pkg, false)) {
                if (setStandby(pkg)) {
                    prefs.edit()
                            .putBoolean(KEY_SMART_STANDBY_APPLIED_PREFIX + pkg, true)
                            .apply();
                }
            }

            if (idle >= forceStopDelay) {
                SmartLifecycleRecoveryPolicy.ForceStopOutcome outcome =
                        SmartLifecycleRecoveryPolicy.onForceStopResult(
                                false, forceStop(pkg, "inactive " + (idle / MINUTE) + " min"));
                if (outcome == SmartLifecycleRecoveryPolicy.ForceStopOutcome.CLEAR_STATE) {
                    clearBackgroundState(pkg);
                }
            }
        }
        return !retryRequired;
    }

    private boolean isEligiblePackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return false;
        if (ProtectedApps.isProtected(context, pkg)) return false;
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_PERSISTENT) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getProtectionReason(String pkg, String mediaDump, String widgetDump,
            String servicesDump, String wallpaperDump, String devicePolicyDump,
            String connectivityDump) {
        if (isEnabledAccessibilityService(pkg)) return "accessibility service";
        if (isEnabledNotificationListener(pkg)) return "notification listener";
        return SmartLifecycleProtectionPolicy.getDumpProtectionReason(
                pkg, mediaDump, widgetDump, servicesDump,
                wallpaperDump, devicePolicyDump, connectivityDump);
    }

    private boolean isEnabledAccessibilityService(String pkg) {
        String raw = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (raw == null || raw.isEmpty()) return false;
        for (String component : raw.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn != null && pkg.equals(cn.getPackageName())) return true;
        }
        return false;
    }

    private boolean isEnabledNotificationListener(String pkg) {
        String raw = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (raw == null || raw.isEmpty()) return false;
        for (String component : raw.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn != null && pkg.equals(cn.getPackageName())) return true;
        }
        return false;
    }

    private String getCurrentForegroundPackage() {
        String activities = safeShell("dumpsys activity activities");
        String foreground = SmartLifecycleTextParser.parseForegroundPackage(activities);
        if (foreground != null) return foreground;

        String window = safeShell("dumpsys window windows");
        return SmartLifecycleTextParser.parseForegroundPackage(window);
    }

    private Set<String> getRunningPackages() {
        return SmartLifecycleTextParser.parseRunningPackages(safeShell("ps -A -o NAME"));
    }

    private boolean setStandby(String pkg) {
        return privilegedShell.setStandbyBucketBlocking(
                pkg, PrivilegedShell.StandbyBucket.RARE);
    }

    private boolean forceStop(String pkg, String reason) {
        return privilegedShell.forceStopPackageBlocking(pkg);
    }

    private void clearBackgroundState(String pkg) {
        prefs.edit()
                .remove(KEY_SMART_BACKGROUND_SINCE_PREFIX + pkg)
                .remove(KEY_SMART_STANDBY_APPLIED_PREFIX + pkg)
                .apply();
    }

    private String safeShell(String command) {
        String output = shellManager.runShellCommandAndGetFullOutput(command);
        return output == null ? "" : output;
    }
}