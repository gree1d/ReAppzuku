package com.gree1d.reappzuku.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.AppPolicy;
import com.gree1d.reappzuku.db.AppPolicyDao;
import com.gree1d.reappzuku.manager.SmartLifecycleManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.gree1d.reappzuku.core.PreferenceKeys.*;

/**
 * Retry-safe bridge from the editable legacy settings model to canonical per-app policies.
 *
 * Migration-owned rows carry explicit provenance. A fingerprint binds those rows to the exact
 * legacy snapshot that produced them. If any old UI/preset/restore writer changes relevant
 * preferences, the fingerprint stops matching immediately and runtime routing ignores only
 * LEGACY_MIGRATED rows until a fresh reconciliation succeeds. User-owned explicit policies are
 * never discarded by this bridge.
 */
public final class AppPolicyLegacyMigrator {
    static final int CURRENT_MIGRATION_VERSION = 2;
    private static final long MINUTE_MS = 60_000L;

    private AppPolicyLegacyMigrator() {}

    public static boolean migrateIfNeeded(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);

        try {
            AppDatabase db = AppDatabase.getInstance(appContext);
            PolicyPresetSeeder.seedBuiltIns(db);

            LegacySnapshot legacy = readSnapshot(prefs);
            String fingerprint = fingerprintSnapshot(legacy);
            if (isMigrationSnapshotCurrent(prefs, fingerprint)) {
                return true;
            }

            PackageInventory inventory = collectPackageInventory(appContext);
            AppPolicyDao policyDao = db.appPolicyDao();

            Set<String> existingExplicitPackages = new HashSet<>();
            for (AppPolicy policy : policyDao.getAll()) {
                if (policy != null
                        && policy.packageName != null
                        && policy.source != AppPolicy.SOURCE_LEGACY_MIGRATED) {
                    existingExplicitPackages.add(policy.packageName);
                }
            }

            List<AppPolicy> planned = planPolicies(
                    legacy,
                    inventory.eligibleInstalledPackages,
                    inventory.failSafePackages,
                    existingExplicitPackages,
                    System.currentTimeMillis());

            // Invalidate before touching Room. If the process dies anywhere below, runtime falls
            // back to live legacy state instead of treating a partially refreshed snapshot as
            // canonical.
            if (!invalidateMigration(prefs.edit()).commit()) {
                return false;
            }

            db.runInTransaction(() -> {
                policyDao.deleteBySource(AppPolicy.SOURCE_LEGACY_MIGRATED);
                policyDao.insertAllIgnore(planned);
            });

            // A legacy write may race the Room transaction. Never bless rows generated from an
            // older snapshot: leave them invalid and let the next coalesced pass rebuild them.
            if (!fingerprint.equals(fingerprintSnapshot(readSnapshot(prefs)))) {
                return false;
            }

            return prefs.edit()
                    .putString(KEY_APP_POLICY_MIGRATION_FINGERPRINT, fingerprint)
                    .putInt(KEY_APP_POLICY_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION)
                    .commit();
        } catch (RuntimeException ignored) {
            // Do not mark a failed migration complete. Stale migration-owned rows remain ignored.
            return false;
        }
    }

    public static boolean isMigrationSnapshotCurrent(SharedPreferences prefs) {
        if (prefs == null) return false;
        try {
            String currentFingerprint = fingerprintSnapshot(readSnapshot(prefs));
            return isMigrationSnapshotCurrent(prefs, currentFingerprint);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isMigrationSnapshotCurrent(
            SharedPreferences prefs, String currentFingerprint) {
        if (prefs.getInt(KEY_APP_POLICY_MIGRATION_VERSION, 0) < CURRENT_MIGRATION_VERSION) {
            return false;
        }
        String stored = prefs.getString(KEY_APP_POLICY_MIGRATION_FINGERPRINT, null);
        return stored != null && stored.equals(currentFingerprint);
    }

    @Nullable
    public static AppPolicy resolveEffectivePolicy(
            @Nullable AppPolicy policy, boolean migrationSnapshotCurrent) {
        if (policy == null) return null;
        if (policy.source == AppPolicy.SOURCE_LEGACY_MIGRATED && !migrationSnapshotCurrent) {
            return null;
        }
        return policy;
    }

    static SharedPreferences.Editor invalidateMigration(SharedPreferences.Editor editor) {
        return editor
                .putInt(KEY_APP_POLICY_MIGRATION_VERSION, 0)
                .remove(KEY_APP_POLICY_MIGRATION_FINGERPRINT);
    }

    public static boolean affectsMigrationKey(@Nullable String key) {
        if (key == null) return false;
        switch (key) {
            case KEY_AUTO_KILL_ENABLED:
            case KEY_ACTIVE_PRESET:
            case KEY_SMART_LIFECYCLE_ENABLED:
            case KEY_SLEEP_MODE_ENABLED:
            case KEY_KILL_MODE:
            case KEY_AUTO_KILL_TYPE:
            case KEY_SMART_LIFECYCLE_PROFILE:
            case KEY_SMART_BOOT_CLEANUP_ENABLED:
            case KEY_PERIODIC_KILL_ENABLED:
            case KEY_KILL_ON_SCREEN_OFF:
            case KEY_RAM_THRESHOLD_ENABLED:
            case KEY_HW_TRIGGER_HEADSET:
            case KEY_HW_TRIGGER_USB:
            case KEY_HW_TRIGGER_CHARGER:
            case KEY_HW_TRIGGER_WIFI:
            case KEY_HW_TRIGGER_BLUETOOTH:
            case KEY_HW_TRIGGER_GPS:
            case KEY_HW_TRIGGER_HOTSPOT:
            case KEY_APP_LAUNCH_TRIGGER_ENABLED:
            case KEY_HIDDEN_APPS:
            case KEY_WHITELISTED_APPS:
            case KEY_BLACKLISTED_APPS:
            case KEY_SLEEP_MODE_APPS:
            case KEY_SLEEP_MODE_APPS_PERMANENT:
            case KEY_SLEEP_MODE_APPS_FROZEN:
            case KEY_AUTOSTART_DISABLED_APPS:
            case KEY_MEDIUM_RESTRICTION_APPS:
            case KEY_HARD_RESTRICTION_APPS:
            case KEY_MANUAL_RESTRICTION_APPS:
                return true;
            default:
                return false;
        }
    }

    static List<AppPolicy> planPolicies(
            LegacySnapshot legacy,
            Set<String> eligibleInstalledPackages,
            Set<String> failSafePackages,
            Set<String> existingPolicyPackages,
            long now) {
        if (legacy == null) return Collections.emptyList();

        Set<String> eligibleInstalled = safeSet(eligibleInstalledPackages);
        Set<String> failSafe = safeSet(failSafePackages);
        Set<String> existing = safeSet(existingPolicyPackages);

        TreeSet<String> candidates = new TreeSet<>();
        candidates.addAll(legacy.whitelistedApps);
        candidates.addAll(legacy.blacklistedApps);
        candidates.addAll(legacy.sleepTimerApps);
        candidates.addAll(legacy.sleepPermanentApps);
        candidates.addAll(legacy.sleepFrozenTimerApps);
        candidates.addAll(legacy.backgroundRestrictedApps);
        candidates.addAll(legacy.mediumRestrictedApps);
        candidates.addAll(legacy.hardRestrictedApps);
        candidates.addAll(legacy.manualRestrictedApps);

        // Whitelist mode historically targets every installed non-protected app that is not
        // explicitly whitelisted. Materialize that effective set now so future installs remain
        // unmanaged until the dedicated new-app flow exists.
        if (legacy.autoKillEnabled && legacy.whitelistMode) {
            candidates.addAll(eligibleInstalled);
        }

        List<AppPolicy> result = new ArrayList<>();
        for (String packageName : candidates) {
            if (!PackageNameValidator.isValid(packageName) || existing.contains(packageName)) {
                continue;
            }

            AppPolicy policy = new AppPolicy(packageName);
            policy.strategy = resolveMigratedStrategy(
                    packageName, legacy, eligibleInstalled, failSafe);
            policy.source = AppPolicy.SOURCE_LEGACY_MIGRATED;
            policy.presetId = null;
            policy.customized = true;
            policy.standbyDelayMs = legacy.smartStandbyDelayMs;
            policy.forceStopDelayMs = legacy.smartForceStopDelayMs;
            policy.killMethod = legacy.killMethod;
            policy.bootCleanup = legacy.smartBootCleanup;
            policy.backgroundRestriction = resolveBackgroundRestriction(packageName, legacy);
            policy.protectMedia = true;
            policy.protectForegroundServices = true;
            policy.protectWidgets = true;
            policy.triggerMask = resolveTriggerMask(policy.strategy, legacy);
            policy.createdAt = now;
            policy.updatedAt = now;
            result.add(policy);
        }
        return result;
    }

    static int resolveMigratedStrategy(
            String packageName,
            LegacySnapshot legacy,
            Set<String> eligibleInstalledPackages,
            Set<String> failSafePackages) {
        if (failSafePackages.contains(packageName)) {
            return AppPolicy.STRATEGY_PROTECTED;
        }

        boolean sleepOwnsPackage =
                legacy.sleepPermanentApps.contains(packageName)
                        || legacy.sleepFrozenTimerApps.contains(packageName)
                        || (legacy.sleepModeEnabled && legacy.sleepTimerApps.contains(packageName));
        if (sleepOwnsPackage) {
            return AppPolicy.STRATEGY_PROTECTED;
        }

        if (legacy.smartLifecycleEnabled && legacy.blacklistedApps.contains(packageName)) {
            return AppPolicy.STRATEGY_SMART;
        }

        if (!legacy.autoKillEnabled) {
            return AppPolicy.STRATEGY_UNMANAGED;
        }

        if (legacy.whitelistMode) {
            if (legacy.hiddenApps.contains(packageName)
                    || legacy.whitelistedApps.contains(packageName)) {
                return AppPolicy.STRATEGY_PROTECTED;
            }
            return eligibleInstalledPackages.contains(packageName)
                    ? AppPolicy.STRATEGY_IMMEDIATE
                    : AppPolicy.STRATEGY_UNMANAGED;
        }

        if (legacy.hiddenApps.contains(packageName)) {
            return AppPolicy.STRATEGY_PROTECTED;
        }

        return legacy.blacklistedApps.contains(packageName)
                ? AppPolicy.STRATEGY_IMMEDIATE
                : AppPolicy.STRATEGY_UNMANAGED;
    }

    static int resolveBackgroundRestriction(String packageName, LegacySnapshot legacy) {
        if (legacy.manualRestrictedApps.contains(packageName)) {
            return AppPolicy.RESTRICTION_MANUAL;
        }
        if (legacy.hardRestrictedApps.contains(packageName)) {
            return AppPolicy.RESTRICTION_HARD;
        }
        if (legacy.mediumRestrictedApps.contains(packageName)) {
            return AppPolicy.RESTRICTION_MEDIUM;
        }
        if (legacy.backgroundRestrictedApps.contains(packageName)) {
            return AppPolicy.RESTRICTION_SOFT;
        }
        return AppPolicy.RESTRICTION_NONE;
    }

    static long resolveTriggerMask(int strategy, LegacySnapshot legacy) {
        if (strategy == AppPolicy.STRATEGY_SMART) {
            return legacy.smartBootCleanup ? AppPolicy.TRIGGER_BOOT_CLEANUP : 0L;
        }
        if (strategy == AppPolicy.STRATEGY_IMMEDIATE) {
            return legacy.immediateTriggerMask;
        }
        return 0L;
    }

    static String fingerprintSnapshot(LegacySnapshot snapshot) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "autoKill", snapshot.autoKillEnabled);
        append(canonical, "activePreset", snapshot.activePresetNumber);
        append(canonical, "smart", snapshot.smartLifecycleEnabled);
        append(canonical, "sleep", snapshot.sleepModeEnabled);
        append(canonical, "whitelistMode", snapshot.whitelistMode);
        append(canonical, "killMethod", snapshot.killMethod);
        append(canonical, "standby", snapshot.smartStandbyDelayMs);
        append(canonical, "forceStop", snapshot.smartForceStopDelayMs);
        append(canonical, "bootCleanup", snapshot.smartBootCleanup);
        append(canonical, "triggers", snapshot.immediateTriggerMask);
        appendSet(canonical, "hidden", snapshot.hiddenApps);
        appendSet(canonical, "whitelist", snapshot.whitelistedApps);
        appendSet(canonical, "blacklist", snapshot.blacklistedApps);
        appendSet(canonical, "sleepTimer", snapshot.sleepTimerApps);
        appendSet(canonical, "sleepPermanent", snapshot.sleepPermanentApps);
        appendSet(canonical, "sleepFrozen", snapshot.sleepFrozenTimerApps);
        appendSet(canonical, "restrictionSoft", snapshot.backgroundRestrictedApps);
        appendSet(canonical, "restrictionMedium", snapshot.mediumRestrictedApps);
        appendSet(canonical, "restrictionHard", snapshot.hardRestrictedApps);
        appendSet(canonical, "restrictionManual", snapshot.manualRestrictedApps);

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void append(StringBuilder target, String key, Object value) {
        target.append(key).append('=').append(value).append('\n');
    }

    private static void appendSet(StringBuilder target, String key, Set<String> values) {
        target.append(key).append('=');
        for (String value : new TreeSet<>(safeSet(values))) {
            target.append(value.length()).append(':').append(value).append(';');
        }
        target.append('\n');
    }

    static LegacySnapshot readSnapshot(SharedPreferences prefs) {
        LegacySnapshot snapshot = new LegacySnapshot();
        snapshot.activePresetNumber = prefs.getInt(KEY_ACTIVE_PRESET, 0);
        snapshot.autoKillEnabled = prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false)
                || snapshot.activePresetNumber != 0;
        snapshot.smartLifecycleEnabled = prefs.getBoolean(KEY_SMART_LIFECYCLE_ENABLED, false);
        snapshot.sleepModeEnabled = prefs.getBoolean(KEY_SLEEP_MODE_ENABLED, false);
        snapshot.whitelistMode = prefs.getInt(KEY_KILL_MODE, AppConstants.DEFAULT_KILL_MODE) == 0;
        snapshot.killMethod = prefs.getInt(KEY_AUTO_KILL_TYPE, 0) == 1
                ? AppPolicy.KILL_METHOD_AM_KILL
                : AppPolicy.KILL_METHOD_FORCE_STOP;
        snapshot.smartStandbyDelayMs =
                SmartLifecycleManager.getStandbyDelayMinutes(prefs) * MINUTE_MS;
        snapshot.smartForceStopDelayMs =
                SmartLifecycleManager.getForceStopDelayMinutes(prefs) * MINUTE_MS;
        snapshot.smartBootCleanup = prefs.getBoolean(KEY_SMART_BOOT_CLEANUP_ENABLED, true);

        long immediateTriggers = 0L;
        if (prefs.getBoolean(KEY_PERIODIC_KILL_ENABLED, false)) {
            immediateTriggers |= AppPolicy.TRIGGER_PERIODIC;
        }
        if (prefs.getBoolean(KEY_KILL_ON_SCREEN_OFF, false)) {
            immediateTriggers |= AppPolicy.TRIGGER_SCREEN_OFF;
        }
        if (prefs.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false)) {
            immediateTriggers |= AppPolicy.TRIGGER_RAM_THRESHOLD;
        }
        if (hasHardwareTrigger(prefs)) {
            immediateTriggers |= AppPolicy.TRIGGER_HARDWARE_EVENT;
        }
        if (prefs.getBoolean(KEY_APP_LAUNCH_TRIGGER_ENABLED, false)) {
            immediateTriggers |= AppPolicy.TRIGGER_APP_LAUNCH;
        }
        snapshot.immediateTriggerMask = immediateTriggers;

        snapshot.hiddenApps = readSet(prefs, KEY_HIDDEN_APPS);
        snapshot.whitelistedApps = readSet(prefs, KEY_WHITELISTED_APPS);
        snapshot.blacklistedApps = readSet(prefs, KEY_BLACKLISTED_APPS);
        snapshot.sleepTimerApps = readSet(prefs, KEY_SLEEP_MODE_APPS);
        snapshot.sleepPermanentApps = readSet(prefs, KEY_SLEEP_MODE_APPS_PERMANENT);
        snapshot.sleepFrozenTimerApps = readSet(prefs, KEY_SLEEP_MODE_APPS_FROZEN);
        snapshot.backgroundRestrictedApps = readSet(prefs, KEY_AUTOSTART_DISABLED_APPS);
        snapshot.mediumRestrictedApps = readSet(prefs, KEY_MEDIUM_RESTRICTION_APPS);
        snapshot.hardRestrictedApps = readSet(prefs, KEY_HARD_RESTRICTION_APPS);
        snapshot.manualRestrictedApps = readSet(prefs, KEY_MANUAL_RESTRICTION_APPS);
        return snapshot;
    }

    private static boolean hasHardwareTrigger(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_HW_TRIGGER_HEADSET, false)
                || prefs.getBoolean(KEY_HW_TRIGGER_USB, false)
                || prefs.getBoolean(KEY_HW_TRIGGER_CHARGER, false)
                || prefs.getBoolean(KEY_HW_TRIGGER_WIFI, false)
                || prefs.getBoolean(KEY_HW_TRIGGER_BLUETOOTH, false)
                || prefs.getBoolean(KEY_HW_TRIGGER_GPS, false)
                || prefs.getBoolean(KEY_HW_TRIGGER_HOTSPOT, false);
    }

    private static Set<String> readSet(SharedPreferences prefs, String key) {
        return new HashSet<>(prefs.getStringSet(key, Collections.emptySet()));
    }

    private static Set<String> safeSet(Set<String> source) {
        return source == null ? Collections.emptySet() : source;
    }

    private static PackageInventory collectPackageInventory(Context context) {
        Set<String> eligible = new HashSet<>();
        Set<String> failSafe = new HashSet<>();
        PackageManager packageManager = context.getPackageManager();

        List<ApplicationInfo> apps =
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo info : apps) {
            if (info == null || !PackageNameValidator.isValid(info.packageName)) continue;
            if (context.getPackageName().equals(info.packageName)) continue;

            boolean persistent = (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
            boolean protectedPackage = ProtectedApps.isProtected(context, info.packageName);
            if (persistent || protectedPackage) {
                failSafe.add(info.packageName);
            } else {
                eligible.add(info.packageName);
            }
        }
        return new PackageInventory(eligible, failSafe);
    }

    static final class LegacySnapshot {
        boolean autoKillEnabled;
        int activePresetNumber;
        boolean smartLifecycleEnabled;
        boolean sleepModeEnabled;
        boolean whitelistMode;
        int killMethod = AppPolicy.KILL_METHOD_FORCE_STOP;
        long smartStandbyDelayMs = AppPolicy.DEFAULT_SMART_STANDBY_DELAY_MS;
        long smartForceStopDelayMs = AppPolicy.DEFAULT_SMART_FORCE_STOP_DELAY_MS;
        boolean smartBootCleanup = true;
        long immediateTriggerMask;

        Set<String> hiddenApps = Collections.emptySet();
        Set<String> whitelistedApps = Collections.emptySet();
        Set<String> blacklistedApps = Collections.emptySet();
        Set<String> sleepTimerApps = Collections.emptySet();
        Set<String> sleepPermanentApps = Collections.emptySet();
        Set<String> sleepFrozenTimerApps = Collections.emptySet();
        Set<String> backgroundRestrictedApps = Collections.emptySet();
        Set<String> mediumRestrictedApps = Collections.emptySet();
        Set<String> hardRestrictedApps = Collections.emptySet();
        Set<String> manualRestrictedApps = Collections.emptySet();
    }

    private static final class PackageInventory {
        final Set<String> eligibleInstalledPackages;
        final Set<String> failSafePackages;

        PackageInventory(Set<String> eligibleInstalledPackages, Set<String> failSafePackages) {
            this.eligibleInstalledPackages = eligibleInstalledPackages;
            this.failSafePackages = failSafePackages;
        }
    }
}