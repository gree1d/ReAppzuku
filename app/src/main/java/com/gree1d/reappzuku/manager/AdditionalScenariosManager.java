package com.gree1d.reappzuku.manager;

import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;

import static com.gree1d.reappzuku.core.PreferenceKeys.*;
import static com.gree1d.reappzuku.core.AppConstants.*;

import com.gree1d.reappzuku.service.HardwareEventReceiver;

public class AdditionalScenariosManager {

    private final Context context;
    private final SharedPreferences prefs;
    private final PresetManager presetManager;
    private HardwareEventReceiver hardwareEventReceiver;
    private boolean receiverRegistered = false;

    public AdditionalScenariosManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.presetManager = new PresetManager(this.context);

    }

    private boolean getPref(String key) {
        if (presetManager.getActivePresetNumber() != 0) {
            return prefs.getBoolean(PresetManager.KEY_BACKUP_PREFIX + key, false);
        }
        return prefs.getBoolean(key, false);
    }

    private void setPref(String key, boolean value) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(key, value);
        if (presetManager.getActivePresetNumber() != 0) {
            editor.putBoolean(PresetManager.KEY_BACKUP_PREFIX + key, value);
        }
        editor.apply();
    }

    public void updateHardwareReceiverState() {
        boolean headset = prefs.getBoolean(KEY_HW_TRIGGER_HEADSET, false);
        boolean usb = prefs.getBoolean(KEY_HW_TRIGGER_USB, false);
        boolean charger = prefs.getBoolean(KEY_HW_TRIGGER_CHARGER, false);
        boolean wifi = prefs.getBoolean(KEY_HW_TRIGGER_WIFI, false);
        boolean bluetooth = prefs.getBoolean(KEY_HW_TRIGGER_BLUETOOTH, false);
        boolean gps = prefs.getBoolean(KEY_HW_TRIGGER_GPS, false);
        boolean hotspot = prefs.getBoolean(KEY_HW_TRIGGER_HOTSPOT, false);

        boolean anyEnabled = headset || usb || charger || wifi || bluetooth || gps || hotspot;



        if (anyEnabled && !receiverRegistered) {
            registerReceiver(headset, usb, charger, wifi, bluetooth, gps, hotspot);
        } else if (!anyEnabled && receiverRegistered) {
            unregisterReceiver();
        } else if (anyEnabled && receiverRegistered) {

            unregisterReceiver();
            registerReceiver(headset, usb, charger, wifi, bluetooth, gps, hotspot);
        }
    }

    private void registerReceiver(boolean headset, boolean usb, boolean charger,
                                   boolean wifi, boolean bluetooth, boolean gps, boolean hotspot) {
        hardwareEventReceiver = new HardwareEventReceiver();
        IntentFilter filter = new IntentFilter();

        if (headset) filter.addAction(android.content.Intent.ACTION_HEADSET_PLUG);
        if (usb) filter.addAction("android.hardware.usb.action.USB_STATE");
        if (charger) {
            filter.addAction(android.content.Intent.ACTION_POWER_CONNECTED);
            filter.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
        }
        if (wifi) {
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        }
        if (bluetooth) filter.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
        if (gps) filter.addAction("android.location.PROVIDERS_CHANGED");
        if (hotspot) filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");

        ContextCompat.registerReceiver(
                context, hardwareEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        receiverRegistered = true;

    }

    private void unregisterReceiver() {
        if (hardwareEventReceiver != null) {
            try {
                context.unregisterReceiver(hardwareEventReceiver);

            } catch (IllegalArgumentException e) {

            }
            hardwareEventReceiver = null;
        }
        receiverRegistered = false;
    }

    public void stop() {

        unregisterReceiver();
    }

    public boolean isHeadsetTriggerEnabled() { return getPref(KEY_HW_TRIGGER_HEADSET); }
    public boolean isUsbTriggerEnabled() { return getPref(KEY_HW_TRIGGER_USB); }
    public boolean isChargerTriggerEnabled() { return getPref(KEY_HW_TRIGGER_CHARGER); }
    public boolean isWifiTriggerEnabled() { return getPref(KEY_HW_TRIGGER_WIFI); }
    public boolean isBluetoothTriggerEnabled() { return getPref(KEY_HW_TRIGGER_BLUETOOTH); }
    public boolean isGpsTriggerEnabled() { return getPref(KEY_HW_TRIGGER_GPS); }
    public boolean isHotspotTriggerEnabled() { return getPref(KEY_HW_TRIGGER_HOTSPOT); }

    public void setHeadsetTriggerEnabled(boolean enabled) { setPref(KEY_HW_TRIGGER_HEADSET, enabled); }
    public void setUsbTriggerEnabled(boolean enabled) { setPref(KEY_HW_TRIGGER_USB, enabled); }
    public void setChargerTriggerEnabled(boolean enabled) { setPref(KEY_HW_TRIGGER_CHARGER, enabled); }
    public void setWifiTriggerEnabled(boolean enabled) { setPref(KEY_HW_TRIGGER_WIFI, enabled); }
    public void setBluetoothTriggerEnabled(boolean enabled) { setPref(KEY_HW_TRIGGER_BLUETOOTH, enabled); }
    public void setGpsTriggerEnabled(boolean enabled) { setPref(KEY_HW_TRIGGER_GPS, enabled); }
    public void setHotspotTriggerEnabled(boolean enabled) { setPref(KEY_HW_TRIGGER_HOTSPOT, enabled); }

    public boolean isAppLaunchTriggerEnabled() { return getPref(KEY_APP_LAUNCH_TRIGGER_ENABLED); }
    public void setAppLaunchTriggerEnabled(boolean enabled) { setPref(KEY_APP_LAUNCH_TRIGGER_ENABLED, enabled); }

    public boolean isAppLaunchClearCacheEnabled() { return getPref(KEY_APP_LAUNCH_CLEAR_CACHE); }
    public void setAppLaunchClearCacheEnabled(boolean enabled) { setPref(KEY_APP_LAUNCH_CLEAR_CACHE, enabled); }

    public Set<String> getAppLaunchTriggerPackages() {
        String key = presetManager.getActivePresetNumber() != 0
                ? PresetManager.KEY_BACKUP_PREFIX + KEY_APP_LAUNCH_TRIGGER_PACKAGES
                : KEY_APP_LAUNCH_TRIGGER_PACKAGES;
        return new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
    }

    public void saveAppLaunchTriggerPackages(Set<String> packages) {

        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(KEY_APP_LAUNCH_TRIGGER_PACKAGES, new HashSet<>(packages));
        if (presetManager.getActivePresetNumber() != 0) {
            editor.putStringSet(PresetManager.KEY_BACKUP_PREFIX + KEY_APP_LAUNCH_TRIGGER_PACKAGES, new HashSet<>(packages));
        }
        editor.apply();
    }
}
