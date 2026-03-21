package com.northmendo.Appzuku;

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
    private boolean backgroundRestricted;

    // Initialize app model
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
        this.backgroundRestricted = false;
    }

    public boolean isBackgroundRestricted() {
        return backgroundRestricted;
    }

    public void setBackgroundRestricted(boolean backgroundRestricted) {
        this.backgroundRestricted = backgroundRestricted;
    }

    // Get and set app name
    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    // Get and set package name
    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    // Get and set app ram usage
    public String getAppRam() {
        return appRam;
    }

    public void setAppRam(String appRam) {
        this.appRam = appRam;
    }

    // Get and set app ram bytes (for sorting)
    public long getAppRamBytes() {
        return appRamBytes;
    }

    public void setAppRamBytes(long appRamBytes) {
        this.appRamBytes = appRamBytes;
    }

    // Get and set app icon
    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    // Get and set system app status
    public boolean isSystemApp() {
        return isSystemApp;
    }

    public void setSystemApp(boolean systemApp) {
        isSystemApp = systemApp;
    }

    // Get and set Persistent app status
    public boolean isPersistentApp() {
        return isPersistentApp;
    }

    public void setPersistentApp(boolean PersistentApp) {
        isPersistentApp = PersistentApp;
    }

    // Get and set selection state
    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    // Get and set protection state
    public boolean isProtected() {
        return isProtected;
    }

    public void setProtected(boolean aProtected) {
        isProtected = aProtected;
    }

    // Get and set whitelist state
    public boolean isWhitelisted() {
        return isWhitelisted;
    }

    public void setWhitelisted(boolean whitelisted) {
        isWhitelisted = whitelisted;
    }
}
