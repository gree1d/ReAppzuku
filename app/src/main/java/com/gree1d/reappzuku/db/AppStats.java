package com.northmendo.Appzuku.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_stats")
public class AppStats {
    @PrimaryKey
    @NonNull
    public String packageName;
    
    public String appName;
    public int killCount;
    public int relaunchCount;
    public long totalRecoveredKb;
    public long lastKillTime;
    public long lastRelaunchTime;

    public AppStats(@NonNull String packageName) {
        this.packageName = packageName;
    }
}
