package com.gree1d.reappzuku;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.gree1d.reappzuku.PreferenceKeys.*;

/**
 * Centralized list of protected apps that should never be killed.
 * These are critical system apps that would cause device instability if
 * stopped.
 */
public final class ProtectedApps {

    private ProtectedApps() {
        // Prevent instantiation
    }

    /**
     * Hardcoded list of protected package names.
     * These apps are critical for device operation.
     */
    private static final Set<String> PROTECTED_PACKAGES = new HashSet<>(Arrays.asList(
            // --- Core & Development ---
            "com.gree1d.reappzuku", // Self
            "com.google.android.gms", // Google Play Services
            "com.google.android.gsf", // Google Services Framework
            "com.android.systemui", // System UI
            "com.android.settings", // Settings
            "com.android.phone", // Phone / Dialer
            "com.android.contacts", // Contacts
            "com.android.mms", // SMS Service
            "com.android.server.telecom", // Telecom Server
            "com.android.bluetooth", // Bluetooth
            "com.android.externalstorage", // External Storage
            "com.google.android.providers.media.module", // Media Module
            "com.google.android.packageinstaller", // Package Installer
            "com.google.android.permissioncontroller", // Permission Controller
            "com.google.android.inputmethod.latin", // Gboard
            "rikka.shizuku.common", // Shizuku
            "moe.shizuku.privileged.api", // Shizuku thedjchi fork
            "com.android.shell",                          // Service for ADB/Shizuku
            "com.android.keychain",                       // Keychain. Can break TLS/VPN/Wi-Fi
            "com.android.packageinstaller",               // AOSP Package Installer
            "com.android.permissioncontroller",           // AOSP Permission Controller
            "com.android.providers.settings",            // Android settings
            "com.android.providers.telephony",           // Provider SMS/MMS
            "com.android.nfc",                            // NFC
            "com.android.networkstack",                   // Network Stack
            "com.android.networkstack.tethering",        // Tethering stack
            "com.android.net.resolv",                     // DNS resolver
            "com.android.vpndialogs",                    // VPN dialogs

            // --- Xiaomi / Poco / Redmi (MIUI & HyperOS) ---
            "com.miui.securitycenter", // Security App
            "com.miui.home", // MIUI Launcher
            "com.miui.miwallpaper", // MIUI Wallpaper
            "com.android.camera", // Camera
            "com.miui.guardprovider", // Security Guard
            "com.miui.core",                              // MIUI Core Services
            "com.miui.powerkeeper",                     // MIUI PowerKeeper - very capricious app 

            // --- Samsung (One UI) ---
            "com.samsung.android.lool", // Device Care
            "com.samsung.android.sm.devicesecurity", // Device Security
            "com.sec.android.app.launcher", // One UI Home
            "com.samsung.android.app.telephonyui",       // Phone UI Samsung
            "com.samsung.android.server.telecom",   

            // --- Oppo / Realme / OnePlus (ColorOS & derivatives) ---
            "com.coloros.safecenter", // Phone Manager
            "com.oppo.launcher", // System Launcher
            "com.coloros.assistantscreen", // Smart Assistant

            // --- Vivo / iQOO (Funtouch OS / OriginOS) ---
            "com.iqoo.secure", // iManager
            "com.bbk.launcher2", // Vivo Launcher

            // --- Huawei / Honor (EMUI / MagicOS) ---
            "com.huawei.systemmanager", // Optimizer
            "com.huawei.android.launcher", // Huawei Home
            "com.hihonor.systemmanager", // Honor System Manager
            
            // --- Root Managers ---
            "com.topjohnwu.magisk", // Magisk
            "me.weishu.kernelsu", // KernelSU
            "com.rifsxd.ksunext", // KernelSU Next
            "me.bmax.apatch", // APatch
            "com.suki.suki", // SukiSU
            "com.suki.suki_ultra", // SukiSU Ultra
            "org.sukisu.manager" // SukiSU (Alternative/New builds)
            
    ));


    /**
     * Check if a package is protected from being killed.
     * This includes both system-protected apps and user-whitelisted apps.
     *
     * @param context     Application context
     * @param packageName Package name to check
     * @return true if the package should not be killed
     */
    public static boolean isProtected(Context context, String packageName) {
        if (packageName == null) {
            return false;
        }

        // Check hardcoded list
        if (PROTECTED_PACKAGES.contains(packageName)) {
            return true;
        }

        // Check if it's the current keyboard
        String currentKeyboard = getCurrentKeyboardPackage(context);
        if (packageName.equals(currentKeyboard)) {
            return true;
        }

        // Check if it's the current launcher
        String currentLauncher = getCurrentLauncherPackage(context);
        if (packageName.equals(currentLauncher)) {
            return true;
        }

        return false;
    }

    /**
     * Check if a package is whitelisted by the user (never kill).
     *
     * @param context     Application context
     * @param packageName Package name to check
     * @return true if the package is in the user's whitelist
     */
    public static boolean isWhitelisted(Context context, String packageName) {
        if (packageName == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        Set<String> whitelisted = prefs.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>());
        return whitelisted.contains(packageName);
    }

    /**
     * Get the current keyboard/input method package name.
     */
    public static String getCurrentKeyboardPackage(Context context) {
        String rawKeyboard = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (rawKeyboard != null && rawKeyboard.contains("/")) {
            return rawKeyboard.split("/")[0];
        }
        return rawKeyboard;
    }

    /**
     * Get the current launcher/home app package name.
     */
    public static String getCurrentLauncherPackage(Context context) {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = context.getPackageManager()
                .resolveActivity(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            return resolveInfo.activityInfo.packageName;
        }
        return null;
    }

}
