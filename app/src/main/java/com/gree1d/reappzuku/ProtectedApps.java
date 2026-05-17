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

public final class ProtectedApps {

    private ProtectedApps() {
    }

    private static final Set<String> PROTECTED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.gree1d.reappzuku",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.systemui", 
            "com.android.settings",
            "com.android.phone", 
            "com.android.contacts", 
            "com.android.mms",
            "com.android.server.telecom",
            "com.android.bluetooth",
            "com.android.externalstorage",
            "com.google.android.providers.media.module",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.google.android.inputmethod.latin",
            "rikka.shizuku.common",
            "moe.shizuku.privileged.api",
            "com.android.shell",
            "com.android.keychain",
            "com.android.packageinstaller", 
            "com.android.permissioncontroller",
            "com.android.providers.settings", 
            "com.android.providers.telephony", 
            "com.android.nfc", 
            "com.android.networkstack", 
            "com.android.networkstack.tethering",
            "com.android.net.resolv", 
            "com.android.vpndialogs",

            "com.miui.securitycenter",
            "com.miui.home",
            "com.miui.miwallpaper",
            "com.android.camera",
            "com.miui.guardprovider",
            "com.miui.core", 
            "com.miui.powerkeeper",

            "com.samsung.android.lool",
            "com.samsung.android.sm.devicesecurity",
            "com.sec.android.app.launcher",
            "com.samsung.android.app.telephonyui",
            "com.samsung.android.server.telecom",   

            "com.coloros.safecenter", 
            "com.oppo.launcher",
            "com.coloros.assistantscreen",

            "com.iqoo.secure",
            "com.bbk.launcher2",

            "com.huawei.systemmanager",
            "com.huawei.android.launcher",
            "com.hihonor.systemmanager",
            
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu", 
            "com.rifsxd.ksunext",
            "me.bmax.apatch",
            "com.suki.suki",
            "com.suki.suki_ultra",
            "org.sukisu.manager"
            
    ));


    public static boolean isProtected(Context context, String packageName) {
        if (packageName == null) {
            return false;
        }

        if (PROTECTED_PACKAGES.contains(packageName)) {
            return true;
        }

        String currentKeyboard = getCurrentKeyboardPackage(context);
        if (packageName.equals(currentKeyboard)) {
            return true;
        }

        String currentLauncher = getCurrentLauncherPackage(context);
        if (packageName.equals(currentLauncher)) {
            return true;
        }

        return false;
    }

    public static boolean isWhitelisted(Context context, String packageName) {
        if (packageName == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        Set<String> whitelisted = prefs.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>());
        return whitelisted.contains(packageName);
    }

    public static String getCurrentKeyboardPackage(Context context) {
        String rawKeyboard = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (rawKeyboard != null && rawKeyboard.contains("/")) {
            return rawKeyboard.split("/")[0];
        }
        return rawKeyboard;
    }

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
