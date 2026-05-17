package com.gree1d.reappzuku;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.gree1d.reappzuku.PreferenceKeys.*;
import com.gree1d.reappzuku.SleepModeLogManager;

public class SleepModeManager {
    private static final String TAG = "SleepModeManager";

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final ShellManager shellManager;
    private final SharedPreferences sharedpreferences;
    private RestrictionsScheduler scheduler;

    public SleepModeManager(Context context, Handler handler, ExecutorService executor,
            ShellManager shellManager) {
        this.context = context;
        this.handler = handler;
        this.executor = executor;
        this.shellManager = shellManager;
        this.sharedpreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public void setScheduler(RestrictionsScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public Set<String> getSleepModeApps() {
        return new HashSet<>(sharedpreferences.getStringSet(KEY_SLEEP_MODE_APPS, new HashSet<>()));
    }

    public void saveSleepModeApps(Set<String> packages) {
        sharedpreferences.edit().putStringSet(KEY_SLEEP_MODE_APPS, new HashSet<>(packages)).apply();
    }

    public boolean isSleepModeEnabled() {
        return sharedpreferences.getBoolean(KEY_SLEEP_MODE_ENABLED, false);
    }

    public void setSleepModeEnabled(boolean enabled) {
        sharedpreferences.edit().putBoolean(KEY_SLEEP_MODE_ENABLED, enabled).apply();
    }

    public void loadSleepModeApps(Consumer<List<AppModel>> callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            Set<String> sleepModeApps = getSleepModeApps();
            List<AppModel> result = new ArrayList<>();
            for (ApplicationInfo appInfo : packages) {
                if (appInfo.packageName.equals(context.getPackageName())) continue;
                boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (isSystem) continue;
                AppModel model = new AppModel(
                        pm.getApplicationLabel(appInfo).toString(),
                        appInfo.packageName,
                        "-",
                        0,
                        pm.getApplicationIcon(appInfo),
                        false,
                        (appInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0,
                        ProtectedApps.isProtected(context, appInfo.packageName));
                model.setSelected(sleepModeApps.contains(appInfo.packageName));
                result.add(model);
            }
            Collections.sort(result, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
            handler.post(() -> callback.accept(result));
        });
    }

    public void freezeBackgroundRestrictedApps(Runnable onComplete) {
        Set<String> packages = getSleepModeApps();
        if (packages.isEmpty()) {
            if (onComplete != null) handler.post(onComplete);
            return;
        }
        executor.execute(() -> {
            for (String packageName : packages) {
                if (scheduler != null && scheduler.isProtected(packageName, RestrictionsScheduler.PROTECT_SLEEP_MODE)) continue;
                boolean ok = shellManager.freezePackage(packageName);
                SleepModeLogManager.logFreeze(context, packageName, ok);
            }
            if (onComplete != null) handler.post(onComplete);
        });
    }

    public void unfreezeBackgroundRestrictedApps(Runnable onComplete) {
        Set<String> packages = getSleepModeApps();
        if (packages.isEmpty()) {
            if (onComplete != null) handler.post(onComplete);
            return;
        }
        executor.execute(() -> {
            for (String packageName : packages) {
                boolean ok = shellManager.unfreezePackage(packageName);
                SleepModeLogManager.logUnfreeze(context, packageName, ok);
            }
            if (onComplete != null) handler.post(onComplete);
        });
    }
}
