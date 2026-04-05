package com.gree1d.reappzuku;

/**
 * Centralized preference keys to ensure consistency across the app.
 */
public final class PreferenceKeys {
    private PreferenceKeys() {
        // Prevent instantiation
    }

    public static final String PREFERENCES_NAME = "AppPreferences";

    // App Lists
    public static final String KEY_HIDDEN_APPS = "hidden_apps";
    public static final String KEY_WHITELISTED_APPS = "whitelisted_apps";
    public static final String KEY_BLACKLISTED_APPS = "blacklisted_apps";
    public static final String KEY_AUTOSTART_DISABLED_APPS = "autostart_disabled_apps";

    // Background Restriction type: packages in this set use HARD restriction (START_FOREGROUND deny)
    // Packages NOT in this set but in KEY_AUTOSTART_DISABLED_APPS use SOFT restriction (RUN_ANY_IN_BACKGROUND ignore)
    public static final String KEY_HARD_RESTRICTION_APPS = "hard_restriction_apps";

    // Kill Mode
    public static final String KEY_KILL_MODE = "kill_mode"; // 0 = Whitelist (default), 1 = Blacklist

    // Service & Automation
    public static final String KEY_AUTO_KILL_ENABLED = "autoKillEnabled";
    public static final String KEY_PERIODIC_KILL_ENABLED = "periodicKillEnabled";
    public static final String KEY_KILL_INTERVAL = "killInterval";
    public static final String KEY_KILL_ON_SCREEN_OFF = "killOnScreenOff";

    // RAM Threshold
    public static final String KEY_RAM_THRESHOLD = "ramThreshold";
    public static final String KEY_RAM_THRESHOLD_ENABLED = "ramThresholdEnabled";

    // Display Settings
    public static final String KEY_SHOW_SYSTEM_APPS = "showSystemApps";
    public static final String KEY_SHOW_PERSISTENT_APPS = "showPersistentApps";
    public static final String KEY_THEME = "appTheme";
    public static final String KEY_ACCENT = "appAccent";
    public static final String KEY_AMOLED = "appAmoled";
    public static final String KEY_SORT_MODE = "sort_mode";
    public static final String KEY_REPLACEMENT_NOTICE_SHOWN_VERSION = "replacement_notice_shown_version";

    // Sleep Mode
    public static final String KEY_SLEEP_MODE_ENABLED = "sleepModeEnabled";
    public static final String KEY_SLEEP_MODE_APPS = "sleepModeApps";
    public static final String KEY_SLEEP_MODE_DELAY = "sleepModeDelay";
}
