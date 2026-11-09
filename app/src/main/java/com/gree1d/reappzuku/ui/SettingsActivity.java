package com.gree1d.reappzuku.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;

import com.gree1d.reappzuku.R;
import com.gree1d.reappzuku.databinding.ActivitySettingsBinding;
import com.gree1d.reappzuku.core.App;
import com.gree1d.reappzuku.core.BackupManager;
import com.gree1d.reappzuku.core.BackgroundWorkPolicy;
import com.gree1d.reappzuku.core.AppBehaviorUiMode;
import com.gree1d.reappzuku.manager.AdditionalScenariosManager;
import com.gree1d.reappzuku.manager.AutoKillManager;
import com.gree1d.reappzuku.manager.BackgroundAppManager;
import com.gree1d.reappzuku.manager.AutomationScheduleManager;
import com.gree1d.reappzuku.manager.RamKillShortcutManager;
import com.gree1d.reappzuku.manager.RestrictionsScheduler;
import com.gree1d.reappzuku.manager.SleepModeManager;
import com.gree1d.reappzuku.core.ShellManager;
import com.gree1d.reappzuku.service.AutoKillWorker;
import com.gree1d.reappzuku.service.ShappkyService;
import com.gree1d.reappzuku.manager.UpdateChecker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static com.gree1d.reappzuku.core.AppConstants.*;
import static com.gree1d.reappzuku.core.PreferenceKeys.*;

public class SettingsActivity extends SettingsActivityDialogs
        implements SharedPreferences.OnSharedPreferenceChangeListener {


    private ActivitySettingsBinding binding;
    private ShellManager shellManager;
    private BackgroundAppManager appManager;
    private AutoKillManager autoKillManager;
    private SleepModeManager sleepModeManager;
    private BackupManager backupManager;
    private RestrictionsScheduler scheduler;
    private AdditionalScenariosManager additionalScenariosManager;
    private RamKillShortcutManager ramKillShortcutManager;
    private Handler handler;
    private ExecutorService executor;
    private int easterEggClickCount = 0;
    private static final int EASTER_EGG_THRESHOLD = 5;
    private AutomationScheduleManager automationScheduleManager;

    private final android.widget.CompoundButton.OnCheckedChangeListener autoKillListener = (buttonView, isChecked) -> {
        if (isChecked && !hasPrivilege()) {
            buttonView.setChecked(false);
            Toast.makeText(this, getString(R.string.settings_requires_privilege), Toast.LENGTH_LONG).show();
            return;
        }
        putAutoKillPref(KEY_AUTO_KILL_ENABLED, isChecked);
        boolean periodicEnabled = binding.switchPeriodicKill.isChecked();
        updateAutomationOptionsVisibility(isChecked, periodicEnabled);
        applyServiceDependentState(isChecked);
        if (isChecked) {

            startAutomationService();
            AutoKillWorker.schedule(this, "Periodic Kill");
        } else {

            stopService(new Intent(this, ShappkyService.class));
            AutoKillWorker.cancel(this);
        }
    };

    private final android.widget.CompoundButton.OnCheckedChangeListener periodicKillListener = (buttonView, isChecked) -> {
        putAutoKillPref(KEY_PERIODIC_KILL_ENABLED, isChecked);
        boolean serviceEnabled = binding.switchAutoKill.isChecked();
        updateAutomationOptionsVisibility(serviceEnabled, isChecked);
    };

    private final android.widget.CompoundButton.OnCheckedChangeListener screenOffListener = (buttonView, isChecked) ->
            putAutoKillPref(KEY_KILL_ON_SCREEN_OFF, isChecked);

    private final android.widget.CompoundButton.OnCheckedChangeListener ramThresholdListener = (buttonView, isChecked) -> {
        putAutoKillPref(KEY_RAM_THRESHOLD_ENABLED, isChecked);
        updateRamThresholdLimitVisibility(isChecked && binding.switchAutoKill.isChecked());
    };

    private final ActivityResultLauncher<String> createBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> { if (uri != null) exportBackup(uri); });

    private final ActivityResultLauncher<String[]> restoreBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) importBackup(uri); });

    @Override protected ActivitySettingsBinding getBinding()                        { return binding; }
    @Override protected BackgroundAppManager getAppManager()                        { return appManager; }
    @Override protected AutoKillManager getAutoKillManager()                        { return autoKillManager; }
    @Override protected SleepModeManager getSleepModeManager()                      { return sleepModeManager; }
    @Override protected BackupManager getBackupManager()                            { return backupManager; }
    @Override protected RestrictionsScheduler getScheduler()                        { return scheduler; }
    @Override protected AdditionalScenariosManager getAdditionalScenariosManager()  { return additionalScenariosManager; }
    @Override protected ExecutorService getExecutor()                               { return executor; }
    @Override protected Handler getHandler()                                        { return handler; }
    @Override public    SharedPreferences getSharedPreferences()                    { return sharedPreferences; }
    @Override protected ActivityResultLauncher<String>   getCreateBackupLauncher()  { return createBackupLauncher; }
    @Override protected ActivityResultLauncher<String[]> getRestoreBackupLauncher() { return restoreBackupLauncher; }

    private boolean updatingAppBehaviorSwitches;
    private static final String STATE_ADVANCED_BEHAVIOR_EXPANDED = "advanced_app_behavior_expanded";
    private boolean advancedAppBehaviorExpanded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        App app = (App) getApplication();
        handler = app.getSharedHandler();
        executor = app.getSharedExecutor();
        shellManager = app.getShellManager();
        appManager = new BackgroundAppManager(this.getApplicationContext(), handler, executor, app.getShellExecutor(), shellManager);
        autoKillManager = new AutoKillManager(this.getApplicationContext(), handler, executor, shellManager, appManager.getCurrentAppsList());
        sleepModeManager = new SleepModeManager(this.getApplicationContext(), handler, executor, shellManager);
        backupManager = new BackupManager(this);
        scheduler = new RestrictionsScheduler(
                getApplicationContext(), handler, executor, shellManager, appManager, sleepModeManager);
        additionalScenariosManager = new AdditionalScenariosManager(this);
        ramKillShortcutManager = new RamKillShortcutManager(this, shellManager);
        automationScheduleManager = new AutomationScheduleManager(this);

        setupToolbar();
        loadSettings();
        setupListeners();
        setupBottomNavigation();
        advancedAppBehaviorExpanded = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ADVANCED_BEHAVIOR_EXPANDED, false);
        updateAdvancedAppBehaviorVisibility();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_ADVANCED_BEHAVIOR_EXPANDED, advancedAppBehaviorExpanded);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding == null) return;
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        boolean autoKill = getAutoKillPref(KEY_AUTO_KILL_ENABLED, false);
        boolean periodic = getAutoKillPref(KEY_PERIODIC_KILL_ENABLED, false);
        boolean screenOff = getAutoKillPref(KEY_KILL_ON_SCREEN_OFF, false);
        boolean ramEnabled = getAutoKillPref(KEY_RAM_THRESHOLD_ENABLED, false);
        binding.switchAutoKill.setChecked(autoKill);
        binding.switchPeriodicKill.setChecked(periodic);
        binding.switchKillScreenOff.setChecked(screenOff);
        binding.switchRamThreshold.setChecked(ramEnabled);
        updateAutomationOptionsVisibility(autoKill, periodic);
        applyServiceDependentState(autoKill);
        applyAutomationScheduleActiveState(isAutomationScheduleActive());
        updateRamThresholdLimitVisibility(ramEnabled && autoKill);
        updateAppBehaviorAvailability();
        updateShellModeText();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        binding = null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (binding == null) return;
        switch (key) {
            case KEY_AUTO_KILL_ENABLED: {
                boolean val = getAutoKillPref(KEY_AUTO_KILL_ENABLED, false);
                binding.switchAutoKill.setOnCheckedChangeListener(null);
                binding.switchAutoKill.setChecked(val);
                binding.switchAutoKill.setOnCheckedChangeListener(autoKillListener);
                updateAutomationOptionsVisibility(val, getAutoKillPref(KEY_PERIODIC_KILL_ENABLED, false));
                applyServiceDependentState(val);
                updateRamThresholdLimitVisibility(getAutoKillPref(KEY_RAM_THRESHOLD_ENABLED, false) && val);
                break;
            }
            case KEY_PERIODIC_KILL_ENABLED: {
                boolean val = getAutoKillPref(KEY_PERIODIC_KILL_ENABLED, false);
                binding.switchPeriodicKill.setOnCheckedChangeListener(null);
                binding.switchPeriodicKill.setChecked(val);
                binding.switchPeriodicKill.setOnCheckedChangeListener(periodicKillListener);
                updateAutomationOptionsVisibility(getAutoKillPref(KEY_AUTO_KILL_ENABLED, false), val);
                break;
            }
            case KEY_KILL_INTERVAL:
                updateKillIntervalText(getAutoKillIntPref(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS));
                break;
            case KEY_KILL_ON_SCREEN_OFF: {
                boolean val = getAutoKillPref(KEY_KILL_ON_SCREEN_OFF, false);
                binding.switchKillScreenOff.setOnCheckedChangeListener(null);
                binding.switchKillScreenOff.setChecked(val);
                binding.switchKillScreenOff.setOnCheckedChangeListener(screenOffListener);
                break;
            }
            case KEY_RAM_THRESHOLD_ENABLED: {
                boolean val = getAutoKillPref(KEY_RAM_THRESHOLD_ENABLED, false);
                binding.switchRamThreshold.setOnCheckedChangeListener(null);
                binding.switchRamThreshold.setChecked(val);
                binding.switchRamThreshold.setOnCheckedChangeListener(ramThresholdListener);
                updateRamThresholdLimitVisibility(val && getAutoKillPref(KEY_AUTO_KILL_ENABLED, false));
                break;
            }
            case KEY_RAM_THRESHOLD:
                updateRamThresholdText(getAutoKillIntPref(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT));
                break;
            case KEY_AUTO_KILL_TYPE:
                updateAutoKillTypeText(autoKillManager.getAutoKillType());
                break;
            case KEY_ACTIVE_PRESET:
                applyAutomationScheduleActiveState(isAutomationScheduleActive());
                break;
        }
        updateAppBehaviorAvailability();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        int accent   = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        int onColor  = sharedPreferences.getInt(KEY_ACCENT_ON_COLOR, ACCENT_ON_WHITE);

        if (accent == ACCENT_SYSTEM) {
            binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_navy));
            binding.toolbar.setTitleTextColor(Color.WHITE);
            return;
        }

        if (accent == ACCENT_CUSTOM) {
            int customColor = sharedPreferences.getInt(KEY_ACCENT_CUSTOM_COLOR, ACCENT_CUSTOM_DEFAULT_COLOR);
            binding.toolbar.setBackgroundColor(customColor);
            int textColor = (onColor == ACCENT_ON_BLACK) ? Color.BLACK : Color.WHITE;
            binding.toolbar.setTitleTextColor(textColor);
            if (binding.toolbar.getNavigationIcon() != null)
                androidx.core.graphics.drawable.DrawableCompat.setTint(
                        binding.toolbar.getNavigationIcon(), textColor);
            applyCustomAccentToSectionHeaders(customColor);
            applyCustomAccentToSwitches(customColor);
            return;
        }

        boolean isLightAccent = (accent == ACCENT_APRICOT || accent == ACCENT_SKY ||
                accent == ACCENT_PAPAYA || accent == ACCENT_LAVENDER ||
                accent == ACCENT_MINT   || accent == ACCENT_PEACH ||
                accent == ACCENT_POWDER || accent == ACCENT_FOG);
        binding.toolbar.setTitleTextColor(isLightAccent ? Color.BLACK : Color.WHITE);
    }

    private void applyCustomAccentToSectionHeaders(int color) {
        int[] titleIds = {
            R.id.section_title_information,
            R.id.section_title_appearance,
            R.id.section_title_behavior,
            R.id.section_title_stability,
            R.id.section_title_autokill,
            R.id.section_title_advanced,
            R.id.section_title_about
        };
        for (int id : titleIds) {
            TextView tv = findViewById(id);
            if (tv != null) tv.setTextColor(color);
        }
    }

    private void applyCustomAccentToSwitches(int color) {
        int trackColor = darkenColor(color, 0.6f);
        int[] switchIds = {
            R.id.switch_auto_kill,
            R.id.switch_periodic_kill,
            R.id.switch_kill_screen_off,
            R.id.switch_ram_threshold,
            R.id.switch_sleep_mode,
            R.id.switch_exit_on_back,
            R.id.switch_on_demand_mode,
            R.id.switch_prevent_shizuku_autostart
        };
        for (int id : switchIds) {
            MaterialSwitch sw = findViewById(id);
            if (sw == null) continue;
            android.content.res.ColorStateList thumbTint = new android.content.res.ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { color, 0xFFAAAAAA });
            android.content.res.ColorStateList trackTint = new android.content.res.ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { trackColor, 0xFF555555 });
            sw.setThumbTintList(thumbTint);
            sw.setTrackTintList(trackTint);
        }
    }

    @Override
    protected int darkenColor(int color, float factor) {
        int a = android.graphics.Color.alpha(color);
        int r = Math.round(android.graphics.Color.red(color)   * factor);
        int g = Math.round(android.graphics.Color.green(color) * factor);
        int b = Math.round(android.graphics.Color.blue(color)  * factor);
        return android.graphics.Color.argb(a,
                Math.min(r, 255), Math.min(g, 255), Math.min(b, 255));
    }

    private void setupBottomNavigation() {
        binding.bottomNavigation.navIconMain.setSelected(false);
        binding.bottomNavigation.navIconSettings.setSelected(true);
        binding.bottomNavigation.navIconStatistics.setSelected(false);
        binding.bottomNavigation.navBtnMain.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            finish();
        });
        binding.bottomNavigation.navBtnSettings.setOnClickListener(v -> {});
        binding.bottomNavigation.navBtnStatistics.setOnClickListener(v -> {
            startActivity(new Intent(this, StatisticsActivity.class));
            finish();
        });
        applyNavBarInsets(binding.bottomNavigation.getRoot());
    }

    @Override
    protected void loadSettings() {
        int theme = sharedPreferences.getInt(KEY_THEME,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        boolean isAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        updateThemeText(theme, isAmoled);

        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        updateAccentText(accent);
        updateAccentLayoutEnabled(theme);

        int onColor = sharedPreferences.getInt(KEY_ACCENT_ON_COLOR, ACCENT_ON_WHITE);
        updateOnColorText(onColor);
        updateOnColorLayoutVisibility(accent);

        int notificationMode = sharedPreferences.getInt(KEY_NOTIFICATION_MODE, NOTIFICATION_MODE_ALL);
        updateNotificationModeText(notificationMode);

        BackgroundWorkPolicy.enforceCompatibleBehavior(this);
        binding.switchPreventShizukuAutostart.setChecked(
                sharedPreferences.getBoolean(KEY_PREVENT_SHIZUKU_AUTOSTART, true));
        binding.switchExitOnBack.setChecked(sharedPreferences.getBoolean(KEY_EXIT_ON_BACK, false));
        updateAppBehaviorAvailability();

        boolean serviceEnabled = getAutoKillPref(KEY_AUTO_KILL_ENABLED, false);
        binding.switchAutoKill.setChecked(serviceEnabled);

        boolean periodicKillEnabled = getAutoKillPref(KEY_PERIODIC_KILL_ENABLED, false);
        binding.switchPeriodicKill.setChecked(periodicKillEnabled);

        int killInterval = getAutoKillIntPref(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        updateKillIntervalText(killInterval);

        binding.switchKillScreenOff.setChecked(getAutoKillPref(KEY_KILL_ON_SCREEN_OFF, false));

        boolean ramThresholdEnabled = getAutoKillPref(KEY_RAM_THRESHOLD_ENABLED, false);
        binding.switchRamThreshold.setChecked(ramThresholdEnabled);
        int ramThreshold = getAutoKillIntPref(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
        updateRamThresholdText(ramThreshold);
        updateRamThresholdLimitVisibility(ramThresholdEnabled && serviceEnabled);

        updateAutoKillTypeText(autoKillManager.getAutoKillType());
        updateAutomationOptionsVisibility(serviceEnabled, periodicKillEnabled);


        binding.switchSleepMode.setChecked(sleepModeManager.isSleepModeEnabled());
        long sleepDelay = sharedPreferences.getLong(KEY_SLEEP_MODE_DELAY, DEFAULT_SLEEP_MODE_DELAY_MS);
        updateSleepModeDelayText(sleepDelay);

        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            binding.textVersion.setText(getString(R.string.settings_version_label, versionName));
        } catch (Exception e) {

            binding.textVersion.setText(R.string.app_name);
        }

        loadAdditionalScenariosSettings();
        updateShellModeText();
    }

    private void setupListeners() {
        binding.layoutTheme.setOnClickListener(v -> showThemeDialog());

        binding.layoutAccent.setOnClickListener(v -> {
            int theme = sharedPreferences.getInt(KEY_THEME,
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            if (theme == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) return;
            showAccentDialog();
        });

        binding.layoutAccentOnColor.setOnClickListener(v -> showAccentOnColorDialog());
        binding.layoutNotificationMode.setOnClickListener(v -> showNotificationModeDialog());

        binding.switchOnDemandMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingAppBehaviorSwitches) return;
            if (isChecked && !BackgroundWorkPolicy.isOnDemandBehaviorAllowed(this)) {
                updatingAppBehaviorSwitches = true;
                buttonView.setChecked(false);
                updatingAppBehaviorSwitches = false;
                Toast.makeText(this, R.string.settings_app_behavior_blocked, Toast.LENGTH_LONG).show();
                return;
            }

            sharedPreferences.edit()
                    .putBoolean(KEY_PREVENT_SHIZUKU_AUTOSTART, isChecked)
                    .putBoolean(KEY_EXIT_ON_BACK, isChecked)
                    .apply();
            BackgroundWorkPolicy.syncShizukuWakeComponent(this);
            updateAppBehaviorAvailability();
        });
        binding.layoutOnDemandMode.setOnClickListener(v -> {
            if (binding.switchOnDemandMode.isEnabled()) {
                binding.switchOnDemandMode.toggle();
            }
        });
        binding.layoutAdvancedAppBehaviorToggle.setOnClickListener(v -> {
            advancedAppBehaviorExpanded = !advancedAppBehaviorExpanded;
            updateAdvancedAppBehaviorVisibility();
        });

        binding.switchPreventShizukuAutostart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingAppBehaviorSwitches) return;
            if (isChecked && !BackgroundWorkPolicy.isOnDemandBehaviorAllowed(this)) {
                buttonView.setChecked(false);
                Toast.makeText(this, R.string.settings_app_behavior_blocked, Toast.LENGTH_LONG).show();
                return;
            }
            sharedPreferences.edit().putBoolean(KEY_PREVENT_SHIZUKU_AUTOSTART, isChecked).apply();
        });
        binding.layoutPreventShizukuAutostart.setOnClickListener(v -> {
            if (binding.switchPreventShizukuAutostart.isEnabled()) {
                binding.switchPreventShizukuAutostart.toggle();
            }
        });

        binding.switchExitOnBack.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingAppBehaviorSwitches) return;
            if (isChecked && !BackgroundWorkPolicy.isOnDemandBehaviorAllowed(this)) {
                buttonView.setChecked(false);
                Toast.makeText(this, R.string.settings_app_behavior_blocked, Toast.LENGTH_LONG).show();
                return;
            }
            sharedPreferences.edit().putBoolean(KEY_EXIT_ON_BACK, isChecked).apply();
        });
        binding.layoutExitOnBack.setOnClickListener(v -> {
            if (binding.switchExitOnBack.isEnabled()) binding.switchExitOnBack.toggle();
        });

        binding.switchAutoKill.setOnCheckedChangeListener(autoKillListener);
        binding.switchPeriodicKill.setOnCheckedChangeListener(periodicKillListener);
        binding.switchKillScreenOff.setOnCheckedChangeListener(screenOffListener);
        binding.switchRamThreshold.setOnCheckedChangeListener(ramThresholdListener);
        binding.layoutRamThreshold.setOnClickListener(v -> showRamThresholdDialog());
        binding.layoutKillInterval.setOnClickListener(v -> showKillIntervalDialog());
        binding.layoutHiddenApps.setOnClickListener(v -> showHiddenAppsDialog());


        binding.layoutBackgroundRestriction.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutBackgroundRestriction.setOnClickListener(v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showBackgroundRestrictionDialog();
        });
        binding.layoutReapplyRestrictions.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutReapplyRestrictions.setOnClickListener(v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            Set<String> savedRestrictions = appManager.getBackgroundRestrictedApps();
            if (savedRestrictions.isEmpty()) {
                Toast.makeText(this, getString(R.string.settings_no_saved_restrictions), Toast.LENGTH_SHORT).show();
                return;
            }
            appManager.reapplySavedBackgroundRestrictions(null);
        });
        binding.layoutRestrictionsScheduler.setOnClickListener(v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showRestrictionsSchedulerDialog();
        });


        binding.switchSleepMode.setChecked(sleepModeManager.isSleepModeEnabled());
        binding.switchSleepMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !isServiceEnabled()) {
                buttonView.setChecked(false);
                showServiceRequiredToast();
                return;
            }
            sleepModeManager.setSleepModeEnabled(isChecked);
        });
        binding.layoutSleepModeApps.setOnClickListener(v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showSleepModeAppsDialog();
        });
        binding.layoutSleepModeDelay.setOnClickListener(v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showSleepModeDelayDialog();
        });

        binding.layoutAutoKillType.setOnClickListener(v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showAutoKillTypeDialog();
        });

        binding.layoutHelp.setOnClickListener(v -> openUrl(getString(R.string.url_help)));
        binding.layoutClearCache.setOnClickListener(v -> {
            binding.layoutClearCache.setEnabled(false);
            appManager.clearCaches(() -> binding.layoutClearCache.setEnabled(true));
        });
        binding.layoutBackupRestore.setOnClickListener(v -> showBackupRestoreDialog());
        binding.layoutGithub.setOnClickListener(v -> openUrl("https://github.com/gree1d/ReAppzuku"));
        binding.layoutCheckUpdates.setOnClickListener(v -> UpdateChecker.checkForUpdatesManual(this, sharedPreferences));
        binding.layoutTelegram.setOnClickListener(v -> openUrl("https://t.me/AkM0o"));
        binding.layoutSpecialThanks.setOnClickListener(v -> showSpecialThanksDialog());

        binding.textVersion.setOnClickListener(v -> {
            easterEggClickCount++;
            if (easterEggClickCount == EASTER_EGG_THRESHOLD) {
                easterEggClickCount = 0;
                showEasterEggDialog();
            }
        });

        applyServiceDependentState(isServiceEnabled());
        applyAutomationScheduleActiveState(isAutomationScheduleActive());
        setupAdditionalScenariosListeners();
    }


    @Override
    protected void updateAutomationOptionsVisibility(boolean serviceEnabled, boolean periodicEnabled) {
        float serviceAlpha  = serviceEnabled ? 1.0f : 0.5f;
        float periodicAlpha = (serviceEnabled && periodicEnabled) ? 1.0f : 0.5f;

        binding.layoutPeriodicKill.setAlpha(serviceAlpha);
        binding.switchPeriodicKill.setEnabled(serviceEnabled);
        binding.layoutScreenLock.setAlpha(serviceAlpha);
        binding.switchKillScreenOff.setEnabled(serviceEnabled);
        binding.layoutRamThresholdToggle.setAlpha(serviceAlpha);
        binding.switchRamThreshold.setEnabled(serviceEnabled);
        boolean ramEnabled = serviceEnabled && binding.switchRamThreshold.isChecked();
        updateRamThresholdLimitVisibility(ramEnabled);
        binding.layoutKillInterval.setAlpha(periodicAlpha);
        binding.layoutKillInterval.setClickable(serviceEnabled && periodicEnabled);
    }

    @Override
    protected void updateRamThresholdText(int threshold) {
        binding.textRamThreshold.setText(getString(R.string.settings_ram_threshold_summary, threshold));
    }

    @Override
    protected void updateRamThresholdLimitVisibility(boolean enabled) {
        binding.layoutRamThreshold.setAlpha(enabled ? 1.0f : 0.5f);
        binding.layoutRamThreshold.setClickable(enabled);
    }

    @Override
    protected void updateKillIntervalText(int intervalMs) {
        String[] labels = getResources().getStringArray(R.array.settings_kill_interval_labels);
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == intervalMs) { binding.textKillInterval.setText(labels[i]); return; }
        }
        binding.textKillInterval.setText(getString(R.string.settings_interval_fallback, intervalMs / 1000));
    }

    @Override
    protected void updateAutoKillTypeText(int type) {
        binding.textAutoKillType.setText(type == 1
                ? getString(R.string.settings_auto_kill_type_kill)
                : getString(R.string.settings_auto_kill_type_force_stop));
    }

    @Override
    protected void updateThemeText(int themeValue, boolean isAmoled) {
        if (isAmoled) { binding.textTheme.setText(getString(R.string.settings_theme_amoled_short)); return; }
        String[] labels = getResources().getStringArray(R.array.settings_theme_labels);
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == themeValue) { binding.textTheme.setText(labels[i]); return; }
        }
    }

    @Override
    protected void updateAccentText(int accentValue) {
        if (accentValue == ACCENT_CUSTOM) {
            int color = sharedPreferences.getInt(KEY_ACCENT_CUSTOM_COLOR, ACCENT_CUSTOM_DEFAULT_COLOR);
            binding.textAccent.setText(String.format("#%06X", 0xFFFFFF & color));
        } else {
            String[] labels = getResources().getStringArray(R.array.settings_accent_labels);
            if (accentValue >= 0 && accentValue < labels.length)
                binding.textAccent.setText(labels[accentValue]);
        }
    }

    @Override
    protected void updateOnColorText(int onColor) {
        binding.textAccentOnColor.setText(onColor == ACCENT_ON_BLACK
                ? R.string.settings_accent_on_color_black
                : R.string.settings_accent_on_color_white);
    }

    @Override
    protected void updateOnColorLayoutVisibility(int accent) {
        binding.layoutAccentOnColor.setVisibility(accent == ACCENT_CUSTOM ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void updateAccentLayoutEnabled(int themeValue) {
        boolean isSystem = (themeValue == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        binding.layoutAccent.setAlpha(isSystem ? 0.4f : 1.0f);
        binding.layoutAccent.setClickable(!isSystem);
    }

    @Override
    protected void updateNotificationModeText(int mode) {
        if (mode == NOTIFICATION_MODE_ALL) {
            binding.textNotificationMode.setText(R.string.settings_notification_mode_all);
            return;
        }
        boolean isRussian = getResources().getConfiguration().locale.getLanguage().equals("ru");
        String ramLabel = isRussian ? "ОЗУ" : "RAM";

        java.util.List<String> selected = new java.util.ArrayList<>();
        if ((mode & NOTIFICATION_MODE_IMPORTANT_ONLY) != 0) {
            selected.add(getString(R.string.settings_notification_mode_important_only));
        }
        if ((mode & NOTIFICATION_MODE_RAM_MONITOR) != 0) {
            selected.add(ramLabel);
        }
        if ((mode & NOTIFICATION_MODE_AUTO_KILL) != 0) {
            selected.add("Auto-Kill");
        }
        binding.textNotificationMode.setText(selected.isEmpty()
                ? getString(R.string.settings_notification_mode_all)
                : String.join(", ", selected));
    }

    private void updateAdvancedAppBehaviorVisibility() {
        binding.layoutAdvancedAppBehaviorOptions.setVisibility(
                advancedAppBehaviorExpanded ? View.VISIBLE : View.GONE);
        binding.textAdvancedAppBehaviorToggle.setText(advancedAppBehaviorExpanded
                ? R.string.settings_app_behavior_advanced_hide
                : R.string.settings_app_behavior_advanced_show);
        binding.textAdvancedAppBehaviorChevron.setText(advancedAppBehaviorExpanded ? "▴" : "▾");
        binding.layoutAdvancedAppBehaviorToggle.setContentDescription(getString(
                advancedAppBehaviorExpanded
                        ? R.string.settings_app_behavior_advanced_hide
                        : R.string.settings_app_behavior_advanced_show));
    }

    private void updateAppBehaviorAvailability() {
        boolean blocked = BackgroundWorkPolicy.enforceCompatibleBehavior(this);
        Set<BackgroundWorkPolicy.Blocker> blockers = BackgroundWorkPolicy.getActiveBlockers(this);
        boolean enabled = !blocked;
        float alpha = enabled ? 1.0f : 0.5f;
        boolean requestedPreventAutoStart =
                sharedPreferences.getBoolean(KEY_PREVENT_SHIZUKU_AUTOSTART, true);
        boolean requestedExitOnBack = sharedPreferences.getBoolean(KEY_EXIT_ON_BACK, false);
        AppBehaviorUiMode.Mode requestedMode = AppBehaviorUiMode.fromRequested(
                requestedPreventAutoStart, requestedExitOnBack);

        // Effective values are temporarily blocked, but saved preferences are untouched.
        boolean preventAutoStart = enabled && requestedPreventAutoStart;
        boolean exitOnBack = enabled && requestedExitOnBack;
        boolean onDemandMode = preventAutoStart && exitOnBack;

        updatingAppBehaviorSwitches = true;
        binding.switchOnDemandMode.setChecked(onDemandMode);
        binding.switchPreventShizukuAutostart.setChecked(preventAutoStart);
        binding.switchExitOnBack.setChecked(exitOnBack);
        updatingAppBehaviorSwitches = false;
        binding.switchOnDemandMode.setEnabled(enabled);
        binding.switchPreventShizukuAutostart.setEnabled(enabled);
        binding.switchExitOnBack.setEnabled(enabled);
        binding.layoutOnDemandMode.setEnabled(enabled);
        binding.layoutPreventShizukuAutostart.setEnabled(enabled);
        binding.layoutExitOnBack.setEnabled(enabled);
        binding.layoutOnDemandMode.setAlpha(alpha);
        binding.layoutPreventShizukuAutostart.setAlpha(alpha);
        binding.layoutExitOnBack.setAlpha(alpha);

        if (blocked) {
            int label = requestedMode == AppBehaviorUiMode.Mode.ON_DEMAND
                    ? R.string.settings_app_behavior_mode_on_demand
                    : requestedMode == AppBehaviorUiMode.Mode.STANDARD
                            ? R.string.settings_app_behavior_mode_standard
                            : R.string.settings_app_behavior_mode_custom;
            binding.textOnDemandModeStatus.setText(getString(
                    R.string.settings_app_behavior_paused_status, getString(label)));
            binding.textOnDemandModeStatus.setVisibility(View.VISIBLE);
        } else if (requestedMode == AppBehaviorUiMode.Mode.CUSTOM) {
            binding.textOnDemandModeStatus.setText(R.string.settings_app_behavior_custom_status);
            binding.textOnDemandModeStatus.setVisibility(View.VISIBLE);
        } else {
            binding.textOnDemandModeStatus.setVisibility(View.GONE);
        }

        if (blocked && !blockers.isEmpty()) {
            binding.textAppBehaviorBlocked.setText(getString(
                    R.string.settings_app_behavior_blocked_by,
                    formatAppBehaviorBlockers(blockers)));
            binding.textAppBehaviorBlocked.setVisibility(View.VISIBLE);
        } else {
            binding.textAppBehaviorBlocked.setVisibility(View.GONE);
        }
    }

    private String formatAppBehaviorBlockers(Set<BackgroundWorkPolicy.Blocker> blockers) {
        List<String> labels = new ArrayList<>();
        for (BackgroundWorkPolicy.Blocker blocker : blockers) {
            switch (blocker) {
                case AUTO_KILL:
                    labels.add(getString(R.string.settings_section_kill_rules));
                    break;
                case SLEEP_MODE:
                    labels.add(getString(R.string.settings_sleep_mode_title));
                    break;
                case ACTIVE_PRESET:
                    labels.add(getString(R.string.automation_schedules_title));
                    break;
                case RESTRICTIONS_SCHEDULE:
                    labels.add(getString(R.string.settings_restrictions_scheduler_title));
                    break;
            }
        }
        StringBuilder joined = new StringBuilder();
        for (String label : labels) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(label);
        }
        return joined.toString();
    }

    @Override
    protected void updateSleepModeDelayText(long delayMs) {
        String[] labels = getResources().getStringArray(R.array.settings_sleep_mode_delay_labels);
        for (int i = 0; i < SLEEP_MODE_DELAYS_MS.length; i++) {
            if (SLEEP_MODE_DELAYS_MS[i] == delayMs) { binding.textSleepModeDelay.setText(labels[i]); return; }
        }
        binding.textSleepModeDelay.setText(getString(R.string.settings_sleep_mode_delay_fallback, delayMs / 60000));
    }

    @Override
    protected void updateAdditionalScenariosSummary() {
        List<String> active = new ArrayList<>();
        if (additionalScenariosManager.isHeadsetTriggerEnabled())
            active.add(getString(R.string.scenarios_hw_headset_short));
        if (additionalScenariosManager.isUsbTriggerEnabled())
            active.add(getString(R.string.scenarios_hw_usb_short));
        if (additionalScenariosManager.isChargerTriggerEnabled())
            active.add(getString(R.string.scenarios_hw_charger_short));
        if (additionalScenariosManager.isWifiTriggerEnabled())
            active.add(getString(R.string.scenarios_hw_wifi_short));
        if (additionalScenariosManager.isBluetoothTriggerEnabled())
            active.add(getString(R.string.scenarios_hw_bluetooth_short));
        if (additionalScenariosManager.isGpsTriggerEnabled())
            active.add(getString(R.string.scenarios_hw_gps_short));
        if (additionalScenariosManager.isHotspotTriggerEnabled())
            active.add(getString(R.string.scenarios_hw_hotspot_short));
        if (additionalScenariosManager.isAppLaunchTriggerEnabled())
            active.add(getString(R.string.scenarios_app_launch_short));

        binding.textAdditionalScenariosSummary.setText(active.isEmpty()
                ? getString(R.string.scenarios_summary_none)
                : android.text.TextUtils.join(", ", active));
    }

    private void applyServiceDependentState(boolean serviceEnabled) {
        float alpha = serviceEnabled ? 1.0f : 0.5f;

        binding.layoutAutoKillType.setAlpha(alpha);

        if (appManager.supportsBackgroundRestriction()) {
            binding.layoutBackgroundRestriction.setAlpha(alpha);
            binding.layoutReapplyRestrictions.setAlpha(alpha);
            binding.layoutRestrictionsScheduler.setAlpha(alpha);
        }

        binding.switchSleepMode.setEnabled(serviceEnabled);
        binding.layoutSleepModeApps.setAlpha(alpha);
        binding.layoutSleepModeDelay.setAlpha(alpha);
    }

    private void applyAutomationScheduleActiveState(boolean presetActive) {
        float alpha = presetActive ? 0.5f : 1.0f;
        View.OnClickListener presetBlocker = presetActive ? v -> showAutomationScheduleActiveDialog() : null;

        binding.switchPeriodicKill.setEnabled(!presetActive);
        binding.switchKillScreenOff.setEnabled(!presetActive);
        binding.switchRamThreshold.setEnabled(!presetActive);

        binding.layoutPeriodicKill.setAlpha(alpha);
        binding.layoutPeriodicKill.setOnClickListener(presetActive ? v -> showAutomationScheduleActiveDialog() : null);
        binding.layoutScreenLock.setAlpha(alpha);
        binding.layoutScreenLock.setOnClickListener(presetBlocker);
        binding.layoutRamThresholdToggle.setAlpha(alpha);
        binding.layoutRamThresholdToggle.setOnClickListener(presetBlocker);
        binding.layoutRamThreshold.setAlpha(alpha);
        binding.layoutRamThreshold.setOnClickListener(presetActive ? v -> showAutomationScheduleActiveDialog() : v -> showRamThresholdDialog());
        binding.layoutKillInterval.setAlpha(alpha);
        binding.layoutKillInterval.setOnClickListener(presetActive ? v -> showAutomationScheduleActiveDialog() : v -> showKillIntervalDialog());
        binding.layoutAutoKillType.setAlpha(alpha);
        binding.layoutAutoKillType.setOnClickListener(presetActive ? v -> showAutomationScheduleActiveDialog() : v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showAutoKillTypeDialog();
        });
        binding.layoutAdditionalScenarios.setAlpha(alpha);
        binding.layoutAdditionalScenarios.setOnClickListener(presetActive ? v -> showAutomationScheduleActiveDialog() : v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showAdditionalScenariosDialog();
        });
    }

    private boolean hasPrivilege() {
        return shellManager.hasShizukuPermission() || shellManager.resolveAnyShellPermission();
    }

    @Override
    protected boolean isAutomationScheduleActive() {
        return automationScheduleManager != null && automationScheduleManager.getActiveScheduleNumber() != 0;
    }

    @Override
    protected boolean isServiceEnabled() {
        return getAutoKillPref(KEY_AUTO_KILL_ENABLED, false);
    }

    @Override
    protected boolean getAutoKillPref(String key, boolean defVal) {
        if (isAutomationScheduleActive()) {
            return sharedPreferences.getBoolean(AutomationScheduleManager.LEGACY_BACKUP_PREFIX + key, defVal);
        }
        return sharedPreferences.getBoolean(key, defVal);
    }

    protected void putAutoKillPref(String key, boolean value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(key, value);
        if (isAutomationScheduleActive()) editor.putBoolean(AutomationScheduleManager.LEGACY_BACKUP_PREFIX + key, value);
        editor.apply();
    }

    @Override
    protected int getAutoKillIntPref(String key, int defVal) {
        if (isAutomationScheduleActive()) {
            return sharedPreferences.getInt(AutomationScheduleManager.LEGACY_BACKUP_PREFIX + key, defVal);
        }
        return sharedPreferences.getInt(key, defVal);
    }

    @Override
    protected void putAutoKillIntPref(String key, int value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(key, value);
        if (isAutomationScheduleActive()) editor.putInt(AutomationScheduleManager.LEGACY_BACKUP_PREFIX + key, value);
        editor.apply();
    }

    private void showServiceRequiredToast() {
        Toast.makeText(this, getString(R.string.settings_requires_service_enabled), Toast.LENGTH_SHORT).show();
    }

    private void updateShellModeText() {
        executor.execute(() -> {
            final boolean privileged = shellManager.hasShizukuPermission() || shellManager.resolveAnyShellPermission();
            final String text;
            if (shellManager.hasShizukuPermission()) {
                text = getString(R.string.settings_shell_shizuku_ok);
            } else if (shellManager.resolveAnyShellPermission()) {
                text = getString(R.string.settings_shell_root_ok);
            } else {
                text = getString(R.string.settings_shell_no_access);
            }
            handler.post(() -> {
                if (binding == null) return;
                binding.textShellMode.setText(text);
                if (!privileged) {
                    binding.switchAutoKill.setEnabled(false);
                    binding.switchAutoKill.setAlpha(0.5f);
                    if (sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false)) {
                        sharedPreferences.edit()
                                .putBoolean(KEY_AUTO_KILL_ENABLED, false)
                                .putBoolean(AutomationScheduleManager.LEGACY_BACKUP_PREFIX + KEY_AUTO_KILL_ENABLED, false)
                                .apply();
                        binding.switchAutoKill.setChecked(false);
                        stopService(new Intent(SettingsActivity.this, ShappkyService.class));                        AutoKillWorker.cancel(SettingsActivity.this);
                    }                    applyServiceDependentState(false);
                } else {
                    binding.switchAutoKill.setEnabled(true);
                    binding.switchAutoKill.setAlpha(1.0f);
                }
            });
        });
    }

    private void startAutomationService() {
        Intent serviceIntent = new Intent(this, ShappkyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void applyAutomationStateFromPreferences() {
        boolean automationEnabled = sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false);
        if (automationEnabled) {
            int killMode = sharedPreferences.getInt(KEY_KILL_MODE, DEFAULT_KILL_MODE);
            Set<String> whitelistedApps = sharedPreferences.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>());
            if (killMode == 0 && whitelistedApps.isEmpty()) {
                sharedPreferences.edit()
                        .putBoolean(KEY_AUTO_KILL_ENABLED, false)
                        .putBoolean(AutomationScheduleManager.LEGACY_BACKUP_PREFIX + KEY_AUTO_KILL_ENABLED, false)
                        .apply();
                if (binding.switchAutoKill != null) binding.switchAutoKill.setChecked(false);
                resetDialogButtonColors(new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dialog_unsafe_whitelist_title)
                        .setMessage(R.string.dialog_unsafe_whitelist_message)
                        .setPositiveButton(R.string.dialog_unsafe_whitelist_ok, (dialog, which) -> dialog.dismiss())
                        .setCancelable(false)
                        .show());
                stopService(new Intent(this, ShappkyService.class));
                AutoKillWorker.cancel(this);
                return;
            }
            startAutomationService();
            AutoKillWorker.schedule(this, "Periodic Kill");
        } else {
            stopService(new Intent(this, ShappkyService.class));
            AutoKillWorker.cancel(this);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {

            Toast.makeText(this, R.string.url_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadAdditionalScenariosSettings() {
        updateAdditionalScenariosSummary();
    }

    private void setupAdditionalScenariosListeners() {
        binding.layoutAdditionalScenarios.setOnClickListener(v -> {
            if (!isServiceEnabled()) { showServiceRequiredToast(); return; }
            showAdditionalScenariosDialog();
        });
        binding.layoutPresets.setOnClickListener(v -> showAutomationSchedulePickerDialog());
        binding.layoutNewAppSetup.setOnClickListener(v ->
                startActivity(new Intent(this, NewAppSetupSettingsActivity.class)));
        binding.layoutAddShortcut.setOnClickListener(v -> ramKillShortcutManager.requestPinShortcut());
    }
}