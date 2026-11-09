package com.gree1d.reappzuku.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.AppPolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.gree1d.reappzuku.core.PreferenceKeys.*;

/**
 * Immutable main-list snapshot of policy inputs.
 *
 * <p>Capture this off the main thread: it reads Room once, then every list row resolves from
 * in-memory state. This avoids per-row database access and keeps badges/filters consistent within
 * a single scan.</p>
 */
public final class AppPolicyListSnapshot {
    private final Map<String, AppPolicy> policies;
    private final Set<String> pendingSetup;
    private final boolean migrationSnapshotCurrent;
    private final boolean legacyAutoKillEnabled;
    private final boolean legacySmartEnabled;
    private final boolean legacyWhitelistMode;
    private final Set<String> legacyWhitelist;
    private final Set<String> legacyBlacklist;

    private AppPolicyListSnapshot(
            Map<String, AppPolicy> policies,
            Set<String> pendingSetup,
            boolean migrationSnapshotCurrent,
            boolean legacyAutoKillEnabled,
            boolean legacySmartEnabled,
            boolean legacyWhitelistMode,
            Set<String> legacyWhitelist,
            Set<String> legacyBlacklist) {
        this.policies = policies;
        this.pendingSetup = pendingSetup;
        this.migrationSnapshotCurrent = migrationSnapshotCurrent;
        this.legacyAutoKillEnabled = legacyAutoKillEnabled;
        this.legacySmartEnabled = legacySmartEnabled;
        this.legacyWhitelistMode = legacyWhitelistMode;
        this.legacyWhitelist = legacyWhitelist;
        this.legacyBlacklist = legacyBlacklist;
    }

    public static AppPolicyListSnapshot capture(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);

        Map<String, AppPolicy> policies = new HashMap<>();
        try {
            for (AppPolicy policy : AppDatabase.getInstance(appContext).appPolicyDao().getAll()) {
                if (policy != null && PackageNameValidator.isValid(policy.packageName)) {
                    policies.put(policy.packageName, policy);
                }
            }
        } catch (RuntimeException ignored) {
            // A display snapshot must never break the running-app list. Legacy state below remains
            // usable if Room is temporarily unavailable.
        }

        Set<String> pending = new HashSet<>(NewAppSetupStore.getPending(appContext));
        Set<String> whitelist = new HashSet<>(
                prefs.getStringSet(KEY_WHITELISTED_APPS, Collections.emptySet()));
        Set<String> blacklist = new HashSet<>(
                prefs.getStringSet(KEY_BLACKLISTED_APPS, Collections.emptySet()));
        boolean autoKillEnabled = prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false)
                || prefs.getInt(KEY_ACTIVE_PRESET, 0) != 0;

        return new AppPolicyListSnapshot(
                policies,
                pending,
                AppPolicyLegacyMigrator.isMigrationSnapshotCurrent(prefs),
                autoKillEnabled,
                prefs.getBoolean(KEY_SMART_LIFECYCLE_ENABLED, false),
                prefs.getInt(KEY_KILL_MODE, AppConstants.DEFAULT_KILL_MODE) == 0,
                whitelist,
                blacklist);
    }

    public int resolveStatus(String packageName, boolean failSafeProtected) {
        if (!PackageNameValidator.isValid(packageName)) {
            return AppPolicyListState.STATUS_UNMANAGED;
        }
        AppPolicyResolver.LegacyState legacyState = new AppPolicyResolver.LegacyState(
                legacyAutoKillEnabled,
                legacySmartEnabled,
                legacyWhitelistMode,
                legacyWhitelist.contains(packageName),
                legacyBlacklist.contains(packageName));

        return AppPolicyListState.resolveStatus(
                policies.get(packageName),
                migrationSnapshotCurrent,
                legacyState,
                pendingSetup.contains(packageName),
                failSafeProtected);
    }
}
