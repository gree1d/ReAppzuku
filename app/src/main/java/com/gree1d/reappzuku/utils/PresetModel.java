package com.gree1d.reappzuku.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.gree1d.reappzuku.core.AppConstants;
import com.gree1d.reappzuku.core.KillIntervalPolicy;
import com.gree1d.reappzuku.core.PackageNameValidator;
import com.gree1d.reappzuku.core.PresetInputPolicy;

import java.util.HashSet;
import java.util.Set;

/**
 * Legacy serialized model for an Automation Schedule slot.
 *
 * <p>The class and JSON field names are retained for import/backup compatibility. It is not a
 * per-app policy preset; those are represented by {@code com.gree1d.reappzuku.db.PolicyPreset}.</p>
 */
public class PresetModel {

    public static final int PRESET_1 = 1;
    public static final int PRESET_2 = 2;

    public int presetNumber;
    public String name;
    public boolean enabled = true;

    public boolean autoKillEnabled;
    public boolean periodicKillEnabled;
    public int killInterval;
    public boolean killOnScreenOff;
    public boolean ramThresholdEnabled;
    public int ramThreshold;
    public int autoKillType;
    public int killMode;

    public boolean hwTriggerHeadset;
    public boolean hwTriggerUsb;
    public boolean hwTriggerCharger;
    public boolean hwTriggerWifi;
    public boolean hwTriggerBluetooth;
    public boolean hwTriggerGps;
    public boolean hwTriggerHotspot;
    public boolean appLaunchTriggerEnabled;
    public boolean appLaunchClearCache;
    public Set<String> appLaunchTriggerPackages;

    public Set<String> whitelistedApps;
    public Set<String> blacklistedApps;

    public int startHour;
    public int startMinute;
    public int endHour;
    public int endMinute;

    public PresetModel(int presetNumber) {
        this.presetNumber = presetNumber;
        this.name = "Preset " + presetNumber;
        this.whitelistedApps = new HashSet<>();
        this.blacklistedApps = new HashSet<>();
        this.appLaunchTriggerPackages = new HashSet<>();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("name", name);
        obj.put("enabled", enabled);
        obj.put("autoKillEnabled", autoKillEnabled);
        obj.put("periodicKillEnabled", periodicKillEnabled);
        obj.put("killInterval", killInterval);
        obj.put("killOnScreenOff", killOnScreenOff);
        obj.put("ramThresholdEnabled", ramThresholdEnabled);
        obj.put("ramThreshold", ramThreshold);
        obj.put("autoKillType", autoKillType);
        obj.put("killMode", killMode);
        obj.put("hwTriggerHeadset", hwTriggerHeadset);
        obj.put("hwTriggerUsb", hwTriggerUsb);
        obj.put("hwTriggerCharger", hwTriggerCharger);
        obj.put("hwTriggerWifi", hwTriggerWifi);
        obj.put("hwTriggerBluetooth", hwTriggerBluetooth);
        obj.put("hwTriggerGps", hwTriggerGps);
        obj.put("hwTriggerHotspot", hwTriggerHotspot);
        obj.put("appLaunchTriggerEnabled", appLaunchTriggerEnabled);
        obj.put("appLaunchClearCache", appLaunchClearCache);
        JSONArray launchPackages = new JSONArray();
        for (String pkg : appLaunchTriggerPackages) launchPackages.put(pkg);
        obj.put("appLaunchTriggerPackages", launchPackages);
        obj.put("startHour", startHour);
        obj.put("startMinute", startMinute);
        obj.put("endHour", endHour);
        obj.put("endMinute", endMinute);

        JSONArray whitelist = new JSONArray();
        for (String pkg : whitelistedApps) whitelist.put(pkg);
        obj.put("whitelistedApps", whitelist);

        JSONArray blacklist = new JSONArray();
        for (String pkg : blacklistedApps) blacklist.put(pkg);
        obj.put("blacklistedApps", blacklist);

        return obj;
    }

    public static PresetModel fromJson(int presetNumber, JSONObject obj) throws JSONException {
        PresetModel model = new PresetModel(presetNumber);
        model.name = obj.optString("name", "Preset " + presetNumber);
        model.enabled = obj.optBoolean("enabled", true);
        model.autoKillEnabled = obj.optBoolean("autoKillEnabled", false);
        model.periodicKillEnabled = obj.optBoolean("periodicKillEnabled", false);
        model.killInterval = KillIntervalPolicy.clampServiceIntervalMs(
                obj.optInt("killInterval", AppConstants.DEFAULT_KILL_INTERVAL_MS));
        model.killOnScreenOff = obj.optBoolean("killOnScreenOff", false);
        model.ramThresholdEnabled = obj.optBoolean("ramThresholdEnabled", false);
        model.ramThreshold = obj.optInt("ramThreshold", 80);
        model.autoKillType = obj.optInt("autoKillType", 0);
        model.killMode = obj.optInt("killMode", AppConstants.DEFAULT_KILL_MODE);
        model.hwTriggerHeadset = obj.optBoolean("hwTriggerHeadset", false);
        model.hwTriggerUsb = obj.optBoolean("hwTriggerUsb", false);
        model.hwTriggerCharger = obj.optBoolean("hwTriggerCharger", false);
        model.hwTriggerWifi = obj.optBoolean("hwTriggerWifi", false);
        model.hwTriggerBluetooth = obj.optBoolean("hwTriggerBluetooth", false);
        model.hwTriggerGps = obj.optBoolean("hwTriggerGps", false);
        model.hwTriggerHotspot = obj.optBoolean("hwTriggerHotspot", false);
        model.appLaunchTriggerEnabled = obj.optBoolean("appLaunchTriggerEnabled", false);
        model.appLaunchClearCache = obj.optBoolean("appLaunchClearCache", false);
        model.startHour = obj.optInt("startHour", 8);
        model.startMinute = obj.optInt("startMinute", 0);
        model.endHour = obj.optInt("endHour", 20);
        model.endMinute = obj.optInt("endMinute", 0);

        try {
            PresetInputPolicy.requireClockTime("start", model.startHour, model.startMinute);
            PresetInputPolicy.requireClockTime("end", model.endHour, model.endMinute);
            addValidatedPackages(
                    model.appLaunchTriggerPackages,
                    obj.optJSONArray("appLaunchTriggerPackages"),
                    "appLaunchTriggerPackages");
            addValidatedPackages(
                    model.whitelistedApps,
                    obj.optJSONArray("whitelistedApps"),
                    "whitelistedApps");
            addValidatedPackages(
                    model.blacklistedApps,
                    obj.optJSONArray("blacklistedApps"),
                    "blacklistedApps");
        } catch (IllegalArgumentException e) {
            throw new JSONException("Invalid preset input: " + e.getMessage());
        }

        return model;
    }

    private static void addValidatedPackages(
            Set<String> target,
            JSONArray packages,
            String field) throws JSONException {
        if (packages == null) return;
        PresetInputPolicy.requirePackageCount(field, packages.length());
        for (int i = 0; i < packages.length(); i++) {
            target.add(PackageNameValidator.requireValid(packages.getString(i)));
        }
    }

    public int getStartTotalMinutes() {
        return startHour * 60 + startMinute;
    }

    public int getEndTotalMinutes() {
        return endHour * 60 + endMinute;
    }

    public boolean overlapsWithExcludingSelf(PresetModel other) {
        if (other == null) return false;
        int thisStart = getStartTotalMinutes();
        int thisEnd = getEndTotalMinutes();
        int otherStart = other.getStartTotalMinutes();
        int otherEnd = other.getEndTotalMinutes();

        if (thisEnd <= thisStart) {
            if (otherEnd <= otherStart) {
                return true;
            }
            return otherStart < thisEnd || otherStart >= thisStart || otherEnd > thisStart;
        }

        if (otherEnd <= otherStart) {
            return thisStart < otherEnd || thisStart >= otherStart || thisEnd > otherStart;
        }

        return thisStart < otherEnd && otherStart < thisEnd;
    }
}