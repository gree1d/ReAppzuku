package com.gree1d.reappzuku.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;

import com.gree1d.reappzuku.core.App;
import com.gree1d.reappzuku.core.ShellManager;
import com.gree1d.reappzuku.manager.BackgroundAppManager;
import com.gree1d.reappzuku.manager.AutoKillManager;
import com.gree1d.reappzuku.db.AppPolicy;
import com.gree1d.reappzuku.manager.PresetManager;

import static com.gree1d.reappzuku.core.AppConstants.*;
import static com.gree1d.reappzuku.core.PreferenceKeys.*;

public class HardwareEventReceiver extends BroadcastReceiver {

    private static final long TRIGGER_DELAY_MS = 10_000L;

    private static final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private static Runnable pendingKill = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();


        boolean relevant = false;
        String eventDescription = "";

        switch (action) {
            case Intent.ACTION_HEADSET_PLUG:
                int headsetState = intent.getIntExtra("state", -1);
                eventDescription = "Headset " + (headsetState == 1 ? "connected" : "disconnected");
                relevant = true;
                break;
            case "android.hardware.usb.action.USB_STATE":
                boolean usbConnected = intent.getBooleanExtra("connected", false);
                eventDescription = "USB " + (usbConnected ? "connected" : "disconnected");
                relevant = true;
                break;
            case Intent.ACTION_POWER_CONNECTED:
                eventDescription = "Charger connected";
                relevant = true;
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                eventDescription = "Charger disconnected";
                relevant = true;
                break;
            case WifiManager.WIFI_STATE_CHANGED_ACTION:
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                String wifiStateStr;
                switch (wifiState) {
                    case WifiManager.WIFI_STATE_ENABLED:   wifiStateStr = "enabled";            break;
                    case WifiManager.WIFI_STATE_DISABLED:  wifiStateStr = "disabled";           break;
                    case WifiManager.WIFI_STATE_ENABLING:  wifiStateStr = "enabling";           break;
                    case WifiManager.WIFI_STATE_DISABLING: wifiStateStr = "disabling";          break;
                    default:                               wifiStateStr = "unknown(" + wifiState + ")"; break;
                }
                eventDescription = "WiFi state: " + wifiStateStr;
                relevant = wifiState == WifiManager.WIFI_STATE_ENABLED || wifiState == WifiManager.WIFI_STATE_DISABLED;
                break;
            case "android.bluetooth.adapter.action.STATE_CHANGED":
                int btState = intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1);
                String btStateStr;
                switch (btState) {
                    case 12: btStateStr = "on";           break;
                    case 10: btStateStr = "off";          break;
                    case 11: btStateStr = "turning on";   break;
                    case 13: btStateStr = "turning off";  break;
                    default: btStateStr = "unknown(" + btState + ")"; break;
                }
                eventDescription = "Bluetooth state: " + btStateStr;
                relevant = btState == 12 || btState == 10;
                break;
            case "android.location.PROVIDERS_CHANGED":
                eventDescription = "GPS/Location providers changed";
                relevant = true;
                break;
            case "android.net.wifi.WIFI_AP_STATE_CHANGED":
                int apState = intent.getIntExtra("wifi_state", -1);
                String apStateStr;
                switch (apState) {
                    case 13: apStateStr = "enabled";   break;
                    case 11: apStateStr = "disabled";  break;
                    case 12: apStateStr = "enabling";  break;
                    case 10: apStateStr = "disabling"; break;
                    default: apStateStr = "unknown(" + apState + ")"; break;
                }
                eventDescription = "Hotspot state: " + apStateStr;
                relevant = apState == 13 || apState == 11;
                break;
        }

        if (!relevant) {

            return;
        }



        if (pendingKill != null) {
            debounceHandler.removeCallbacks(pendingKill);

        }

        final String finalDescription = eventDescription;
        final Context appContext = context.getApplicationContext();

        pendingKill = () -> {
            pendingKill = null;

            App app = (App) appContext;
            ExecutorService executor = app.getSharedExecutor();
            ShellManager shellManager = app.getShellManager();
            BackgroundAppManager appManager = new BackgroundAppManager(appContext, debounceHandler, executor, app.getShellExecutor(), shellManager);
            AutoKillManager autoKillManager = new AutoKillManager(appContext, debounceHandler, executor, shellManager,
                    appManager.getCurrentAppsList());

            autoKillManager.performAutoKill(() -> {

            }, resolveKillSource(appContext, "Hardware event: " + finalDescription),
                    AppPolicy.TRIGGER_HARDWARE_EVENT);
        };


        debounceHandler.postDelayed(pendingKill, TRIGGER_DELAY_MS);
    }

    private static String resolveKillSource(Context context, String defaultSource) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        int activePreset = prefs.getInt(KEY_ACTIVE_PRESET, 0);
        if (activePreset != 0) {
            PresetManager pm = new PresetManager(context);
            return defaultSource + " · " + pm.getPresetName(activePreset);
        }
        return defaultSource;
    }
}
