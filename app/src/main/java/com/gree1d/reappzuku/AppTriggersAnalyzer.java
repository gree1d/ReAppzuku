package com.gree1d.reappzuku;

import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes why a specific app is running in the background.
 * Uses shell commands via ShellManager (Root or Shizuku).
 *
 * All methods are blocking — call only from a background thread.
 */
public class AppTriggersAnalyzer {

    private static final String TAG = "AppTriggersAnalyzer";

    public static final class TriggerInfo {
        public final String category;   // e.g. "Foreground Service"
        public final String detail;     // human-readable detail line
        public final Severity severity; // color/icon hint

        public enum Severity { HIGH, MEDIUM, LOW, INFO }

        public TriggerInfo(String category, String detail, Severity severity) {
            this.category = category;
            this.detail   = detail;
            this.severity = severity;
        }
    }

    private final ShellManager shellManager;

    public AppTriggersAnalyzer(ShellManager shellManager) {
        this.shellManager = shellManager;
    }

    /**
     * Run all analyses for the given package and return a combined list.
     * Blocking — must be called from a background thread.
     */
    public List<TriggerInfo> analyze(String packageName) {
        List<TriggerInfo> results = new ArrayList<>();

        results.addAll(analyzeForegroundServices(packageName));
        results.addAll(analyzeBroadcastReceivers(packageName));
        results.addAll(analyzeAlarms(packageName));
        results.addAll(analyzeJobs(packageName));
        results.addAll(analyzeDozeExemption(packageName));
        results.addAll(analyzeWakelocks(packageName));

        if (results.isEmpty()) {
            results.add(new TriggerInfo(
                    "—",
                    "Активных триггеров не обнаружено",
                    TriggerInfo.Severity.INFO));
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // 1. Foreground Services
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeForegroundServices(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys activity services " + packageName);

        if (output == null || output.trim().isEmpty()) return list;

        // Each ServiceRecord block that mentions isForeground=true
        boolean inTargetPackage = false;
        String currentService = null;
        for (String line : output.split("\n")) {
            if (line.contains("ServiceRecord") && line.contains(packageName)) {
                inTargetPackage = true;
                // Extract short service class name
                Matcher m = Pattern.compile("ServiceRecord\\{[^}]+\\s(\\S+)\\}").matcher(line);
                currentService = m.find() ? m.group(1) : packageName;
                // Remove package prefix for brevity
                if (currentService != null && currentService.startsWith(packageName + "/")) {
                    currentService = currentService.substring(packageName.length() + 1);
                }
            }
            if (inTargetPackage && line.contains("isForeground=true")) {
                list.add(new TriggerInfo(
                        "Foreground Service",
                        currentService != null ? currentService : packageName,
                        TriggerInfo.Severity.HIGH));
                inTargetPackage = false;
                currentService = null;
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 2. Broadcast Receivers
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeBroadcastReceivers(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys package " + packageName);

        if (output == null || output.trim().isEmpty()) return list;

        // Section "Registered Receivers:" / "receivers:" under the package block
        boolean inReceiverSection = false;
        List<String> actions = new ArrayList<>();

        for (String line : output.split("\n")) {
            String trimmed = line.trim();

            if (trimmed.startsWith("Receiver #") || trimmed.startsWith("ReceiverInfo{")) {
                inReceiverSection = true;
            }
            if (inReceiverSection && trimmed.startsWith("Action:")) {
                String action = trimmed.replaceFirst("Action:\\s*\"?", "").replace("\"", "").trim();
                // Shorten android.intent.action.XXX → XXX
                if (action.startsWith("android.intent.action.")) {
                    action = action.substring("android.intent.action.".length());
                } else if (action.startsWith("android.net.")) {
                    action = action.substring("android.net.".length());
                }
                if (!actions.contains(action)) actions.add(action);
            }
            // Stop at next top-level section
            if (inReceiverSection && trimmed.startsWith("Service #")) {
                break;
            }
        }

        if (!actions.isEmpty()) {
            // Group into one entry to keep the dialog compact
            String detail = String.join(", ", actions.subList(0, Math.min(actions.size(), 6)));
            if (actions.size() > 6) detail += " (+" + (actions.size() - 6) + ")";
            list.add(new TriggerInfo(
                    "Broadcast Receivers (" + actions.size() + ")",
                    detail,
                    TriggerInfo.Severity.MEDIUM));
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 3. Alarms
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeAlarms(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys alarm");

        if (output == null || output.trim().isEmpty()) return list;

        int wakeupCount   = 0;
        int normalCount   = 0;
        long minInterval  = Long.MAX_VALUE; // ms
        boolean hasExact  = false;

        for (String line : output.split("\n")) {
            if (!line.contains(packageName)) continue;

            boolean isWakeup = line.contains("RTC_WAKEUP") || line.contains("ELAPSED_WAKEUP")
                    || line.contains("*walarm*");
            if (isWakeup) wakeupCount++; else normalCount++;

            if (line.contains("*walarm*")) hasExact = true;

            // repeatInterval=NNNNN
            Matcher m = Pattern.compile("repeatInterval=(\\d+)").matcher(line);
            if (m.find()) {
                long interval = Long.parseLong(m.group(1));
                if (interval > 0 && interval < minInterval) minInterval = interval;
            }
        }

        int total = wakeupCount + normalCount;
        if (total == 0) return list;

        StringBuilder detail = new StringBuilder();
        if (wakeupCount > 0) detail.append(wakeupCount).append(" wake-up");
        if (normalCount > 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(normalCount).append(" normal");
        }
        if (minInterval != Long.MAX_VALUE) {
            long sec = minInterval / 1000;
            String intervalStr = sec < 60
                    ? "каждые " + sec + " сек"
                    : "каждые " + (sec / 60) + " мин";
            detail.append(" · ").append(intervalStr);
        }
        if (hasExact) detail.append(" · setExactAndAllowWhileIdle");

        TriggerInfo.Severity severity = wakeupCount > 0
                ? (minInterval != Long.MAX_VALUE && minInterval < 120_000
                        ? TriggerInfo.Severity.HIGH
                        : TriggerInfo.Severity.MEDIUM)
                : TriggerInfo.Severity.LOW;

        list.add(new TriggerInfo("Alarms", detail.toString(), severity));
        return list;
    }

    // -------------------------------------------------------------------------
    // 4. Jobs / WorkManager / JobScheduler
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeJobs(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys jobscheduler");

        if (output == null || output.trim().isEmpty()) return list;

        int pending  = 0;
        int running  = 0;

        boolean inPending = false;
        boolean inRunning = false;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();

            if (trimmed.startsWith("Pending queue:"))  { inPending = true;  inRunning = false; continue; }
            if (trimmed.startsWith("Active jobs:"))    { inRunning = true;  inPending = false; continue; }
            if (trimmed.startsWith("Past jobs:"))      { inPending = false; inRunning = false; }

            if ((inPending || inRunning) && trimmed.contains(packageName)) {
                if (inPending) pending++;
                if (inRunning) running++;
            }
        }

        if (pending == 0 && running == 0) return list;

        StringBuilder detail = new StringBuilder();
        if (running > 0) detail.append(running).append(" выполняется");
        if (pending > 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(pending).append(" в очереди");
        }

        list.add(new TriggerInfo(
                "Jobs / WorkManager",
                detail.toString(),
                running > 0 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
        return list;
    }

    // -------------------------------------------------------------------------
    // 5. Doze exemption (deviceidle whitelist)
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeDozeExemption(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys deviceidle | grep -E 'whitelist|except'");

        if (output == null || output.trim().isEmpty()) return list;

        for (String line : output.split("\n")) {
            if (line.contains(packageName)) {
                String detail = line.contains("sys-") ? "System whitelist (не обходимо)" : "User whitelist — обходит Doze";
                list.add(new TriggerInfo(
                        "Doze Exempt",
                        detail,
                        TriggerInfo.Severity.HIGH));
                break;
            }
        }
        return list;
    }


    private List<TriggerInfo> analyzeWakelocks(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();


        String uidOutput = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys package " + packageName + " | grep userId=");
        if (uidOutput == null) return list;

        String uid = null;
        for (String line : uidOutput.split("\n")) {
            Matcher m = Pattern.compile("userId=(\\d+)").matcher(line);
            if (m.find()) { uid = m.group(1); break; }
        }
        if (uid == null) return list;

        String powerOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys power");
        if (powerOutput == null || powerOutput.trim().isEmpty()) return list;


        if (!powerOutput.contains("Wake Locks:")) {
            Log.d(TAG, "dumpsys power: Wake Locks section not available (likely no DUMP permission)");
            return list;
        }

        boolean inWakeLockSection = false;
        for (String line : powerOutput.split("\n")) {
            if (line.trim().startsWith("Wake Locks:")) { inWakeLockSection = true; continue; }
            if (inWakeLockSection && line.trim().startsWith("Suspend Blockers:")) break;

            if (!inWakeLockSection) continue;
            if (!line.contains("uid=" + uid)) continue;

            String type = "WAKE_LOCK";
            if (line.contains("PARTIAL_WAKE_LOCK")) type = "PARTIAL (CPU не спит)";
            else if (line.contains("FULL_WAKE_LOCK")) type = "FULL (экран + CPU)";

            String tag = "";
            Matcher tagMatcher = Pattern.compile("'([^']+)'").matcher(line);
            if (tagMatcher.find()) tag = tagMatcher.group(1);

            String held = "";
            Matcher timeMatcher = Pattern.compile("(\\d+m\\s*\\d+s|\\d+s)").matcher(line);
            if (timeMatcher.find()) held = " · " + timeMatcher.group(1);

            String detail = type + (tag.isEmpty() ? "" : " · " + tag) + held;
            list.add(new TriggerInfo("WakeLock", detail, TriggerInfo.Severity.HIGH));
        }

        return list;
    }
}
