package com.gree1d.reappzuku;

import android.content.Context;

import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.SleepModeLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SleepModeLogManager {

    private static final int MAX_ENTRIES    = 200;
    private static final int MAX_DETAIL_LEN = 180;

    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor();

    private SleepModeLogManager() {}

    public static void logFreeze(Context context, String packageName, boolean succeeded) {
        append(context, packageName, "freeze", succeeded ? "ok" : "error");
    }

    public static void logUnfreeze(Context context, String packageName, boolean succeeded) {
        append(context, packageName, "unfreeze", succeeded ? "ok" : "error");
    }

    private static void append(Context context, String packageName,
                                String action, String outcome) {
        if (context == null) return;

        SleepModeLog entry = new SleepModeLog();
        entry.timestamp   = System.currentTimeMillis();
        entry.packageName = sanitize(packageName == null || packageName.trim().isEmpty() ? "-" : packageName);
        entry.action      = action;
        entry.outcome     = outcome;

        DB_EXECUTOR.execute(() -> {
            SleepModeLog.Dao dao = AppDatabase.getInstance(context).sleepModeLogDao();
            dao.insert(entry);

            int count = dao.getCount();
            if (count > MAX_ENTRIES) {
                dao.deleteOldest(count - MAX_ENTRIES);
            }
        });
    }

    public static String readDisplayText(Context context) {
        List<LogEntry> entries = readEntries(context);
        if (entries.isEmpty()) {
            return context.getString(R.string.log_sleep_mode_empty);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(entries.get(i).toDisplayLine());
        }
        return sb.toString();
    }

    public static List<LogEntry> readEntries(Context context) {
        List<SleepModeLog> rows =
                AppDatabase.getInstance(context).sleepModeLogDao().getRecent(MAX_ENTRIES);
        List<LogEntry> result = new ArrayList<>(rows.size());
        for (SleepModeLog row : rows) {
            result.add(new LogEntry(
                    formatTimestamp(row.timestamp),
                    row.action      != null ? row.action      : "event",
                    row.packageName != null ? row.packageName : "-",
                    row.outcome     != null ? row.outcome     : "unknown"
            ));
        }
        return result;
    }

    public static void clear(Context context) {
        if (context == null) return;
        DB_EXECUTOR.execute(() ->
                AppDatabase.getInstance(context).sleepModeLogDao().clearAll());
    }

    private static String formatTimestamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String s = value.replace('\r', ' ')
                        .replace('\n', ' ')
                        .replace('|', '/')
                        .replaceAll("\\s+", " ")
                        .trim();
        return s.length() > MAX_DETAIL_LEN ? s.substring(0, MAX_DETAIL_LEN - 3) + "..." : s;
    }

    public static final class LogEntry {
        public final String timestamp;
        public final String action;
        public final String packageName;
        public final String outcome;

        private LogEntry(String timestamp, String action, String packageName, String outcome) {
            this.timestamp   = timestamp;
            this.action      = action;
            this.packageName = packageName;
            this.outcome     = outcome;
        }

        public String toDisplayLine() {
            return timestamp + " | " + action + " | " + packageName + " | " + outcome;
        }
    }
}
