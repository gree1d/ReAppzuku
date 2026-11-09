package com.gree1d.reappzuku.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.AppPolicy;
import com.gree1d.reappzuku.db.PolicyPreset;
import com.gree1d.reappzuku.manager.BackgroundAppManager;
import com.gree1d.reappzuku.manager.PresetManager;
import com.gree1d.reappzuku.service.SmartLifecycleWorker;
import com.gree1d.reappzuku.utils.PresetModel;
import static com.gree1d.reappzuku.core.PreferenceKeys.*;

public class BackupManager {
    private static final String KEY_MANUAL_OPS_MASKS = "manual_ops_masks";
    private static final String KEY_MANUAL_BUCKETS = "manual_buckets";
    private static final String KEY_MANUAL_WHITELIST_REMOVALS = "manual_whitelist_removals";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_PRESET_PREFIX = "preset_";

    private final Context context;
    private final SharedPreferences prefs;
    private final BackupCodec backupCodec;
    private final RestoreFaultInjector restoreFaultInjector;

    enum RestoreCommitPoint {
        AFTER_MAIN_COMMIT,
        AFTER_PRESET_1_COMMIT,
        AFTER_PRESET_2_COMMIT,
        AFTER_PHASE9_DB_COMMIT
    }

    interface RestoreFaultInjector {
        RestoreFaultInjector NONE = point -> { };

        void afterCommit(RestoreCommitPoint point);
    }

    public BackupManager(Context context) {
        this(context, new BackupCodec(), RestoreFaultInjector.NONE);
    }

    BackupManager(Context context, BackupCodec backupCodec) {
        this(context, backupCodec, RestoreFaultInjector.NONE);
    }

    BackupManager(Context context, BackupCodec backupCodec, RestoreFaultInjector restoreFaultInjector) {
        if (backupCodec == null) throw new IllegalArgumentException("backupCodec == null");
        if (restoreFaultInjector == null) throw new IllegalArgumentException("restoreFaultInjector == null");
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PreferenceKeys.PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.backupCodec = backupCodec;
        this.restoreFaultInjector = restoreFaultInjector;
    }

    private boolean getSafeBool(String key, boolean defVal) {
        try {
            return prefs.getBoolean(key, defVal);
        } catch (ClassCastException e) {

            String raw = prefs.getString(key, null);
            if (raw == null) return defVal;
            return Boolean.parseBoolean(raw);
        }
    }

    public String createBackupJson() {

        try {
            JSONObject root = backupCodec.newRoot();


            putStringSet(root, KEY_HIDDEN_APPS);
            putStringSet(root, KEY_WHITELISTED_APPS);
            putStringSet(root, KEY_BLACKLISTED_APPS);
            putStringSet(root, KEY_AUTOSTART_DISABLED_APPS);
            putStringSet(root, KEY_HARD_RESTRICTION_APPS);
            putStringSet(root, KEY_MANUAL_RESTRICTION_APPS);


            putManualRestrictionDetails(root);


            putStringSet(root, KEY_SLEEP_MODE_APPS);
            putStringSet(root, KEY_SLEEP_MODE_APPS_PERMANENT);
            putStringSet(root, KEY_MEDIUM_RESTRICTION_APPS);
            putStringSet(root, KEY_BATTERY_WHITELIST_REMOVED);
            putStringSet(root, KEY_APP_LAUNCH_TRIGGER_PACKAGES);


            root.put(KEY_KILL_MODE, prefs.getInt(KEY_KILL_MODE, AppConstants.DEFAULT_KILL_MODE));
            root.put(KEY_AUTO_KILL_ENABLED, getSafeBool(KEY_AUTO_KILL_ENABLED, false));
            root.put(KEY_PERIODIC_KILL_ENABLED, getSafeBool(KEY_PERIODIC_KILL_ENABLED, false));
            root.put(KEY_KILL_INTERVAL, prefs.getInt(KEY_KILL_INTERVAL, AppConstants.DEFAULT_KILL_INTERVAL_MS));
            root.put(KEY_KILL_ON_SCREEN_OFF, getSafeBool(KEY_KILL_ON_SCREEN_OFF, false));


            root.put(KEY_RAM_THRESHOLD, prefs.getInt(KEY_RAM_THRESHOLD, AppConstants.DEFAULT_RAM_THRESHOLD_PERCENT));
            root.put(KEY_RAM_THRESHOLD_ENABLED, getSafeBool(KEY_RAM_THRESHOLD_ENABLED, false));


            root.put(KEY_SHOW_SYSTEM_APPS, getSafeBool(KEY_SHOW_SYSTEM_APPS, false));
            root.put(KEY_SHOW_PERSISTENT_APPS, getSafeBool(KEY_SHOW_PERSISTENT_APPS, false));
            root.put(KEY_THEME, prefs.getInt(KEY_THEME, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
            root.put(KEY_ACCENT, prefs.getInt(KEY_ACCENT, AppConstants.ACCENT_SYSTEM));
            root.put(KEY_ACCENT_CUSTOM_COLOR, prefs.getInt(KEY_ACCENT_CUSTOM_COLOR, AppConstants.ACCENT_CUSTOM_DEFAULT_COLOR));
            root.put(KEY_ACCENT_ON_COLOR, prefs.getInt(KEY_ACCENT_ON_COLOR, AppConstants.ACCENT_ON_WHITE));
            root.put(KEY_AMOLED, getSafeBool(KEY_AMOLED, false));
            root.put(KEY_SORT_MODE, prefs.getInt(KEY_SORT_MODE, AppConstants.SORT_MODE_DEFAULT));
            root.put(KEY_NOTIFICATION_MODE, prefs.getInt(KEY_NOTIFICATION_MODE, NOTIFICATION_MODE_ALL));
            root.put(KEY_AUTO_KILL_TYPE, prefs.getInt(KEY_AUTO_KILL_TYPE, 0));


            root.put(KEY_SLEEP_MODE_ENABLED, getSafeBool(KEY_SLEEP_MODE_ENABLED, false));
            root.put(KEY_SLEEP_MODE_DELAY, prefs.getLong(KEY_SLEEP_MODE_DELAY, AppConstants.DEFAULT_SLEEP_MODE_DELAY_MS));


            root.put(KEY_EXIT_ON_BACK, getSafeBool(KEY_EXIT_ON_BACK, false));
            root.put(KEY_PREVENT_SHIZUKU_AUTOSTART, getSafeBool(KEY_PREVENT_SHIZUKU_AUTOSTART, true));
            root.put(KEY_SMART_LIFECYCLE_ENABLED, getSafeBool(KEY_SMART_LIFECYCLE_ENABLED, false));
            root.put(KEY_SMART_BOOT_CLEANUP_ENABLED, getSafeBool(KEY_SMART_BOOT_CLEANUP_ENABLED, true));
            root.put(KEY_SMART_LIFECYCLE_PROFILE, prefs.getInt(KEY_SMART_LIFECYCLE_PROFILE, 1));


            root.put(KEY_HW_TRIGGER_HEADSET, getSafeBool(KEY_HW_TRIGGER_HEADSET, false));
            root.put(KEY_HW_TRIGGER_USB, getSafeBool(KEY_HW_TRIGGER_USB, false));
            root.put(KEY_HW_TRIGGER_CHARGER, getSafeBool(KEY_HW_TRIGGER_CHARGER, false));
            root.put(KEY_HW_TRIGGER_WIFI, getSafeBool(KEY_HW_TRIGGER_WIFI, false));
            root.put(KEY_HW_TRIGGER_BLUETOOTH, getSafeBool(KEY_HW_TRIGGER_BLUETOOTH, false));
            root.put(KEY_HW_TRIGGER_GPS, getSafeBool(KEY_HW_TRIGGER_GPS, false));
            root.put(KEY_HW_TRIGGER_HOTSPOT, getSafeBool(KEY_HW_TRIGGER_HOTSPOT, false));
            root.put(KEY_APP_LAUNCH_TRIGGER_ENABLED, getSafeBool(KEY_APP_LAUNCH_TRIGGER_ENABLED, false));
            root.put(KEY_APP_LAUNCH_CLEAR_CACHE, getSafeBool(KEY_APP_LAUNCH_CLEAR_CACHE, false));


            putPresets(root);
            putPhase9(root);


            String result = backupCodec.encode(root);

            return result;
        } catch (Exception e) {

            return null;
        }
    }

    public boolean restoreBackupJson(String json) {

        BackupCodec.DecodedBackup decoded;
        try {
            decoded = backupCodec.decode(json);
        } catch (BackupCodec.DecodeException ignored) {
            return false;
        }

        Map<String, ?> mainSnapshot = null;
        Map<String, ?> preset1Snapshot = null;
        Map<String, ?> preset2Snapshot = null;
        List<AppPolicy> appPolicySnapshot = null;
        List<PolicyPreset> policyPresetSnapshot = null;
        PresetManager presetManager = new PresetManager(context);
        AppDatabase database = AppDatabase.getInstance(context);
        boolean durableWriteStarted = false;
        try {
            JSONObject root = decoded.root;
            // Validate all imported JSON before the first durable write.
            PresetModel[] restoredPresets = parsePresets(root);
            boolean containsPresetSection = root.has(KEY_PRESETS);
            Phase9BackupCodec.Snapshot restoredPhase9 =
                    Phase9BackupCodec.parse(root, decoded.version);

            // Snapshot every durable store participating in the transaction.
            mainSnapshot = deepCopyPreferenceMap(prefs.getAll());
            preset1Snapshot = presetManager.snapshotPresetStorage(PresetModel.PRESET_1);
            preset2Snapshot = presetManager.snapshotPresetStorage(PresetModel.PRESET_2);
            if (restoredPhase9 != null) {
                appPolicySnapshot = new ArrayList<>(database.appPolicyDao().getAll());
                policyPresetSnapshot = new ArrayList<>(database.policyPresetDao().getAll());
            }

            SharedPreferences.Editor editor = prefs.edit();
            restoreSet(editor, root, KEY_HIDDEN_APPS);
            restoreSet(editor, root, KEY_WHITELISTED_APPS);
            restoreSet(editor, root, KEY_BLACKLISTED_APPS);
            restoreSet(editor, root, KEY_AUTOSTART_DISABLED_APPS);
            restoreSet(editor, root, KEY_HARD_RESTRICTION_APPS);
            restoreSet(editor, root, KEY_MANUAL_RESTRICTION_APPS);
            restoreManualRestrictionDetails(editor, root, decoded.version);
            restoreSet(editor, root, KEY_SLEEP_MODE_APPS);
            restoreSet(editor, root, KEY_SLEEP_MODE_APPS_PERMANENT);
            restoreSet(editor, root, KEY_MEDIUM_RESTRICTION_APPS);
            restoreSet(editor, root, KEY_BATTERY_WHITELIST_REMOVED);
            restoreSet(editor, root, KEY_APP_LAUNCH_TRIGGER_PACKAGES);

            restoreInt(editor, root, KEY_KILL_MODE);
            restoreBoolean(editor, root, KEY_AUTO_KILL_ENABLED);
            restoreBoolean(editor, root, KEY_PERIODIC_KILL_ENABLED);
            restoreInt(editor, root, KEY_KILL_INTERVAL);
            restoreBoolean(editor, root, KEY_KILL_ON_SCREEN_OFF);
            restoreInt(editor, root, KEY_RAM_THRESHOLD);
            restoreBoolean(editor, root, KEY_RAM_THRESHOLD_ENABLED);

            restoreBoolean(editor, root, KEY_SHOW_SYSTEM_APPS);
            restoreBoolean(editor, root, KEY_SHOW_PERSISTENT_APPS);
            restoreInt(editor, root, KEY_THEME);
            restoreInt(editor, root, KEY_ACCENT);
            restoreInt(editor, root, KEY_ACCENT_CUSTOM_COLOR);
            restoreInt(editor, root, KEY_ACCENT_ON_COLOR);
            restoreBoolean(editor, root, KEY_AMOLED);
            restoreInt(editor, root, KEY_SORT_MODE);
            restoreInt(editor, root, KEY_NOTIFICATION_MODE);
            restoreInt(editor, root, KEY_AUTO_KILL_TYPE);

            restoreBoolean(editor, root, KEY_SLEEP_MODE_ENABLED);
            restoreLong(editor, root, KEY_SLEEP_MODE_DELAY);
            restoreBoolean(editor, root, KEY_EXIT_ON_BACK);
            restoreBoolean(editor, root, KEY_PREVENT_SHIZUKU_AUTOSTART);
            restoreBoolean(editor, root, KEY_SMART_LIFECYCLE_ENABLED);
            restoreBoolean(editor, root, KEY_SMART_BOOT_CLEANUP_ENABLED);
            restoreInt(editor, root, KEY_SMART_LIFECYCLE_PROFILE);

            restoreBoolean(editor, root, KEY_HW_TRIGGER_HEADSET);
            restoreBoolean(editor, root, KEY_HW_TRIGGER_USB);
            restoreBoolean(editor, root, KEY_HW_TRIGGER_CHARGER);
            restoreBoolean(editor, root, KEY_HW_TRIGGER_WIFI);
            restoreBoolean(editor, root, KEY_HW_TRIGGER_BLUETOOTH);
            restoreBoolean(editor, root, KEY_HW_TRIGGER_GPS);
            restoreBoolean(editor, root, KEY_HW_TRIGGER_HOTSPOT);
            restoreBoolean(editor, root, KEY_APP_LAUNCH_TRIGGER_ENABLED);
            restoreBoolean(editor, root, KEY_APP_LAUNCH_CLEAR_CACHE);

            if (containsPresetSection) {
                // Active preset state is runtime state, not portable configuration.
                // The imported main settings become the new base state.
                editor.remove(KEY_ACTIVE_PRESET);
                for (String key : prefs.getAll().keySet()) {
                    if (key.startsWith(PresetManager.KEY_BACKUP_PREFIX)) editor.remove(key);
                }
            }

            if (restoredPhase9 != null) {
                editor.putInt(KEY_NEW_APP_SETUP_MODE, restoredPhase9.newAppSetupMode);
                editor.putLong(KEY_NEW_APP_DEFAULT_PRESET_ID, restoredPhase9.defaultPresetId);
                editor.putStringSet(KEY_NEW_APP_SETUP_QUEUE,
                        new HashSet<>(restoredPhase9.pendingSetup));
                AppPolicyLegacyMigrator.invalidateMigration(editor);
            }

            durableWriteStarted = true;
            if (!editor.commit()) throw new IllegalStateException("main preferences commit failed");
            restoreFaultInjector.afterCommit(RestoreCommitPoint.AFTER_MAIN_COMMIT);

            if (containsPresetSection) {
                if (!writePresetStorage(presetManager, PresetModel.PRESET_1, restoredPresets[0]))
                    throw new IllegalStateException("preset 1 commit failed");
                restoreFaultInjector.afterCommit(RestoreCommitPoint.AFTER_PRESET_1_COMMIT);
                if (!writePresetStorage(presetManager, PresetModel.PRESET_2, restoredPresets[1]))
                    throw new IllegalStateException("preset 2 commit failed");
                restoreFaultInjector.afterCommit(RestoreCommitPoint.AFTER_PRESET_2_COMMIT);
            }

            if (restoredPhase9 != null) {
                replacePhase9Database(database, restoredPhase9);
                restoreFaultInjector.afterCommit(RestoreCommitPoint.AFTER_PHASE9_DB_COMMIT);
            }

            // Side effects only after all durable state was committed successfully.
            if (containsPresetSection) presetManager.restoreAfterBoot();
            if (restoredPhase9 != null && !AppPolicyLegacyMigrator.migrateIfNeeded(context)) {
                throw new IllegalStateException("Phase 9 legacy reconciliation failed");
            }
            BackgroundWorkPolicy.enforceCompatibleBehavior(context);
            SmartLifecycleWorker.reconcilePeriodic(context);

            return true;
        } catch (Exception e) {

            if (durableWriteStarted && mainSnapshot != null) {
                rollbackRestore(
                        presetManager,
                        mainSnapshot,
                        preset1Snapshot,
                        preset2Snapshot,
                        appPolicySnapshot,
                        policyPresetSnapshot);
            }
            return false;
        }
    }

    private void restoreSet(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            JSONArray array = root.getJSONArray(key);
            BackupCollectionPolicy.requirePackageEntryCount(key, array.length());
            Set<String> set = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                String packageName = array.getString(i);
                if (!PackageNameValidator.isValid(packageName)) {
                    throw new IllegalArgumentException("Invalid package name in backup: " + key);
                }
                set.add(packageName);
            }
            editor.putStringSet(key, set);

        }
    }

    private void putStringSet(JSONObject root, String key) throws Exception {
        Set<String> stored = prefs.getStringSet(key, new HashSet<>());
        Set<String> set = stored == null ? new HashSet<>() : new HashSet<>(stored);
        BackupCollectionPolicy.requirePackageEntryCount(key, set.size());
        root.put(key, new JSONArray(set));

    }

    private void putManualRestrictionDetails(JSONObject root) throws Exception {
        Set<String> stored = prefs.getStringSet(KEY_MANUAL_RESTRICTION_APPS, new HashSet<>());
        Set<String> manualPackages = stored == null ? new HashSet<>() : new HashSet<>(stored);
        BackupCollectionPolicy.requirePackageEntryCount(KEY_MANUAL_OPS_MASKS, manualPackages.size());

        JSONObject masks = new JSONObject();
        JSONObject buckets = new JSONObject();
        JSONObject whitelistRemovals = new JSONObject();
        for (String pkg : manualPackages) {
            if (!PackageNameValidator.isValid(pkg)) {
                throw new IllegalArgumentException("Invalid package name in manual restriction preferences");
            }
            int storedMask = prefs.getInt(KEY_MANUAL_OPS_PREFIX + pkg, 0x01);
            int mask = ManualOpsMaskPolicy.sanitize(storedMask, BackgroundAppManager.ALL_OPS.length);
            int bucket = ManualRestrictionBackupPolicy.requireBucket(
                    prefs.getInt(KEY_MANUAL_BUCKET_PREFIX + pkg, ManualRestrictionBackupPolicy.BUCKET_NONE));
            boolean whitelistRemoval = prefs.getBoolean(KEY_MANUAL_WHITELIST_REMOVAL_PREFIX + pkg, false);
            masks.put(pkg, mask);
            buckets.put(pkg, bucket);
            whitelistRemovals.put(pkg, whitelistRemoval);
        }
        root.put(KEY_MANUAL_OPS_MASKS, masks);
        root.put(KEY_MANUAL_BUCKETS, buckets);
        root.put(KEY_MANUAL_WHITELIST_REMOVALS, whitelistRemovals);

    }

    private void restoreManualRestrictionDetails(SharedPreferences.Editor editor,
                                                 JSONObject root,
                                                 int backupVersion) throws Exception {
        boolean hasManualApps = root.has(KEY_MANUAL_RESTRICTION_APPS);
        boolean hasOps = root.has(KEY_MANUAL_OPS_MASKS);
        boolean hasBuckets = root.has(KEY_MANUAL_BUCKETS);
        boolean hasWhitelist = root.has(KEY_MANUAL_WHITELIST_REMOVALS);
        if (!hasManualApps && !hasOps && !hasBuckets && !hasWhitelist) return;
        if (!hasManualApps) {
            throw new IllegalArgumentException("Manual restriction detail maps require manual_restriction_apps");
        }

        Set<String> manualPackages = readPackageArray(root, KEY_MANUAL_RESTRICTION_APPS);
        if (backupVersion >= 6 && (!hasOps || !hasBuckets || !hasWhitelist)) {
            throw new IllegalArgumentException("Backup v6 requires all manual restriction detail maps");
        }

        JSONObject masks = hasOps
                ? requireExactManualDetailMap(root, KEY_MANUAL_OPS_MASKS, manualPackages)
                : null;
        JSONObject buckets = hasBuckets
                ? requireExactManualDetailMap(root, KEY_MANUAL_BUCKETS, manualPackages)
                : null;
        JSONObject whitelistRemovals = hasWhitelist
                ? requireExactManualDetailMap(root, KEY_MANUAL_WHITELIST_REMOVALS, manualPackages)
                : null;

        // Validate every imported value before mutating the pending editor transaction.
        if (masks != null) {
            for (String pkg : manualPackages) {
                ManualOpsMaskPolicy.requireKnownBits(
                        requireJsonInt(masks, pkg), BackgroundAppManager.ALL_OPS.length);
            }
        }
        if (buckets != null) {
            for (String pkg : manualPackages) {
                ManualRestrictionBackupPolicy.requireBucket(requireJsonInt(buckets, pkg));
            }
        }
        if (whitelistRemovals != null) {
            for (String pkg : manualPackages) {
                requireJsonBoolean(whitelistRemovals, pkg);
            }
        }

        // Snapshot semantics: once a manual-app section is present, all old per-package details
        // are replaced. V5/legacy backups lacked bucket/whitelist maps, so their defaults are
        // restored instead of leaking device-local stale values into the imported state.
        clearPreferencePrefix(editor, KEY_MANUAL_OPS_PREFIX);
        clearPreferencePrefix(editor, KEY_MANUAL_BUCKET_PREFIX);
        clearPreferencePrefix(editor, KEY_MANUAL_WHITELIST_REMOVAL_PREFIX);

        for (String pkg : manualPackages) {
            if (masks != null) {
                editor.putInt(KEY_MANUAL_OPS_PREFIX + pkg, requireJsonInt(masks, pkg));
            }
            if (buckets != null) {
                editor.putInt(KEY_MANUAL_BUCKET_PREFIX + pkg, requireJsonInt(buckets, pkg));
            }
            if (whitelistRemovals != null) {
                editor.putBoolean(KEY_MANUAL_WHITELIST_REMOVAL_PREFIX + pkg,
                        requireJsonBoolean(whitelistRemovals, pkg));
            }
        }

    }

    private Set<String> readPackageArray(JSONObject root, String key) throws Exception {
        JSONArray array = root.getJSONArray(key);
        BackupCollectionPolicy.requirePackageEntryCount(key, array.length());
        Set<String> result = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String packageName = array.getString(i);
            if (!PackageNameValidator.isValid(packageName) || !result.add(packageName)) {
                throw new IllegalArgumentException("Invalid or duplicate package name in backup: " + key);
            }
        }
        return result;
    }

    private JSONObject requireExactManualDetailMap(JSONObject root,
                                                   String key,
                                                   Set<String> manualPackages) throws Exception {
        JSONObject map = root.getJSONObject(key);
        BackupCollectionPolicy.requirePackageEntryCount(key, map.length());
        Set<String> keys = new HashSet<>();
        java.util.Iterator<String> iterator = map.keys();
        while (iterator.hasNext()) {
            String pkg = iterator.next();
            if (!PackageNameValidator.isValid(pkg) || !keys.add(pkg)) {
                throw new IllegalArgumentException("Invalid package name in manual restriction map: " + key);
            }
        }
        if (!keys.equals(manualPackages)) {
            throw new IllegalArgumentException("Manual restriction map does not match manual app set: " + key);
        }
        return map;
    }

    private int requireJsonInt(JSONObject object, String key) throws Exception {
        Object raw = object.get(key);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException("Expected integer for " + key);
        }
        Number number = (Number) raw;
        long value = number.longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
                || number.doubleValue() != (double) value) {
            throw new IllegalArgumentException("Integer out of range for " + key);
        }
        return (int) value;
    }

    private boolean requireJsonBoolean(JSONObject object, String key) throws Exception {
        Object raw = object.get(key);
        if (!(raw instanceof Boolean)) {
            throw new IllegalArgumentException("Expected boolean for " + key);
        }
        return (Boolean) raw;
    }

    private void clearPreferencePrefix(SharedPreferences.Editor editor, String prefix) {
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
    }

    private void putPhase9(JSONObject root) throws Exception {
        AppDatabase database = AppDatabase.getInstance(context);
        List<AppPolicy> explicitPolicies = new ArrayList<>();
        for (AppPolicy policy : database.appPolicyDao().getAll()) {
            if (policy != null && policy.source == AppPolicy.SOURCE_EXPLICIT) {
                explicitPolicies.add(policy);
            }
        }

        List<PolicyPreset> userPresets = new ArrayList<>();
        for (PolicyPreset preset : database.policyPresetDao().getAll()) {
            if (preset != null && !preset.builtIn) {
                userPresets.add(preset);
            }
        }

        Phase9BackupCodec.putPhase9(
                root,
                explicitPolicies,
                userPresets,
                NewAppSetupStore.getMode(context),
                NewAppSetupStore.getDefaultPresetId(context),
                new HashSet<>(NewAppSetupStore.getPending(context)));
    }

    private void putPresets(JSONObject root) throws Exception {
        PresetManager presetManager = new PresetManager(context);
        JSONObject presets = new JSONObject();
        for (int presetNumber : new int[]{ PresetModel.PRESET_1, PresetModel.PRESET_2 }) {
            PresetModel model = presetManager.loadPreset(presetNumber);
            if (model == null) {

                continue;
            }
            presets.put(KEY_PRESET_PREFIX + presetNumber, model.toJson());

        }
        root.put(KEY_PRESETS, presets);
    }

    private PresetModel[] parsePresets(JSONObject root) throws Exception {
        PresetModel[] result = new PresetModel[2];
        if (!root.has(KEY_PRESETS)) return result;
        JSONObject presets = root.getJSONObject(KEY_PRESETS);
        for (int presetNumber : new int[]{PresetModel.PRESET_1, PresetModel.PRESET_2}) {
            String key = KEY_PRESET_PREFIX + presetNumber;
            if (presets.has(key)) {
                result[presetNumber - 1] =
                        PresetModel.fromJson(presetNumber, presets.getJSONObject(key));
            }
        }
        return result;
    }

    private boolean writePresetStorage(PresetManager manager, int number, PresetModel model) {
        return model == null
                ? manager.clearPresetStorageBlocking(number)
                : manager.savePresetBlocking(model);
    }

    private void replacePhase9Database(
            AppDatabase database, Phase9BackupCodec.Snapshot restored) {
        PolicyPresetSeeder.seedBuiltIns(database);
        database.runInTransaction(() -> {
            database.appPolicyDao().deleteAll();
            database.policyPresetDao().deleteUserPresets();
            if (!restored.userPresets.isEmpty()) {
                database.policyPresetDao().upsertAll(restored.userPresets);
            }
            if (!restored.appPolicies.isEmpty()) {
                database.appPolicyDao().upsertAll(restored.appPolicies);
            }
        });
    }

    private boolean restorePhase9DatabaseSnapshot(
            List<AppPolicy> appPolicies, List<PolicyPreset> policyPresets) {
        if (appPolicies == null || policyPresets == null) return true;
        try {
            AppDatabase database = AppDatabase.getInstance(context);
            database.runInTransaction(() -> {
                database.appPolicyDao().deleteAll();
                database.policyPresetDao().deleteAll();
                if (!policyPresets.isEmpty()) {
                    database.policyPresetDao().upsertAll(policyPresets);
                }
                if (!appPolicies.isEmpty()) {
                    database.appPolicyDao().upsertAll(appPolicies);
                }
            });
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean rollbackRestore(PresetManager manager,
                                    Map<String, ?> mainSnapshot,
                                    Map<String, ?> preset1Snapshot,
                                    Map<String, ?> preset2Snapshot) {
        return rollbackRestore(
                manager, mainSnapshot, preset1Snapshot, preset2Snapshot, null, null);
    }

    private boolean rollbackRestore(PresetManager manager,
                                    Map<String, ?> mainSnapshot,
                                    Map<String, ?> preset1Snapshot,
                                    Map<String, ?> preset2Snapshot,
                                    List<AppPolicy> appPolicySnapshot,
                                    List<PolicyPreset> policyPresetSnapshot) {
        boolean mainOk = restorePreferenceMap(prefs, mainSnapshot);
        boolean p1Ok = preset1Snapshot != null
                && manager.restorePresetStorageBlocking(PresetModel.PRESET_1, preset1Snapshot);
        boolean p2Ok = preset2Snapshot != null
                && manager.restorePresetStorageBlocking(PresetModel.PRESET_2, preset2Snapshot);
        boolean phase9Ok = restorePhase9DatabaseSnapshot(appPolicySnapshot, policyPresetSnapshot);
        try {
            manager.restoreAfterBoot();
            BackgroundWorkPolicy.enforceCompatibleBehavior(context);
            SmartLifecycleWorker.reconcilePeriodic(context);
        } catch (Exception e) {

            return false;
        }
        return mainOk && p1Ok && p2Ok && phase9Ok;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> deepCopyPreferenceMap(Map<String, ?> source) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set) value = new HashSet<>((Set<String>) value);
            copy.put(entry.getKey(), value);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static boolean restorePreferenceMap(SharedPreferences target, Map<String, ?> values) {
        SharedPreferences.Editor editor = target.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
            else throw new IllegalArgumentException("Unsupported preference type for " + key);
        }
        return editor.commit();
    }

    private void restoreBoolean(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            boolean value = root.getBoolean(key);
            editor.putBoolean(key, value);

        }
    }

    private void restoreInt(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            int value = root.getInt(key);
            editor.putInt(key, value);

        }
    }

    private void restoreLong(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            long value = root.getLong(key);
            editor.putLong(key, value);

        }
    }
}