package com.gree1d.reappzuku;

/**
 * Centralized constants for timing, thresholds, and other magic numbers.
 */
public final class AppConstants {
        private AppConstants() {
                // Prevent instantiation
        }

        // Kill Intervals (milliseconds)
        public static final int DEFAULT_KILL_INTERVAL_MS = 18000; // 18 seconds
        public static final int[] KILL_INTERVALS_MS = { 10000, 18000, 30000, 60000, 300000 };

        // RAM Thresholds
        public static final int DEFAULT_RAM_THRESHOLD_PERCENT = 80;
        public static final int[] RAM_THRESHOLD_VALUES = { 75, 80, 85, 90, 95, 100 };

        // Stats & History
        public static final long STATS_HISTORY_DURATION_MS = 12 * 60 * 60 * 1000L; // 12 hours
        public static final long STATS_PRUNE_THRESHOLD_MS = 48 * 60 * 60 * 1000L; // 48 hours
        public static final int RELAUNCH_GREEDY_THRESHOLD = 3; // Consider app "greedy" if relaunched more than this

        // Delays
        public static final int RELAUNCH_CHECK_DELAY_MS = 2000; // 2 seconds delay before checking relaunches
        public static final int ROOT_CHECK_TIMEOUT_MS = 1000; // 1 second timeout for root check

        // RAM Monitor
        public static final int RAM_MONITOR_UPDATE_INTERVAL_MS = 2000; // 2 seconds

        // Notification IDs
        public static final int NOTIFICATION_ID_SERVICE = 1;
        public static final int NOTIFICATION_ID_KILL = 2;

        // Notification Channels
        public static final String CHANNEL_ID_SERVICE = "AppzukuChannel";
        public static final String CHANNEL_ID_ACTIONS = "AppzukuActions";

        // Sort Modes
        public static final int SORT_MODE_DEFAULT = 0;
        public static final int SORT_MODE_RAM_DESC = 1;
        public static final int SORT_MODE_RAM_ASC = 2;
        public static final int SORT_MODE_NAME_ASC = 3;
        public static final int SORT_MODE_NAME_DESC = 4;

        public static final String[] SORT_MODE_LABELS = {
                        "Default (System → Name)",
                        "Most RAM → Least RAM",
                        "Least RAM → Most RAM",
                        "Name A → Z",
                        "Name Z → A"
        };

        // Theme values
        public static final int[] THEME_VALUES = {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        };

        // Sleep Mode
        public static final long DEFAULT_SLEEP_MODE_DELAY_MS = 60 * 60 * 1000L; // 60 minutes
        public static final long[] SLEEP_MODE_DELAYS_MS = {
                5 * 60 * 1000L,
                10 * 60 * 1000L,
                15 * 60 * 1000L,
                20 * 60 * 1000L,
                30 * 60 * 1000L,
                60 * 60 * 1000L
        };
}
