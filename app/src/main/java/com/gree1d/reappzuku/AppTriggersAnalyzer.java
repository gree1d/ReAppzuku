package com.gree1d.reappzuku;

import android.content.Context;
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
        public final String category;    // заголовок секции
        public final String detail;      // основная строка — что обнаружено
        public final String explanation; // пояснение для пользователя
        public final Severity severity;

        public enum Severity { HIGH, MEDIUM, LOW, INFO }

        public TriggerInfo(String category, String detail, String explanation, Severity severity) {
            this.category    = category;
            this.detail      = detail;
            this.explanation = explanation;
            this.severity    = severity;
        }
    }

    private final ShellManager shellManager;
    private final Context context;

    public AppTriggersAnalyzer(Context context, ShellManager shellManager) {
        this.context      = context.getApplicationContext();
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
                    context.getString(R.string.triggers_none_title),
                    context.getString(R.string.triggers_none_detail),
                    context.getString(R.string.triggers_none_explanation),
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

        boolean inTargetPackage = false;
        String currentService = null;

        for (String line : output.split("\n")) {
            if (line.contains("ServiceRecord") && line.contains(packageName)) {
                inTargetPackage = true;
                currentService = extractServiceShortName(line, packageName);
            }
            if (inTargetPackage && line.contains("isForeground=true")) {
                String detail = currentService != null ? currentService : packageName;
                list.add(new TriggerInfo(
                        "Foreground Service",
                        detail,
                        context.getString(R.string.triggers_fg_service_explanation),
                        TriggerInfo.Severity.HIGH));
                inTargetPackage = false;
                currentService = null;
            }
        }
        return list;
    }

    /**
     * Извлекает короткое имя класса сервиса из строки ServiceRecord.
     * Форматы:
     *   ServiceRecord{hex com.pkg/.ClassName}
     *   ServiceRecord{hex com.pkg/com.pkg.ClassName}
     */
    private String extractServiceShortName(String line, String packageName) {
        Matcher m = Pattern.compile("ServiceRecord\\{[^}]+\\s([\\w./]+)\\}").matcher(line);
        if (!m.find()) return null;
        String fullName = m.group(1);

        if (fullName.contains("/")) {
            String className = fullName.substring(fullName.indexOf('/') + 1);
            if (className.startsWith(".")) return className.substring(1);
            if (className.startsWith(packageName + ".")) {
                return className.substring(packageName.length() + 1);
            }
            return className;
        }
        return fullName;
    }

    // -------------------------------------------------------------------------
    // 2. Broadcast Receivers
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeBroadcastReceivers(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys package " + packageName);

        if (output == null || output.trim().isEmpty()) return list;

        boolean inReceiverSection = false;
        List<String> actions = new ArrayList<>();

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Receiver #") || trimmed.startsWith("ReceiverInfo{")) {
                inReceiverSection = true;
            }
            if (inReceiverSection && trimmed.startsWith("Action:")) {
                String action = trimmed.replaceFirst("Action:\\s*\"?", "").replace("\"", "").trim();
                action = shortenAction(action);
                if (!actions.contains(action)) actions.add(action);
            }
            if (inReceiverSection && trimmed.startsWith("Service #")) break;
        }

        if (actions.isEmpty()) return list;

        String detail = String.join(", ", actions.subList(0, Math.min(actions.size(), 5)));
        if (actions.size() > 5) detail += " (+" + (actions.size() - 5) + ")";

        boolean hasBootReceiver = actions.stream().anyMatch(
                a -> a.contains("BOOT") || a.contains("LOCKED_BOOT"));
        boolean hasConnectivity = actions.stream().anyMatch(
                a -> a.contains("CONNECTIVITY") || a.contains("NETWORK"));

        StringBuilder explanation = new StringBuilder(
                context.getString(R.string.triggers_receivers_explanation_base));
        if (hasBootReceiver) {
            explanation.append(context.getString(R.string.triggers_receivers_explanation_boot));
        }
        if (hasConnectivity) {
            explanation.append(context.getString(R.string.triggers_receivers_explanation_network));
        }

        list.add(new TriggerInfo(
                "Broadcast Receivers (" + actions.size() + ")",
                detail,
                explanation.toString(),
                TriggerInfo.Severity.MEDIUM));
        return list;
    }

    private String shortenAction(String action) {
        if (action.startsWith("android.intent.action.")) return action.substring("android.intent.action.".length());
        if (action.startsWith("android.net.conn."))       return action.substring("android.net.conn.".length());
        if (action.startsWith("android.net."))            return action.substring("android.net.".length());
        if (action.startsWith("com.android."))            return action.substring("com.android.".length());
        return action;
    }

    // -------------------------------------------------------------------------
    // 3. Alarms
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeAlarms(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys alarm");

        if (output == null || output.trim().isEmpty()) return list;

        int wakeupCount  = 0;
        int normalCount  = 0;
        long minInterval = Long.MAX_VALUE;
        boolean hasExact = false;

        for (String line : output.split("\n")) {
            if (!line.contains(packageName)) continue;
            boolean isWakeup = line.contains("RTC_WAKEUP") || line.contains("ELAPSED_WAKEUP")
                    || line.contains("*walarm*");
            if (isWakeup) wakeupCount++; else normalCount++;
            if (line.contains("*walarm*")) hasExact = true;
            Matcher m = Pattern.compile("repeatInterval=(\\d+)").matcher(line);
            if (m.find()) {
                long interval = Long.parseLong(m.group(1));
                if (interval > 0 && interval < minInterval) minInterval = interval;
            }
        }

        if (wakeupCount + normalCount == 0) return list;

        // detail
        StringBuilder detail = new StringBuilder();
        if (wakeupCount > 0) detail.append(wakeupCount).append(" будящих");
        if (normalCount > 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(normalCount).append(" обычных");
        }
        if (minInterval != Long.MAX_VALUE) detail.append(" · повтор ").append(formatInterval(minInterval));
        if (hasExact) detail.append(" · setExact");

        // explanation
        StringBuilder explanation = new StringBuilder();
        if (wakeupCount > 0) {
            explanation.append(context.getString(R.string.triggers_alarms_wakeup_explanation));
            if (minInterval != Long.MAX_VALUE && minInterval < 60_000) {
                explanation.append(context.getString(R.string.triggers_alarms_wakeup_aggressive));
            } else if (minInterval != Long.MAX_VALUE && minInterval < 300_000) {
                explanation.append(context.getString(R.string.triggers_alarms_wakeup_frequent));
            }
        } else {
            explanation.append(context.getString(R.string.triggers_alarms_normal_explanation));
        }
        if (hasExact) {
            explanation.append(context.getString(R.string.triggers_alarms_exact_explanation));
        }

        TriggerInfo.Severity severity = wakeupCount > 0
                ? (minInterval != Long.MAX_VALUE && minInterval < 120_000
                        ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM)
                : TriggerInfo.Severity.LOW;

        list.add(new TriggerInfo("Alarms", detail.toString(), explanation.toString(), severity));
        return list;
    }

    private String formatInterval(long ms) {
        long sec = ms / 1000;
        if (sec < 60)   return "каждые " + sec + " сек";
        if (sec < 3600) return "каждые " + (sec / 60) + " мин";
        return "каждые " + (sec / 3600) + " ч";
    }

    // -------------------------------------------------------------------------
    // 4. Jobs / WorkManager
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeJobs(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys jobscheduler");

        if (output == null || output.trim().isEmpty()) return list;

        int pending = 0;
        int running = 0;
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

        // detail
        StringBuilder detail = new StringBuilder();
        if (running > 0) detail.append(running).append(" выполняется прямо сейчас");
        if (pending > 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(pending).append(" ждут запуска");
        }

        // explanation
        String explanation;
        if (running > 0 && pending > 0) {
            explanation = context.getString(R.string.triggers_jobs_running_and_pending_explanation, running, pending);
        } else if (running > 0) {
            explanation = context.getString(R.string.triggers_jobs_running_explanation, running);
        } else {
            explanation = context.getString(R.string.triggers_jobs_pending_explanation, pending);
        }

        list.add(new TriggerInfo(
                "Jobs / WorkManager",
                detail.toString(),
                explanation,
                running > 0 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
        return list;
    }

    // -------------------------------------------------------------------------
    // 5. Doze exemption
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeDozeExemption(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys deviceidle | grep -E 'whitelist|except'");

        if (output == null || output.trim().isEmpty()) return list;

        for (String line : output.split("\n")) {
            if (!line.contains(packageName)) continue;
            boolean isSys = line.contains("sys-");
            list.add(new TriggerInfo(
                    "Doze Exempt",
                    context.getString(isSys
                            ? R.string.triggers_doze_sys_detail
                            : R.string.triggers_doze_user_detail),
                    context.getString(isSys
                            ? R.string.triggers_doze_sys_explanation
                            : R.string.triggers_doze_user_explanation),
                    TriggerInfo.Severity.HIGH));
            break;
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 6. WakeLocks
    // -------------------------------------------------------------------------

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
            Log.d(TAG, "dumpsys power: Wake Locks section not available");
            return list;
        }

        boolean inWakeLockSection = false;
        for (String line : powerOutput.split("\n")) {
            if (line.trim().startsWith("Wake Locks:"))                    { inWakeLockSection = true; continue; }
            if (inWakeLockSection && line.trim().startsWith("Suspend Blockers:")) break;
            if (!inWakeLockSection || !line.contains("uid=" + uid))       continue;

            int typeResId, explainResId;
            if (line.contains("PARTIAL_WAKE_LOCK")) {
                typeResId    = R.string.triggers_wakelock_partial_type;
                explainResId = R.string.triggers_wakelock_partial_explain;
            } else if (line.contains("FULL_WAKE_LOCK")) {
                typeResId    = R.string.triggers_wakelock_full_type;
                explainResId = R.string.triggers_wakelock_full_explain;
            } else {
                typeResId    = R.string.triggers_wakelock_generic_type;
                explainResId = R.string.triggers_wakelock_generic_explain;
            }

            String tag = "";
            Matcher tagMatcher = Pattern.compile("'([^']+)'").matcher(line);
            if (tagMatcher.find()) tag = tagMatcher.group(1);

            String held = "";
            Matcher timeMatcher = Pattern.compile("(\\d+m\\s*\\d+s|\\d+s)").matcher(line);
            if (timeMatcher.find()) held = ", " + timeMatcher.group(1);

            String type   = context.getString(typeResId);
            String detail = type + (tag.isEmpty() ? "" : " · " + tag) + held;
            String explanation = context.getString(R.string.triggers_wakelock_explanation,
                    context.getString(explainResId));

            list.add(new TriggerInfo("WakeLock", detail, explanation, TriggerInfo.Severity.HIGH));
        }
        return list;
    }
}
