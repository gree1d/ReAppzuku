package com.gree1d.reappzuku;

public final class AppConstants {
        private AppConstants() {
        }

        // Kill Intervals (milliseconds)
        public static final int DEFAULT_KILL_INTERVAL_MS = 18000; // 18 seconds
        public static final int[] KILL_INTERVALS_MS = { 10000, 18000, 30000, 60000, 300000 };

        // RAM Thresholds
        public static final int DEFAULT_RAM_THRESHOLD_PERCENT = 80;
        public static final int[] RAM_THRESHOLD_VALUES = { 75, 80, 85, 90, 95, 100 };

        // Stats & History
        public static final long STATS_HISTORY_DURATION_MS = 12 * 60 * 60 * 1000L;
        public static final int STATS_MAX_COUNT = 10_000;
        public static final int RELAUNCH_GREEDY_THRESHOLD = 3;

        // Delays
        public static final int RELAUNCH_CHECK_DELAY_MS = 2000;
        public static final int ROOT_CHECK_TIMEOUT_MS = 1000;

        // RAM Monitor
        public static final int RAM_MONITOR_UPDATE_INTERVAL_MS = 2000;

        // Notification IDs
        public static final int NOTIFICATION_ID_SERVICE = 1;
        public static final int NOTIFICATION_ID_KILL = 2;
        public static final int NOTIFICATION_ID_SHIZUKU_LOST = 3;

        // Notification Channels
        public static final String CHANNEL_ID_SERVICE = "AppzukuChannel";
        public static final String CHANNEL_ID_ACTIONS = "AppzukuActions";

        // Sort Modes
        public static final int SORT_MODE_DEFAULT = 0;
        public static final int SORT_MODE_RAM_DESC = 1;
        public static final int SORT_MODE_RAM_ASC = 2;
        public static final int SORT_MODE_CPU_DESC = 3;
        public static final int SORT_MODE_CPU_ASC = 4;
        public static final int SORT_MODE_NAME_ASC = 5;
        public static final int SORT_MODE_NAME_DESC = 6;            

        public static final int[] THEME_VALUES = {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        };

        // Accent values
        public static final int ACCENT_SYSTEM    = 0;
        public static final int ACCENT_INDIGO    = 1;
        public static final int ACCENT_CRIMSON   = 2;
        public static final int ACCENT_FOREST    = 3;
        public static final int ACCENT_SLATE     = 4; 
        public static final int ACCENT_ROSE      = 5;
        public static final int ACCENT_AMBER     = 6;
        public static final int ACCENT_TEAL      = 7;
        public static final int ACCENT_TERRACOTA = 8;
        public static final int ACCENT_MOCHA     = 9;
        public static final int ACCENT_OLIVE     = 10;
        public static final int ACCENT_STEEL     = 11;
        // light accents
        public static final int ACCENT_APRICOT      = 12;
        public static final int ACCENT_SKY          = 13;
        public static final int ACCENT_PAPAYA       = 14;
        public static final int ACCENT_LAVENDER     = 15;
        public static final int ACCENT_MINT         = 16;
        public static final int ACCENT_PEACH        = 17;
        public static final int ACCENT_POWDER       = 18;
        public static final int ACCENT_FOG          = 19;

        // Sleep Mode
        public static final long DEFAULT_SLEEP_MODE_DELAY_MS = 60 * 60 * 1000L;
        public static final long[] SLEEP_MODE_DELAYS_MS = {
                5 * 60 * 1000L,
                10 * 60 * 1000L,
                15 * 60 * 1000L,
                20 * 60 * 1000L,
                30 * 60 * 1000L,
                60 * 60 * 1000L
        };

        // Shizuku monitoring
        public static final long SHIZUKU_POLL_INTERVAL_MS = 15_000;
}
