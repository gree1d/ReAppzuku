package com.gree1d.reappzuku.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.migration.Migration;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.annotation.NonNull;

@Database(entities = {AppStats.class}, version = 2, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE app_stats ADD COLUMN totalRecoveredKb INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract AppStatsDao appStatsDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "appzuku_db")
                    .addMigrations(MIGRATION_1_2)
                    .build();
        }
        return instance;
    }
}
