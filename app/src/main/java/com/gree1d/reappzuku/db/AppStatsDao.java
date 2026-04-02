package com.northmendo.Appzuku.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AppStatsDao {
    @Query("SELECT * FROM app_stats WHERE packageName = :packageName")
    AppStats getStats(String packageName);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(AppStats stats);

    @Update
    void update(AppStats stats);

    @Query("SELECT * FROM app_stats ORDER BY relaunchCount DESC")
    List<AppStats> getAllStats();

    @Query("SELECT * FROM app_stats WHERE lastRelaunchTime > :sinceTime ORDER BY relaunchCount DESC")
    List<AppStats> getStatsSince(long sinceTime);

    @Query("SELECT * FROM app_stats WHERE lastKillTime > :sinceTime OR lastRelaunchTime > :sinceTime ORDER BY killCount DESC")
    List<AppStats> getAllStatsSince(long sinceTime);

    @Query("UPDATE app_stats SET relaunchCount = relaunchCount + 1, lastRelaunchTime = :time WHERE packageName = :packageName")
    void incrementRelaunch(String packageName, long time);

    @Query("UPDATE app_stats SET killCount = killCount + 1, lastKillTime = :time WHERE packageName = :packageName")
    void incrementKill(String packageName, long time);

    @Query("UPDATE app_stats SET totalRecoveredKb = totalRecoveredKb + :recoveredKb WHERE packageName = :packageName")
    void addRecoveredKb(String packageName, long recoveredKb);

    @Query("UPDATE app_stats SET appName = :appName WHERE packageName = :packageName")
    void updateAppName(String packageName, String appName);
    
    @Query("DELETE FROM app_stats WHERE lastKillTime < :threshold AND lastRelaunchTime < :threshold")
    void deleteOldStats(long threshold);
}
