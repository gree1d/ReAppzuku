package com.gree1d.reappzuku;


public final class PreferenceKeys {
    private PreferenceKeys() {
       
    }

    public static final String PREFERENCES_NAME = "AppPreferences";

    // App Lists
    public static final String KEY_HIDDEN_APPS = "hidden_apps";
    public static final String KEY_WHITELISTED_APPS = "whitelisted_apps";
    public static final String KEY_BLACKLISTED_APPS = "blacklisted_apps";
    public static final String KEY_AUTOSTART_DISABLED_APPS = "autostart_disabled_apps";

    public static final String KEY_HARD_RESTRICTION_APPS = "hard_restriction_apps";
    public static final String KEY_MANUAL_RESTRICTION_APPS = "manual_restriction_apps";
    public static final String KEY_MANUAL_OPS_PREFIX = "manual_ops_mask_";
    public static final String KEY_APPLIED_OPS_MASK_PREFIX = "applied_ops_mask_";

    public static final String KEY_BATTERY_WHITELIST_REMOVED = "battery_whitelist_removed";

    // Kill Mode
    public static final String KEY_KILL_MODE = "kill_mode"; 

    // Auto-Kill Type: which shell command to use when killing apps
    public static final String KEY_AUTO_KILL_TYPE = "auto_kill_type";

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

    // Notifications
    // Values: NOTIFICATION_MODE_ALL (0) = all notifications, NOTIFICATION_MODE_IMPORTANT_ONLY (1) = critical only
    public static final String KEY_NOTIFICATION_MODE = "notificationMode";
    public static final int NOTIFICATION_MODE_ALL = 0;
    public static final int NOTIFICATION_MODE_IMPORTANT_ONLY = 1;

    // Sleep Mode
    public static final String KEY_SLEEP_MODE_ENABLED = "sleepModeEnabled";
    public static final String KEY_SLEEP_MODE_APPS = "sleepModeApps";
    public static final String KEY_SLEEP_MODE_DELAY = "sleepModeDelay";

}
