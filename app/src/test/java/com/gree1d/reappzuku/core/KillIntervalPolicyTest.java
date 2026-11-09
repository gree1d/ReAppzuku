package com.gree1d.reappzuku.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KillIntervalPolicyTest {

    @Test
    public void storedIntervalsPassClampUnchanged() {
        for (int interval : AppConstants.KILL_INTERVALS_MS) {
            assertEquals(interval, KillIntervalPolicy.clampServiceIntervalMs(interval));
        }
    }

    @Test
    public void legacyMinuteScaleFallbackCannotSpinTheServiceLoop() {
        // Older preset backup/restore paths wrote 15 into the millisecond key.
        assertEquals(KillIntervalPolicy.MIN_INTERVAL_MS,
                KillIntervalPolicy.clampServiceIntervalMs(15));
        assertEquals(KillIntervalPolicy.MIN_INTERVAL_MS,
                KillIntervalPolicy.clampServiceIntervalMs(0));
        assertEquals(KillIntervalPolicy.MIN_INTERVAL_MS,
                KillIntervalPolicy.clampServiceIntervalMs(-1));
    }

    @Test
    public void oversizedIntervalIsClampedToLargestSupportedValue() {
        assertEquals(KillIntervalPolicy.MAX_INTERVAL_MS,
                KillIntervalPolicy.clampServiceIntervalMs(Integer.MAX_VALUE));
    }

    @Test
    public void workerIntervalConvertsMillisecondsToMinutes() {
        // 18000 ms must become the 15-minute WorkManager floor, never 18000 minutes.
        assertEquals(15L, KillIntervalPolicy.workerIntervalMinutes(18_000));
        assertEquals(15L, KillIntervalPolicy.workerIntervalMinutes(300_000));
        assertEquals(15L, KillIntervalPolicy.workerIntervalMinutes(15));
        assertEquals(15L, KillIntervalPolicy.workerIntervalMinutes(Integer.MAX_VALUE));
    }

    @Test
    public void defaultIntervalIsWithinSupportedRange() {
        assertEquals(AppConstants.DEFAULT_KILL_INTERVAL_MS,
                KillIntervalPolicy.clampServiceIntervalMs(AppConstants.DEFAULT_KILL_INTERVAL_MS));
    }
}
