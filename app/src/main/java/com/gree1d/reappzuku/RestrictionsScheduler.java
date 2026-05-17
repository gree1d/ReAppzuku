package com.gree1d.reappzuku;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.gree1d.reappzuku.db.AppDatabase;
import com.gree1d.reappzuku.db.SchedulerLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static com.gree1d.reappzuku.PreferenceKeys.*;


public class RestrictionsScheduler {

    private static final String TAG = "RestrictionsScheduler";


    public static final String ACTION_SCHEDULER_TICK = "SCHEDULER_TICK";


    private static final int SCHEDULER_ALARM_REQUEST_CODE = 2001;


    public static final int MAX_SCHEDULES = 15;


    private static final String KEY_SCHEDULES      = "restrictions_schedules";
    private static final String KEY_TEMP_PROTECTED = "temp_protected_packages";


    public static final int PROTECT_AUTO_KILL       = 1;
    public static final int PROTECT_BG_RESTRICTIONS = 1 << 1;
    public static final int PROTECT_SLEEP_MODE      = 1 << 2;
    public static final int PROTECT_ALL             = PROTECT_AUTO_KILL | PROTECT_BG_RESTRICTIONS | PROTECT_SLEEP_MODE;


    public static final int ON_ACTIVATE_NOTHING  = 0;
    public static final int ON_ACTIVATE_ACTIVITY = 1;
    public static final int ON_ACTIVATE_SERVICE  = 2;
    public static final int ON_ACTIVATE_RECEIVER = 3;


    public static class ScheduleEntry {

        public long id;

        public String packageName;

        public int startHour;

        public int startMinute;

        public int endHour;

        public int endMinute;

        public int protectFlags;

        public int onActivateAction;

        public String componentName;

        public boolean enabled;

        public ScheduleEntry() {
            this.id               = System.currentTimeMillis();
            this.protectFlags     = PROTECT_ALL;
            this.onActivateAction = ON_ACTIVATE_NOTHING;
            this.enabled          = true;
        }


        public boolean isActiveNow(int currentHour, int currentMinute) {
            if (!enabled) return false;
            int now  = currentHour  * 60 + currentMinute;
            int from = startHour    * 60 + startMinute;
            int to   = endHour      * 60 + endMinute;
            if (from == to) return false;
            if (from < to)  return now >= from && now < to;
            return now >= from || now < to;
        }


        public long nextStartMillis() {
            return nextEventMillis(startHour, startMinute);
        }


        public long nextEndMillis() {
            return nextEventMillis(endHour, endMinute);
        }

        private long nextEventMillis(int hour, int minute) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE,      minute);
            cal.set(Calendar.SECOND,      0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            return cal.getTimeInMillis();
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id",          id);
            obj.put("packageName", packageName);
            obj.put("startHour",   startHour);
            obj.put("startMin",    startMinute);
            obj.put("endHour",     endHour);
            obj.put("endMin",      endMinute);
            obj.put("flags",       protectFlags);
            obj.put("onActivate",     onActivateAction);
            obj.put("componentName",  componentName != null ? componentName : "");
            obj.put("enabled",        enabled);
            return obj;
        }

        public static ScheduleEntry fromJson(JSONObject obj) throws JSONException {
            ScheduleEntry e = new ScheduleEntry();
            e.id               = obj.getLong("id");
            e.packageName      = obj.getString("packageName");
            e.startHour        = obj.getInt("startHour");
            e.startMinute      = obj.getInt("startMin");
            e.endHour          = obj.getInt("endHour");
            e.endMinute        = obj.getInt("endMin");
            e.protectFlags     = obj.getInt("flags");
            e.onActivateAction = obj.getInt("onActivate");
            String comp        = obj.optString("componentName", "");
            e.componentName    = comp.isEmpty() ? null : comp;
            e.enabled          = obj.optBoolean("enabled", true);
            return e;
        }
    }


    public static final class SchedulerLog {

        private static final int MAX_ENTRIES    = 200;
        private static final int MAX_DETAIL_LEN = 180;

        private SchedulerLog() {}


        public static void logLift(Context context, String packageName,
                                   String outcome, String componentName, boolean use24h) {
            String detail = componentName != null
                    ? "action=" + shortName(componentName)
                    : "action=none";
            append(context, "lift", packageName, outcome, detail);
        }


        private static String shortName(String componentName) {
            int dot = componentName.lastIndexOf('.');
            return dot >= 0 ? componentName.substring(dot + 1) : componentName;
        }


        public static void logRestore(Context context, String packageName,
                                      String outcome, boolean forceStop, boolean use24h) {
            String detail = "stop=" + (forceStop ? "force-stop" : "am-kill");
            append(context, "restore", packageName, outcome, detail);
        }

        public static String readDisplayText(Context context) {
            if (context == null) return context.getString(R.string.log_scheduler_empty);
            List<SchedulerLog.Entry> entries = readEntries(context);
            if (entries.isEmpty()) return context.getString(R.string.log_scheduler_empty);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(entries.get(i).toDisplayLine());
            }
            return sb.toString();
        }


        public static List<SchedulerLog.Entry> readEntries(Context context) {
            List<com.gree1d.reappzuku.db.SchedulerLog> rows =
                    AppDatabase.getInstance(context).schedulerLogDao().getRecent(MAX_ENTRIES);
            List<SchedulerLog.Entry> result = new ArrayList<>(rows.size());
            for (com.gree1d.reappzuku.db.SchedulerLog row : rows) {
                result.add(new Entry(
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
            AppDatabase.getInstance(context).schedulerLogDao().clearAll();
        }

        private static void append(Context context, String action, String packageName,
                                   String outcome, String detail) {
            if (context == null) return;

            com.gree1d.reappzuku.db.SchedulerLog entry = new com.gree1d.reappzuku.db.SchedulerLog();
            entry.timestamp   = System.currentTimeMillis();
            entry.action      = sanitize(action);
            entry.packageName = sanitize(packageName != null ? packageName : "-");
            entry.outcome     = sanitize(outcome     != null ? outcome     : "unknown");
            entry.detail      = sanitize(detail);

            com.gree1d.reappzuku.db.SchedulerLog.Dao dao =
                    AppDatabase.getInstance(context).schedulerLogDao();
            dao.insert(entry);


            int count = dao.getCount();
            if (count > MAX_ENTRIES) {
                dao.deleteOldest(count - MAX_ENTRIES);
            }
        }

        private static String formatTimestamp(long millis) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
        }

        private static String sanitize(String value) {
            if (value == null) return "";
            String s = value.replace('\r', ' ').replace('\n', ' ')
                            .replace('|', '/').replaceAll("\\s+", " ").trim();
            return s.length() > MAX_DETAIL_LEN ? s.substring(0, MAX_DETAIL_LEN - 3) + "..." : s;
        }


        public static final class Entry {
            public final String timestamp;
            public final String action;
            public final String packageName;
            public final String outcome;
            public final String detail;

            private Entry(String timestamp, String action, String packageName,
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


    public static final class SchedulerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent serviceIntent = new Intent(context, ShappkyService.class);
            serviceIntent.setAction(ACTION_SCHEDULER_TICK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }


    private final Context              context;
    private final Handler              handler;
    private final ExecutorService      executor;
    private final ShellManager         shellManager;
    private final BackgroundAppManager backgroundAppManager;
    private final SharedPreferences    prefs;

    public RestrictionsScheduler(Context context,
                                 Handler handler,
                                 ExecutorService executor,
                                 ShellManager shellManager,
                                 BackgroundAppManager backgroundAppManager) {
        this.context              = context;
        this.handler              = handler;
        this.executor             = executor;
        this.shellManager         = shellManager;
        this.backgroundAppManager = backgroundAppManager;
        this.prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }


    public List<ScheduleEntry> getSchedules() {
        List<ScheduleEntry> list = new ArrayList<>();
        String json = prefs.getString(KEY_SCHEDULES, null);
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(ScheduleEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "getSchedules: parse error", e);
        }
        return list;
    }

    public void saveSchedules(List<ScheduleEntry> schedules) {
        JSONArray arr = new JSONArray();
        for (ScheduleEntry entry : schedules) {
            try { arr.put(entry.toJson()); }
            catch (JSONException e) { Log.e(TAG, "saveSchedules: " + entry.packageName, e); }
        }
        prefs.edit().putString(KEY_SCHEDULES, arr.toString()).apply();
    }


    public boolean addSchedule(ScheduleEntry entry) {
        List<ScheduleEntry> list = getSchedules();
        if (list.size() >= MAX_SCHEDULES) {
            Log.w(TAG, "addSchedule: limit reached (" + MAX_SCHEDULES + ")");
            return false;
        }
        list.add(entry);
        saveSchedules(list);
        scheduleNext();
        return true;
    }


    public boolean updateSchedule(ScheduleEntry updated) {
        List<ScheduleEntry> list = getSchedules();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == updated.id) {
                list.set(i, updated);
                saveSchedules(list);
                scheduleNext();
                return true;
            }
        }
        return false;
    }


    public void removeSchedule(long id) {
        List<ScheduleEntry> list = getSchedules();
        list.removeIf(e -> e.id == id);
        saveSchedules(list);
        scheduleNext();
    }


    public Set<String> getTempProtectedPackages() {
        return new HashSet<>(prefs.getStringSet(KEY_TEMP_PROTECTED, new HashSet<>()));
    }


    public boolean isProtected(String packageName, int flag) {
        if (!getTempProtectedPackages().contains(packageName)) return false;
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        for (ScheduleEntry e : getSchedules()) {
            if (e.packageName.equals(packageName) && e.isActiveNow(h, m)) {
                return (e.protectFlags & flag) != 0;
            }
        }
        return false;
    }


    public void scheduleNext() {
        List<ScheduleEntry> schedules = getSchedules();
        if (schedules.isEmpty()) {
            cancelAlarm();
            return;
        }

        long now     = System.currentTimeMillis();
        long nearest = Long.MAX_VALUE;

        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);

        for (ScheduleEntry e : schedules) {
            if (!e.enabled) continue;

            long candidate = e.isActiveNow(h, m) ? e.nextEndMillis() : e.nextStartMillis();
            if (candidate < nearest) nearest = candidate;
        }

        if (nearest == Long.MAX_VALUE || nearest <= now) {
            cancelAlarm();
            return;
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nearest, getAlarmIntent());
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, nearest, getAlarmIntent());
        }

        Log.d(TAG, "scheduleNext: alarm in " + ((nearest - now) / 1000 / 60) + " min");
    }


    public static void scheduleNextStatic(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SCHEDULES, null);
        if (json == null || json.isEmpty()) return;

        List<ScheduleEntry> schedules = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                schedules.add(ScheduleEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "scheduleNextStatic: parse error", e);
            return;
        }

        long now     = System.currentTimeMillis();
        long nearest = Long.MAX_VALUE;
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);

        for (ScheduleEntry e : schedules) {
            if (!e.enabled) continue;
            long candidate = e.isActiveNow(h, m) ? e.nextEndMillis() : e.nextStartMillis();
            if (candidate < nearest) nearest = candidate;
        }

        if (nearest == Long.MAX_VALUE || nearest <= now) return;

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = getAlarmIntent(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nearest, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, nearest, pi);
        }
        Log.d(TAG, "scheduleNextStatic: alarm in " + ((nearest - now) / 1000 / 60) + " min");
    }

    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(getAlarmIntent(context));
    }

    private PendingIntent getAlarmIntent() {
        return getAlarmIntent(context);
    }

    private static PendingIntent getAlarmIntent(Context context) {
        Intent intent = new Intent(context, SchedulerReceiver.class);
        intent.setAction(ACTION_SCHEDULER_TICK);
        return PendingIntent.getBroadcast(
                context,
                SCHEDULER_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }


    public void tick() {
        executor.execute(() -> {
            Calendar cal = Calendar.getInstance();
            int hour   = cal.get(Calendar.HOUR_OF_DAY);
            int minute = cal.get(Calendar.MINUTE);

            List<ScheduleEntry> schedules    = getSchedules();
            Set<String>         wasProtected = getTempProtectedPackages();


            Set<String> shouldBeProtected = new HashSet<>();
            for (ScheduleEntry e : schedules) {
                if (e.isActiveNow(hour, minute)) shouldBeProtected.add(e.packageName);
            }

            Set<String> newlyActivated   = diff(shouldBeProtected, wasProtected);
            Set<String> newlyDeactivated = diff(wasProtected, shouldBeProtected);


            saveTempProtectedPackages(shouldBeProtected);

            boolean use24h = android.text.format.DateFormat.is24HourFormat(context);


            for (String pkg : newlyActivated) {
                ScheduleEntry entry = findActiveEntry(schedules, pkg, hour, minute);
                if (entry == null) continue;
                Log.d(TAG, "tick: activating " + pkg);

                if ((entry.protectFlags & PROTECT_BG_RESTRICTIONS) != 0) {
                    String outcome = backgroundAppManager.liftRestrictionsForScheduler(pkg);
                    SchedulerLog.logLift(context, pkg, outcome, entry.componentName, use24h);
                }

                if ((entry.protectFlags & PROTECT_SLEEP_MODE) != 0) {
                    shellManager.unfreezePackage(pkg);
                }

                if (entry.onActivateAction != ON_ACTIVATE_NOTHING && entry.componentName != null) {
                    final String component = entry.componentName;
                    final int    action    = entry.onActivateAction;

                    handler.postDelayed(() -> launchComponent(component, action), 500);
                }
            }


            for (String pkg : newlyDeactivated) {
                ScheduleEntry entry = findEntryForPackage(schedules, pkg);
                if (entry == null) continue;
                Log.d(TAG, "tick: deactivating " + pkg);

                boolean forceStop = isForceStopMode();

                if ((entry.protectFlags & PROTECT_BG_RESTRICTIONS) != 0) {
                    String outcome = backgroundAppManager.restoreRestrictionsForScheduler(pkg);
                    SchedulerLog.logRestore(context, pkg, outcome, forceStop, use24h);
                } else if ((entry.protectFlags & PROTECT_AUTO_KILL) != 0) {


                    stopApp(pkg, forceStop);
                    SchedulerLog.logRestore(context, pkg, "ok", forceStop, use24h);
                }


            }


            scheduleNext();
        });
    }


    private void stopApp(String packageName, boolean forceStop) {
        String cmd = (forceStop ? "am force-stop " : "am kill ") + packageName;
        shellManager.runShellCommandForResult(cmd);
        Log.d(TAG, "stopApp: " + cmd);
    }


    private boolean isForceStopMode() {
        return prefs.getInt(KEY_AUTO_KILL_TYPE, 0) == 0;
    }


    private void launchComponent(String componentName, int type) {
        String cmd;
        switch (type) {
            case ON_ACTIVATE_ACTIVITY:
                cmd = "am start -n " + componentName;
                break;
            case ON_ACTIVATE_SERVICE:
                cmd = "am start-foreground-service -n " + componentName;
                break;
            case ON_ACTIVATE_RECEIVER:
                cmd = "am broadcast -n " + componentName;
                break;
            default:
                Log.w(TAG, "launchComponent: unknown type " + type);
                return;
        }
        ShellManager.ShellResult r = shellManager.runShellCommandForResult(cmd);
        if (r.succeeded()) {
            Log.d(TAG, "launchComponent: ok — " + cmd);
        } else {
            Log.w(TAG, "launchComponent: failed (exit=" + r.exitCode() + ") — " + cmd);
        }
    }


    public String getActivationLabel(Context context, ScheduleEntry entry) {
        if (entry.onActivateAction == ON_ACTIVATE_NOTHING || entry.componentName == null) {
            return context.getString(R.string.scheduler_action_none);
        }
        int dot = entry.componentName.lastIndexOf('.');
        String shortName = dot >= 0
                ? entry.componentName.substring(dot + 1)
                : entry.componentName;
        return context.getString(R.string.scheduler_action_launch_main, shortName);
    }


    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    private ScheduleEntry findActiveEntry(List<ScheduleEntry> list, String pkg,
                                          int hour, int minute) {
        for (ScheduleEntry e : list) {
            if (e.packageName.equals(pkg) && e.isActiveNow(hour, minute)) return e;
        }
        return null;
    }

    private ScheduleEntry findEntryForPackage(List<ScheduleEntry> list, String pkg) {
        for (ScheduleEntry e : list) {
            if (e.packageName.equals(pkg)) return e;
        }
        return null;
    }

    private void saveTempProtectedPackages(Set<String> packages) {
        prefs.edit().putStringSet(KEY_TEMP_PROTECTED, new HashSet<>(packages)).apply();
    }
}
