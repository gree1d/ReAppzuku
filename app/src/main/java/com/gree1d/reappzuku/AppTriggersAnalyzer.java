package com.gree1d.reappzuku;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
        public final String category;
        public final String detail;
        public final String explanation;
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

        results.addAll(analyzeCallerApp(packageName));
        results.addAll(analyzeForegroundServices(packageName));
        results.addAll(analyzeStickyServices(packageName));
        results.addAll(analyzeBroadcastReceivers(packageName));
        results.addAll(analyzeContentProviders(packageName));
        results.addAll(analyzeSyncAdapters(packageName));
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
    // 0. Caller app
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeCallerApp(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys activity processes");
        if (output == null || output.trim().isEmpty()) return list;

        boolean inTargetProcess = false;
        String callerPkg = null;

        for (String line : output.split("\n")) {
            if (line.contains("ProcessRecord") && line.contains(packageName)) {
                inTargetProcess = true;
                callerPkg = null;
            }
            if (inTargetProcess && line.contains("ProcessRecord") && !line.contains(packageName)) {
                break;
            }
            if (!inTargetProcess) continue;

            Matcher m = Pattern.compile("clientPackage=([\\w.]+)").matcher(line);
            if (m.find()) { callerPkg = m.group(1); break; }

            Matcher m2 = Pattern.compile("callingPackage=([\\w.]+)").matcher(line);
            if (m2.find()) { callerPkg = m2.group(1); break; }
        }

        if (callerPkg == null || callerPkg.equals(packageName) || callerPkg.equals("android")) {
            return list;
        }

        String callerName = resolveAppName(callerPkg);
        String detail = callerName + " (" + callerPkg + ")";

        list.add(new TriggerInfo(
                context.getString(R.string.triggers_cat_caller),
                detail,
                context.getString(R.string.triggers_caller_explanation, callerName),
                TriggerInfo.Severity.HIGH));
        return list;
    }

    private String resolveAppName(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
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
                list.add(new TriggerInfo(
                        context.getString(R.string.triggers_cat_fg_service),
                        currentService != null ? currentService : packageName,
                        context.getString(R.string.triggers_fg_service_explanation),
                        TriggerInfo.Severity.HIGH));
                inTargetPackage = false;
                currentService = null;
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 2. Sticky Services
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeStickyServices(String packageName) {
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
            // START_STICKY = 1, проверяем оба варианта написания
            if (inTargetPackage
                    && (line.contains("START_STICKY") || line.contains("startRequested=true"))
                    && !line.contains("isForeground=true")) {  // foreground уже покрыт выше
                list.add(new TriggerInfo(
                        context.getString(R.string.triggers_cat_sticky),
                        currentService != null ? currentService : packageName,
                        context.getString(R.string.triggers_sticky_explanation),
                        TriggerInfo.Severity.HIGH));
                inTargetPackage = false;
                currentService = null;
            }
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 3. Broadcast Receivers
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

        int shown = Math.min(actions.size(), 5);
        StringBuilder detail = new StringBuilder(String.join(", ", actions.subList(0, shown)));
        if (actions.size() > shown) {
            detail.append(context.getString(R.string.triggers_receivers_detail_overflow,
                    actions.size() - shown));
        }

        boolean hasBootReceiver = actions.stream().anyMatch(
                a -> a.contains("BOOT") || a.contains("LOCKED_BOOT"));
        boolean hasConnectivity = actions.stream().anyMatch(
                a -> a.contains("CONNECTIVITY") || a.contains("NETWORK"));

        StringBuilder explanation = new StringBuilder(
                context.getString(R.string.triggers_receivers_explanation_base));
        if (hasBootReceiver) explanation.append(context.getString(R.string.triggers_receivers_explanation_boot));
        if (hasConnectivity) explanation.append(context.getString(R.string.triggers_receivers_explanation_network));

        list.add(new TriggerInfo(
                context.getString(R.string.triggers_cat_receivers, actions.size()),
                detail.toString(),
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
    // 4. Content Providers
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeContentProviders(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys package " + packageName);
        if (output == null || output.trim().isEmpty()) return list;

        boolean inProviderSection = false;
        List<String> authorities = new ArrayList<>();

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Provider #")) {
                inProviderSection = true;
            }
            if (inProviderSection && trimmed.startsWith("authority=")) {
                String auth = trimmed.replaceFirst("authority=", "").trim();
                // Убираем packageName-prefix для краткости
                if (auth.startsWith(packageName + ".")) {
                    auth = auth.substring(packageName.length() + 1);
                }
                if (!authorities.contains(auth)) authorities.add(auth);
            }
            // Следующая секция — выходим
            if (inProviderSection && trimmed.startsWith("Activity #")) break;
        }

        if (authorities.isEmpty()) return list;

        int shown = Math.min(authorities.size(), 3);
        String detail = String.join(", ", authorities.subList(0, shown));
        if (authorities.size() > shown) detail += " (+" + (authorities.size() - shown) + ")";

        list.add(new TriggerInfo(
                context.getString(R.string.triggers_cat_provider),
                detail,
                context.getString(R.string.triggers_provider_explanation),
                TriggerInfo.Severity.LOW));
        return list;
    }

    // -------------------------------------------------------------------------
    // 5. Sync Adapters
    // -------------------------------------------------------------------------

    private List<TriggerInfo> analyzeSyncAdapters(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys content | grep -A3 " + packageName);
        if (output == null || output.trim().isEmpty()) return list;

        int accountCount = 0;
        for (String line : output.split("\n")) {
            if (line.contains(packageName) && line.contains("accountType")) {
                accountCount++;
            }
        }

        if (accountCount == 0) {
            String pkgOutput = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys package " + packageName + " | grep -i sync");
            if (pkgOutput != null && pkgOutput.toLowerCase().contains("syncadapter")) {
                accountCount = 1;
            }
        }

        if (accountCount == 0) return list;

        list.add(new TriggerInfo(
                context.getString(R.string.triggers_cat_sync),
                context.getString(R.string.triggers_sync_detail, accountCount),
                context.getString(R.string.triggers_sync_explanation),
                TriggerInfo.Severity.MEDIUM));
        return list;
    }

    // -------------------------------------------------------------------------
    // 6. Alarms
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

        StringBuilder detail = new StringBuilder();
        if (wakeupCount > 0) {
            detail.append(context.getString(R.string.triggers_alarms_detail_wakeup, wakeupCount));
        }
        if (normalCount > 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(context.getString(R.string.triggers_alarms_detail_normal, normalCount));
        }
        if (minInterval != Long.MAX_VALUE) {
            detail.append(context.getString(R.string.triggers_alarms_detail_repeat,
                    formatInterval(minInterval)));
        }
        if (hasExact) {
            detail.append(context.getString(R.string.triggers_alarms_detail_exact));
        }

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

        list.add(new TriggerInfo(
                context.getString(R.string.triggers_cat_alarms),
                detail.toString(),
                explanation.toString(),
                severity));
        return list;
    }

    private String formatInterval(long ms) {
        long sec = ms / 1000;
        if (sec < 60)   return context.getString(R.string.triggers_alarms_interval_sec, (int) sec);
        if (sec < 3600) return context.getString(R.string.triggers_alarms_interval_min, (int) (sec / 60));
        return context.getString(R.string.triggers_alarms_interval_hour, (int) (sec / 3600));
    }

    // -------------------------------------------------------------------------
    // 7. Jobs / WorkManager
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
        if (running > 0) {
            detail.append(context.getString(R.string.triggers_jobs_detail_running, running));
        }
        if (pending > 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(context.getString(R.string.triggers_jobs_detail_pending, pending));
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
                context.getString(R.string.triggers_cat_jobs),
                detail.toString(),
                explanation,
                running > 0 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
        return list;
    }

    // -------------------------------------------------------------------------
    // 8. Doze exemption
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
                    context.getString(R.string.triggers_cat_doze),
                    context.getString(isSys ? R.string.triggers_doze_sys_detail : R.string.triggers_doze_user_detail),
                    context.getString(isSys ? R.string.triggers_doze_sys_explanation : R.string.triggers_doze_user_explanation),
                    TriggerInfo.Severity.HIGH));
            break;
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // 9. WakeLocks
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
            if (line.trim().startsWith("Wake Locks:"))                            { inWakeLockSection = true; continue; }
            if (inWakeLockSection && line.trim().startsWith("Suspend Blockers:")) break;
            if (!inWakeLockSection || !line.contains("uid=" + uid))               continue;

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
            if (timeMatcher.find()) {
                held = context.getString(R.string.triggers_wakelock_detail_held, timeMatcher.group(1));
            }

            String type   = context.getString(typeResId);
            String detail = type + (tag.isEmpty() ? "" : " · " + tag) + held;
            String explanation = context.getString(R.string.triggers_wakelock_explanation,
                    context.getString(explainResId));

            list.add(new TriggerInfo(
                    context.getString(R.string.triggers_cat_wakelock),
                    detail,
                    explanation,
                    TriggerInfo.Severity.HIGH));
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractServiceShortName(String line, String packageName) {
        Matcher m = Pattern.compile("ServiceRecord\\{[^}]+\\s([\\w./]+)\\}").matcher(line);
        if (!m.find()) return null;
        String fullName = m.group(1);
        if (fullName.contains("/")) {
            String className = fullName.substring(fullName.indexOf('/') + 1);
            if (className.startsWith(".")) return className.substring(1);
            if (className.startsWith(packageName + ".")) return className.substring(packageName.length() + 1);
            return className;
        }
        return fullName;
    }
}
