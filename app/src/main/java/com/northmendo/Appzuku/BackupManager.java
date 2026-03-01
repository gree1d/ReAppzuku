package com.northmendo.Appzuku;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

import static com.northmendo.Appzuku.PreferenceKeys.*;

public class BackupManager {
    private static final String KEY_BACKUP_VERSION = "backup_version";
    private static final int BACKUP_VERSION = 2;
    private static final String KEY_SYSTEM_APPS_WARNING_SHOWN = "system_apps_warning_shown";

    private final SharedPreferences prefs;

    public BackupManager(Context context) {
        this.prefs = context.getSharedPreferences(PreferenceKeys.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String createBackupJson() {
        try {
            JSONObject root = new JSONObject();
            root.put(KEY_BACKUP_VERSION, BACKUP_VERSION);

            putStringSet(root, KEY_HIDDEN_APPS);
            putStringSet(root, KEY_WHITELISTED_APPS);
            putStringSet(root, KEY_BLACKLISTED_APPS);
            putStringSet(root, KEY_AUTOSTART_DISABLED_APPS);

            root.put(KEY_KILL_MODE, prefs.getInt(KEY_KILL_MODE, 0));
            root.put(KEY_AUTO_KILL_ENABLED, prefs.getBoolean(KEY_AUTO_KILL_ENABLED, false));
            root.put(KEY_PERIODIC_KILL_ENABLED, prefs.getBoolean(KEY_PERIODIC_KILL_ENABLED, false));
            root.put(KEY_KILL_INTERVAL, prefs.getInt(KEY_KILL_INTERVAL, AppConstants.DEFAULT_KILL_INTERVAL_MS));
            root.put(KEY_KILL_ON_SCREEN_OFF, prefs.getBoolean(KEY_KILL_ON_SCREEN_OFF, false));
            root.put(KEY_RAM_THRESHOLD, prefs.getInt(KEY_RAM_THRESHOLD, AppConstants.DEFAULT_RAM_THRESHOLD_PERCENT));
            root.put(KEY_RAM_THRESHOLD_ENABLED, prefs.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false));
            root.put(KEY_SHOW_SYSTEM_APPS, prefs.getBoolean(KEY_SHOW_SYSTEM_APPS, false));
            root.put(KEY_SHOW_PERSISTENT_APPS, prefs.getBoolean(KEY_SHOW_PERSISTENT_APPS, false));
            root.put(KEY_THEME, prefs.getInt(KEY_THEME,
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
            root.put(KEY_SORT_MODE, prefs.getInt(KEY_SORT_MODE, AppConstants.SORT_MODE_DEFAULT));
            root.put(KEY_SYSTEM_APPS_WARNING_SHOWN, prefs.getBoolean(KEY_SYSTEM_APPS_WARNING_SHOWN, false));
            return root.toString(4); // Pretty print with 4 spaces
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean restoreBackupJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            SharedPreferences.Editor editor = prefs.edit();

            restoreSet(editor, root, KEY_HIDDEN_APPS);
            restoreSet(editor, root, KEY_WHITELISTED_APPS);
            restoreSet(editor, root, KEY_BLACKLISTED_APPS);
            restoreSet(editor, root, KEY_AUTOSTART_DISABLED_APPS);
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
            restoreInt(editor, root, KEY_SORT_MODE);
            restoreBoolean(editor, root, KEY_SYSTEM_APPS_WARNING_SHOWN);

            editor.apply();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void restoreSet(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            JSONArray array = root.getJSONArray(key);
            Set<String> set = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                set.add(array.getString(i));
            }
            editor.putStringSet(key, set);
        }
    }

    private void putStringSet(JSONObject root, String key) throws Exception {
        Set<String> stored = prefs.getStringSet(key, new HashSet<>());
        Set<String> set = stored == null ? new HashSet<>() : new HashSet<>(stored);
        root.put(key, new JSONArray(set));
    }

    private void restoreBoolean(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            editor.putBoolean(key, root.getBoolean(key));
        }
    }

    private void restoreInt(SharedPreferences.Editor editor, JSONObject root, String key) throws Exception {
        if (root.has(key)) {
            editor.putInt(key, root.getInt(key));
        }
    }
}
