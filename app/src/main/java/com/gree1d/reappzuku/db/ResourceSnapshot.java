package com.gree1d.reappzuku.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;


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

    /**
     * Total CPU jiffies (all states: user+nice+system+idle+iowait+irq+softirq)
     * across all cores, read from /proc/stat at snapshot time.
     * 1 jiffy = 10 ms (USER_HZ = 100 on Android).
     * Used as the denominator for accurate per-app CPU % calculation.
     */
    public long totalCpuJiffies;

    /**
     * Active CPU jiffies (totalCpuJiffies - idle - iowait) across all cores.
     * Represents the "wall-clock CPU capacity actually used" baseline.
     * appCpuPct = dAppCpuMs / (dTotalJiffies * 10) * 100
     */
    public long activeCpuJiffies;
}
