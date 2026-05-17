package com.gree1d.reappzuku;

import android.content.Context;
import android.graphics.drawable.Drawable;

public class AppModel {
    private String appName;
    private String packageName;
    private String appRam;
    private long appRamBytes;
    private Drawable appIcon;
    private boolean isSystemApp;
    private boolean isPersistentApp;
    private boolean selected;
    private boolean isProtected;
    private boolean isWhitelisted;
    private boolean backgroundRestrictionDesired;
    private boolean backgroundRestrictionActual;
    private boolean backgroundRestrictionActualKnown;
    private String cpuUsage = "";
    private float cpuUsageValue = -1f;
    private int pid = -1;

    public AppModel(String appName, String packageName, String appRam, long appRamBytes, Drawable appIcon,
            boolean isSystemApp, boolean isPersistentApp, boolean isProtected) {
        this.appName = appName;
        this.packageName = packageName;
        this.appRam = appRam;
        this.appRamBytes = appRamBytes;
        this.appIcon = appIcon;
        this.isSystemApp = isSystemApp;
        this.isPersistentApp = isPersistentApp;
        this.isProtected = isProtected;
        this.selected = false;
        this.isWhitelisted = false;
        this.backgroundRestrictionDesired = false;
        this.backgroundRestrictionActual = false;
        this.backgroundRestrictionActualKnown = false;
    }

    public boolean isBackgroundRestricted() {
        return backgroundRestrictionDesired;
    }

    public void setBackgroundRestricted(boolean backgroundRestricted) {
        this.backgroundRestrictionDesired = backgroundRestricted;
    }

    public boolean isBackgroundRestrictionDesired() {
        return backgroundRestrictionDesired;
    }

    public void setBackgroundRestrictionDesired(boolean backgroundRestrictionDesired) {
        this.backgroundRestrictionDesired = backgroundRestrictionDesired;
    }

    public boolean isBackgroundRestrictionActual() {
        return backgroundRestrictionActual;
    }

    public void setBackgroundRestrictionActual(boolean backgroundRestrictionActual) {
        this.backgroundRestrictionActual = backgroundRestrictionActual;
    }

    public boolean isBackgroundRestrictionActualKnown() {
        return backgroundRestrictionActualKnown;
    }

    public void setBackgroundRestrictionActualKnown(boolean backgroundRestrictionActualKnown) {
        this.backgroundRestrictionActualKnown = backgroundRestrictionActualKnown;
    }

    public boolean isBackgroundRestrictionOutOfSync() {
        return backgroundRestrictionActualKnown && backgroundRestrictionDesired != backgroundRestrictionActual;
    }

    public boolean isBackgroundRestrictionExternal() {
        return backgroundRestrictionActualKnown && !backgroundRestrictionDesired && backgroundRestrictionActual;
    }

    public boolean needsBackgroundRestrictionReapply() {
        return backgroundRestrictionActualKnown && backgroundRestrictionDesired && !backgroundRestrictionActual;
    }

    public String getBackgroundRestrictionStatusText(Context context) {
        if (!backgroundRestrictionActualKnown) {
            return backgroundRestrictionDesired ? context.getString(R.string.restriction_status_saved) : "";
        }
        if (isBackgroundRestrictionExternal()) {
            return context.getString(R.string.restriction_status_external);
        }
        if (needsBackgroundRestrictionReapply()) {
            return context.getString(R.string.restriction_status_not_applied);
        }
        return "";
    }

    public int getPid() {
        return pid;
    }

    public void setPid(int pid) {
        this.pid = pid;
    }

    public String getCpuUsage() {
        return cpuUsage;
    }

    public float getCpuUsageValue() {
        return cpuUsageValue;
    }

    public void setCpuUsage(String cpuUsage, float value) {
        this.cpuUsage = cpuUsage;
        this.cpuUsageValue = value;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppRam() {
        return appRam;
    }

    public void setAppRam(String appRam) {
        this.appRam = appRam;
    }

    public long getAppRamBytes() {
        return appRamBytes;
    }

    public void setAppRamBytes(long appRamBytes) {
        this.appRamBytes = appRamBytes;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    public boolean isSystemApp() {
        return isSystemApp;
    }

    public void setSystemApp(boolean systemApp) {
        isSystemApp = systemApp;
    }

    public boolean isPersistentApp() {
        return isPersistentApp;
    }

    public void setPersistentApp(boolean PersistentApp) {
        isPersistentApp = PersistentApp;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isProtected() {
        return isProtected;
    }

    public void setProtected(boolean aProtected) {
        isProtected = aProtected;
    }

    public boolean isWhitelisted() {
        return isWhitelisted;
    }

    public void setWhitelisted(boolean whitelisted) {
        isWhitelisted = whitelisted;
    }
}
