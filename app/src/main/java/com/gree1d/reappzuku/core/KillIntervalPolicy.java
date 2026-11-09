package com.gree1d.reappzuku.core;

/**
 * Pure bounds for the periodic Auto-Kill interval preference.
 *
 * The preference stores milliseconds ({@link AppConstants#KILL_INTERVALS_MS}).
 * Historical code paths wrote minute-scale fallbacks into the same key, so every
 * consumer must clamp before scheduling: the in-service loop must never spin
 * faster than the smallest supported interval, and the WorkManager fallback must
 * convert to minutes instead of reinterpreting milliseconds as minutes.
 */
public final class KillIntervalPolicy {
    private KillIntervalPolicy() {
    }

    public static final int MIN_INTERVAL_MS = AppConstants.KILL_INTERVALS_MS[0];
    public static final int MAX_INTERVAL_MS =
            AppConstants.KILL_INTERVALS_MS[AppConstants.KILL_INTERVALS_MS.length - 1];
    /** WorkManager does not run periodic work more often than every 15 minutes. */
    public static final long MIN_WORKER_INTERVAL_MINUTES = 15L;

    /** Clamp a persisted interval to the supported millisecond range. */
    public static int clampServiceIntervalMs(int storedMs) {
        if (storedMs < MIN_INTERVAL_MS) return MIN_INTERVAL_MS;
        if (storedMs > MAX_INTERVAL_MS) return MAX_INTERVAL_MS;
        return storedMs;
    }

    /** Convert the stored millisecond interval to the WorkManager period in minutes. */
    public static long workerIntervalMinutes(int storedMs) {
        long minutes = (clampServiceIntervalMs(storedMs) + 59_999L) / 60_000L;
        return Math.max(minutes, MIN_WORKER_INTERVAL_MINUTES);
    }
}
