package com.gree1d.reappzuku.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity — one resource snapshot for one app at one point in time.
 *
 * A snapshot is taken every 30–60 minutes by BatteryStatsManager.
 * Period stats (2h/6h/12h/24h) are derived by diff-ing two snapshots
 * bracketing the desired time window.
 */
@Entity(
    tableName = "resource_snapshots",
    indices = {
        @Index(value = {"packageName", "timestamp"}),
        @Index(value = {"timestamp"})
    }
)
public class ResourceSnapshot {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Unix timestamp in ms when this snapshot was collected. */
    public long timestamp;

    /** App package name, e.g. "com.example.app". */
    public String packageName;

    /**
     * Cumulative estimated battery drain in mAh since last charge.
     * Sourced from: dumpsys batterystats --charged --checkin  (pwi lines).
     * To get drain for a period, diff two snapshots: delta = current - previous.
     */
    public double batteryMah;

    /**
     * Average RAM usage in MB (PSS — Proportional Set Size) over the procstats window.
     * Sourced from: dumpsys procstats --hours 24.
     * PSS is the most honest per-app memory metric on Android.
     */
    public double ramMb;

    /**
     * Cumulative CPU time in milliseconds (user + kernel) since process start.
     * Sourced from: batterystats cpu= fields (converted to ms).
     * To get CPU activity for a period, diff two snapshots.
     */
    public long cpuTimeMs;
}
