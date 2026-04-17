package com.gree1d.reappzuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Handler;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.widget.Toast;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.gree1d.reappzuku.databinding.ActivitySettingsBinding;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;

import static com.gree1d.reappzuku.PreferenceKeys.*;
import static com.gree1d.reappzuku.AppConstants.*;

public class SettingsActivity extends BaseActivity {
    private static final String TAG = "SettingsActivity";

    private ActivitySettingsBinding binding;
    private ShellManager shellManager;
    private BackgroundAppManager appManager;
    private BackupManager backupManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final ActivityResultLauncher<String> createBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> { if (uri != null) exportBackup(uri); });

    private final ActivityResultLauncher<String[]> restoreBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) importBackup(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        shellManager = new ShellManager(this.getApplicationContext(), handler, executor);
        appManager = new BackgroundAppManager(this.getApplicationContext(), handler, executor, shellManager);
        backupManager = new BackupManager(this);

        setupToolbar();
        loadSettings();
        setupListeners();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        if (accent == ACCENT_SYSTEM) {
            binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_navy));
        }
    }

    private void loadSettings() {
        int theme = sharedPreferences.getInt(KEY_THEME,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        boolean isAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        updateThemeText(theme, isAmoled);

        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        updateAccentText(accent);
        updateAccentLayoutEnabled(theme);

        int notificationMode = sharedPreferences.getInt(KEY_NOTIFICATION_MODE, NOTIFICATION_MODE_ALL);
        updateNotificationModeText(notificationMode);

        boolean serviceEnabled = sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false);
        binding.switchAutoKill.setChecked(serviceEnabled);

        boolean periodicKillEnabled = sharedPreferences.getBoolean(KEY_PERIODIC_KILL_ENABLED, false);
        binding.switchPeriodicKill.setChecked(periodicKillEnabled);

        int killInterval = sharedPreferences.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        updateKillIntervalText(killInterval);

        binding.switchKillScreenOff.setChecked(sharedPreferences.getBoolean(KEY_KILL_ON_SCREEN_OFF, false));

        boolean ramThresholdEnabled = sharedPreferences.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);
        binding.switchRamThreshold.setChecked(ramThresholdEnabled);
        int ramThreshold = sharedPreferences.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
        updateRamThresholdText(ramThreshold);

        updateAutoKillTypeText(appManager.getAutoKillType());
        updateAutomationOptionsVisibility(serviceEnabled, periodicKillEnabled);

        binding.switchSleepMode.setChecked(appManager.isSleepModeEnabled());
        long sleepDelay = sharedPreferences.getLong(KEY_SLEEP_MODE_DELAY, DEFAULT_SLEEP_MODE_DELAY_MS);
        updateSleepModeDelayText(sleepDelay);

        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            binding.textVersion.setText(getString(R.string.settings_version_label, versionName));
        } catch (Exception e) {
            binding.textVersion.setText(R.string.app_name);
        }
    }

    private void setupListeners() {
        binding.layoutTheme.setOnClickListener(v -> showThemeDialog());

        binding.layoutAccent.setOnClickListener(v -> {
            int theme = sharedPreferences.getInt(KEY_THEME,
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            if (theme == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) return;
            showAccentDialog();
        });

        binding.layoutNotificationMode.setOnClickListener(v -> showNotificationModeDialog());

        binding.switchAutoKill.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_AUTO_KILL_ENABLED, isChecked).apply();
            boolean periodicEnabled = binding.switchPeriodicKill.isChecked();
            updateAutomationOptionsVisibility(isChecked, periodicEnabled);
            if (isChecked) {
                startAutomationService();
                AutoKillWorker.schedule(this);
            } else {
                stopService(new Intent(this, ShappkyService.class));
                AutoKillWorker.cancel(this);
            }
        });

        binding.switchPeriodicKill.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_PERIODIC_KILL_ENABLED, isChecked).apply();
            boolean serviceEnabled = binding.switchAutoKill.isChecked();
            updateAutomationOptionsVisibility(serviceEnabled, isChecked);
        });

        binding.switchKillScreenOff.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean(KEY_KILL_ON_SCREEN_OFF, isChecked).apply());

        binding.switchRamThreshold.setOnCheckedChangeListener((buttonView, isChecked) ->
                sharedPreferences.edit().putBoolean(KEY_RAM_THRESHOLD_ENABLED, isChecked).apply());
        binding.layoutRamThresholdToggle.setOnClickListener(v -> {
            if (binding.switchRamThreshold.isChecked()) showRamThresholdDialog();
        });

        binding.layoutKillInterval.setOnClickListener(v -> showKillIntervalDialog());
        binding.layoutWhitelist.setOnClickListener(v -> showWhitelistDialog());
        binding.layoutHiddenApps.setOnClickListener(v -> showHiddenAppsDialog());

        binding.layoutBackgroundRestriction.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutBackgroundRestriction.setOnClickListener(v -> showBackgroundRestrictionDialog());
        binding.layoutReapplyRestrictions.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutReapplyRestrictions.setOnClickListener(v -> {
            Set<String> savedRestrictions = appManager.getBackgroundRestrictedApps();
            if (savedRestrictions.isEmpty()) {
                Toast.makeText(this, getString(R.string.settings_no_saved_restrictions), Toast.LENGTH_SHORT).show();
                return;
            }
            appManager.reapplySavedBackgroundRestrictions(null);
        });

        binding.switchSleepMode.setChecked(appManager.isSleepModeEnabled());
        binding.switchSleepMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.settings_sleep_mode_title))
                        .setMessage(getString(R.string.settings_sleep_mode_restart_message))
                        .setPositiveButton(getString(R.string.dialog_ok), (dialog, which) -> {
                            appManager.setSleepModeEnabled(true);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                    () -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
                        })
                        .setNegativeButton(getString(R.string.dialog_cancel), (dialog, which) -> buttonView.setChecked(false))
                        .setCancelable(false)
                        .show();
            } else {
                appManager.setSleepModeEnabled(false);
            }
        });
        binding.layoutSleepModeApps.setOnClickListener(v -> showSleepModeAppsDialog());
        binding.layoutSleepModeDelay.setOnClickListener(v -> showSleepModeDelayDialog());

        binding.layoutKillMode.setOnClickListener(v -> showKillModeDialog());
        binding.layoutBlacklist.setOnClickListener(v -> showBlacklistDialog());
        binding.layoutAutoKillType.setOnClickListener(v -> showAutoKillTypeDialog());

        binding.layoutHelp.setOnClickListener(v -> openUrl(getString(R.string.url_help)));

        updateShellModeText();

        binding.layoutClearCache.setOnClickListener(v -> {
            binding.layoutClearCache.setEnabled(false);
            appManager.clearCaches(() -> binding.layoutClearCache.setEnabled(true));
        });

        binding.layoutBackupRestore.setOnClickListener(v -> showBackupRestoreDialog());
        binding.layoutGithub.setOnClickListener(v -> openUrl("https://github.com/gree1d/ReAppzuku"));
        binding.layoutCheckUpdates.setOnClickListener(v -> openUrl("https://github.com/gree1d/ReAppzuku/releases"));
        binding.layoutTelegram.setOnClickListener(v -> openUrl("https://t.me/AkM0o"));

        updateKillModeVisibility();
    }

    private void updateKillModeVisibility() {
        int mode = appManager.getKillMode();
        binding.textKillMode.setText(mode == 0 ? R.string.settings_mode_whitelist : R.string.settings_mode_blacklist);
        binding.layoutBlacklist.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        binding.layoutWhitelist.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
    }

    private void updateShellModeText() {
        executor.execute(() -> {
            final String text;
            if (shellManager.hasShizukuPermission()) {
                text = getString(R.string.settings_shell_shizuku_ok);
            } else if (shellManager.resolveAnyShellPermission()) {
                text = getString(R.string.settings_shell_root_ok);
            } else {
                text = getString(R.string.settings_shell_no_access);
            }
            handler.post(() -> binding.textShellMode.setText(text));
        });
    }

    private void showAutoKillTypeDialog() {
        String[] types = {
                getString(R.string.settings_auto_kill_type_force_stop),
                getString(R.string.settings_auto_kill_type_kill)
        };

        View titleView = LayoutInflater.from(this).inflate(R.layout.dialog_killtype_info, null);
        ((TextView) titleView.findViewById(R.id.dialog_title)).setText(R.string.settings_auto_kill_type_title);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setCustomTitle(titleView)
                .setSingleChoiceItems(types, appManager.getAutoKillType(), (d, which) -> {
                    appManager.setAutoKillType(which);
                    updateAutoKillTypeText(which);
                    d.dismiss();
                });

        AlertDialog dialog = builder.create();
        titleView.findViewById(R.id.btn_help).setOnClickListener(v -> {
            dialog.dismiss();
            showAutoKillTypeHelpDialog(() -> showAutoKillTypeDialog());
        });
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void showAutoKillTypeHelpDialog(Runnable onBack) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_auto_kill_type_help_title))
                .setMessage(getString(R.string.settings_auto_kill_type_help_message))
                .setPositiveButton(getString(R.string.dialog_ok_got_it), (d, w) -> {
                    d.dismiss();
                    if (onBack != null) onBack.run();
                })
                .create();
        dialog.show();
        styleDialogButtons(dialog);
    }

    private void showKillModeDialog() {
        String[] modes = {
                getString(R.string.settings_mode_whitelist),
                getString(R.string.settings_mode_blacklist)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_kill_mode_dialog_title))
                .setSingleChoiceItems(modes, appManager.getKillMode(), (dialog, which) -> {
                    appManager.setKillMode(which);
                    updateKillModeVisibility();
                    dialog.dismiss();
                    if (which == 0) {
                        boolean autoKillEnabled = sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false);
                        Set<String> whitelistedApps = sharedPreferences.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>());
                        if (autoKillEnabled && whitelistedApps.isEmpty()) {
                            new AlertDialog.Builder(this)
                                    .setTitle(R.string.dialog_unsafe_whitelist_title)
                                    .setMessage(R.string.dialog_unsafe_whitelist_message)
                                    .setPositiveButton(R.string.dialog_unsafe_whitelist_ok, (d, w) -> d.dismiss())
                                    .setCancelable(false)
                                    .show();
                        }
                    }
                })
                .show();
    }

    private void showBlacklistDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_blacklist_dialog_title))
                .setView(dialogView)
                .create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_save), (d, w) -> {});
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_cancel), (d, w) -> d.dismiss());
        searchBox.setVisibility(View.GONE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

        appManager.loadAllApps(allApps -> {
            Set<String> blacklisted = appManager.getBlacklistedApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, blacklisted);
            listView.setAdapter(filterAdapter);
            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);
            filterOptions.setVisibility(View.VISIBLE);

            setupFilterListeners(dialogView, filterAdapter);
            appManager.updateRunningState(allApps, () -> {
                if (!dialog.isShowing()) return;
                filterAdapter.notifyDataSetChanged();
            });

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterAdapter.getFilter().filter(s); }
                @Override public void afterTextChanged(Editable s) {}
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                appManager.saveBlacklistedApps(filterAdapter.getSelectedPackages());
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void showWhitelistDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        AlertDialog whitelistDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_whitelist_dialog_title))
                .setView(dialogView)
                .create();
        whitelistDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        whitelistDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_cancel), (dialog, which) -> dialog.dismiss());
        whitelistDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_save), (dialog, which) -> {});

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        whitelistDialog.show();
        whitelistDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        whitelistDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

        appManager.loadAllApps(allApps -> {
            Set<String> whitelistedApps = appManager.getWhitelistedApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, whitelistedApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);
            filterOptions.setVisibility(View.VISIBLE);

            setupFilterListeners(dialogView, filterAdapter);
            appManager.updateRunningState(allApps, () -> {
                if (!whitelistDialog.isShowing()) return;
                filterAdapter.notifyDataSetChanged();
            });

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterAdapter.getFilter().filter(s); }
                @Override public void afterTextChanged(Editable s) {}
            });

            whitelistDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                appManager.saveWhitelistedApps(filterAdapter.getSelectedPackages());
                whitelistDialog.dismiss();
            });
            whitelistDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void showHiddenAppsDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        AlertDialog filterDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_hidden_apps_dialog_title))
                .setView(dialogView)
                .create();
        filterDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        filterDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_cancel), (dialog, which) -> dialog.dismiss());
        filterDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_save), (dialog, which) -> {});

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        filterDialog.show();
        filterDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        filterDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

        appManager.loadAllApps(allApps -> {
            Set<String> hiddenApps = appManager.getHiddenApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, hiddenApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);
            filterOptions.setVisibility(View.VISIBLE);

            setupFilterListeners(dialogView, filterAdapter);
            appManager.updateRunningState(allApps, () -> {
                if (!filterDialog.isShowing()) return;
                filterAdapter.notifyDataSetChanged();
            });

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterAdapter.getFilter().filter(s); }
                @Override public void afterTextChanged(Editable s) {}
            });

            filterDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                appManager.saveHiddenApps(filterAdapter.getSelectedPackages());
                filterDialog.dismiss();
            });
            filterDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void showBackgroundRestrictionDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        View titleView = inflater.inflate(R.layout.dialog_backgroundrest_help, null);
        ((TextView) titleView.findViewById(R.id.dialog_title)).setText(R.string.settings_background_restriction_title);

        AlertDialog restrictionDialog = new AlertDialog.Builder(this)
                .setCustomTitle(titleView)
                .setView(dialogView)
                .create();
        restrictionDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        restrictionDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_cancel), (dialog, which) -> dialog.dismiss());
        restrictionDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_save), (dialog, which) -> {});

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        restrictionDialog.show();

        titleView.findViewById(R.id.btn_help).setOnClickListener(v -> {
            restrictionDialog.dismiss();
            showRestrictionTypeHelpDialog(() -> showBackgroundRestrictionDialog());
        });

        restrictionDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        restrictionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

        appManager.loadBackgroundRestrictionApps(allApps -> {
            Set<String> desiredRestrictedApps = appManager.getBackgroundRestrictedApps();
            Set<String> hardRestrictedApps = appManager.getHardRestrictedApps();

            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, desiredRestrictedApps, hardRestrictedApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);
            filterOptions.setVisibility(View.VISIBLE);

            setupFilterListeners(dialogView, filterAdapter);
            appManager.updateRunningState(allApps, () -> {
                if (!restrictionDialog.isShowing()) return;
                filterAdapter.notifyDataSetChanged();
            });

            searchBox.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterAdapter.getFilter().filter(s); }
                @Override public void afterTextChanged(Editable s) {}
            });

            filterAdapter.setOnSelectionChangedListener(() -> {
                restrictionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(getString(R.string.dialog_apply));
                restrictionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
            });

            Runnable doApply = () -> {
                Set<String> targetPackages = filterAdapter.getSelectedPackages();
                Set<String> hardPackages = filterAdapter.getHardRestrictedPackages();
                Set<String> currentDesired = new java.util.HashSet<>(desiredRestrictedApps);
                Set<String> packagesToRestrict = new java.util.HashSet<>(targetPackages);
                packagesToRestrict.removeAll(currentDesired);

                int systemAppCount = 0;
                for (AppModel app : allApps) {
                    if (packagesToRestrict.contains(app.getPackageName()) && app.isSystemApp()) systemAppCount++;
                }

                if (systemAppCount > 0) {
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle(getString(R.string.settings_restriction_system_apps_title))
                            .setMessage(getString(R.string.settings_restriction_system_apps_message, systemAppCount))
                            .setPositiveButton(getString(R.string.settings_restriction_system_apps_confirm), (d2, w2) ->
                                    appManager.applyBackgroundRestriction(targetPackages, hardPackages, null))
                            .setNegativeButton(getString(R.string.dialog_cancel), null)
                            .show();
                } else if (!packagesToRestrict.isEmpty()) {
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle(getString(R.string.settings_restriction_warning_title))
                            .setMessage(getString(R.string.settings_restriction_warning_message))
                            .setPositiveButton(getString(R.string.dialog_apply), (d2, w2) ->
                                    appManager.applyBackgroundRestriction(targetPackages, hardPackages, null))
                            .setNegativeButton(getString(R.string.dialog_cancel), null)
                            .show();
                } else {
                    appManager.applyBackgroundRestriction(targetPackages, hardPackages, null);
                }
            };

            restrictionDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_save), (dialog, which) -> doApply.run());
            restrictionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void showRestrictionTypeHelpDialog(Runnable onBack) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start = sb.length();
        sb.append(getString(R.string.bgrest_help_soft_title));
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), 0);
        sb.append("\n").append(getString(R.string.bgrest_help_soft_body)).append("\n\n\n\n");
        start = sb.length();
        sb.append(getString(R.string.bgrest_help_hard_title));
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), 0);
        sb.append("\n").append(getString(R.string.bgrest_help_hard_body));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_backgroundrest_title))
                .setMessage(sb)
                .setPositiveButton(getString(R.string.dialog_ok_got_it), (d, w) -> {
                    d.dismiss();
                    if (onBack != null) onBack.run();
                })
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setText(sb);
        styleDialogButtons(dialog);
    }

    private void updateSleepModeDelayText(long delayMs) {
        String[] labels = getResources().getStringArray(R.array.settings_sleep_mode_delay_labels);
        for (int i = 0; i < SLEEP_MODE_DELAYS_MS.length; i++) {
            if (SLEEP_MODE_DELAYS_MS[i] == delayMs) { binding.textSleepModeDelay.setText(labels[i]); return; }
        }
        binding.textSleepModeDelay.setText(getString(R.string.settings_sleep_mode_delay_fallback, delayMs / 60000));
    }

    private void showSleepModeDelayDialog() {
        long current = sharedPreferences.getLong(KEY_SLEEP_MODE_DELAY, DEFAULT_SLEEP_MODE_DELAY_MS);
        int selected = SLEEP_MODE_DELAYS_MS.length - 1;
        for (int i = 0; i < SLEEP_MODE_DELAYS_MS.length; i++) {
            if (SLEEP_MODE_DELAYS_MS[i] == current) { selected = i; break; }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_sleep_mode_delay_title)
                .setSingleChoiceItems(getResources().getStringArray(R.array.settings_sleep_mode_delay_labels), selected,
                        (d, which) -> {
                            sharedPreferences.edit().putLong(KEY_SLEEP_MODE_DELAY, SLEEP_MODE_DELAYS_MS[which]).apply();
                            updateSleepModeDelayText(SLEEP_MODE_DELAYS_MS[which]);
                            d.dismiss();
                        })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
    }

    private void showSleepModeAppsDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_sleep_mode_apps_dialog_title))
                .setView(dialogView)
                .create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_cancel), (d, w) -> d.dismiss());
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_save), (d, w) -> {});

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        filterOptions.setVisibility(View.GONE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

        appManager.loadSleepModeApps(allApps -> {
            Set<String> sleepModeApps = appManager.getSleepModeApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, sleepModeApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);

            searchBox.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterAdapter.getFilter().filter(s); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });

            dialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dialog_save), (d, w) ->
                    appManager.saveSleepModeApps(filterAdapter.getSelectedPackages()));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void updateRamThresholdText(int threshold) {
        binding.textRamThreshold.setText(getString(R.string.settings_ram_threshold_summary, threshold));
    }

    private void showRamThresholdDialog() {
        int current = sharedPreferences.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
        int selected = 1;
        for (int i = 0; i < RAM_THRESHOLD_VALUES.length; i++) {
            if (RAM_THRESHOLD_VALUES[i] == current) { selected = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_ram_threshold_dialog_title))
                .setSingleChoiceItems(getResources().getStringArray(R.array.settings_ram_threshold_labels), selected,
                        (dialog, which) -> {
                            sharedPreferences.edit().putInt(KEY_RAM_THRESHOLD, RAM_THRESHOLD_VALUES[which]).apply();
                            updateRamThresholdText(RAM_THRESHOLD_VALUES[which]);
                            dialog.dismiss();
                        })
                .show();
    }

    private void updateNotificationModeText(int mode) {
        binding.textNotificationMode.setText(mode == NOTIFICATION_MODE_IMPORTANT_ONLY
                ? getString(R.string.settings_notification_mode_important_only)
                : getString(R.string.settings_notification_mode_all));
    }

    private void showNotificationModeDialog() {
        int current = sharedPreferences.getInt(KEY_NOTIFICATION_MODE, NOTIFICATION_MODE_ALL);
        String[] options = {
                getString(R.string.settings_notification_mode_all),
                getString(R.string.settings_notification_mode_important_only)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_notification_mode_title))
                .setSingleChoiceItems(options, current, (dialog, which) -> {
                    sharedPreferences.edit().putInt(KEY_NOTIFICATION_MODE, which).apply();
                    updateNotificationModeText(which);
                    dialog.dismiss();
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    private void updateThemeText(int themeValue, boolean isAmoled) {
        if (isAmoled) { binding.textTheme.setText(getString(R.string.settings_theme_amoled_short)); return; }
        String[] themeLabels = getResources().getStringArray(R.array.settings_theme_labels);
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == themeValue) { binding.textTheme.setText(themeLabels[i]); return; }
        }
    }

    private void updateAccentText(int accentValue) {
        String[] accentLabels = getResources().getStringArray(R.array.settings_accent_labels);
        if (accentValue >= 0 && accentValue < accentLabels.length) binding.textAccent.setText(accentLabels[accentValue]);
    }

    private void updateAccentLayoutEnabled(int themeValue) {
        boolean isSystemTheme = (themeValue == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        binding.layoutAccent.setAlpha(isSystemTheme ? 0.4f : 1.0f);
        binding.layoutAccent.setClickable(!isSystemTheme);
    }

    private void showThemeDialog() {
        int currentTheme = sharedPreferences.getInt(KEY_THEME,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        boolean isAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        int selectedIndex = isAmoled ? 3 : 0;
        if (!isAmoled) {
            for (int i = 0; i < THEME_VALUES.length; i++) {
                if (THEME_VALUES[i] == currentTheme) { selectedIndex = i; break; }
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_theme_dialog_title))
                .setSingleChoiceItems(getResources().getStringArray(R.array.settings_theme_labels), selectedIndex, (d, which) -> {
                    if (which == 3) {
                        sharedPreferences.edit()
                                .putBoolean(KEY_AMOLED, true)
                                .putInt(KEY_THEME, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                                .apply();
                        updateThemeText(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES, true);
                        updateAccentLayoutEnabled(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
                    } else {
                        int newTheme = THEME_VALUES[which];
                        sharedPreferences.edit().putInt(KEY_THEME, newTheme).putBoolean(KEY_AMOLED, false).apply();
                        updateThemeText(newTheme, false);
                        updateAccentLayoutEnabled(newTheme);
                        if (newTheme == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
                            sharedPreferences.edit().putInt(KEY_ACCENT, ACCENT_SYSTEM).apply();
                            updateAccentText(ACCENT_SYSTEM);
                        }
                        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newTheme);
                    }
                    d.dismiss();
                    recreate();
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
    }

    private void showAccentDialog() {
        int currentAccent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_INDIGO);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_accent_title))
                .setSingleChoiceItems(getResources().getStringArray(R.array.settings_accent_labels), currentAccent, (d, which) -> {
                    sharedPreferences.edit().putInt(KEY_ACCENT, which).apply();
                    updateAccentText(which);
                    d.dismiss();
                    recreate();
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
    }

    private void updateAutomationOptionsVisibility(boolean serviceEnabled, boolean periodicEnabled) {
        float serviceAlpha = serviceEnabled ? 1.0f : 0.5f;
        float periodicAlpha = (serviceEnabled && periodicEnabled) ? 1.0f : 0.5f;

        binding.layoutPeriodicKill.setAlpha(serviceAlpha);
        binding.switchPeriodicKill.setEnabled(serviceEnabled);
        binding.layoutScreenLock.setAlpha(serviceAlpha);
        binding.switchKillScreenOff.setEnabled(serviceEnabled);
        binding.layoutRamThresholdToggle.setAlpha(serviceAlpha);
        binding.switchRamThreshold.setEnabled(serviceEnabled);
        binding.layoutKillInterval.setAlpha(periodicAlpha);
        binding.layoutKillInterval.setClickable(serviceEnabled && periodicEnabled);
    }

    private void updateKillIntervalText(int intervalMs) {
        String[] killIntervalLabels = getResources().getStringArray(R.array.settings_kill_interval_labels);
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == intervalMs) { binding.textKillInterval.setText(killIntervalLabels[i]); return; }
        }
        binding.textKillInterval.setText(getString(R.string.settings_interval_fallback, intervalMs / 1000));
    }

    private void updateAutoKillTypeText(int type) {
        binding.textAutoKillType.setText(type == 1
                ? getString(R.string.settings_auto_kill_type_kill)
                : getString(R.string.settings_auto_kill_type_force_stop));
    }

    private void showKillIntervalDialog() {
        if (!binding.switchAutoKill.isChecked() || !binding.switchPeriodicKill.isChecked()) return;

        int currentInterval = sharedPreferences.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        int selectedIndex = 1;
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == currentInterval) { selectedIndex = i; break; }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_check_frequency_title))
                .setSingleChoiceItems(getResources().getStringArray(R.array.settings_kill_interval_labels), selectedIndex,
                        (d, which) -> {
                            sharedPreferences.edit().putInt(KEY_KILL_INTERVAL, KILL_INTERVALS_MS[which]).apply();
                            updateKillIntervalText(KILL_INTERVALS_MS[which]);
                            d.dismiss();
                        })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create();
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL: " + url, e);
            Toast.makeText(this, R.string.url_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupFilterListeners(View dialogView, FilterAppsAdapter adapter) {
        CheckBox chkSystem = dialogView.findViewById(R.id.filter_chk_system);
        CheckBox chkUser = dialogView.findViewById(R.id.filter_chk_user);
        CheckBox chkRunning = dialogView.findViewById(R.id.filter_chk_running);
        android.widget.TextView btnClear = dialogView.findViewById(R.id.filter_btn_clear);

        android.widget.CompoundButton.OnCheckedChangeListener listener = (buttonView, isChecked) ->
                adapter.setFilters(chkSystem.isChecked(), chkUser.isChecked(), chkRunning.isChecked());
        chkSystem.setOnCheckedChangeListener(listener);
        chkUser.setOnCheckedChangeListener(listener);
        chkRunning.setOnCheckedChangeListener(listener);
        btnClear.setOnClickListener(v -> adapter.clearSelection());
    }

    private void showBackupRestoreDialog() {
        String[] options = {
                getString(R.string.settings_backup_option_save),
                getString(R.string.settings_backup_option_restore)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_backup_restore_title))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) createBackupLauncher.launch("appzuku_backup.json");
                    else restoreBackupLauncher.launch(new String[]{"application/json"});
                })
                .show();
    }

    private void exportBackup(Uri uri) {
        executor.execute(() -> {
            String json = backupManager.createBackupJson();
            if (json == null) {
                handler.post(() -> Toast.makeText(this, getString(R.string.settings_backup_create_failed), Toast.LENGTH_SHORT).show());
                return;
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    handler.post(() -> Toast.makeText(this, getString(R.string.settings_backup_success), Toast.LENGTH_SHORT).show());
                } else {
                    handler.post(() -> Toast.makeText(this, getString(R.string.settings_backup_write_failed), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                handler.post(() -> Toast.makeText(this, getString(R.string.settings_backup_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void importBackup(Uri uri) {
        executor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);

                boolean success = backupManager.restoreBackupJson(sb.toString());
                handler.post(() -> {
                    if (success) {
                        Set<String> restoredRestrictedApps = new java.util.HashSet<>(
                                sharedPreferences.getStringSet(KEY_AUTOSTART_DISABLED_APPS, new java.util.HashSet<>()));
                        Runnable finishRestore = () -> {
                            applyAutomationStateFromPreferences();
                            loadSettings();
                            updateKillModeVisibility();
                            if (appManager.supportsBackgroundRestriction()
                                    && !restoredRestrictedApps.isEmpty()
                                    && !appManager.canApplyBackgroundRestrictionNow()) {
                                Toast.makeText(this, getString(R.string.settings_restore_need_permission), Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, getString(R.string.settings_restore_success), Toast.LENGTH_SHORT).show();
                            }
                        };
                        if (appManager.canApplyBackgroundRestrictionNow()) {
                            appManager.applyBackgroundRestriction(restoredRestrictedApps, finishRestore);
                        } else {
                            finishRestore.run();
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.settings_restore_failed), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                handler.post(() -> Toast.makeText(this, getString(R.string.settings_restore_import_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void styleDialogButtons(AlertDialog dialog) {
        int color = ContextCompat.getColor(this, R.color.dialog_button_text);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(color);
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
    }

    private void startAutomationService() {
        Intent serviceIntent = new Intent(this, ShappkyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void applyAutomationStateFromPreferences() {
        boolean automationEnabled = sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false);
        if (automationEnabled) {
            int killMode = sharedPreferences.getInt(KEY_KILL_MODE, 0);
            Set<String> whitelistedApps = sharedPreferences.getStringSet(KEY_WHITELISTED_APPS, new HashSet<>());
            if (killMode == 0 && whitelistedApps.isEmpty()) {
                sharedPreferences.edit().putBoolean(KEY_AUTO_KILL_ENABLED, false).apply();
                if (binding.switchAutoKill != null) binding.switchAutoKill.setChecked(false);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_unsafe_whitelist_title)
                        .setMessage(R.string.dialog_unsafe_whitelist_message)
                        .setPositiveButton(R.string.dialog_unsafe_whitelist_ok, (dialog, which) -> dialog.dismiss())
                        .setCancelable(false)
                        .show();
                stopService(new Intent(this, ShappkyService.class));
                AutoKillWorker.cancel(this);
                return;
            }
            startAutomationService();
            AutoKillWorker.schedule(this);
        } else {
            stopService(new Intent(this, ShappkyService.class));
            AutoKillWorker.cancel(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        binding = null;
    }
}
