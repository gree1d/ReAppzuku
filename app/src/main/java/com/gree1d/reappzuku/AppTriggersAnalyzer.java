package com.gree1d.reappzuku;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppTriggersAnalyzer {

    private static final String TAG = "AppTriggersAnalyzer";

    public static final class TriggerInfo {

        public enum Group { ACTIVE_NOW, CAN_WAKE, OTHER }

        public enum Severity { HIGH, MEDIUM, LOW, INFO }

        public final Group    group;
        public final String   category;
        public final String   detail;
        public final String   explanation;
        public final Severity severity;

        public TriggerInfo(Group group, String category, String detail,
                           String explanation, Severity severity) {
            this.group       = group;
            this.category    = category;
            this.detail      = detail;
            this.explanation = explanation;
            this.severity    = severity;
        }

        public TriggerInfo(String category, String detail,
                           String explanation, Severity severity) {
            this(Group.OTHER, category, detail, explanation, severity);
        }
    }

    private final ShellManager   shellManager;
    private final Context        context;

    private String cachedUid = null;

    public AppTriggersAnalyzer(Context context, ShellManager shellManager) {
        this.context        = context.getApplicationContext();
        this.shellManager   = shellManager;
    }

    public List<TriggerInfo> analyze(String packageName) {
        cachedUid = resolveUid(packageName);

        List<TriggerInfo> results = new ArrayList<>();

        safeAdd(results, () -> analyzeProcessState(packageName));
        safeAdd(results, () -> analyzeServicesAndBindings(packageName));
        safeAdd(results, () -> analyzeFgNotification(packageName));
        safeAdd(results, () -> analyzeWakelocks(packageName));
        safeAdd(results, () -> analyzeNetworkActivity(packageName));
        safeAdd(results, () -> analyzeSensors(packageName));
        safeAdd(results, () -> analyzeLocationRequests(packageName));
        safeAdd(results, () -> analyzeAudioFocus(packageName));
        safeAdd(results, () -> analyzeBluetooth(packageName));

        safeAdd(results, () -> analyzeAlarms(packageName));
        safeAdd(results, () -> analyzeJobs(packageName));
        safeAdd(results, () -> analyzePendingIntents(packageName));
        safeAdd(results, () -> analyzeExcessiveWakeups(packageName));
        safeAdd(results, () -> analyzeContentObservers(packageName));
        safeAdd(results, () -> analyzeFcmRegistration(packageName));
        safeAdd(results, () -> analyzeAppOps(packageName));

        safeAdd(results, () -> analyzeChainLaunch(packageName));
        safeAdd(results, () -> analyzeBroadcastReceivers(packageName));
        safeAdd(results, () -> analyzeBootReceivers(packageName));
        safeAdd(results, () -> analyzeContentProviders(packageName));
        safeAdd(results, () -> analyzeSyncAdapters(packageName));
        safeAdd(results, () -> analyzeDozeExemption(packageName));
        safeAdd(results, () -> analyzeStandbyBucket(packageName));
        safeAdd(results, () -> analyzeBatteryStats(packageName));
        safeAdd(results, () -> analyzeBroadcastEfficiency(packageName));
        safeAdd(results, () -> analyzeMultipleProcesses(packageName));
        safeAdd(results, () -> analyzeAccessibilityAndIme(packageName));
        safeAdd(results, () -> analyzeDeviceAdmin(packageName));
        safeAdd(results, () -> analyzeUsageStats(packageName));

        if (results.isEmpty()) {
            results.add(new TriggerInfo(
                    context.getString(R.string.triggers_none_title),
                    context.getString(R.string.triggers_none_detail),
                    context.getString(R.string.triggers_none_explanation),
                    TriggerInfo.Severity.INFO));
        }

        return results;
    }

    public enum AppStatus { ACTIVE, BACKGROUND, CACHED }

    public AppStatus resolveAppStatus(String packageName) {
        Log.d(TAG, "resolveAppStatus: start pkg=" + packageName);
        try {

            String psOutput = shellManager.runShellCommandAndGetFullOutput(
                    "ps -eo pid,name | grep " + packageName);
            Log.d(TAG, "resolveAppStatus: ps output=" + (psOutput != null ? psOutput.trim() : "null"));

            if (psOutput == null || psOutput.trim().isEmpty()) {
                Log.d(TAG, "resolveAppStatus: result=null (not running)");
                return null;
            }

            String pid = null;
            for (String line : psOutput.trim().split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                if (parts[1].equals(packageName)) {
                    pid = parts[0];
                    break;
                }
                if (pid == null && parts[1].startsWith(packageName)) {
                    pid = parts[0];
                }
            }

            if (pid == null) {
                Log.d(TAG, "resolveAppStatus: result=null (pid not parsed)");
                return null;
            }

            String adjStr = shellManager.runShellCommandAndGetFullOutput(
                    "cat /proc/" + pid + "/oom_score_adj");
            Log.d(TAG, "resolveAppStatus: pid=" + pid + " oom_score_adj=" + (adjStr != null ? adjStr.trim() : "null"));

            if (adjStr == null || adjStr.trim().isEmpty()) {

                Log.d(TAG, "resolveAppStatus: result=null (oom_score_adj unreadable)");
                return null;
            }

            int adj = Integer.parseInt(adjStr.trim());

            if (adj <= 224) {
                Log.d(TAG, "resolveAppStatus: result=ACTIVE (adj=" + adj + ")");
                return AppStatus.ACTIVE;
            }
            if (adj <= 499) {
                Log.d(TAG, "resolveAppStatus: result=BACKGROUND (adj=" + adj + ")");
                return AppStatus.BACKGROUND;
            }
            Log.d(TAG, "resolveAppStatus: result=CACHED (adj=" + adj + ")");
            return AppStatus.CACHED;

        } catch (NumberFormatException e) {
            Log.w(TAG, "resolveAppStatus: oom_score_adj parse error: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "resolveAppStatus failed: " + e.getMessage());
            return null;
        }
    }

    private interface Analyzer { List<TriggerInfo> run() throws Exception; }

    private void safeAdd(List<TriggerInfo> out, Analyzer a) {
        try {
            List<TriggerInfo> partial = a.run();
            if (partial != null) out.addAll(partial);
        } catch (Exception e) {
            Log.w(TAG, "analyzer failed: " + e.getMessage());
        }
    }

    private List<TriggerInfo> analyzeProcessState(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys activity processes");
        if (output == null || output.trim().isEmpty()) return list;

        boolean inBlock    = false;
        int     adj        = Integer.MAX_VALUE;
        String  procState  = null;
        boolean persistent = false;

        Pattern procPat  = Pattern.compile(
                "ProcessRecord\\{[^}]+\\s" + Pattern.quote(packageName) + "/");
        Pattern adjPat   = Pattern.compile("\\badj=([-\\d]+)");
        Pattern statePat = Pattern.compile("\\bcurProcState=([A-Z_]+)");

        for (String line : output.split("\n")) {
            if (procPat.matcher(line).find()) {
                inBlock    = true;
                persistent = line.contains("persistent=true");
                continue;
            }
            if (inBlock && line.trim().startsWith("ProcessRecord{")
                    && !line.contains(packageName)) break;
            if (!inBlock) continue;

            Matcher mAdj = adjPat.matcher(line);
            if (mAdj.find() && adj == Integer.MAX_VALUE)
                adj = Integer.parseInt(mAdj.group(1));

            Matcher mState = statePat.matcher(line);
            if (mState.find() && procState == null)
                procState = mState.group(1);

            if (line.contains("persistent=true")) persistent = true;
        }

        if (procState == null && adj == Integer.MAX_VALUE) return list;

        String label = mapProcState(procState, adj);

        TriggerInfo.Severity severity;
        TriggerInfo.Group    group;
        if (persistent || "PERSISTENT".equals(procState)) {
            severity = TriggerInfo.Severity.HIGH;   group = TriggerInfo.Group.ACTIVE_NOW;
        } else if (adj != Integer.MAX_VALUE && adj <= 200) {
            severity = TriggerInfo.Severity.HIGH;   group = TriggerInfo.Group.ACTIVE_NOW;
        } else if (adj != Integer.MAX_VALUE && adj <= 500) {
            severity = TriggerInfo.Severity.MEDIUM; group = TriggerInfo.Group.ACTIVE_NOW;
        } else {
            severity = TriggerInfo.Severity.LOW;    group = TriggerInfo.Group.OTHER;
        }

        String detail = label + (adj != Integer.MAX_VALUE ? " (adj=" + adj + ")" : "");
        if (persistent) detail += ", " + context.getString(R.string.triggers_proc_persistent);

        list.add(new TriggerInfo(group,
                context.getString(R.string.triggers_cat_proc_state),
                detail,
                context.getString(R.string.triggers_proc_state_explanation, label),
                severity));
        return list;
    }

    private String mapProcState(String state, int adj) {
        if (state != null) switch (state) {
            case "PERSISTENT":               return "Persistent";
            case "TOP":                      return "Foreground (Top)";
            case "BOUND_TOP":                return "Bound to Top";
            case "FOREGROUND_SERVICE":       return "Foreground Service";
            case "BOUND_FOREGROUND_SERVICE": return "Bound FG Service";
            case "IMPORTANT_FOREGROUND":     return "Important Foreground";
            case "IMPORTANT_BACKGROUND":     return "Important Background";
            case "TRANSIENT_BACKGROUND":     return "Transient Background";
            case "BACKUP":                   return "Backup";
            case "SERVICE":                  return "Service";
            case "RECEIVER":                 return "Receiver";
            case "HOME":                     return "Home";
            case "LAST_ACTIVITY":            return "Last Activity";
            case "CACHED_ACTIVITY":          return "Cached (Activity)";
            case "CACHED_ACTIVITY_CLIENT":   return "Cached (Client)";
            case "CACHED_EMPTY":             return "Cached (Empty)";
            default:                         return state;
        }
        if (adj <= 0)   return "Persistent";
        if (adj <= 100) return "Foreground";
        if (adj <= 200) return "Visible";
        if (adj <= 500) return "Service";
        return "Cached";
    }

    private static final Pattern[] BINDER_PATS = {
            Pattern.compile("ProcessRecord\\{[^}]+\\s([\\w.]+)/"),
            Pattern.compile("client=ProcessRecord\\{[^}]+\\s([\\w.]+)/")
    };

    private List<TriggerInfo> analyzeServicesAndBindings(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys activity services " + packageName);
        if (output == null || output.trim().isEmpty()) return list;

        boolean inBlock       = false;
        String  currentSvc    = null;
        String  fgType        = null;
        boolean killable      = true;
        String  notifChannel  = null;
        String  notifImport   = null;
        List<String> binders  = new ArrayList<>();

        for (String line : output.split("\n")) {
            String t = line.trim();

            if (t.contains("ServiceRecord") && t.contains(packageName)) {
                inBlock      = true;
                currentSvc   = extractServiceShortName(t, packageName);
                fgType       = null;
                killable     = true;
                notifChannel = null;
                notifImport  = null;
                continue;
            }
            if (inBlock && t.contains("ServiceRecord") && !t.contains(packageName)) {
                inBlock = false;
            }
            if (!inBlock) continue;

            Matcher mFgType = Pattern.compile("foregroundServiceType=(\\S+)").matcher(t);
            if (mFgType.find()) fgType = parseForegroundServiceType(mFgType.group(1));

            if (t.contains("stopWithTask=false") || t.contains("persistentProcess=true"))
                killable = false;

            Matcher mChan = Pattern.compile("channelId=([\\w.\\-]+)").matcher(t);
            if (mChan.find()) notifChannel = mChan.group(1);

            Matcher mImp = Pattern.compile("importance=(\\d+)").matcher(t);
            if (mImp.find()) notifImport = mapNotifImportance(Integer.parseInt(mImp.group(1)));


            if (t.contains("isForeground=true")) {
                String svcName = currentSvc != null ? currentSvc : packageName;
                StringBuilder detail = new StringBuilder(svcName);
                if (fgType       != null) detail.append(" [").append(fgType).append("]");
                if (notifChannel != null) detail.append(" · ch:").append(notifChannel);
                if (notifImport  != null) detail.append(" · notif:").append(notifImport);
                detail.append(" · ").append(killable
                        ? context.getString(R.string.triggers_fg_service_killable)
                        : context.getString(R.string.triggers_fg_service_not_killable));

                list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                        context.getString(R.string.triggers_cat_fg_service),
                        detail.toString(),
                        context.getString(R.string.triggers_fg_service_explanation),
                        TriggerInfo.Severity.HIGH));
            }


            if ((t.contains("START_STICKY") || t.contains("startRequested=true"))
                    && !t.contains("isForeground=true")) {
                list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                        context.getString(R.string.triggers_cat_sticky),
                        currentSvc != null ? currentSvc : packageName,
                        context.getString(R.string.triggers_sticky_explanation),
                        TriggerInfo.Severity.HIGH));
            }


            for (Pattern bp : BINDER_PATS) {
                Matcher m = bp.matcher(t);
                if (m.find()) {
                    String pkg = m.group(1);
                    if (!pkg.equals(packageName) && !pkg.equals("android")
                            && !binders.contains(pkg)) binders.add(pkg);
                }
            }
        }

        if (!binders.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            StringBuilder expl   = new StringBuilder(
                    context.getString(R.string.triggers_bindings_explanation_base));
            for (int i = 0; i < Math.min(binders.size(), 4); i++) {
                if (i > 0) detail.append(", ");
                String p = binders.get(i);
                detail.append(resolveAppName(p)).append(" (").append(p).append(")");
            }
            if (binders.size() > 4)
                detail.append(context.getString(
                        R.string.triggers_bindings_overflow, binders.size() - 4));
            if (anyContains(binders, "google.gms", "gms"))
                expl.append(context.getString(R.string.triggers_bindings_gms_note));
            if (anyContains(binders, "push", "firebase", "fcm"))
                expl.append(context.getString(R.string.triggers_bindings_push_note));

            list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                    context.getString(R.string.triggers_cat_bindings, binders.size()),
                    detail.toString(), expl.toString(),
                    TriggerInfo.Severity.HIGH));
        }

        return list;
    }

    private String parseForegroundServiceType(String raw) {
        try {
            int mask = Integer.parseInt(raw);
            if (mask == 0) return "NONE";
            Object[][] bits = {
                {0x001,"DATA_SYNC"},{0x002,"MEDIA_PLAYBACK"},{0x004,"PHONE_CALL"},
                {0x008,"LOCATION"},{0x010,"CONNECTED_DEVICE"},{0x020,"MEDIA_PROJECTION"},
                {0x040,"CAMERA"},{0x080,"MICROPHONE"},{0x100,"HEALTH"},
                {0x200,"REMOTE_MESSAGING"},{0x400,"SYSTEM_EXEMPTED"},{0x800,"SHORT_SERVICE"}
            };
            StringBuilder sb = new StringBuilder();
            for (Object[] b : bits) if ((mask & (int) b[0]) != 0) {
                if (sb.length() > 0) sb.append("|");
                sb.append((String) b[1]);
            }
            return sb.length() > 0 ? sb.toString() : raw;
        } catch (NumberFormatException ignored) { return raw; }
    }

    private String mapNotifImportance(int imp) {
        switch (imp) {
            case 5: return "URGENT";
            case 4: return "HIGH";
            case 3: return "DEFAULT";
            case 2: return "LOW";
            case 1: return "MIN";
            default: return "NONE";
        }
    }

    private List<TriggerInfo> analyzeWakelocks(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();

        String powerOutput = shellManager.runShellCommandAndGetFullOutput("dumpsys power");
        if (powerOutput == null || powerOutput.trim().isEmpty()) return list;


        StringBuilder wlBlock = new StringBuilder();
        boolean inSection = false;
        for (String line : powerOutput.split("\n")) {
            if (line.trim().startsWith("Wake Locks:"))          { inSection = true;  continue; }
            if (inSection && line.trim().startsWith("Suspend Blockers:")) break;
            if (inSection) wlBlock.append(line).append("\n");
        }

        if (wlBlock.length() > 0) {
            Pattern heldMsPat  = Pattern.compile("held=(\\d+)ms");
            Pattern acquirePat = Pattern.compile("acq(?:uire)?[=:](\\d+)");
            Pattern releasePat = Pattern.compile("rel(?:ease)?[=:](\\d+)");
            Pattern heldLegacy = Pattern.compile("(\\d+m\\s*\\d+s|\\d+s)");
            Pattern tagPat     = Pattern.compile("'([^']{1,60})'");
            String  uid        = cachedUid;

            for (String line : wlBlock.toString().split("\n")) {
                boolean byUid = uid != null && line.contains("uid=" + uid);
                boolean byTag = line.contains(packageName);
                if (!byUid && !byTag) continue;


                String typeLabel, typeExplain;
                if      (line.contains("PARTIAL"))      { typeLabel="Partial";   typeExplain=context.getString(R.string.triggers_wakelock_partial_explain); }
                else if (line.contains("FULL"))         { typeLabel="Full";      typeExplain=context.getString(R.string.triggers_wakelock_full_explain); }
                else if (line.contains("SCREEN"))       { typeLabel="Screen";    typeExplain=context.getString(R.string.triggers_wakelock_screen_explain); }
                else if (line.contains("PROXIMITY"))    { typeLabel="Proximity"; typeExplain=context.getString(R.string.triggers_wakelock_proximity_explain); }
                else                                    { typeLabel="WakeLock";  typeExplain=context.getString(R.string.triggers_wakelock_generic_explain); }


                String tag = "";
                Matcher mTag = tagPat.matcher(line);
                if (mTag.find()) tag = mTag.group(1);


                String heldStr = "";
                Matcher mHeldMs = heldMsPat.matcher(line);
                if (mHeldMs.find()) {
                    heldStr = formatDuration(Long.parseLong(mHeldMs.group(1)));
                } else {
                    Matcher mLeg = heldLegacy.matcher(line);
                    if (mLeg.find()) heldStr = mLeg.group(1);
                }


                String acqRel = "";
                Matcher mAcq = acquirePat.matcher(line);
                Matcher mRel = releasePat.matcher(line);
                if (mAcq.find() && mRel.find()) {
                    acqRel = context.getString(R.string.triggers_wakelock_acq_rel,
                            Integer.parseInt(mAcq.group(1)), Integer.parseInt(mRel.group(1)));
                } else if (mAcq.find()) {
                    acqRel = context.getString(R.string.triggers_wakelock_acq_only,
                            Integer.parseInt(mAcq.group(1)));
                }

                StringBuilder detail = new StringBuilder(typeLabel);
                if (!tag.isEmpty())     detail.append(" · ").append(tag);
                if (!heldStr.isEmpty()) detail.append(" · ")
                        .append(context.getString(R.string.triggers_wakelock_detail_held, heldStr));
                if (!acqRel.isEmpty())  detail.append(" · ").append(acqRel);

                if (byTag && !byUid)
                    detail.append(" ").append(context.getString(R.string.triggers_wakelock_held_by_system));

                list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                        context.getString(R.string.triggers_cat_wakelock),
                        detail.toString(),
                        context.getString(R.string.triggers_wakelock_explanation, typeExplain),
                        TriggerInfo.Severity.HIGH));
            }
        }

        if (list.isEmpty()) {
            String bsOut = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys batterystats " + packageName);
            if (bsOut != null) {
                Pattern p = Pattern.compile(
                        "Wakelock\\s+(\\S+):\\s+(\\d+)ms realtime.*?\\((\\d+)\\s+times\\)",
                        Pattern.CASE_INSENSITIVE);
                for (String line : bsOut.split("\n")) {
                    Matcher m = p.matcher(line);
                    if (!m.find()) continue;
                    long heldMs = Long.parseLong(m.group(2));
                    int  count  = Integer.parseInt(m.group(3));
                    if (heldMs == 0 && count == 0) continue;
                    list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                            context.getString(R.string.triggers_cat_wakelock),
                            context.getString(R.string.triggers_wakelock_fallback_detail,
                                    m.group(1), formatDuration(heldMs), count),
                            context.getString(R.string.triggers_wakelock_fallback_explanation),
                            count > 10 || heldMs > 60_000
                                    ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
                }
            }
        }

        return list;
    }

    private List<TriggerInfo> analyzeNetworkActivity(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String uid = cachedUid;
        if (uid == null) return list;


        long rxBytes = 0, txBytes = 0;
        String netstats = null;
        try {
            netstats = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys netstats detail | grep -A5 uid=" + uid);
            if (netstats == null || netstats.trim().isEmpty())
                netstats = shellManager.runShellCommandAndGetFullOutput(
                        "dumpsys netstats | grep " + packageName);
        } catch (Exception e) { Log.w(TAG, "netstats failed: " + e.getMessage()); }
        if (netstats != null) {
            Matcher mRx = Pattern.compile("rxBytes=(\\d+)").matcher(netstats);
            Matcher mTx = Pattern.compile("txBytes=(\\d+)").matcher(netstats);
            while (mRx.find()) rxBytes += Long.parseLong(mRx.group(1));
            while (mTx.find()) txBytes += Long.parseLong(mTx.group(1));
        }

        List<String> established = new ArrayList<>();
        for (String procFile : new String[]{"tcp6", "tcp"}) {
            try {
                String raw = shellManager.runShellCommandAndGetFullOutput(
                        "grep ' " + uid + " ' /proc/net/" + procFile + " 2>/dev/null");
                if (raw == null) continue;
                for (String line : raw.split("\n")) {
                    String[] cols = line.trim().split("\\s+");
                    if (cols.length < 4) continue;
                    if (!"01".equals(cols[3])) continue;
                    String remote = hexToAddress(cols.length > 2 ? cols[2] : "", procFile.equals("tcp6"));
                    if (!established.contains(remote) && established.size() < 5)
                        established.add(remote);
                }
            } catch (Exception e) { Log.w(TAG, "proc/net/" + procFile + " failed: " + e.getMessage()); }
        }

        long total = rxBytes + txBytes;
        if (total < 10 * 1024 && established.isEmpty()) return list;

        StringBuilder detail = new StringBuilder();
        if (!established.isEmpty()) {
            detail.append(context.getString(
                    R.string.triggers_network_established, established.size()));
            detail.append(": ").append(String.join(", ", established));
        }
        if (total > 0) {
            if (detail.length() > 0) detail.append(", ");
            detail.append(context.getString(R.string.triggers_network_traffic,
                    formatBytes(rxBytes), formatBytes(txBytes)));
        }

        TriggerInfo.Severity sev = !established.isEmpty() ? TriggerInfo.Severity.HIGH
                : total > 1024 * 1024 ? TriggerInfo.Severity.MEDIUM : TriggerInfo.Severity.LOW;

        list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                context.getString(R.string.triggers_cat_network),
                detail.toString(),
                context.getString(R.string.triggers_network_explanation),
                sev));
        return list;
    }

    private String hexToAddress(String hex, boolean is6) {
        try {
            String[] parts = hex.split(":");
            if (parts.length < 2) return hex;
            int port = Integer.parseInt(parts[1], 16);
            if (!is6 && parts[0].length() == 8) {
                long a = Long.parseLong(parts[0], 16);
                return String.format("%d.%d.%d.%d:%d",
                        a & 0xFF, (a >> 8) & 0xFF, (a >> 16) & 0xFF, (a >> 24) & 0xFF, port);
            }
            return "*:" + port;
        } catch (Exception e) { return hex; }
    }

    private List<TriggerInfo> analyzeSensors(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();

        List<String> sensors = parseSensorService(packageName);
        if (sensors.isEmpty()) sensors = parseSensorsBatteryStats(packageName);
        if (sensors.isEmpty()) return list;

        boolean heavy = sensors.stream()
                .anyMatch(s -> s.startsWith("GPS") || s.startsWith("Gyro")
                        || s.startsWith("Baro"));

        list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                context.getString(R.string.triggers_cat_sensors, sensors.size()),
                String.join(", ", sensors.subList(0, Math.min(sensors.size(), 6))),
                context.getString(R.string.triggers_sensors_explanation),
                heavy ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
        return list;
    }

    private List<String> parseSensorService(String packageName) {
        List<String> result = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys sensorservice");
        if (output == null || output.trim().isEmpty()) return result;

        boolean inConn = false, relevant = false;
        List<String> found = new ArrayList<>();

        for (String line : output.split("\n")) {
            String t = line.trim();

            if (t.startsWith("Connection Number:") || t.startsWith("Active connections")) {
                if (relevant && !found.isEmpty())
                    for (String s : found) if (!result.contains(s)) result.add(s);
                inConn = true; relevant = false; found.clear();
                continue;
            }
            if (!inConn) continue;


            if (t.startsWith("packageName=") || t.startsWith("package=")
                    || t.startsWith("Identity="))
                relevant = t.contains(packageName);

            if (!relevant) continue;

            if (t.startsWith("Sensor:") || t.startsWith("SensorName=")
                    || t.startsWith("sensor=")) {
                String raw = t.replaceFirst("(?:Sensor:|SensorName=|sensor=)\\s*", "");
                int delim = raw.indexOf("  ");
                if (delim > 0) raw = raw.substring(0, delim).trim();

                String rate = "";
                Matcher mUs = Pattern.compile("samplingPeriod[Uu]s[=:]\\s*(\\d+)").matcher(t);
                if (mUs.find()) {
                    long us = Long.parseLong(mUs.group(1));
                    if (us > 0) rate = "@" + (1_000_000L / us) + "Hz";
                } else {
                    Matcher mHz = Pattern.compile("rate[=:]\\s*(\\d+)\\s*[Hh]z").matcher(t);
                    if (mHz.find()) rate = "@" + mHz.group(1) + "Hz";
                }
                String label = classifySensor(raw) + (rate.isEmpty() ? "" : " " + rate);
                if (!found.contains(label)) found.add(label);
            }
            if (t.contains("GNSS") || t.contains("Gnss") || t.contains("GPS"))
                if (!found.contains("GPS")) found.add("GPS");
        }
        if (relevant && !found.isEmpty())
            for (String s : found) if (!result.contains(s)) result.add(s);
        return result;
    }

    private List<String> parseSensorsBatteryStats(String packageName) {
        List<String> result = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys batterystats " + packageName);
        if (output == null) return result;

        boolean inPkg = false;
        Pattern srPat = Pattern.compile(
                "Sensor\\s+(?:#)?(\\d+)[^:]*:\\s*(.*)", Pattern.CASE_INSENSITIVE);
        for (String line : output.split("\n")) {
            if (line.contains(packageName)) inPkg = true;
            if (!inPkg) continue;
            Matcher m = srPat.matcher(line.trim());
            if (!m.find()) continue;
            int    handle = Integer.parseInt(m.group(1));
            String rest   = m.group(2).trim();
            String dur    = "";
            Matcher mDur = Pattern.compile("(\\d+)ms").matcher(rest);
            if (mDur.find()) dur = " (" + formatDuration(Long.parseLong(mDur.group(1))) + ")";
            String label = sensorHandleToName(handle) + dur;
            if (!result.contains(label)) result.add(label);
        }
        return result;
    }

    private String sensorHandleToName(int h) {
        switch (h) {
            case 1:  return "Accelerometer";   case 2:  return "Magnetometer";
            case 3:  return "Orientation";     case 4:  return "Gyroscope";
            case 5:  return "Light";           case 6:  return "Pressure";
            case 8:  return "Proximity";       case 9:  return "Gravity";
            case 10: return "Linear Accel";    case 11: return "Rotation Vector";
            case 14: return "Uncal Magneto";   case 15: return "Game Rotation";
            case 16: return "Uncal Gyro";      case 17: return "Step Detector";
            case 18: return "Step Counter";    case 19: return "Geo Rotation";
            case 21: return "Tilt Detector";   case 24: return "Pickup Gesture";
            case 28: return "Stationary";      case 29: return "Motion Detect";
            case 30: return "Heart Beat";      case 34: return "OffBody Detect";
            case 35: return "Uncal Accel";
            default: return "Sensor#" + h;
        }
    }

    private String classifySensor(String raw) {
        String n = raw.toLowerCase();
        if (n.contains("accelero"))                             return "Accelerometer";
        if (n.contains("gyro"))                                 return "Gyroscope";
        if (n.contains("magnet"))                               return "Magnetometer";
        if (n.contains("barometer") || n.contains("pressure")) return "Barometer";
        if (n.contains("proximity"))                            return "Proximity";
        if (n.contains("light"))                                return "Light";
        if (n.contains("gravity"))                              return "Gravity";
        if (n.contains("rotation"))                             return "Rotation";
        if (n.contains("step") || n.contains("pedometer"))     return "Pedometer";
        if (n.contains("heart") || n.contains("pulse"))        return "HeartRate";
        if (n.contains("gnss") || n.contains("gps"))           return "GPS";
        if (n.contains("temperature"))                          return "Temperature";
        if (n.contains("humidity"))                             return "Humidity";
        return raw.length() > 20 ? raw.substring(0, 20) : raw;
    }

    private List<TriggerInfo> analyzeLocationRequests(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys location");
        if (output == null || output.trim().isEmpty()) return list;

        int     reqCount  = 0;
        boolean hasFg = false, hasBg = false;
        String  bestAcc   = null;
        long    minIvMs   = Long.MAX_VALUE;
        long    activeGps = 0;

        Pattern reqPat  = Pattern.compile(
                "LocationRequest\\[([^]]+)].*?" + Pattern.quote(packageName),
                Pattern.CASE_INSENSITIVE);
        Pattern ivPat   = Pattern.compile("interval=(\\d+)");
        Pattern fgPat   = Pattern.compile("foreground=(true|false)");
        Pattern gpsPat  = Pattern.compile("activeGps(?:TimeMs)?=(\\d+)");
        Pattern accPat  = Pattern.compile(
                "(PRIORITY_HIGH_ACCURACY|HIGH_ACCURACY|PRIORITY_BALANCED|BALANCED"
                + "|PRIORITY_LOW_POWER|LOW_POWER|PRIORITY_NO_POWER|NO_POWER|PASSIVE)",
                Pattern.CASE_INSENSITIVE);

        boolean inBlock = false;
        for (String line : output.split("\n")) {
            String t = line.trim();
            boolean hasPkg = t.contains(packageName);

            if (reqPat.matcher(t).find()
                    || (hasPkg && t.startsWith("LocationRequest"))) {
                inBlock = true; reqCount++;
                Matcher mA = accPat.matcher(t);
                if (mA.find()) bestAcc = mergeAccuracy(bestAcc, normalizeAccuracy(mA.group(1)));
                Matcher mI = ivPat.matcher(t);
                if (mI.find()) { long iv=Long.parseLong(mI.group(1)); if(iv>0&&iv<minIvMs) minIvMs=iv; }
                continue;
            }
            if (inBlock && (t.isEmpty() || (!hasPkg && t.startsWith("LocationRequest"))))
                inBlock = false;
            if (!inBlock && !hasPkg) continue;

            Matcher mF = fgPat.matcher(t);
            if (mF.find()) { if("true".equalsIgnoreCase(mF.group(1))) hasFg=true; else hasBg=true; }
            Matcher mG = gpsPat.matcher(t);
            if (mG.find()) activeGps += Long.parseLong(mG.group(1));
        }

        if (reqCount == 0) return list;

        StringBuilder detail = new StringBuilder(
                context.getString(R.string.triggers_location_requests, reqCount));
        if (bestAcc != null) detail.append(" · ").append(bestAcc);
        detail.append(" · ").append(hasFg && hasBg ? context.getString(R.string.triggers_location_fg_bg)
                : hasFg ? context.getString(R.string.triggers_location_fg)
                        : context.getString(R.string.triggers_location_bg));
        if (minIvMs != Long.MAX_VALUE)
            detail.append(context.getString(R.string.triggers_location_interval, formatInterval(minIvMs)));
        if (activeGps > 0)
            detail.append(context.getString(R.string.triggers_location_active_gps, formatDuration(activeGps)));

        TriggerInfo.Severity sev = hasBg && "HIGH_ACCURACY".equals(bestAcc)
                ? TriggerInfo.Severity.HIGH
                : "HIGH_ACCURACY".equals(bestAcc) || hasBg
                        ? TriggerInfo.Severity.MEDIUM : TriggerInfo.Severity.LOW;

        list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                context.getString(R.string.triggers_cat_location),
                detail.toString(),
                context.getString(hasBg ? R.string.triggers_location_bg_explanation
                                        : R.string.triggers_location_fg_explanation),
                sev));
        return list;
    }

    private List<TriggerInfo> analyzeAlarms(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys alarm");
        if (output == null || output.trim().isEmpty()) return list;

        int  exactCount=0, inexactCount=0, awIdle=0, clockCount=0;
        int  wakeupCount=0, normalCount=0;
        long minInterval=Long.MAX_VALUE, sumInterval=0;
        int  intervalSamples=0;
        long minTriggerDiff=Long.MAX_VALUE;
        long nowMs  = System.currentTimeMillis();
        long bootMs = nowMs - android.os.SystemClock.elapsedRealtime();


        List<String> alarmEntries = new ArrayList<>();

        Pattern ivPat    = Pattern.compile("interval=(\\d+)");
        Pattern whenPat  = Pattern.compile("\\bwhen=(-?\\d+)");

        Pattern elNumPat = Pattern.compile("whenElapsed=(\\d{6,})");

        Pattern elHrPat  = Pattern.compile("whenElapsed=\\+((?:(\\d+)h)?(?:(\\d+)m)?(\\d+)s)");
        Pattern winPat   = Pattern.compile("window=(-?\\d+)");
        Pattern flgPat   = Pattern.compile("flgs=0x([0-9a-fA-F]+)");

        Pattern typePat  = Pattern.compile("^\\s*(RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED)\\s+#\\d+");
        Pattern tagPat   = Pattern.compile("\\btag=([^\\s,]+)");


        String  curType     = null;
        String  curTag      = null;
        boolean curIsWakeup = false;
        long    curFireDiff = Long.MAX_VALUE;
        long    curInterval = 0;
        boolean curExact    = false;
        boolean curWhileIdle= false;

        String[] lines = output.split("\n");

        for (String line : lines) {
            String t = line.trim();

            Matcher mType = typePat.matcher(line);
            if (mType.find()) {
                if (curType != null && curTag != null && curTag.contains(packageName)) {
                    commitAlarmEntry(alarmEntries, curType, curTag, curFireDiff,
                            curInterval, curExact, curWhileIdle, packageName);
                    if (curIsWakeup) wakeupCount++; else normalCount++;
                    if (curTag.contains("AlarmClock") || curTag.contains("ALARM_CLOCK")) clockCount++;
                    if (curWhileIdle) awIdle++;
                    if (curExact) exactCount++; else inexactCount++;
                    if (curInterval > 0) {
                        if (curInterval < minInterval) minInterval = curInterval;
                        sumInterval += curInterval; intervalSamples++;
                    }
                    if (curFireDiff != Long.MAX_VALUE && curFireDiff < minTriggerDiff)
                        minTriggerDiff = curFireDiff;
                }
                curType      = mType.group(1);
                curTag       = null;
                curIsWakeup  = curType.contains("WAKEUP");
                curFireDiff  = Long.MAX_VALUE;
                curInterval  = 0;
                curExact     = false;
                curWhileIdle = false;
                continue;
            }

            if (curType == null) continue;

            if (curTag == null) {
                Matcher mTag = tagPat.matcher(t);
                if (mTag.find()) curTag = mTag.group(1);
            }

            if (curFireDiff == Long.MAX_VALUE) {
                Matcher mHr = elHrPat.matcher(t);
                if (mHr.find()) {
                    long ms = 0;
                    if (mHr.group(2) != null) ms += Long.parseLong(mHr.group(2)) * 3600_000L;
                    if (mHr.group(3) != null) ms += Long.parseLong(mHr.group(3)) * 60_000L;
                    ms += Long.parseLong(mHr.group(4)) * 1000L;
                    curFireDiff = ms;
                } else {
                    Matcher mEl = elNumPat.matcher(t);
                    if (mEl.find()) {
                        long diff = bootMs + Long.parseLong(mEl.group(1)) - nowMs;
                        if (diff > 0) curFireDiff = diff;
                    } else {
                        Matcher mW = whenPat.matcher(t);
                        if (mW.find()) {
                            long when = Long.parseLong(mW.group(1));
                            long diff = when > 1_000_000_000_000L ? when - nowMs : bootMs + when - nowMs;
                            if (diff > 0) curFireDiff = diff;
                        }
                    }
                }
            }

            Matcher mWin = winPat.matcher(t);
            if (mWin.find()) curExact = Long.parseLong(mWin.group(1)) < 0;

            Matcher mIv = ivPat.matcher(t);
            if (mIv.find() && curInterval == 0) curInterval = Long.parseLong(mIv.group(1));

            Matcher mFlg = flgPat.matcher(t);
            if (mFlg.find() && ((int) Long.parseLong(mFlg.group(1), 16) & 0xC) != 0)
                curWhileIdle = true;
            if (t.contains("ALLOW_WHILE_IDLE") || t.contains("allowWhileIdle=true"))
                curWhileIdle = true;
        }

        if (curType != null && curTag != null && curTag.contains(packageName)) {
            commitAlarmEntry(alarmEntries, curType, curTag, curFireDiff,
                    curInterval, curExact, curWhileIdle, packageName);
            if (curIsWakeup) wakeupCount++; else normalCount++;
            if (curTag.contains("AlarmClock") || curTag.contains("ALARM_CLOCK")) clockCount++;
            if (curWhileIdle) awIdle++;
            if (curExact) exactCount++; else inexactCount++;
            if (curInterval > 0) {
                if (curInterval < minInterval) minInterval = curInterval;
                sumInterval += curInterval; intervalSamples++;
            }
            if (curFireDiff != Long.MAX_VALUE && curFireDiff < minTriggerDiff)
                minTriggerDiff = curFireDiff;
        }

        List<String> topAlarmLines = new ArrayList<>();
        boolean inTopAlarms = false;
        Pattern topEntryPat = Pattern.compile(
                "(\\S+)\\s+running,\\s*(\\d+)\\s+wakeups?,\\s*(\\d+)\\s+alarms?:\\s*\\d+:([\\w.]+)\\s+tag=(\\S+)");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("Top Alarms:") || t.startsWith("Top alarm senders:"))
                { inTopAlarms = true; continue; }
            if (inTopAlarms && (t.isEmpty() || (t.endsWith(":") && !t.startsWith("+"))))
                { inTopAlarms = false; continue; }
            if (!inTopAlarms) continue;
            Matcher m = topEntryPat.matcher(t);
            if (!m.find()) continue;
            if (!m.group(4).equals(packageName)) continue;
            String shortTag = m.group(5)
                    .replaceAll("^\\*[^*]+\\*/", "")
                    .replaceAll(".*\\.([^.]+)$", "$1");
            topAlarmLines.add(shortTag + ":" + m.group(2) + "×wakeup/" + m.group(1));
            if (topAlarmLines.size() >= 3) break;
        }

        int total = wakeupCount + normalCount;
        if (total == 0 && topAlarmLines.isEmpty()) return list;

        StringBuilder detail = new StringBuilder();
        if (!alarmEntries.isEmpty()) {
            detail.append(String.join("\n", alarmEntries));
        } else {
            if (exactCount   > 0) detail.append(context.getString(R.string.triggers_alarms_exact,      exactCount));
            if (inexactCount > 0) { if(detail.length()>0) detail.append(", ");
                detail.append(context.getString(R.string.triggers_alarms_inexact, inexactCount)); }
            if (awIdle       > 0) { if(detail.length()>0) detail.append(", ");
                detail.append(context.getString(R.string.triggers_alarms_while_idle, awIdle)); }
            if (clockCount   > 0) { if(detail.length()>0) detail.append(", ");
                detail.append(context.getString(R.string.triggers_alarms_clock, clockCount)); }
            if (wakeupCount  > 0 && detail.length() == 0)
                detail.append(context.getString(R.string.triggers_alarms_wakeup_count, wakeupCount));
            if (normalCount  > 0 && wakeupCount == 0)
                detail.append(context.getString(R.string.triggers_alarms_normal_count, normalCount));
        }
        if (minTriggerDiff != Long.MAX_VALUE)
            detail.append(context.getString(R.string.triggers_alarms_next, formatInterval(minTriggerDiff)));
        if (intervalSamples > 0)
            detail.append(context.getString(R.string.triggers_alarms_avg_interval,
                    formatInterval(sumInterval / intervalSamples)));
        if (!topAlarmLines.isEmpty())
            detail.append("\nTop: ").append(String.join(", ", topAlarmLines));

        StringBuilder expl = new StringBuilder();
        if (wakeupCount > 0) {
            expl.append(context.getString(R.string.triggers_alarms_wakeup_explanation));
            if (minInterval < 60_000)       expl.append(context.getString(R.string.triggers_alarms_wakeup_aggressive));
            else if (minInterval < 300_000) expl.append(context.getString(R.string.triggers_alarms_wakeup_frequent));
        } else {
            expl.append(context.getString(R.string.triggers_alarms_normal_explanation));
        }
        if (exactCount > 0) expl.append(context.getString(R.string.triggers_alarms_exact_explanation));
        if (awIdle     > 0) expl.append(context.getString(R.string.triggers_alarms_while_idle_explanation));

        TriggerInfo.Severity sev = wakeupCount > 0
                ? (minInterval < 120_000 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM)
                : TriggerInfo.Severity.LOW;

        list.add(new TriggerInfo(TriggerInfo.Group.CAN_WAKE,
                context.getString(R.string.triggers_cat_alarms),
                detail.toString(), expl.toString(), sev));
        return list;
    }

    private void commitAlarmEntry(List<String> entries, String type, String tag,
            long fireDiffMs, long intervalMs, boolean exact, boolean whileIdle,
            String packageName) {
        if (entries.size() >= 5) return;
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case "RTC_WAKEUP":     sb.append("RTC_WU"); break;
            case "ELAPSED_WAKEUP": sb.append("EL_WU");  break;
            case "RTC":            sb.append("RTC");     break;
            default:               sb.append("ELAPSED"); break;
        }
        String shortTag = tag;
        if (shortTag.startsWith("*") && shortTag.contains("/"))
            shortTag = shortTag.substring(shortTag.indexOf('/') + 1);
        if (shortTag.startsWith(packageName + "/"))
            shortTag = shortTag.substring(packageName.length() + 1);
        if (shortTag.startsWith(packageName + "."))
            shortTag = shortTag.substring(packageName.length() + 1);
        if (shortTag.startsWith("."))
            shortTag = shortTag.substring(1);
        if (shortTag.length() > 40 && shortTag.contains("."))
            shortTag = shortTag.substring(shortTag.lastIndexOf('.') + 1);
        sb.append(" · ").append(shortTag);
        if (fireDiffMs != Long.MAX_VALUE) sb.append(" · in ").append(formatInterval(fireDiffMs));
        if (intervalMs > 0)              sb.append(" · every ").append(formatInterval(intervalMs));
        if (exact)                        sb.append(" · exact");
        if (whileIdle)                    sb.append(" · while-idle");
        entries.add(sb.toString());
    }

    private List<TriggerInfo> analyzeJobs(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();

        try {
            String output = shellManager.runShellCommandAndGetFullOutput("dumpsys jobscheduler");
            if (output != null && !output.trim().isEmpty()) {
                int pending=0, running=0;
                boolean inPending=false, inRunning=false, inPast=false, inJobBlock=false;
                List<String> jobDetails  = new ArrayList<>();
                List<String> stopReasons = new ArrayList<>();
                StringBuilder jobBlock   = new StringBuilder();

                for (String line : output.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("Pending queue:") || t.startsWith("Pending:")
                            || t.startsWith("JobScheduler pending")) {
                        inPending=true; inRunning=false; inPast=false; continue;
                    }
                    if (t.startsWith("Active jobs:") || t.startsWith("Running:")
                            || t.startsWith("Currently running")) {
                        inRunning=true; inPending=false; inPast=false; continue;
                    }
                    if (t.startsWith("Past jobs:") || t.startsWith("History:")
                            || t.startsWith("Completed jobs:")) {
                        inPending=false; inRunning=false; inPast=true; continue;
                    }

                    if ((inPending || inRunning) && t.contains(packageName)) {
                        if (inPending) pending++;
                        if (inRunning) running++;
                        if (t.startsWith("JOB #") || t.startsWith("JobInfo{")
                                || t.startsWith("Job{"))
                            { inJobBlock=true; jobBlock.setLength(0); }
                    }
                    if (inJobBlock) {
                        jobBlock.append(t).append("\n");
                        if (t.isEmpty() || (t.startsWith("JOB #") && jobBlock.length() > 10)) {
                            if (jobDetails.size() < 3) {
                                String d = parseJobBlock(jobBlock.toString());
                                if (d != null) jobDetails.add(d);
                            }
                            inJobBlock=false; jobBlock.setLength(0);
                        }
                    }
                    if (inPast && t.contains(packageName)) {
                        Matcher m = Pattern.compile("stopReason=([\\w_]+)").matcher(t);
                        if (m.find() && stopReasons.size() < 3) stopReasons.add(m.group(1));
                    }
                }

                if (pending > 0 || running > 0) {
                    StringBuilder detail = new StringBuilder();
                    if (running > 0) detail.append(context.getString(R.string.triggers_jobs_detail_running, running));
                    if (pending > 0) { if (detail.length()>0) detail.append(", ");
                                       detail.append(context.getString(R.string.triggers_jobs_detail_pending, pending)); }
                    if (!jobDetails.isEmpty())  detail.append(" · ").append(String.join("; ", jobDetails));
                    if (!stopReasons.isEmpty()) detail.append(context.getString(
                            R.string.triggers_jobs_stop_reasons, String.join(", ", stopReasons)));

                    String expl = running>0&&pending>0
                            ? context.getString(R.string.triggers_jobs_running_and_pending_explanation, running, pending)
                            : running>0 ? context.getString(R.string.triggers_jobs_running_explanation, running)
                                        : context.getString(R.string.triggers_jobs_pending_explanation, pending);

                    list.add(new TriggerInfo(TriggerInfo.Group.CAN_WAKE,
                            context.getString(R.string.triggers_cat_jobs),
                            detail.toString(), expl,
                            running > 0 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
                }
            }
        } catch (Exception e) { Log.w(TAG, "analyzeJobs/jobscheduler failed: " + e.getMessage()); }

        return list;
    }

    private String parseJobBlock(String block) {
        List<String> parts = new ArrayList<>();

        boolean isWm = block.contains("WorkManager") || block.contains("androidx.work");


        Matcher mNet = Pattern.compile("required-network-type=([\\w_]+)").matcher(block);
        if (!mNet.find()) mNet = Pattern.compile("networkType=([\\w_]+)").matcher(block);
        if (mNet.find()) parts.add("net:" + mNet.group(1));


        if (block.contains("charging=true")        || block.contains("requireCharging=true"))   parts.add("charging");
        if (block.contains("idle=true")            || block.contains("requireDeviceIdle=true"))  parts.add("idle");
        if (block.contains("battery-not-low=true"))                                              parts.add("!batt-low");

        Matcher mPeriodHr = Pattern.compile(
                "period=\\+((?:(\\d+)h)?(?:(\\d+)m)?(\\d+)s)").matcher(block);
        Matcher mPeriodMs = Pattern.compile("periodMs=(\\d+)").matcher(block);
        if (mPeriodHr.find()) {
            long ms = 0;
            if (mPeriodHr.group(2) != null) ms += Long.parseLong(mPeriodHr.group(2)) * 3600_000L;
            if (mPeriodHr.group(3) != null) ms += Long.parseLong(mPeriodHr.group(3)) * 60_000L;
            ms += Long.parseLong(mPeriodHr.group(4)) * 1000L;
            if (ms > 0) parts.add("every " + formatInterval(ms));
        } else if (mPeriodMs.find()) {
            long ms = Long.parseLong(mPeriodMs.group(1));
            if (ms > 0) parts.add("every " + formatInterval(ms));
        }

        Matcher mLastRun = Pattern.compile(
                "(?:last-run|lastRunTime)=elapsed-((?:(\\d+)h)?(?:(\\d+)m)?(\\d+)s)").matcher(block);
        if (mLastRun.find()) {
            long ms = 0;
            if (mLastRun.group(2) != null) ms += Long.parseLong(mLastRun.group(2)) * 3600_000L;
            if (mLastRun.group(3) != null) ms += Long.parseLong(mLastRun.group(3)) * 60_000L;
            ms += Long.parseLong(mLastRun.group(4)) * 1000L;
            if (ms > 0) parts.add("last " + formatInterval(ms) + " ago");
        }


        Matcher mDL = Pattern.compile("latest-runtime=(\\d+)").matcher(block);
        if (mDL.find()) {
            long diff = Long.parseLong(mDL.group(1)) - System.currentTimeMillis();
            if (diff > 0) parts.add("deadline:" + formatInterval(diff));
        }


        Matcher mBk = Pattern.compile("backoff-policy=(\\w+)").matcher(block);
        if (mBk.find()) parts.add("backoff:" + mBk.group(1));

        if (isWm) parts.add(0, "WM");
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private List<TriggerInfo> analyzePendingIntents(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys activity intents");
        if (output == null || output.trim().isEmpty()) return list;


        final int MAX_PI_ENTRIES = 4;
        List<String> piEntries = new ArrayList<>();
        int actC=0, svcC=0, bcastC=0, alarmC=0, mediaC=0, pushC=0;
        List<String> creators = new ArrayList<>();

        Pattern recPat     = Pattern.compile(
                "PendingIntentRecord\\{[^}]+\\s+([\\w.]+)\\s+type=(\\w+)", Pattern.CASE_INSENSITIVE);
        Pattern actPat     = Pattern.compile("act=([\\w./-]+)");
        Pattern cmpPat     = Pattern.compile("cmp=([\\w./]+)");
        Pattern creatorPat = Pattern.compile("(?:creator=\\[|creatorPackage=)([\\w.]+)");

        boolean inBlock = false;
        String  blkType = null;
        String  blkAct  = null;
        String  blkCmp  = null;

        for (String line : output.split("\n")) {
            String t = line.trim();

            Matcher mRec = recPat.matcher(t);
            if (mRec.find()) {

                if (inBlock && blkType != null)
                    recordPiEntry(piEntries, blkType, blkAct, blkCmp, MAX_PI_ENTRIES, packageName);

                String owner = mRec.group(1);
                blkType = mRec.group(2).toLowerCase();
                blkAct  = null;
                blkCmp  = null;
                inBlock = owner.equals(packageName);

                if (inBlock) {
                    switch (blkType) {
                        case "activity":  actC++;  break;
                        case "service":   svcC++;  break;
                        case "broadcast": bcastC++; break;
                    }
                }

                Matcher mCr = creatorPat.matcher(t);
                if (mCr.find()) {
                    String cr = mCr.group(1);
                    if (!cr.equals(packageName) && !creators.contains(cr)) creators.add(cr);
                }
                continue;
            }

            if (!inBlock) {

                if (!t.contains(packageName)) continue;
                if      (t.contains("type=activity")  || t.contains("Activity"))  actC++;
                else if (t.contains("type=service")   || t.contains("Service"))   svcC++;
                else if (t.contains("type=broadcast") || t.contains("Broadcast")) bcastC++;
                Matcher mA = actPat.matcher(t);
                if (mA.find()) {
                    String a = mA.group(1);
                    if (a.contains("ALARM") || a.contains("alarmmanager")) alarmC++;
                    if (a.contains("MEDIA_BUTTON"))                        mediaC++;
                    if (a.contains("GCM")||a.contains("FCM")
                            ||a.contains("push")||a.contains("PUSH"))      pushC++;
                }
                Matcher mCr = creatorPat.matcher(t);
                if (mCr.find()) {
                    String cr = mCr.group(1);
                    if (!cr.equals(packageName) && !creators.contains(cr)) creators.add(cr);
                }
                continue;
            }


            Matcher mA = actPat.matcher(t);
            if (mA.find() && blkAct == null) {
                blkAct = mA.group(1);
                if (blkAct.contains("ALARM") || blkAct.contains("alarmmanager")) alarmC++;
                if (blkAct.contains("MEDIA_BUTTON"))                              mediaC++;
                if (blkAct.contains("GCM")||blkAct.contains("FCM")
                        ||blkAct.contains("push")||blkAct.contains("PUSH"))       pushC++;
            }
            Matcher mCmp = cmpPat.matcher(t);
            if (mCmp.find() && blkCmp == null) blkCmp = mCmp.group(1);
        }

        if (inBlock && blkType != null)
            recordPiEntry(piEntries, blkType, blkAct, blkCmp, MAX_PI_ENTRIES, packageName);

        int total = actC + svcC + bcastC;
        if (total == 0) return list;


        StringBuilder detail = new StringBuilder();
        if (!piEntries.isEmpty()) {
            detail.append(String.join("\n", piEntries));
            if (total > piEntries.size())
                detail.append("\n+").append(total - piEntries.size()).append(" more");
        } else {

            if (actC   > 0) detail.append(context.getString(R.string.triggers_pending_activity,  actC));
            if (svcC   > 0) { if(detail.length()>0) detail.append(", ");
                detail.append(context.getString(R.string.triggers_pending_service,  svcC)); }
            if (bcastC > 0) { if(detail.length()>0) detail.append(", ");
                detail.append(context.getString(R.string.triggers_pending_broadcast, bcastC)); }
        }
        if (alarmC > 0) detail.append(context.getString(R.string.triggers_pending_alarm,        alarmC));
        if (mediaC > 0) detail.append(context.getString(R.string.triggers_pending_media_button, mediaC));
        if (pushC  > 0) detail.append(context.getString(R.string.triggers_pending_push,         pushC));
        if (!creators.isEmpty())
            detail.append(context.getString(R.string.triggers_pending_creators,
                    String.join(", ", creators.subList(0, Math.min(creators.size(), 2)))));

        list.add(new TriggerInfo(TriggerInfo.Group.CAN_WAKE,
                context.getString(R.string.triggers_cat_pending_intents, total),
                detail.toString(),
                context.getString(R.string.triggers_pending_explanation),
                TriggerInfo.Severity.MEDIUM));
        return list;
    }


    private void recordPiEntry(List<String> entries, String type, String act,
            String cmp, int maxEntries, String packageName) {
        if (entries.size() >= maxEntries) return;
        StringBuilder sb = new StringBuilder();
        switch (type) {
            case "broadcast": sb.append("BC"); break;
            case "service":   sb.append("SV"); break;
            case "activity":  sb.append("AC"); break;
            default: sb.append(type.substring(0, Math.min(2, type.length())).toUpperCase());
        }
        if (cmp != null) {
            String cls = cmp.contains("/") ? cmp.substring(cmp.indexOf('/') + 1) : cmp;
            if (cls.startsWith(packageName + ".")) cls = cls.substring(packageName.length() + 1);
            if (cls.startsWith(".")) cls = cls.substring(1);
            if (cls.length() > 40 && cls.contains("."))
                cls = cls.substring(cls.lastIndexOf('.') + 1);
            sb.append(" → ").append(cls);
        } else if (act != null) {
            sb.append(" → ").append(shortenAction(act));
        }
        entries.add(sb.toString());
    }


    private List<TriggerInfo> analyzeExcessiveWakeups(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys batterystats " + packageName);
        if (output == null || output.trim().isEmpty()) return list;

        int alarmW=0, jobW=0, gcmW=0, bcastW=0;
        List<String> alarmTags = new ArrayList<>();


        Pattern ap = Pattern.compile(
                "Wakeup alarm\\s+([\\w./]+):\\s*(\\d+)\\s+times", Pattern.CASE_INSENSITIVE);
        Pattern jp = Pattern.compile(
                "Job\\s+\\S+:\\s+\\d+ms.*?\\((\\d+)\\s+times\\)", Pattern.CASE_INSENSITIVE);
        Pattern gp = Pattern.compile(
                "(?:GCM|FCM|push).*?wakeup.*?:\\s*(\\d+)",        Pattern.CASE_INSENSITIVE);
        Pattern bp = Pattern.compile(
                "Broadcast\\s+\\S+.*?\\((\\d+)\\s+times\\)",      Pattern.CASE_INSENSITIVE);

        for (String line : output.split("\n")) {
            try {
                Matcher m;
                if ((m=ap.matcher(line)).find()) {
                    int cnt = Integer.parseInt(m.group(2));
                    alarmW += cnt;
                    if (alarmTags.size() < 3) {
                        String tag = m.group(1);

                        if (tag.contains("/")) tag = tag.substring(tag.indexOf('/') + 1);
                        if (tag.startsWith(".")) tag = tag.substring(1);
                        if (tag.startsWith(packageName + ".")) tag = tag.substring(packageName.length() + 1);
                        alarmTags.add(tag + "×" + cnt);
                    }
                    continue;
                }
                if ((m=jp.matcher(line)).find()) { jobW  +=Integer.parseInt(m.group(1)); continue; }
                if ((m=gp.matcher(line)).find()) { gcmW  +=Integer.parseInt(m.group(1)); continue; }
                if ((m=bp.matcher(line)).find())   bcastW+=Integer.parseInt(m.group(1));
            } catch (Exception e) { Log.w(TAG, "analyzeExcessiveWakeups line parse failed: " + e.getMessage()); }
        }

        int total = alarmW + jobW + gcmW + bcastW;
        if (total == 0) return list;

        StringBuilder detail = new StringBuilder(
                context.getString(R.string.triggers_wakeups_total, total));
        if (alarmW > 0) {
            detail.append(context.getString(R.string.triggers_wakeups_alarms, alarmW));
            if (!alarmTags.isEmpty())
                detail.append(" (").append(String.join(", ", alarmTags)).append(")");
        }
        if (jobW   > 0) detail.append(context.getString(R.string.triggers_wakeups_jobs,      jobW));
        if (gcmW   > 0) detail.append(context.getString(R.string.triggers_wakeups_gcm,       gcmW));
        if (bcastW > 0) detail.append(context.getString(R.string.triggers_wakeups_broadcast, bcastW));

        list.add(new TriggerInfo(TriggerInfo.Group.CAN_WAKE,
                context.getString(R.string.triggers_cat_wakeups),
                detail.toString(),
                context.getString(R.string.triggers_wakeups_explanation),
                total>50 ? TriggerInfo.Severity.HIGH
                : total>15 ? TriggerInfo.Severity.MEDIUM : TriggerInfo.Severity.LOW));
        return list;
    }


    private List<TriggerInfo> analyzeChainLaunch(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();


        try {
            String procOut = shellManager.runShellCommandAndGetFullOutput("dumpsys activity processes");
            if (procOut != null) {
                boolean inBlock = false;
                Pattern callerPat = Pattern.compile("(?:clientPackage|callingPackage)=([\\w.]+)");
                for (String line : procOut.split("\n")) {
                    if (line.contains("ProcessRecord") && line.contains(packageName)) inBlock = true;
                    if (inBlock && line.contains("ProcessRecord") && !line.contains(packageName)) break;
                    if (!inBlock) continue;
                    Matcher m = callerPat.matcher(line);
                    if (m.find()) {
                        String caller = m.group(1);
                        if (!caller.equals(packageName) && !caller.equals("android")) {
                            String name = resolveAppName(caller);
                            list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                                    context.getString(R.string.triggers_cat_chain_launch),
                                    context.getString(R.string.triggers_chain_direct_detail, name+"("+caller+")"),
                                    context.getString(R.string.triggers_chain_direct_explanation, name),
                                    TriggerInfo.Severity.HIGH));
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "chain/processes failed: " + e.getMessage()); }


        try {
            String bcastOut = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys activity broadcasts history");
            if (bcastOut != null) {
                List<String> relevant = new ArrayList<>();
                for (String line : bcastOut.split("\n"))
                    if (line.contains(packageName)) relevant.add(line);

                int start = Math.max(0, relevant.size() - 30);
                List<String> callers = new ArrayList<>(), actions = new ArrayList<>();
                Pattern cPat = Pattern.compile("callerPackage=([\\w.]+)");
                Pattern aPat = Pattern.compile("act=([\\w.]+)");

                for (String line : relevant.subList(start, relevant.size())) {
                    Matcher mC = cPat.matcher(line), mA = aPat.matcher(line);
                    String caller = mC.find() ? mC.group(1) : null;
                    String action = mA.find() ? shortenAction(mA.group(1)) : "?";
                    if (caller != null && !caller.equals(packageName)
                            && !caller.equals("android") && !caller.equals("null")
                            && !callers.contains(caller)) {
                        callers.add(caller); actions.add(action);
                    }
                }
                for (int i = 0; i < Math.min(callers.size(), 3); i++) {
                    String pkg  = callers.get(i);
                    String name = resolveAppName(pkg);
                    list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                            context.getString(R.string.triggers_cat_chain_launch),
                            context.getString(R.string.triggers_chain_broadcast_detail, name+"("+pkg+")", actions.get(i)),
                            context.getString(R.string.triggers_chain_broadcast_explanation, name, actions.get(i)),
                            TriggerInfo.Severity.MEDIUM));
                }
            }
        } catch (Exception e) { Log.w(TAG, "chain/broadcasts failed: " + e.getMessage()); }
        return list;
    }

    private List<TriggerInfo> analyzeBroadcastReceivers(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();


        String pkgOut = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys package " + packageName);
        List<String> staticActions = new ArrayList<>();
        if (pkgOut != null) {
            boolean inSection = false;
            for (String line : pkgOut.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Receiver #") || t.startsWith("ReceiverInfo{")) inSection = true;
                if (inSection && t.startsWith("Action:")) {
                    String a = shortenAction(
                            t.replaceFirst("Action:\\s*\"?", "").replace("\"", "").trim());
                    if (!staticActions.contains(a)) staticActions.add(a);
                }
                if (inSection && t.startsWith("Service #")) break;
            }
        }


        List<String> dynamicActions = new ArrayList<>();
        try {
            String regOut = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys activity broadcasts registered");
            if (regOut != null) {
                boolean inBlock = false;
                for (String line : regOut.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("ReceiverList{") || t.startsWith("* ReceiverList")) {
                        inBlock = t.contains(packageName);
                        continue;
                    }
                    if (inBlock && t.startsWith("ReceiverList{") && !t.contains(packageName)) {
                        inBlock = false;
                        continue;
                    }
                    if (!inBlock) continue;
                    if (t.startsWith("Action:") || t.startsWith("+ Action:")) {
                        String a = shortenAction(
                                t.replaceFirst("\\+?\\s*Action:\\s*\"?", "").replace("\"", "").trim());
                        if (!a.isEmpty() && !dynamicActions.contains(a)) dynamicActions.add(a);
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "dynamic receivers failed: " + e.getMessage()); }

        if (staticActions.isEmpty() && dynamicActions.isEmpty()) return list;


        if (!staticActions.isEmpty()) {
            int shown = Math.min(staticActions.size(), 5);
            StringBuilder detail = new StringBuilder(
                    String.join(", ", staticActions.subList(0, shown)));
            if (staticActions.size() > shown)
                detail.append(context.getString(
                        R.string.triggers_receivers_detail_overflow, staticActions.size() - shown));

            StringBuilder expl = new StringBuilder(
                    context.getString(R.string.triggers_receivers_explanation_base));
            if (staticActions.stream().anyMatch(a -> a.contains("BOOT") || a.contains("LOCKED_BOOT")))
                expl.append(context.getString(R.string.triggers_receivers_explanation_boot));
            if (staticActions.stream().anyMatch(a -> a.contains("CONNECTIVITY") || a.contains("NETWORK")))
                expl.append(context.getString(R.string.triggers_receivers_explanation_network));

            list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                    context.getString(R.string.triggers_cat_receivers, staticActions.size()),
                    detail.toString(), expl.toString(), TriggerInfo.Severity.MEDIUM));
        }


        if (!dynamicActions.isEmpty()) {
            int shown = Math.min(dynamicActions.size(), 5);
            StringBuilder detail = new StringBuilder(
                    String.join(", ", dynamicActions.subList(0, shown)));
            if (dynamicActions.size() > shown)
                detail.append(context.getString(
                        R.string.triggers_receivers_detail_overflow, dynamicActions.size() - shown));

            list.add(new TriggerInfo(TriggerInfo.Group.CAN_WAKE,
                    "Dynamic Receivers (" + dynamicActions.size() + ")",
                    detail.toString(),
                    context.getString(R.string.triggers_receivers_explanation_base),
                    dynamicActions.size() > 3 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
        }

        return list;
    }

    private List<TriggerInfo> analyzeBootReceivers(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        boolean hasBoot = false, hasLocked = false;


        try {
            String o1 = shellManager.runShellCommandAndGetFullOutput(
                    "cmd package query-receivers --action android.intent.action.BOOT_COMPLETED");
            if (o1 != null && o1.contains(packageName)) hasBoot = true;
        } catch (Exception e) { Log.w(TAG, "boot query failed: " + e.getMessage()); }
        try {
            String o2 = shellManager.runShellCommandAndGetFullOutput(
                    "cmd package query-receivers --action android.intent.action.LOCKED_BOOT_COMPLETED");
            if (o2 != null && o2.contains(packageName)) hasLocked = true;
        } catch (Exception e) { Log.w(TAG, "locked-boot query failed: " + e.getMessage()); }


        if (!hasBoot && !hasLocked) {
            try {
                String pkgOut = shellManager.runShellCommandAndGetFullOutput(
                        "dumpsys package " + packageName);
                if (pkgOut != null) {
                    for (String line : pkgOut.split("\n")) {
                        String t = line.trim();
                        if (t.contains("BOOT_COMPLETED"))        hasBoot   = true;
                        if (t.contains("LOCKED_BOOT_COMPLETED")) hasLocked = true;
                        if (hasBoot && hasLocked) break;
                    }
                }
            } catch (Exception e) { Log.w(TAG, "boot/package fallback failed: " + e.getMessage()); }
        }

        if (!hasBoot && !hasLocked) return list;

        String detail = hasBoot && hasLocked ? context.getString(R.string.triggers_boot_detail_both)
                : hasLocked ? context.getString(R.string.triggers_boot_detail_locked)
                            : context.getString(R.string.triggers_boot_detail_normal);
        String expl = hasBoot && hasLocked ? context.getString(R.string.triggers_boot_explanation_both)
                : hasLocked ? context.getString(R.string.triggers_boot_explanation_locked)
                            : context.getString(R.string.triggers_boot_explanation_normal);

        list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                context.getString(R.string.triggers_cat_boot), detail, expl,
                TriggerInfo.Severity.HIGH));
        return list;
    }

    private List<TriggerInfo> analyzeContentProviders(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys package " + packageName);
        if (output == null || output.trim().isEmpty()) return list;

        boolean inSection = false;
        List<String> auths = new ArrayList<>();
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Provider #")) inSection = true;
            if (inSection && t.startsWith("authority=")) {
                String a = t.replaceFirst("authority=", "").trim();
                if (a.startsWith(packageName + ".")) a = a.substring(packageName.length() + 1);
                if (!auths.contains(a)) auths.add(a);
            }
            if (inSection && t.startsWith("Activity #")) break;
        }
        if (auths.isEmpty()) return list;

        int shown = Math.min(auths.size(), 3);
        String detail = String.join(", ", auths.subList(0, shown))
                + (auths.size() > shown ? " (+" + (auths.size() - shown) + ")" : "");

        list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                context.getString(R.string.triggers_cat_provider), detail,
                context.getString(R.string.triggers_provider_explanation),
                TriggerInfo.Severity.LOW));
        return list;
    }

    private List<TriggerInfo> analyzeSyncAdapters(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();


        String output = shellManager.runShellCommandAndGetFullOutput("dumpsys content");
        if (output == null || output.trim().isEmpty()) {

            try {
                String pkgOut = shellManager.runShellCommandAndGetFullOutput(
                        "dumpsys package " + packageName);
                if (pkgOut != null && pkgOut.toLowerCase().contains("syncadapter")) {
                    list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                            context.getString(R.string.triggers_cat_sync),
                            context.getString(R.string.triggers_sync_detail, 1),
                            context.getString(R.string.triggers_sync_explanation),
                            TriggerInfo.Severity.MEDIUM));
                }
            } catch (Exception e) { Log.w(TAG, "sync/package fallback failed: " + e.getMessage()); }
            return list;
        }


        int count = 0;
        List<String> entries = new ArrayList<>();

        Pattern authPat      = Pattern.compile("authority=([\\w.]+)");
        Pattern acctPat      = Pattern.compile("accountType=([\\w.]+)");
        Pattern periodPat    = Pattern.compile("period=(\\d+)s");
        Pattern periodMsPat  = Pattern.compile("(?:mPeriod|periodMs)=(\\d+)");
        Pattern lastSuccPat  = Pattern.compile("lastSuccessTime=([\\d\\- :]+)");
        Pattern nextRunPat   = Pattern.compile("nextRunTime=([\\d\\- :]+)");
        Pattern syncablePat  = Pattern.compile("(?:syncable|mSyncable)=(true|false)");

        boolean inBlock   = false;
        String  authority = null;
        String  acctType  = null;
        boolean syncable  = false;
        long    periodSec = 0;
        String  lastSucc  = null;
        String  nextRun   = null;

        for (String line : output.split("\n")) {
            String t = line.trim();


            boolean isHeader = t.startsWith("SyncAdapterType") || t.startsWith("SyncAdapter:");
            if (isHeader) {

                if (inBlock && authority != null) {
                    count++;
                    if (entries.size() < 3) entries.add(
                            buildSyncEntry(authority, acctType, syncable, periodSec, lastSucc, nextRun));
                }

                inBlock   = t.contains(packageName);
                authority = null; acctType = null; syncable = false;
                periodSec = 0; lastSucc = null; nextRun = null;

                if (inBlock) {
                    Matcher mA = authPat.matcher(t);
                    if (mA.find()) authority = mA.group(1);
                    Matcher mAc = acctPat.matcher(t);
                    if (mAc.find()) acctType = mAc.group(1);
                }
                continue;
            }

            if (!inBlock) {

                if (t.contains(packageName) && t.contains("authority=")) {
                    inBlock = true;
                    Matcher mA = authPat.matcher(t);
                    if (mA.find()) authority = mA.group(1);
                    Matcher mAc = acctPat.matcher(t);
                    if (mAc.find()) acctType = mAc.group(1);
                }
                continue;
            }


            Matcher mSy = syncablePat.matcher(t);
            if (mSy.find()) syncable = "true".equals(mSy.group(1));

            Matcher mP = periodPat.matcher(t);
            if (mP.find() && periodSec == 0) periodSec = Long.parseLong(mP.group(1));
            else {
                Matcher mPms = periodMsPat.matcher(t);
                if (mPms.find() && periodSec == 0) periodSec = Long.parseLong(mPms.group(1)) / 1000;
            }

            Matcher mLs = lastSuccPat.matcher(t);
            if (mLs.find() && lastSucc == null) lastSucc = mLs.group(1).trim();

            Matcher mNr = nextRunPat.matcher(t);
            if (mNr.find() && nextRun == null) nextRun = mNr.group(1).trim();


            if (t.isEmpty() && authority != null) {
                count++;
                if (entries.size() < 3) entries.add(
                        buildSyncEntry(authority, acctType, syncable, periodSec, lastSucc, nextRun));
                inBlock = false; authority = null; acctType = null;
                syncable = false; periodSec = 0; lastSucc = null; nextRun = null;
            }
        }

        if (inBlock && authority != null) {
            count++;
            if (entries.size() < 3) entries.add(
                    buildSyncEntry(authority, acctType, syncable, periodSec, lastSucc, nextRun));
        }

        if (count == 0) return list;

        StringBuilder detail = new StringBuilder(
                context.getString(R.string.triggers_sync_detail, count));
        if (!entries.isEmpty())
            detail.append(": ").append(String.join(" | ", entries));

        list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                context.getString(R.string.triggers_cat_sync),
                detail.toString(),
                context.getString(R.string.triggers_sync_explanation),
                TriggerInfo.Severity.MEDIUM));
        return list;
    }


    private String buildSyncEntry(String authority, String acctType,
            boolean syncable, long periodSec, String lastSucc, String nextRun) {
        StringBuilder sb = new StringBuilder();

        String auth = authority.contains(".")
                ? authority.substring(authority.lastIndexOf('.') + 1) : authority;
        sb.append(auth);
        if (!syncable) sb.append("(off)");
        if (periodSec > 0) sb.append(" every ").append(formatInterval(periodSec * 1000L));
        if (lastSucc != null) {

            String t = lastSucc.contains(" ") ? lastSucc.substring(lastSucc.indexOf(' ') + 1) : lastSucc;
            if (t.length() > 5) t = t.substring(0, 5);
            sb.append(" last:").append(t);
        }
        return sb.toString();
    }

    private List<TriggerInfo> analyzeDozeExemption(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys deviceidle | grep -E 'whitelist|except'");
        if (output == null || output.trim().isEmpty()) return list;

        for (String line : output.split("\n")) {
            if (!line.contains(packageName)) continue;
            boolean sys = line.contains("sys-");
            list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                    context.getString(R.string.triggers_cat_doze),
                    context.getString(sys ? R.string.triggers_doze_sys_detail
                                         : R.string.triggers_doze_user_detail),
                    context.getString(sys ? R.string.triggers_doze_sys_explanation
                                         : R.string.triggers_doze_user_explanation),
                    TriggerInfo.Severity.HIGH));
            break;
        }
        return list;
    }


    private List<TriggerInfo> analyzeStandbyBucket(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "am get-standby-bucket " + packageName);
        if (output == null || output.trim().isEmpty()) return list;

        int bv = -1;
        try { bv = Integer.parseInt(output.trim()); }
        catch (NumberFormatException ignored) {
            Matcher m = Pattern.compile("(\\d+)").matcher(output);
            if (m.find()) bv = Integer.parseInt(m.group(1));
        }
        if (bv == -1) return list;

        String currentName = bucketValueToName(bv);


        List<String> history = new ArrayList<>();
        try {
            String usOut = shellManager.runShellCommandAndGetFullOutput("dumpsys usagestats");
            if (usOut != null) {
                boolean inPkg = false;
                Pattern bPat = Pattern.compile("bucket=(\\d+)");
                Pattern rPat = Pattern.compile("reason=0x([0-9a-fA-F]+)");
                for (String line : usOut.split("\n")) {
                    try {
                        if (line.contains("package=" + packageName)
                                || line.contains("Package[" + packageName)) inPkg = true;
                        if (inPkg && line.trim().startsWith("package=")
                                && !line.contains(packageName)) inPkg = false;
                        if (!inPkg) continue;

                        Matcher mB = bPat.matcher(line);
                        if (!mB.find()) continue;
                        String bn = bucketValueToName(Integer.parseInt(mB.group(1)));

                        String reason = "";
                        Matcher mR = rPat.matcher(line);
                        if (mR.find()) {
                            int main = ((int) Long.parseLong(mR.group(1), 16) >> 8) & 0xF;
                            switch (main) {
                                case 0x0: reason="default";     break;
                                case 0x1: reason="usage";       break;
                                case 0x2: reason="timeout";     break;
                                case 0x3: reason="predicted";   break;
                                case 0x4: reason="sys-forced";  break;
                                case 0x6: reason="user-forced"; break;
                            }
                        }
                        String entry = bn + (reason.isEmpty() ? "" : "(" + reason + ")");
                        if (history.isEmpty() || !history.get(history.size()-1).startsWith(bn))
                            history.add(entry);
                    } catch (Exception e) {
                        Log.w(TAG, "standby bucket history parse failed: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "usagestats failed: " + e.getMessage()); }

        if (history.size() > 4) history = history.subList(history.size()-4, history.size());

        if (history.isEmpty() || !history.get(history.size()-1).startsWith(currentName))
            history.add(currentName);

        String detail = currentName;
        if (history.size() > 1)
            detail += context.getString(R.string.triggers_bucket_history,
                    String.join(" → ", history));

        TriggerInfo.Severity sev;
        String expl;
        if      (bv <= 10) { sev=TriggerInfo.Severity.HIGH;   expl=context.getString(R.string.triggers_bucket_active_explanation); }
        else if (bv <= 20) { sev=TriggerInfo.Severity.MEDIUM; expl=context.getString(R.string.triggers_bucket_working_set_explanation); }
        else if (bv <= 30) { sev=TriggerInfo.Severity.LOW;    expl=context.getString(R.string.triggers_bucket_frequent_explanation); }
        else               { sev=TriggerInfo.Severity.INFO;   expl=context.getString(R.string.triggers_bucket_rare_explanation); }

        list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                context.getString(R.string.triggers_cat_bucket), detail, expl, sev));
        return list;
    }

    private List<TriggerInfo> analyzeBatteryStats(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys batterystats " + packageName);
        if (output == null || output.trim().isEmpty()) return list;

        long wlMs=0; int wlCnt=0, alarms=0, jobs=0, syncs=0;
        double powerMah = -1;

        Pattern wp   = Pattern.compile("Wakelock\\s+\\S+:\\s+(\\d+)ms realtime.*?\\((\\d+)\\s+times\\)", Pattern.CASE_INSENSITIVE);
        Pattern ap   = Pattern.compile("Wakeup alarm.*?:\\s*(\\d+)\\s+times",                            Pattern.CASE_INSENSITIVE);
        Pattern jp   = Pattern.compile("Job\\s+\\S+:\\s+\\d+ms realtime.*?\\((\\d+)\\s+times\\)",        Pattern.CASE_INSENSITIVE);
        Pattern sp   = Pattern.compile("Sync\\s+\\S+:\\s+\\d+ms realtime.*?\\((\\d+)\\s+times\\)",       Pattern.CASE_INSENSITIVE);

        Pattern pwrP = Pattern.compile("Uid\\s+u0a\\d+:\\s*([\\d.]+)(?:\\s*mAh)?", Pattern.CASE_INSENSITIVE);

        boolean inPowerSection = false;
        String  uid = cachedUid;

        for (String line : output.split("\n")) {
            try {

                if (line.contains("Estimated power use")) { inPowerSection = true; continue; }
                if (inPowerSection && !line.startsWith("  ")) inPowerSection = false;

                if (inPowerSection && powerMah < 0) {


                    if (uid != null) {
                        try {
                            int uidInt = Integer.parseInt(uid);
                            int appId  = uidInt - 10000;
                            if (appId >= 0 && line.contains("u0a" + appId)) {
                                Matcher mPwr = pwrP.matcher(line);
                                if (mPwr.find()) powerMah = Double.parseDouble(mPwr.group(1));
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    if (powerMah < 0 && line.contains(packageName)) {
                        Matcher mPwr = pwrP.matcher(line);
                        if (mPwr.find()) powerMah = Double.parseDouble(mPwr.group(1));
                    }
                }

                Matcher m;
                if ((m=wp.matcher(line)).find()) { wlMs+=Long.parseLong(m.group(1)); wlCnt+=Integer.parseInt(m.group(2)); continue; }
                if ((m=ap.matcher(line)).find()) { alarms+=Integer.parseInt(m.group(1)); continue; }
                if ((m=jp.matcher(line)).find()) { jobs  +=Integer.parseInt(m.group(1)); continue; }
                if ((m=sp.matcher(line)).find())   syncs +=Integer.parseInt(m.group(1));
            } catch (Exception e) { Log.w(TAG, "analyzeBatteryStats line parse failed: " + e.getMessage()); }
        }
        if (wlCnt==0&&alarms==0&&jobs==0&&syncs==0&&powerMah<0) return list;

        StringBuilder detail = new StringBuilder();
        if (powerMah >= 0)
            detail.append(String.format("%.2f mAh", powerMah));
        if (wlCnt  > 0) { if(detail.length()>0) detail.append(", ");
            detail.append(context.getString(R.string.triggers_batterystats_wakelock, wlCnt, formatDuration(wlMs))); }
        if (alarms > 0) { if(detail.length()>0) detail.append(", ");
            detail.append(context.getString(R.string.triggers_batterystats_alarms, alarms)); }
        if (jobs   > 0) { if(detail.length()>0) detail.append(", ");
            detail.append(context.getString(R.string.triggers_batterystats_jobs,   jobs)); }
        if (syncs  > 0) { if(detail.length()>0) detail.append(", ");
            detail.append(context.getString(R.string.triggers_batterystats_syncs,  syncs)); }

        TriggerInfo.Severity sev = alarms>50||wlMs>600_000||(powerMah>50) ? TriggerInfo.Severity.HIGH
                : alarms>10||wlMs>60_000||jobs>20||(powerMah>10) ? TriggerInfo.Severity.MEDIUM : TriggerInfo.Severity.LOW;

        list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                context.getString(R.string.triggers_cat_batterystats),
                detail.toString(),
                context.getString(R.string.triggers_batterystats_explanation), sev));
        return list;
    }


    private List<TriggerInfo> analyzeBroadcastEfficiency(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        String output = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys activity broadcasts history");
        if (output == null || output.trim().isEmpty()) return list;


        List<String> relevant = new ArrayList<>();
        for (String line : output.split("\n"))
            if (line.contains(packageName)) relevant.add(line);
        if (relevant.isEmpty()) return list;
        int start = Math.max(0, relevant.size() - 200);
        relevant = relevant.subList(start, relevant.size());

        int  delivered=0, launched=0, lastHour=0, lastDay=0;
        long nowMs=System.currentTimeMillis(), hourAgo=nowMs-3_600_000L, dayAgo=nowMs-86_400_000L;

        Pattern timePat  = Pattern.compile(
                "(?:enqueueClockTime|dispatchClockTime|finishTime)=(\\d{10,13})");
        Pattern startPat = Pattern.compile(
                "(?:start(?:ing)?\\s+proc|not\\s+running)", Pattern.CASE_INSENSITIVE);
        Pattern alivePat = Pattern.compile(
                "(?:already\\s+running|isAlive=true)",       Pattern.CASE_INSENSITIVE);

        long curTime=0; boolean curStart=false, curAlive=false, counted=false;

        for (String line : relevant) {
            if (line.contains("BroadcastRecord{") || line.contains("Broadcast #")) {
                if (counted) {
                    delivered++;
                    if (curStart && !curAlive) launched++;
                    if (curTime > hourAgo) lastHour++;
                    if (curTime > dayAgo)  lastDay++;
                }
                curTime=0; curStart=false; curAlive=false; counted=true;
            }
            Matcher mT = timePat.matcher(line);
            if (mT.find()) {
                long t = Long.parseLong(mT.group(1));
                if (t < 9_999_999_999L) t *= 1000;
                curTime = t;
            }
            if (startPat.matcher(line).find()) curStart = true;
            if (alivePat.matcher(line).find()) curAlive = true;
        }
        if (counted) {
            delivered++;
            if (curStart && !curAlive) launched++;
            if (curTime > hourAgo) lastHour++;
            if (curTime > dayAgo)  lastDay++;
        }

        if (delivered == 0) return list;
        int pct = (int)(launched * 100L / delivered);

        StringBuilder detail = new StringBuilder(
                context.getString(R.string.triggers_bcast_eff_total, delivered));
        if (lastHour > 0) detail.append(context.getString(R.string.triggers_bcast_eff_hour, lastHour));
        if (lastDay  > 0 && lastDay != lastHour)
            detail.append(context.getString(R.string.triggers_bcast_eff_day, lastDay));
        if (launched > 0)
            detail.append(context.getString(R.string.triggers_bcast_eff_launched, launched, pct));

        TriggerInfo.Severity sev = launched>10||(pct>50&&delivered>5) ? TriggerInfo.Severity.HIGH
                : launched>3||lastHour>20 ? TriggerInfo.Severity.MEDIUM : TriggerInfo.Severity.LOW;

        list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                context.getString(R.string.triggers_cat_bcast_eff),
                detail.toString(),
                context.getString(R.string.triggers_bcast_eff_explanation), sev));
        return list;
    }


    private List<TriggerInfo> analyzeFgNotification(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        try {
            String output = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys notification | grep -A20 'pkg=" + packageName + "'");
            if (output == null || output.trim().isEmpty()) return list;

            Pattern chanPat   = Pattern.compile("channelId=([\\w.\\-]+)");
            Pattern namePat   = Pattern.compile("name=([^,\\n]{1,40})");
            Pattern impPat    = Pattern.compile("importance=(\\d+)");
            Pattern soundPat  = Pattern.compile("sound=([^,\\n]+)");
            Pattern vibPat    = Pattern.compile("vibration=([^,\\n]+)");

            boolean inPkg = false;
            String chanId = null, chanName = null, importance = null;
            boolean hasSound = false, hasVibration = false;

            for (String line : output.split("\n")) {
                if (line.contains("pkg=" + packageName)) { inPkg = true; }
                if (inPkg && line.contains("pkg=") && !line.contains(packageName)) break;
                if (!inPkg) continue;

                Matcher mChan = chanPat.matcher(line);
                if (mChan.find() && chanId == null) chanId = mChan.group(1);

                Matcher mName = namePat.matcher(line);
                if (mName.find() && chanName == null) chanName = trimTo(mName.group(1).trim(), 30);

                Matcher mImp = impPat.matcher(line);
                if (mImp.find() && importance == null)
                    importance = mapNotifImportance(Integer.parseInt(mImp.group(1)));

                Matcher mSound = soundPat.matcher(line);
                if (mSound.find() && !mSound.group(1).trim().equals("null")) hasSound = true;

                Matcher mVib = vibPat.matcher(line);
                if (mVib.find() && !mVib.group(1).trim().equals("null")
                        && !mVib.group(1).trim().equals("[]")) hasVibration = true;
            }

            if (chanId == null && importance == null) return list;

            StringBuilder detail = new StringBuilder();
            if (chanName != null) detail.append(chanName);
            else if (chanId != null) detail.append(chanId);
            if (importance != null) detail.append(" · ").append(importance);
            if (hasSound)     detail.append(" · sound");
            if (hasVibration) detail.append(" · vibration");

            boolean isHighPriority = "URGENT".equals(importance) || "HIGH".equals(importance);
            list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                    context.getString(R.string.triggers_cat_fg_notification),
                    detail.toString(),
                    context.getString(isHighPriority
                            ? R.string.triggers_fg_notification_high_explanation
                            : R.string.triggers_fg_notification_explanation),
                    isHighPriority ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
        } catch (Exception e) {
            Log.w(TAG, "analyzeFgNotification failed: " + e.getMessage());
        }
        return list;
    }


    private List<TriggerInfo> analyzeAudioFocus(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();


        try {
            String audioOut = shellManager.runShellCommandAndGetFullOutput("dumpsys audio");
            if (audioOut != null) {
                boolean inFocusSection = false;
                String  focusType      = null;
                String  focusStream    = null;

                Pattern focusTypePat   = Pattern.compile(
                        "focusGain=([\\w_]+)", Pattern.CASE_INSENSITIVE);
                Pattern streamTypePat  = Pattern.compile(
                        "stream=(\\d+)");

                for (String line : audioOut.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("Audio Focus stack")) { inFocusSection = true; continue; }
                    if (inFocusSection && t.startsWith("---")) break;
                    if (!inFocusSection) continue;

                    if (!t.contains(packageName)) continue;

                    Matcher mFt = focusTypePat.matcher(t);
                    if (mFt.find() && focusType == null)
                        focusType = mapAudioFocusGain(mFt.group(1));

                    Matcher mSt = streamTypePat.matcher(t);
                    if (mSt.find() && focusStream == null)
                        focusStream = mapAudioStream(Integer.parseInt(mSt.group(1)));
                }

                if (focusType != null) {
                    String detail = focusType
                            + (focusStream != null ? " · stream:" + focusStream : "");
                    boolean isGain = focusType.contains("GAIN");
                    list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                            context.getString(R.string.triggers_cat_audio_focus),
                            detail,
                            context.getString(isGain
                                    ? R.string.triggers_audio_focus_gain_explanation
                                    : R.string.triggers_audio_focus_duck_explanation),
                            isGain ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
                }
            }
        } catch (Exception e) { Log.w(TAG, "analyzeAudioFocus/audio failed: " + e.getMessage()); }


        try {
            String msOut = shellManager.runShellCommandAndGetFullOutput("dumpsys media_session");
            if (msOut != null) {
                boolean inSession  = false;
                String  sessionTag = null;
                String  state      = null;

                Pattern tagPat    = Pattern.compile("tag=([^,\\s]+)");
                Pattern statePat  = Pattern.compile("state=(\\d+)");

                for (String line : msOut.split("\n")) {
                    String t = line.trim();
                    if (t.contains("package=" + packageName)
                            || t.contains("packageName=" + packageName)) {
                        inSession = true; sessionTag = null; state = null;
                    }
                    if (inSession && t.contains("package=")
                            && !t.contains(packageName)) inSession = false;
                    if (!inSession) continue;

                    Matcher mTag = tagPat.matcher(t);
                    if (mTag.find() && sessionTag == null)
                        sessionTag = trimTo(mTag.group(1), 30);

                    Matcher mSt = statePat.matcher(t);
                    if (mSt.find() && state == null)
                        state = mapMediaSessionState(Integer.parseInt(mSt.group(1)));
                }

                if (state != null) {
                    String detail = (sessionTag != null ? sessionTag + " · " : "") + state;
                    boolean isPlaying = "PLAYING".equals(state);

                    boolean alreadyReported = list.stream()
                            .anyMatch(i -> i.category.equals(
                                    context.getString(R.string.triggers_cat_audio_focus)));
                    if (!alreadyReported || !isPlaying) {
                        list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                                context.getString(R.string.triggers_cat_media_session),
                                detail,
                                context.getString(isPlaying
                                        ? R.string.triggers_media_session_playing_explanation
                                        : R.string.triggers_media_session_paused_explanation),
                                isPlaying ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "analyzeAudioFocus/media_session failed: " + e.getMessage()); }

        return list;
    }

    private String mapAudioFocusGain(String raw) {
        switch (raw.toUpperCase()) {
            case "AUDIOFOCUS_GAIN":               return "GAIN (exclusive)";
            case "AUDIOFOCUS_GAIN_TRANSIENT":     return "GAIN_TRANSIENT";
            case "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK": return "GAIN_TRANSIENT_DUCK";
            case "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE": return "GAIN_EXCLUSIVE";
            case "AUDIOFOCUS_LOSS":               return "LOSS";
            case "AUDIOFOCUS_LOSS_TRANSIENT":     return "LOSS_TRANSIENT";
            case "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK": return "LOSS_DUCK";
            default: return raw;
        }
    }

    private String mapAudioStream(int stream) {
        switch (stream) {
            case 0:  return "VOICE_CALL";
            case 1:  return "SYSTEM";
            case 2:  return "RING";
            case 3:  return "MUSIC";
            case 4:  return "ALARM";
            case 5:  return "NOTIFICATION";
            case 6:  return "BLUETOOTH_SCO";
            case 10: return "ACCESSIBILITY";
            default: return "STREAM_" + stream;
        }
    }

    private String mapMediaSessionState(int state) {
        switch (state) {
            case 0:  return "NONE";
            case 1:  return "STOPPED";
            case 2:  return "PAUSED";
            case 3:  return "PLAYING";
            case 4:  return "FAST_FORWARDING";
            case 5:  return "REWINDING";
            case 6:  return "BUFFERING";
            case 7:  return "ERROR";
            case 8:  return "CONNECTING";
            case 9:  return "SKIPPING_TO_PREVIOUS";
            case 10: return "SKIPPING_TO_NEXT";
            case 11: return "SKIPPING_TO_QUEUE_ITEM";
            default: return "STATE_" + state;
        }
    }


    private List<TriggerInfo> analyzeBluetooth(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();


        try {
            String btOut = shellManager.runShellCommandAndGetFullOutput("dumpsys bluetooth_manager");
            if (btOut != null) {
                boolean inScan  = false;
                int     scanCnt = 0;
                String  scanMode = null;

                Pattern modePat = Pattern.compile("scanMode=(\\w+)", Pattern.CASE_INSENSITIVE);

                for (String line : btOut.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("Scan clients:") || t.startsWith("Active scan clients"))
                        { inScan = true; continue; }
                    if (inScan && t.startsWith("---")) break;
                    if (!inScan) continue;

                    if (t.contains(packageName)) {
                        scanCnt++;
                        Matcher m = modePat.matcher(t);
                        if (m.find() && scanMode == null) scanMode = m.group(1);
                    }
                }

                if (scanCnt > 0) {
                    String detail = context.getString(R.string.triggers_ble_scan_count, scanCnt)
                            + (scanMode != null ? " · mode:" + scanMode : "");
                    boolean isLowLatency = scanMode != null
                            && scanMode.toUpperCase().contains("LOW_LATENCY");
                    list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                            context.getString(R.string.triggers_cat_ble_scan),
                            detail,
                            context.getString(isLowLatency
                                    ? R.string.triggers_ble_scan_low_latency_explanation
                                    : R.string.triggers_ble_scan_explanation),
                            isLowLatency ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));
                }
            }
        } catch (Exception e) { Log.w(TAG, "analyzeBluetooth/manager failed: " + e.getMessage()); }


        try {
            String gattOut = shellManager.runShellCommandAndGetFullOutput("dumpsys gatt");
            if (gattOut != null) {
                int     connCnt    = 0;
                boolean inConn     = false;
                List<String> addrs = new ArrayList<>();

                Pattern addrPat = Pattern.compile(
                        "address=([0-9A-Fa-f:]{17})", Pattern.CASE_INSENSITIVE);

                for (String line : gattOut.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("GATT Connections:") || t.startsWith("Connections:"))
                        { inConn = true; continue; }
                    if (inConn && t.startsWith("---")) break;
                    if (!inConn) continue;

                    if (!t.contains(packageName)) continue;
                    connCnt++;
                    Matcher m = addrPat.matcher(t);
                    if (m.find() && addrs.size() < 3) addrs.add(m.group(1));
                }

                if (connCnt > 0) {
                    StringBuilder detail = new StringBuilder(
                            context.getString(R.string.triggers_gatt_conn_count, connCnt));
                    if (!addrs.isEmpty())
                        detail.append(": ").append(String.join(", ", addrs));
                    list.add(new TriggerInfo(TriggerInfo.Group.ACTIVE_NOW,
                            context.getString(R.string.triggers_cat_gatt),
                            detail.toString(),
                            context.getString(R.string.triggers_gatt_explanation),
                            TriggerInfo.Severity.HIGH));
                }
            }
        } catch (Exception e) { Log.w(TAG, "analyzeBluetooth/gatt failed: " + e.getMessage()); }

        return list;
    }


    private List<TriggerInfo> analyzeContentObservers(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        try {
            String output = shellManager.runShellCommandAndGetFullOutput("dumpsys content");
            if (output == null || output.trim().isEmpty()) return list;

            boolean inObservers = false;
            List<String> uris   = new ArrayList<>();
            int total = 0;

            Pattern uriPat = Pattern.compile("uri=([^\\s,]+)");
            Pattern pkgPat = Pattern.compile("package=([\\w.]+)");

            for (String line : output.split("\n")) {
                String t = line.trim();
                if (t.startsWith("Observers:") || t.startsWith("Content observers:"))
                    { inObservers = true; continue; }
                if (inObservers && t.startsWith("---")) { inObservers = false; continue; }
                if (!inObservers) continue;


                boolean hasPkg = t.contains(packageName);
                if (!hasPkg) {
                    Matcher mPkg = pkgPat.matcher(t);
                    hasPkg = mPkg.find() && mPkg.group(1).equals(packageName);
                }
                if (!hasPkg) continue;

                total++;
                Matcher mUri = uriPat.matcher(t);
                if (mUri.find()) {
                    String uri = mUri.group(1);

                    uri = uri.replace("content://", "");
                    if (uri.length() > 40) uri = uri.substring(0, 40) + "…";
                    if (!uris.contains(uri) && uris.size() < 4) uris.add(uri);
                }
            }

            if (total == 0) return list;

            StringBuilder detail = new StringBuilder(
                    context.getString(R.string.triggers_content_obs_count, total));
            if (!uris.isEmpty())
                detail.append(": ").append(String.join(", ", uris));
            if (total > uris.size())
                detail.append(context.getString(
                        R.string.triggers_content_obs_overflow, total - uris.size()));

            list.add(new TriggerInfo(TriggerInfo.Group.CAN_WAKE,
                    context.getString(R.string.triggers_cat_content_obs),
                    detail.toString(),
                    context.getString(R.string.triggers_content_obs_explanation),
                    total > 5 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));

        } catch (Exception e) { Log.w(TAG, "analyzeContentObservers failed: " + e.getMessage()); }
        return list;
    }


    private List<TriggerInfo> analyzeFcmRegistration(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        try {
            String pkgOut = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys package " + packageName);
            if (pkgOut == null) return list;

            boolean hasFirebase = false;
            boolean hasFcmService = false;
            boolean hasDataMsg = false;

            for (String line : pkgOut.split("\n")) {
                String t = line.toLowerCase();
                if (t.contains("firebase") || t.contains("fcm") || t.contains("iid"))
                    hasFirebase = true;
                if (t.contains("firebasemessagingservice")
                        || t.contains("com.google.firebase.messaging"))
                    hasFcmService = true;
                if (t.contains("com.google.android.c2dm.intent.receive")
                        || t.contains("com.google.firebase.messaging.intent.action"))
                    hasDataMsg = true;
            }

            if (!hasFirebase && !hasFcmService && !hasDataMsg) return list;

            String detail;
            if (hasFcmService) detail = context.getString(R.string.triggers_fcm_service_detail);
            else if (hasDataMsg) detail = context.getString(R.string.triggers_fcm_receiver_detail);
            else detail = context.getString(R.string.triggers_fcm_generic_detail);

            list.add(new TriggerInfo(TriggerInfo.Group.CAN_WAKE,
                    context.getString(R.string.triggers_cat_fcm),
                    detail,
                    context.getString(R.string.triggers_fcm_explanation),
                    TriggerInfo.Severity.MEDIUM));

        } catch (Exception e) { Log.w(TAG, "analyzeFcmRegistration failed: " + e.getMessage()); }
        return list;
    }


    private List<TriggerInfo> analyzeMultipleProcesses(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        try {

            String psOut = shellManager.runShellCommandAndGetFullOutput(
                    "ps -A -o pid,name 2>/dev/null | grep " + packageName);
            if (psOut == null || psOut.trim().isEmpty()) {

                psOut = shellManager.runShellCommandAndGetFullOutput(
                        "ps -A 2>/dev/null | grep " + packageName);
            }
            if (psOut == null || psOut.trim().isEmpty()) return list;

            List<String> processNames = new ArrayList<>();
            List<Integer> pids        = new ArrayList<>();

            Pattern pidPat  = Pattern.compile("^\\s*\\S+\\s+(\\d+)");
            Pattern namePat = Pattern.compile("(" + Pattern.quote(packageName) + "[:\\w]*)\\s*$");

            for (String line : psOut.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Matcher mName = namePat.matcher(line);
                if (!mName.find()) continue;
                String procName = mName.group(1);
                if (!processNames.contains(procName)) processNames.add(procName);

                Matcher mPid = pidPat.matcher(line);
                if (mPid.find()) {
                    try { pids.add(Integer.parseInt(mPid.group(1))); }
                    catch (NumberFormatException ignored) {}
                }
            }

            int count = processNames.size();
            if (count <= 1) return list;


            List<String> subNames = new ArrayList<>();
            for (String n : processNames) {
                if (n.equals(packageName)) subNames.add(0, "main");
                else if (n.startsWith(packageName + ":"))
                    subNames.add(n.substring(packageName.length()));
                else
                    subNames.add(n);
            }

            StringBuilder detail = new StringBuilder(
                    context.getString(R.string.triggers_multiproc_count, count));
            detail.append(": ").append(String.join(", ", subNames));

            list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                    context.getString(R.string.triggers_cat_multiproc),
                    detail.toString(),
                    context.getString(R.string.triggers_multiproc_explanation),
                    count > 3 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));

        } catch (Exception e) { Log.w(TAG, "analyzeMultipleProcesses failed: " + e.getMessage()); }
        return list;
    }


    private List<TriggerInfo> analyzeAccessibilityAndIme(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();


        try {
            String a11yOut = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys accessibility");
            if (a11yOut != null) {
                boolean enabled = false;
                String  svcName = null;

                Pattern svcPat = Pattern.compile(
                        "([\\w.]+/[\\w.]+)", Pattern.CASE_INSENSITIVE);

                boolean inEnabled = false;
                for (String line : a11yOut.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("Enabled services:") || t.startsWith("Installed services:"))
                        inEnabled = true;
                    if (inEnabled && t.startsWith("---")) inEnabled = false;
                    if (!inEnabled) continue;

                    if (!t.contains(packageName)) continue;
                    enabled = true;
                    Matcher m = svcPat.matcher(t);
                    if (m.find()) svcName = m.group(1);
                    if (svcName != null && svcName.contains("/")) {

                        String cls = svcName.substring(svcName.indexOf('/') + 1);
                        if (cls.startsWith(packageName + "."))
                            cls = cls.substring(packageName.length() + 1);
                        svcName = cls;
                    }
                    break;
                }

                if (enabled) {
                    list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                            context.getString(R.string.triggers_cat_accessibility),
                            svcName != null ? svcName
                                    : context.getString(R.string.triggers_a11y_detail_generic),
                            context.getString(R.string.triggers_a11y_explanation),
                            TriggerInfo.Severity.HIGH));
                }
            }
        } catch (Exception e) { Log.w(TAG, "analyzeAccessibility failed: " + e.getMessage()); }


        try {
            String imeOut = shellManager.runShellCommandAndGetFullOutput("dumpsys input_method");
            if (imeOut != null) {
                boolean isCurrentIme = false;
                String  imeName = null;

                for (String line : imeOut.split("\n")) {
                    String t = line.trim();

                    if ((t.startsWith("mCurMethodId=") || t.startsWith("mCurId="))
                            && t.contains(packageName)) {
                        isCurrentIme = true;
                        Matcher m = Pattern.compile(packageName + "/([\\w.$]+)").matcher(t);
                        if (m.find()) imeName = m.group(1);
                        break;
                    }
                }

                if (isCurrentIme) {
                    list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                            context.getString(R.string.triggers_cat_ime),
                            imeName != null ? imeName
                                    : context.getString(R.string.triggers_ime_detail_generic),
                            context.getString(R.string.triggers_ime_explanation),
                            TriggerInfo.Severity.HIGH));
                }
            }
        } catch (Exception e) { Log.w(TAG, "analyzeIme failed: " + e.getMessage()); }

        return list;
    }


    private List<TriggerInfo> analyzeDeviceAdmin(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        try {
            String dpOut = shellManager.runShellCommandAndGetFullOutput("dumpsys device_policy");
            if (dpOut == null || dpOut.trim().isEmpty()) return list;

            boolean isOwner  = false;
            boolean isAdmin  = false;
            String  ownerType = null;

            for (String line : dpOut.split("\n")) {
                String t = line.trim();

                if ((t.startsWith("Device Owner:") || t.startsWith("mDeviceOwner="))
                        && t.contains(packageName)) {
                    isOwner = true; ownerType = "device";
                }

                if ((t.startsWith("Profile Owner") || t.startsWith("mProfileOwner="))
                        && t.contains(packageName)) {
                    isOwner = true; ownerType = "profile";
                }

                if (t.contains("Active admin") || t.contains("AdminList:"))
                    isAdmin = t.contains(packageName);
                if (!isAdmin && t.contains(packageName)
                        && (t.contains("ComponentInfo") || t.contains("admin=")))
                    isAdmin = true;
            }

            if (!isOwner && !isAdmin) return list;

            String detail, expl;
            TriggerInfo.Severity sev;
            if (isOwner) {
                detail = context.getString(ownerType.equals("device")
                        ? R.string.triggers_device_admin_owner_device
                        : R.string.triggers_device_admin_owner_profile);
                expl   = context.getString(R.string.triggers_device_admin_owner_explanation);
                sev    = TriggerInfo.Severity.HIGH;
            } else {
                detail = context.getString(R.string.triggers_device_admin_active);
                expl   = context.getString(R.string.triggers_device_admin_explanation);
                sev    = TriggerInfo.Severity.MEDIUM;
            }

            list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                    context.getString(R.string.triggers_cat_device_admin),
                    detail, expl, sev));

        } catch (Exception e) { Log.w(TAG, "analyzeDeviceAdmin failed: " + e.getMessage()); }
        return list;
    }


    private List<TriggerInfo> analyzeAppOps(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        try {


            String out = shellManager.runShellCommandAndGetFullOutput(
                    "appops get " + packageName);
            if (out == null || out.trim().isEmpty()) {
                out = shellManager.runShellCommandAndGetFullOutput(
                        "cmd appops get " + packageName);
            }
            if (out == null || out.trim().isEmpty()) return list;

            Pattern opPat   = Pattern.compile(
                    "^([A-Z_]+):\\s*(allow|foreground|ignore|deny|default)",
                    Pattern.CASE_INSENSITIVE);


            Pattern timePat = Pattern.compile(
                    "time=\\+([\\d][\\d\\w\\s]*)ago", Pattern.CASE_INSENSITIVE);

            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;

                try {
                    Matcher mOp = opPat.matcher(t);
                    if (!mOp.find()) continue;

                    String op   = mOp.group(1).toUpperCase();
                    String mode = mOp.group(2).toLowerCase();

                    OpDescriptor desc = appOpDescriptor(op);
                    if (desc == null) continue;

                    if ("ignore".equals(mode) || "deny".equals(mode)) continue;

                    String timeStr = null;
                    Matcher mTime = timePat.matcher(t);
                    if (mTime.find()) timeStr = mTime.group(1).trim();

                    boolean isPresenceOnly = op.startsWith("RUN_") || op.equals("START_FOREGROUND");
                    if (timeStr == null && !isPresenceOnly) continue;

                    StringBuilder detail = new StringBuilder(desc.label);
                    if (timeStr != null)
                        detail.append(" · ")
                              .append(context.getString(R.string.triggers_appops_last_used, timeStr));
                    if (!"allow".equals(mode))
                        detail.append(" [").append(mode).append("]");

                    list.add(new TriggerInfo(desc.group,
                            context.getString(R.string.triggers_cat_appops),
                            detail.toString(),
                            desc.explanation,
                            desc.severity));
                } catch (Exception e) {
                    Log.w(TAG, "analyzeAppOps line parse failed: " + e.getMessage());
                }
            }


            boolean hasRunAny = false;
            boolean hasRun    = false;
            for (TriggerInfo i : list) {
                if (i.detail != null && i.detail.startsWith("RUN_ANY")) hasRunAny = true;
                if (i.detail != null && i.detail.startsWith("RUN_IN"))  hasRun    = true;
            }
            if (hasRunAny && hasRun)
                list.removeIf(i -> i.detail != null && i.detail.startsWith("RUN_IN_BACKGROUND ·"));

        } catch (Exception e) { Log.w(TAG, "analyzeAppOps failed: " + e.getMessage()); }
        return list;
    }

    private static final class OpDescriptor {
        final String               label;
        final String               explanation;
        final TriggerInfo.Group    group;
        final TriggerInfo.Severity severity;
        OpDescriptor(String label, String explanation,
                     TriggerInfo.Group group, TriggerInfo.Severity severity) {
            this.label       = label;
            this.explanation = explanation;
            this.group       = group;
            this.severity    = severity;
        }
    }


    private OpDescriptor appOpDescriptor(String op) {
        switch (op) {
            case "WAKE_LOCK":
                return new OpDescriptor("WAKE_LOCK",
                        context.getString(R.string.triggers_appops_wakelock_explanation),
                        TriggerInfo.Group.ACTIVE_NOW, TriggerInfo.Severity.HIGH);
            case "START_FOREGROUND":
                return new OpDescriptor("START_FOREGROUND",
                        context.getString(R.string.triggers_appops_fg_explanation),
                        TriggerInfo.Group.ACTIVE_NOW, TriggerInfo.Severity.HIGH);
            case "RUN_IN_BACKGROUND":
                return new OpDescriptor("RUN_IN_BACKGROUND",
                        context.getString(R.string.triggers_appops_run_bg_explanation),
                        TriggerInfo.Group.CAN_WAKE, TriggerInfo.Severity.MEDIUM);
            case "RUN_ANY_IN_BACKGROUND":
                return new OpDescriptor("RUN_ANY_IN_BACKGROUND",
                        context.getString(R.string.triggers_appops_run_any_bg_explanation),
                        TriggerInfo.Group.CAN_WAKE, TriggerInfo.Severity.HIGH);
            case "SCHEDULE_EXACT_ALARM":
                return new OpDescriptor("SCHEDULE_EXACT_ALARM",
                        context.getString(R.string.triggers_appops_exact_alarm_explanation),
                        TriggerInfo.Group.CAN_WAKE, TriggerInfo.Severity.HIGH);
            case "USE_EXACT_ALARM":
                return new OpDescriptor("USE_EXACT_ALARM",
                        context.getString(R.string.triggers_appops_exact_alarm_explanation),
                        TriggerInfo.Group.CAN_WAKE, TriggerInfo.Severity.HIGH);
            case "RECEIVE_EXPLICIT_USER_INTERACTION":
                return new OpDescriptor("USER_INTERACTION",
                        context.getString(R.string.triggers_appops_user_interaction_explanation),
                        TriggerInfo.Group.CAN_WAKE, TriggerInfo.Severity.MEDIUM);
            case "ACTIVITY_RECOGNITION":
                return new OpDescriptor("ACTIVITY_RECOGNITION",
                        context.getString(R.string.triggers_appops_activity_recognition_explanation),
                        TriggerInfo.Group.ACTIVE_NOW, TriggerInfo.Severity.MEDIUM);
            default:
                return null;
        }
    }


    private List<TriggerInfo> analyzeUsageStats(String packageName) {
        List<TriggerInfo> list = new ArrayList<>();
        try {
            String out = shellManager.runShellCommandAndGetFullOutput(
                    "dumpsys usagestats | grep -A 15 \"" + packageName + "\"");
            if (out == null || out.trim().isEmpty()) return list;


            Pattern usedPat  = Pattern.compile(
                    "(?:lastTimeUsed|mLastTimeUsed|last_time_used)[=:](\\d+)");
            Pattern fgPat    = Pattern.compile(
                    "(?:lastTimeForeground|mLastTimeForeground|last_time_fg)[=:](\\d+)");
            Pattern totalPat = Pattern.compile(
                    "(?:totalTimeInForeground|mTotalTimeInForeground|total_time_fg)[=:](\\d+)");

            long lastUsed  = -1;
            long lastFg    = -1;
            long totalFgMs = -1;

            for (String line : out.split("\n")) {
                if (!line.contains(packageName) && lastUsed == -1 && lastFg == -1) continue;

                try {
                    Matcher mU = usedPat.matcher(line);
                    if (mU.find() && lastUsed == -1)
                        lastUsed = Long.parseLong(mU.group(1));

                    Matcher mF = fgPat.matcher(line);
                    if (mF.find() && lastFg == -1)
                        lastFg = Long.parseLong(mF.group(1));

                    Matcher mT = totalPat.matcher(line);
                    if (mT.find() && totalFgMs == -1)
                        totalFgMs = Long.parseLong(mT.group(1));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "analyzeUsageStats parse failed: " + e.getMessage());
                }

                if (lastUsed > 0 && lastFg > 0 && totalFgMs > 0) break;
            }

            if (lastUsed <= 0) return list;

            long nowMs     = System.currentTimeMillis();
            long sinceUsed = nowMs - lastUsed;
            long sinceFg   = lastFg > 0 ? nowMs - lastFg : -1;


            if (sinceUsed < 0 || sinceUsed > 30L * 24 * 3600 * 1000) return list;


            if (sinceFg >= 0 && sinceFg < 5 * 60 * 1000) return list;


            boolean isBgWake = sinceUsed < 10 * 60 * 1000
                    && (sinceFg < 0 || sinceFg > sinceUsed + 60_000);

            if (!isBgWake) return list;

            StringBuilder detail = new StringBuilder(
                    context.getString(R.string.triggers_usagestats_last_used,
                            formatDuration(sinceUsed)));
            if (sinceFg > 0)
                detail.append(" · ")
                      .append(context.getString(R.string.triggers_usagestats_last_fg,
                              formatDuration(sinceFg)));
            if (totalFgMs > 0)
                detail.append(" · ")
                      .append(context.getString(R.string.triggers_usagestats_total_fg,
                              formatDuration(totalFgMs)));

            list.add(new TriggerInfo(TriggerInfo.Group.OTHER,
                    context.getString(R.string.triggers_cat_usagestats),
                    detail.toString(),
                    context.getString(R.string.triggers_usagestats_explanation),
                    sinceUsed < 60_000 ? TriggerInfo.Severity.HIGH : TriggerInfo.Severity.MEDIUM));

        } catch (Exception e) { Log.w(TAG, "analyzeUsageStats failed: " + e.getMessage()); }
        return list;
    }


    private String resolveUid(String packageName) {
        String out = shellManager.runShellCommandAndGetFullOutput(
                "dumpsys package " + packageName + " | grep userId=");
        if (out == null) return null;
        for (String line : out.split("\n")) {
            Matcher m = Pattern.compile("userId=(\\d+)").matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String resolveAppName(String pkg) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return context.getPackageManager().getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    private String bucketValueToName(int v) {
        if (v <= 10) return "ACTIVE";
        if (v <= 20) return "WORKING_SET";
        if (v <= 30) return "FREQUENT";
        if (v <= 40) return "RARE";
        if (v <= 45) return "RESTRICTED";
        return "NEVER";
    }

    private String shortenAction(String action) {
        if (action.startsWith("android.intent.action.")) return action.substring(22);
        if (action.startsWith("android.net.conn."))      return action.substring(17);
        if (action.startsWith("android.net."))           return action.substring(12);
        if (action.startsWith("com.android."))           return action.substring(12);
        return action;
    }

    private String extractServiceShortName(String line, String packageName) {
        Matcher m = Pattern.compile("ServiceRecord\\{[^}]+\\s([\\w./]+)\\}").matcher(line);
        if (!m.find()) return null;
        String full = m.group(1);
        if (!full.contains("/")) return full;
        String cls = full.substring(full.indexOf('/') + 1);
        if (cls.startsWith("."))               return cls.substring(1);
        if (cls.startsWith(packageName + ".")) return cls.substring(packageName.length() + 1);
        return cls;
    }

    private String normalizeAccuracy(String raw) {
        String n = raw.toUpperCase();
        if (n.contains("HIGH"))    return "HIGH_ACCURACY";
        if (n.contains("BALANCE")) return "BALANCED";
        if (n.contains("LOW"))     return "LOW_POWER";
        return "NO_POWER";
    }

    private String mergeAccuracy(String cur, String cand) {
        if (cur == null) return cand;
        String[] ord = {"HIGH_ACCURACY","BALANCED","LOW_POWER","NO_POWER"};
        int ci=3, ca=3;
        for (int i=0;i<ord.length;i++) { if(ord[i].equals(cur)) ci=i; if(ord[i].equals(cand)) ca=i; }
        return ci <= ca ? cur : cand;
    }

    private String formatInterval(long ms) {
        long sec = ms / 1000;
        if (sec < 60)   return context.getString(R.string.triggers_alarms_interval_sec,  (int) sec);
        if (sec < 3600) return context.getString(R.string.triggers_alarms_interval_min,  (int)(sec/60));
        return             context.getString(R.string.triggers_alarms_interval_hour, (int)(sec/3600));
    }

    private String formatDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60)   return sec + context.getString(R.string.time_unit_sec);
        if (sec < 3600) return (sec/60) + context.getString(R.string.time_unit_min);
        return               (sec/3600) + context.getString(R.string.time_unit_hour);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024)            return bytes + " B";
        if (bytes < 1024*1024)       return (bytes/1024) + " KB";
        if (bytes < 1024L*1024*1024) return (bytes/(1024*1024)) + " MB";
        return (bytes/(1024L*1024*1024)) + " GB";
    }

    private static boolean anyContains(List<String> list, String... tokens) {
        for (String s : list) for (String t : tokens) if (s.contains(t)) return true;
        return false;
    }

    private static String trimTo(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
