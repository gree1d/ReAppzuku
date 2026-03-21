package com.northmendo.Appzuku;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class BackgroundRestrictionLog {
    private static final String LOG_FILE_NAME = "background_restriction.log";
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_DETAIL_LENGTH = 180;
    private static final Object LOCK = new Object();

    private BackgroundRestrictionLog() {
    }

    public static void log(Context context, String packageName, String action, String outcome, String detail) {
        if (context == null) {
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String safePackage = sanitize(packageName == null || packageName.trim().isEmpty() ? "-" : packageName);
        String safeAction = sanitize(action == null || action.trim().isEmpty() ? "event" : action);
        String safeOutcome = sanitize(outcome == null || outcome.trim().isEmpty() ? "unknown" : outcome);
        String safeDetail = sanitize(detail);

        StringBuilder entry = new StringBuilder()
                .append(timestamp)
                .append(" | ")
                .append(safeAction)
                .append(" | ")
                .append(safePackage)
                .append(" | ")
                .append(safeOutcome);
        if (!safeDetail.isEmpty()) {
            entry.append(" | ").append(safeDetail);
        }

        appendLine(context, entry.toString());
    }

    public static String readDisplayText(Context context) {
        List<LogEntry> entries = readEntries(context);
        if (entries.isEmpty()) {
            return "No background restriction events logged yet.\n\nEntries are stored temporarily in cache and trimmed automatically.";
        }

        StringBuilder display = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                display.append('\n');
            }
            display.append(entries.get(i).toDisplayLine());
        }
        return display.toString();
    }

    public static List<LogEntry> readEntries(Context context) {
        List<String> lines = readLines(context);
        Collections.reverse(lines);
        List<LogEntry> entries = new ArrayList<>();
        for (String line : lines) {
            entries.add(parseEntry(line));
        }
        return entries;
    }

    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            File file = getLogFile(context);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    private static void appendLine(Context context, String line) {
        synchronized (LOCK) {
            List<String> lines = readLinesInternal(context);
            lines.add(line);
            int start = Math.max(0, lines.size() - MAX_ENTRIES);
            List<String> trimmed = new ArrayList<>(lines.subList(start, lines.size()));
            writeLines(context, trimmed);
        }
    }

    private static List<String> readLines(Context context) {
        synchronized (LOCK) {
            return new ArrayList<>(readLinesInternal(context));
        }
    }

    private static List<String> readLinesInternal(Context context) {
        List<String> lines = new ArrayList<>();
        File file = getLogFile(context);
        if (!file.exists()) {
            return lines;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException ignored) {
        }
        return lines;
    }

    private static void writeLines(Context context, List<String> lines) {
        File file = getLogFile(context);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException ignored) {
        }
    }

    private static File getLogFile(Context context) {
        return new File(context.getCacheDir(), LOG_FILE_NAME);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('|', '/')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() > MAX_DETAIL_LENGTH) {
            return normalized.substring(0, MAX_DETAIL_LENGTH - 3) + "...";
        }
        return normalized;
    }

    private static LogEntry parseEntry(String line) {
        if (line == null || line.trim().isEmpty()) {
            return new LogEntry("", "event", "-", "unknown", "");
        }
        String[] parts = line.split("\\s\\|\\s", 5);
        String timestamp = parts.length > 0 ? parts[0].trim() : "";
        String action = parts.length > 1 ? parts[1].trim() : "event";
        String packageName = parts.length > 2 ? parts[2].trim() : "-";
        String outcome = parts.length > 3 ? parts[3].trim() : "unknown";
        String detail = parts.length > 4 ? parts[4].trim() : "";
        return new LogEntry(timestamp, action, packageName, outcome, detail);
    }

    public static final class LogEntry {
        public final String timestamp;
        public final String action;
        public final String packageName;
        public final String outcome;
        public final String detail;

        private LogEntry(String timestamp, String action, String packageName, String outcome, String detail) {
            this.timestamp = timestamp;
            this.action = action;
            this.packageName = packageName;
            this.outcome = outcome;
            this.detail = detail;
        }

        private String toDisplayLine() {
            StringBuilder line = new StringBuilder()
                    .append(timestamp)
                    .append(" | ")
                    .append(action)
                    .append(" | ")
                    .append(packageName)
                    .append(" | ")
                    .append(outcome);
            if (!detail.isEmpty()) {
                line.append(" | ").append(detail);
            }
            return line.toString();
        }
    }
}
