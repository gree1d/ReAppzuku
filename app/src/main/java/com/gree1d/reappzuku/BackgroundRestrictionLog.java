package com.gree1d.reappzuku;

import android.content.Context;

import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.BgRestrictionLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackgroundRestrictionLog {

    private static final int MAX_ENTRIES    = 200;
    private static final int MAX_DETAIL_LEN = 180;

    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor();

    private BackgroundRestrictionLog() {}

    public static void log(Context context, String packageName,
                           String action, String outcome, String detail) {
        if (context == null) return;

        BgRestrictionLog entry = new BgRestrictionLog();
        entry.timestamp   = System.currentTimeMillis();
        entry.packageName = sanitize(packageName == null || packageName.trim().isEmpty() ? "-" : packageName);
        entry.action      = sanitize(action      == null || action.trim().isEmpty()      ? "event"   : action);
        entry.outcome     = sanitize(outcome      == null || outcome.trim().isEmpty()     ? "unknown" : outcome);
        entry.detail      = sanitize(detail);

        DB_EXECUTOR.execute(() -> {
            BgRestrictionLog.Dao dao = AppDatabase.getInstance(context).bgRestrictionLogDao();
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
            return context.getString(R.string.log_bg_restriction_empty);
        }
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) display.append('\n');
            display.append(entries.get(i).toDisplayLine());
        }
        return display.toString();
    }

    public static List<LogEntry> readEntries(Context context) {
        List<BgRestrictionLog> rows =
                AppDatabase.getInstance(context).bgRestrictionLogDao().getRecent(MAX_ENTRIES);
        List<LogEntry> result = new ArrayList<>(rows.size());
        for (BgRestrictionLog row : rows) {
            result.add(new LogEntry(
                    formatTimestamp(row.timestamp),
                    row.action      != null ? row.action      : "event",
                    row.packageName != null ? row.packageName : "-",
                    row.outcome     != null ? row.outcome     : "unknown",
                    row.detail      != null ? row.detail      : ""
            ));
        }
        return result;
    }

    public static void clear(Context context) {
        if (context == null) return;
        DB_EXECUTOR.execute(() ->
                AppDatabase.getInstance(context).bgRestrictionLogDao().clearAll());
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
        public final String detail;

        private LogEntry(String timestamp, String action, String packageName,
                         String outcome, String detail) {
            this.timestamp   = timestamp;
            this.action      = action;
            this.packageName = packageName;
            this.outcome     = outcome;
            this.detail      = detail;
        }

        public String toDisplayLine() {
            StringBuilder line = new StringBuilder()
                    .append(timestamp).append(" | ")
                    .append(action).append(" | ")
                    .append(packageName).append(" | ")
                    .append(outcome);
            if (!detail.isEmpty()) line.append(" | ").append(detail);
            return line.toString();
        }
    }
}
