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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.gree1d.reappzuku.PreferenceKeys.*;
import static com.gree1d.reappzuku.AppConstants.*;

public class SettingsActivity extends BaseActivity {
    private static final String TAG = "SettingsActivity";
    private static final int TOP_OFFENDERS_LIMIT = 50;
    private static final String[] TOP_OFFENDER_FILTER_LABELS = {
            "Last 12 hours",
            "Last 24 hours",
            "Last 7 days",
            "All time"
    };
    private static final long[] TOP_OFFENDER_FILTER_WINDOWS_MS = {
            STATS_HISTORY_DURATION_MS,
            24 * 60 * 60 * 1000L,
            7 * 24 * 60 * 60 * 1000L,
            -1L
    };

    private ActivitySettingsBinding binding;
    private BackgroundAppManager appManager;
    private BackupManager backupManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final ActivityResultLauncher<String> createBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
                if (uri != null) {
                    exportBackup(uri);
                }
            });

    private final ActivityResultLauncher<String[]> restoreBackupLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    importBackup(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize app manager for dialogs
        ShellManager shellManager = new ShellManager(this.getApplicationContext(), handler, executor);
        appManager = new BackgroundAppManager(this.getApplicationContext(), handler, executor, shellManager);
        backupManager = new BackupManager(this);

        setupToolbar();
        loadSettings();
        setupListeners();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void loadSettings() {
        // Load theme
        int theme = sharedPreferences.getInt(KEY_THEME,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        updateThemeText(theme);

        // Load background service state
        boolean serviceEnabled = sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false);
        binding.switchAutoKill.setChecked(serviceEnabled);

        // Load periodic kill state
        boolean periodicKillEnabled = sharedPreferences.getBoolean(KEY_PERIODIC_KILL_ENABLED, false);
        binding.switchPeriodicKill.setChecked(periodicKillEnabled);

        // Load kill interval
        int killInterval = sharedPreferences.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        updateKillIntervalText(killInterval);

        // Load kill on screen off
        binding.switchKillScreenOff.setChecked(sharedPreferences.getBoolean(KEY_KILL_ON_SCREEN_OFF, false));

        // Load RAM threshold
        boolean ramThresholdEnabled = sharedPreferences.getBoolean(KEY_RAM_THRESHOLD_ENABLED, false);
        binding.switchRamThreshold.setChecked(ramThresholdEnabled);
        int ramThreshold = sharedPreferences.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
        updateRamThresholdText(ramThreshold);

        // Update visibility of automation options
        updateAutomationOptionsVisibility(serviceEnabled, periodicKillEnabled);

        // Load show system apps
        boolean showSystemApps = sharedPreferences.getBoolean(KEY_SHOW_SYSTEM_APPS, false);
        binding.switchShowSystem.setChecked(showSystemApps);

        // Load show persistent apps
        boolean showPersistentApps = sharedPreferences.getBoolean(KEY_SHOW_PERSISTENT_APPS, false);
        binding.switchShowPersistent.setChecked(showPersistentApps);

        // Load Sleep Mode state
        binding.switchSleepMode.setChecked(appManager.isSleepModeEnabled());

        // Set version text
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            binding.textVersion.setText(getString(R.string.settings_version_label, versionName));
        } catch (Exception e) {
            binding.textVersion.setText(R.string.app_name);
        }
    }

    private void setupListeners() {
        // Theme selector
        binding.layoutTheme.setOnClickListener(v -> showThemeDialog());

        // Background Service toggle
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

        // Periodic Auto-Kill toggle
        binding.switchPeriodicKill.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_PERIODIC_KILL_ENABLED, isChecked).apply();
            boolean serviceEnabled = binding.switchAutoKill.isChecked();
            updateAutomationOptionsVisibility(serviceEnabled, isChecked);
        });

        // Kill on screen off
        binding.switchKillScreenOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_KILL_ON_SCREEN_OFF, isChecked).apply();
        });

        // RAM threshold
        binding.switchRamThreshold.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_RAM_THRESHOLD_ENABLED, isChecked).apply();
        });
        binding.layoutRamThresholdToggle.setOnClickListener(v -> {
            if (binding.switchRamThreshold.isChecked()) {
                showRamThresholdDialog();
            }
        });

        // Kill interval selector
        binding.layoutKillInterval.setOnClickListener(v -> showKillIntervalDialog());

        // Show system apps toggle with warning
        binding.switchShowSystem.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !sharedPreferences.getBoolean("system_apps_warning_shown", false)) {
                // Show warning on first enable
                new AlertDialog.Builder(this)
                        .setTitle("âš ï¸ Warning: System Apps")
                        .setMessage(
                                "System apps are critical for device stability. Blocking or killing system apps (like 'Security' on Xiaomi devices) may cause crashes, boot loops, or device malfunction.\n\nOnly modify system apps if you know what you're doing.")
                        .setPositiveButton("I Understand", (dialog, which) -> {
                            sharedPreferences.edit()
                                    .putBoolean(KEY_SHOW_SYSTEM_APPS, true)
                                    .putBoolean("system_apps_warning_shown", true)
                                    .apply();
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            buttonView.setChecked(false);
                        })
                        .show();
            } else if (!isChecked) {
                sharedPreferences.edit().putBoolean(KEY_SHOW_SYSTEM_APPS, false).apply();
            }
        });

        // Show persistent apps toggle
        binding.switchShowPersistent.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_SHOW_PERSISTENT_APPS, isChecked).apply();
        });

        // Whitelist
        binding.layoutWhitelist.setOnClickListener(v -> showWhitelistDialog());

        // Hidden apps
        binding.layoutHiddenApps.setOnClickListener(v -> showHiddenAppsDialog());

        // Background Restriction
        binding.layoutBackgroundRestriction.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutBackgroundRestriction.setOnClickListener(v -> showBackgroundRestrictionDialog());
        binding.layoutReapplyRestrictions.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutReapplyRestrictions.setOnClickListener(v -> {
            Set<String> savedRestrictions = appManager.getBackgroundRestrictedApps();
            if (savedRestrictions.isEmpty()) {
                Toast.makeText(this, "No saved background restrictions to re-apply", Toast.LENGTH_SHORT).show();
                return;
            }
            appManager.reapplySavedBackgroundRestrictions(null);
        });

        // Sleep Mode
        binding.switchSleepMode.setChecked(appManager.isSleepModeEnabled());
        binding.switchSleepMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appManager.setSleepModeEnabled(isChecked);
        });
        binding.layoutSleepModeApps.setOnClickListener(v -> showSleepModeAppsDialog());

        // Kill Mode
        binding.layoutKillMode.setOnClickListener(v -> showKillModeDialog());
        binding.layoutBlacklist.setOnClickListener(v -> showBlacklistDialog());

        // Help
        binding.layoutHelp.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));

        // Clear Cache
        binding.layoutClearCache.setOnClickListener(v -> {
            binding.layoutClearCache.setEnabled(false);
            appManager.clearCaches(() -> binding.layoutClearCache.setEnabled(true));
        });

        // Statistics
        binding.layoutStats.setOnClickListener(v -> showStatsDialog());
        binding.layoutTopOffenders.setOnClickListener(v -> showTopOffendersDialog());
        binding.layoutRestrictionLog.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutRestrictionLog.setOnClickListener(v -> showBackgroundRestrictionLogDialog());

        // Backup & Restore
        binding.layoutBackupRestore.setOnClickListener(v -> showBackupRestoreDialog());

        // GitHub
        binding.layoutGithub.setOnClickListener(v -> openUrl("https://github.com/gree1d/ReAppzuku"));

        // Check for Updates
        binding.layoutCheckUpdates.setOnClickListener(v -> openUrl("https://github.com/gree1d/ReAppzuku/releases"));

        updateKillModeVisibility();
    }

    private void updateKillModeVisibility() {
        int mode = appManager.getKillMode();
        binding.textKillMode
                .setText(mode == 0 ? R.string.settings_mode_whitelist : R.string.settings_mode_blacklist);
        binding.layoutBlacklist.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        binding.layoutWhitelist.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
    }

    private void showKillModeDialog() {
        String[] modes = { "Whitelist Mode (Kill all except...)", "Blacklist Mode (Kill only...)" };
        new AlertDialog.Builder(this)
                .setTitle("Select Kill Mode")
                .setSingleChoiceItems(modes, appManager.getKillMode(), (dialog, which) -> {
                    appManager.setKillMode(which);
                    updateKillModeVisibility();
                    dialog.dismiss();
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

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Blacklisted apps (Target to kill)")
                .setView(dialogView);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (d, w) -> {
        });
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (d, w) -> d.dismiss());
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
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (d, w) -> {
                appManager.saveBlacklistedApps(filterAdapter.getSelectedPackages());
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void showStatsDialog() {
        executor.execute(() -> {
            long twelveHoursAgo = System.currentTimeMillis() - STATS_HISTORY_DURATION_MS;
            com.gree1d.reappzuku.db.AppStatsDao appStatsDao = com.gree1d.reappzuku.db.AppDatabase
                    .getInstance(this).appStatsDao();
            java.util.List<com.gree1d.reappzuku.db.AppStats> statsList = appStatsDao.getAllStatsSince(twelveHoursAgo);

            final List<String> highRelaunchPackages = new ArrayList<>();
            List<KillHistoryEntry> historyEntries = new ArrayList<>();
            int totalKills = 0;
            int totalRelaunches = 0;
            long totalRecoveredKb = 0;
            java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("h:mm a",
                    java.util.Locale.getDefault());

            for (com.gree1d.reappzuku.db.AppStats stats : statsList) {
                if (stats == null || stats.packageName == null) {
                    continue;
                }
                if (stats.killCount <= 0 && stats.relaunchCount <= 0) {
                    continue;
                }

                if (stats.relaunchCount > RELAUNCH_GREEDY_THRESHOLD) {
                    highRelaunchPackages.add(stats.packageName);
                }

                List<String> detailParts = new ArrayList<>();
                if (stats.killCount > 0) {
                    String killDetail = "Killed " + stats.killCount + "x";
                    if (stats.lastKillTime > 0) {
                        killDetail += " (last " + timeFormat.format(new java.util.Date(stats.lastKillTime)) + ")";
                    }
                    detailParts.add(killDetail);
                }
                if (stats.relaunchCount > 0) {
                    String relaunchDetail = "Relaunched " + stats.relaunchCount + "x";
                    if (stats.lastRelaunchTime > 0) {
                        relaunchDetail += " (last " + timeFormat.format(new java.util.Date(stats.lastRelaunchTime)) + ")";
                    }
                    detailParts.add(relaunchDetail);
                }
                if (stats.totalRecoveredKb > 0) {
                    detailParts.add("Recovered " + formatRecoveredSize(stats.totalRecoveredKb));
                }

                long lastEventTime = Math.max(stats.lastKillTime, stats.lastRelaunchTime);
                String badge = lastEventTime > 0 ? timeFormat.format(new java.util.Date(lastEventTime)) : "";
                historyEntries.add(new KillHistoryEntry(
                        resolveStatsAppName(stats, appStatsDao),
                        stats.packageName,
                        String.join(" | ", detailParts),
                        badge,
                        lastEventTime));
                totalKills += stats.killCount;
                totalRelaunches += stats.relaunchCount;
                totalRecoveredKb += stats.totalRecoveredKb;
            }

            Collections.sort(historyEntries, (a, b) -> Long.compare(b.lastEventTime, a.lastEventTime));
            List<SettingsSurfaceRow> rows = buildKillHistoryRows(historyEntries);
            String summary = String.format(Locale.US,
                    "Last 12 hours: %d apps | Kills %d | Relaunches %d | Recovered %s",
                    rows.size(),
                    totalKills,
                    totalRelaunches,
                    formatRecoveredSize(totalRecoveredKb));

            handler.post(() -> {
                SettingsListContent content = createSettingsListContent("No activity in the last 12 hours.", false);
                SettingsSurfaceAdapter adapter = new SettingsSurfaceAdapter();
                adapter.setItems(rows);
                content.listView.setAdapter(adapter);
                content.listView.setEmptyView(content.emptyView);
                content.listView.setOnItemClickListener((parent, view, position, id) -> {
                    SettingsSurfaceRow row = adapter.getItem(position);
                    if (row != null && row.packageName != null && !row.packageName.isEmpty()) {
                        openAppInfo(row.packageName);
                    }
                });
                content.summaryText.setText(summary);
                content.loading.setVisibility(View.GONE);
                content.listView.setVisibility(View.VISIBLE);
                content.emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

                AlertDialog dialog = createSettingsSurfaceDialog("Kill History", "Last 12 hours", content.rootView);
                dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Close", (d, w) -> d.dismiss());
                if (appManager.supportsBackgroundRestriction() && !highRelaunchPackages.isEmpty()) {
                    dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Restrict Greedy Apps in Background", (d, w) -> {
                        Set<String> currentRestricted = appManager.getBackgroundRestrictedApps();
                        currentRestricted.addAll(highRelaunchPackages);
                        appManager.applyBackgroundRestriction(currentRestricted, null);
                    });
                }
                dialog.show();
                styleDialogButtons(dialog);
            });
        });
    }

    private void showTopOffendersDialog() {
        SettingsListContent content = createSettingsListContent("No offenders for this period", true);
        Spinner filterSpinner = content.filterSpinner;
        TextView summaryText = content.summaryText;
        ProgressBar loading = content.loading;
        ListView listView = content.listView;
        TextView emptyView = content.emptyView;

        SettingsSurfaceAdapter offendersAdapter = new SettingsSurfaceAdapter();
        listView.setAdapter(offendersAdapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            SettingsSurfaceRow row = offendersAdapter.getItem(position);
            if (row != null && row.packageName != null && !row.packageName.isEmpty()) {
                openAppInfo(row.packageName);
            }
        });

        ArrayAdapter<String> filterAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, TOP_OFFENDER_FILTER_LABELS);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(filterAdapter);

        AlertDialog dialog = createSettingsSurfaceDialog(
                "Top Offenders",
                "Rank apps by kills, relaunches, and RAM recovered.",
                content.rootView);
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Close", (d, w) -> d.dismiss());
        dialog.show();
        styleDialogButtons(dialog);

        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadTopOffenders(position, offendersAdapter, summaryText, loading, listView, emptyView);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadTopOffenders(int filterIndex, SettingsSurfaceAdapter adapter, TextView summaryText,
                                  ProgressBar loading, ListView listView, TextView emptyView) {
        if (filterIndex < 0 || filterIndex >= TOP_OFFENDER_FILTER_WINDOWS_MS.length) {
            filterIndex = 0;
        }

        final int selectedFilterIndex = filterIndex;
        loading.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        summaryText.setText("Loading...");

        executor.execute(() -> {
            long windowMs = TOP_OFFENDER_FILTER_WINDOWS_MS[selectedFilterIndex];
            com.gree1d.reappzuku.db.AppStatsDao appStatsDao =
                    com.gree1d.reappzuku.db.AppDatabase.getInstance(this).appStatsDao();
            List<com.gree1d.reappzuku.db.AppStats> stats;
            if (windowMs > 0) {
                long since = System.currentTimeMillis() - windowMs;
                stats = appStatsDao.getAllStatsSince(since);
            } else {
                stats = appStatsDao.getAllStats();
            }

            List<TopOffender> offenders = buildTopOffenders(stats, appStatsDao);

            int totalKills = 0;
            int totalRelaunches = 0;
            long totalRecoveredKb = 0;
            for (TopOffender offender : offenders) {
                totalKills += offender.killCount;
                totalRelaunches += offender.relaunchCount;
                totalRecoveredKb += offender.recoveredKb;
            }

            String summary = String.format(Locale.US,
                    "%s: %d apps | Kills %d | Relaunches %d | Recovered %s",
                    TOP_OFFENDER_FILTER_LABELS[selectedFilterIndex],
                    offenders.size(),
                    totalKills,
                    totalRelaunches,
                    formatRecoveredSize(totalRecoveredKb));

            handler.post(() -> {
                if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                    return;
                }
                adapter.setItems(buildTopOffenderRows(offenders));
                summaryText.setText(summary);
                loading.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(offenders.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private List<TopOffender> buildTopOffenders(List<com.gree1d.reappzuku.db.AppStats> statsList,
                                                 com.gree1d.reappzuku.db.AppStatsDao appStatsDao) {
        List<TopOffender> offenders = new ArrayList<>();
        for (com.gree1d.reappzuku.db.AppStats stats : statsList) {
            if (stats == null || stats.packageName == null) {
                continue;
            }
            if (stats.killCount <= 0 && stats.relaunchCount <= 0 && stats.totalRecoveredKb <= 0) {
                continue;
            }

            String appName = resolveStatsAppName(stats, appStatsDao);
            double score = calculateOffenderScore(stats.killCount, stats.relaunchCount, stats.totalRecoveredKb);
            offenders.add(new TopOffender(appName, stats.packageName, stats.killCount, stats.relaunchCount,
                    stats.totalRecoveredKb, score));
        }

        Collections.sort(offenders, (a, b) -> {
            int scoreCompare = Double.compare(b.score, a.score);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            int killCompare = Integer.compare(b.killCount, a.killCount);
            if (killCompare != 0) {
                return killCompare;
            }
            int relaunchCompare = Integer.compare(b.relaunchCount, a.relaunchCount);
            if (relaunchCompare != 0) {
                return relaunchCompare;
            }
            return Long.compare(b.recoveredKb, a.recoveredKb);
        });

        if (offenders.size() > TOP_OFFENDERS_LIMIT) {
            return new ArrayList<>(offenders.subList(0, TOP_OFFENDERS_LIMIT));
        }
        return offenders;
    }

    private String resolveStatsAppName(com.gree1d.reappzuku.db.AppStats stats,
                                       com.gree1d.reappzuku.db.AppStatsDao appStatsDao) {
        if (stats.appName != null && !stats.appName.trim().isEmpty()) {
            return stats.appName;
        }

        String resolvedName = resolveInstalledAppName(stats.packageName);
        if (resolvedName != null && !resolvedName.trim().isEmpty()) {
            stats.appName = resolvedName;
            appStatsDao.updateAppName(stats.packageName, resolvedName);
            return resolvedName;
        }

        return stats.packageName;
    }

    private String resolveInstalledAppName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return packageName;
        }
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = getPackageManager().getApplicationLabel(appInfo);
            if (label != null) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return packageName;
    }

    private double calculateOffenderScore(int killCount, int relaunchCount, long recoveredKb) {
        return (killCount * 1.0) + (relaunchCount * 2.0) + (recoveredKb / 102400.0);
    }

    private String formatRecoveredSize(long kb) {
        if (kb < 1024) {
            return kb + " KB";
        } else if (kb < 1024 * 1024) {
            return String.format(Locale.US, "%.2f MB", kb / 1024f);
        } else {
            return String.format(Locale.US, "%.2f GB", kb / (1024f * 1024f));
        }
    }

    private String formatScore(double score) {
        return String.format(Locale.US, "%.1f", score);
    }

    private void openAppInfo(String packageName) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to open app info", Toast.LENGTH_SHORT).show();
        }
    }

    private static class TopOffender {
        final String appName;
        final String packageName;
        final int killCount;
        final int relaunchCount;
        final long recoveredKb;
        final double score;

        TopOffender(String appName, String packageName, int killCount, int relaunchCount, long recoveredKb,
                    double score) {
            this.appName = appName;
            this.packageName = packageName;
            this.killCount = killCount;
            this.relaunchCount = relaunchCount;
            this.recoveredKb = recoveredKb;
            this.score = score;
        }
    }

    private static class KillHistoryEntry {
        final String appName;
        final String packageName;
        final String detail;
        final String badge;
        final long lastEventTime;

        KillHistoryEntry(String appName, String packageName, String detail, String badge, long lastEventTime) {
            this.appName = appName;
            this.packageName = packageName;
            this.detail = detail;
            this.badge = badge;
            this.lastEventTime = lastEventTime;
        }
    }

    private static class SettingsSurfaceRow {
        final String leadingText;
        final String title;
        final String subtitle;
        final String detail;
        final String badge;
        final String packageName;

        SettingsSurfaceRow(String leadingText, String title, String subtitle, String detail, String badge,
                           String packageName) {
            this.leadingText = leadingText;
            this.title = title;
            this.subtitle = subtitle;
            this.detail = detail;
            this.badge = badge;
            this.packageName = packageName;
        }
    }

    private static class SettingsListContent {
        final View rootView;
        final Spinner filterSpinner;
        final TextView summaryText;
        final ProgressBar loading;
        final ListView listView;
        final TextView emptyView;

        SettingsListContent(View rootView, Spinner filterSpinner, TextView summaryText,
                            ProgressBar loading, ListView listView, TextView emptyView) {
            this.rootView = rootView;
            this.filterSpinner = filterSpinner;
            this.summaryText = summaryText;
            this.loading = loading;
            this.listView = listView;
            this.emptyView = emptyView;
        }
    }

    private class SettingsSurfaceAdapter extends BaseAdapter {
        private final List<SettingsSurfaceRow> items = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(SettingsActivity.this);

        void setItems(List<SettingsSurfaceRow> newItems) {
            items.clear();
            if (newItems != null) {
                items.addAll(newItems);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public SettingsSurfaceRow getItem(int position) {
            if (position < 0 || position >= items.size()) {
                return null;
            }
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_top_offender, parent, false);
            }

            SettingsSurfaceRow item = getItem(position);
            if (item == null) {
                return view;
            }

            TextView rankView = view.findViewById(R.id.offender_rank);
            TextView nameView = view.findViewById(R.id.offender_name);
            TextView packageView = view.findViewById(R.id.offender_package);
            TextView metricsView = view.findViewById(R.id.offender_metrics);
            TextView scoreView = view.findViewById(R.id.offender_score);

            bindOptionalText(rankView, item.leadingText);
            nameView.setText(item.title);
            bindOptionalText(packageView, item.subtitle);
            bindOptionalText(metricsView, item.detail);
            bindOptionalText(scoreView, item.badge);

            return view;
        }
    }

    private void updateThemeText(int themeValue) {
        String[] themeLabels = getResources().getStringArray(R.array.settings_theme_labels);
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == themeValue) {
                binding.textTheme.setText(themeLabels[i]);
                return;
            }
        }
    }

    private void showThemeDialog() {
        int currentTheme = sharedPreferences.getInt(KEY_THEME,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        int selectedIndex = 0;
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == currentTheme) {
                selectedIndex = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select App Theme");
        builder.setSingleChoiceItems(getResources().getStringArray(R.array.settings_theme_labels), selectedIndex,
                (dialog, which) -> {
            int newTheme = THEME_VALUES[which];
            sharedPreferences.edit().putInt(KEY_THEME, newTheme).apply();
            updateThemeText(newTheme);
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newTheme);
            dialog.dismiss();
            recreate(); // Recreate activity to apply theme
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
    }

    private void updateAutomationOptionsVisibility(boolean serviceEnabled, boolean periodicEnabled) {
        float serviceAlpha = serviceEnabled ? 1.0f : 0.5f;
        float periodicAlpha = (serviceEnabled && periodicEnabled) ? 1.0f : 0.5f;

        // Sub-options depend on service being enabled
        binding.layoutPeriodicKill.setAlpha(serviceAlpha);
        binding.switchPeriodicKill.setEnabled(serviceEnabled);

        binding.layoutScreenLock.setAlpha(serviceAlpha);
        binding.switchKillScreenOff.setEnabled(serviceEnabled);

        binding.layoutRamThresholdToggle.setAlpha(serviceAlpha);
        binding.switchRamThreshold.setEnabled(serviceEnabled);

        // Kill interval depends on both service AND periodic kill being enabled
        binding.layoutKillInterval.setAlpha(periodicAlpha);
        binding.layoutKillInterval.setClickable(serviceEnabled && periodicEnabled);
    }

    private void updateKillIntervalText(int intervalMs) {
        String[] killIntervalLabels = getResources().getStringArray(R.array.settings_kill_interval_labels);
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == intervalMs) {
                binding.textKillInterval.setText(killIntervalLabels[i]);
                return;
            }
        }
        binding.textKillInterval.setText(getString(R.string.settings_interval_fallback, intervalMs / 1000));
    }

    private void showKillIntervalDialog() {
        if (!binding.switchAutoKill.isChecked() || !binding.switchPeriodicKill.isChecked()) {
            return;
        }

        int currentInterval = sharedPreferences.getInt(KEY_KILL_INTERVAL, DEFAULT_KILL_INTERVAL_MS);
        int selectedIndex = 1; // default
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == currentInterval) {
                selectedIndex = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Kill Interval");
        builder.setSingleChoiceItems(getResources().getStringArray(R.array.settings_kill_interval_labels), selectedIndex,
                (dialog, which) -> {
            int newInterval = KILL_INTERVALS_MS[which];
            sharedPreferences.edit().putInt(KEY_KILL_INTERVAL, newInterval).apply();
            updateKillIntervalText(newInterval);
            dialog.dismiss();
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
    }

    private void showWhitelistDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Whitelisted apps (never kill)")
                .setView(dialogView);

        AlertDialog whitelistDialog = builder.create();
        whitelistDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        whitelistDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> dialog.dismiss());
        whitelistDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
        });

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
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            whitelistDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
                Set<String> packagesToWhitelist = filterAdapter.getSelectedPackages();
                appManager.saveWhitelistedApps(packagesToWhitelist);
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

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Select the apps you want to hide")
                .setView(dialogView);

        AlertDialog filterDialog = builder.create();
        filterDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        filterDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> dialog.dismiss());
        filterDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
        });

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
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            filterDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
                Set<String> packagesToHide = filterAdapter.getSelectedPackages();
                appManager.saveHiddenApps(packagesToHide);
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

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Background Restriction")
                .setView(dialogView);

        AlertDialog restrictionDialog = builder.create();
        restrictionDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        restrictionDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> dialog.dismiss());
        restrictionDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
        });

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        restrictionDialog.show();

        restrictionDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        restrictionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

        appManager.loadBackgroundRestrictionApps(allApps -> {
            Set<String> desiredRestrictedApps = appManager.getBackgroundRestrictedApps();
            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, desiredRestrictedApps);
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
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            restrictionDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
                Set<String> targetPackages = filterAdapter.getSelectedPackages();
                Set<String> currentDesired = new java.util.HashSet<>(desiredRestrictedApps);
                Set<String> packagesToRestrict = new java.util.HashSet<>(targetPackages);
                packagesToRestrict.removeAll(currentDesired);

                // Count system apps in selection
                int systemAppCount = 0;
                for (AppModel app : allApps) {
                    if (packagesToRestrict.contains(app.getPackageName()) && app.isSystemApp()) {
                        systemAppCount++;
                    }
                }

                // Show warning if system apps are selected
                if (systemAppCount > 0) {
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("System Apps Selected")
                            .setMessage("You have selected " + systemAppCount
                                    + " system app(s) for background restriction.\n\nThis can break notifications, widgets, VPNs, keyboards, accessibility services, companion devices, or device stability.\n\nDo you want to continue?")
                            .setPositiveButton("Yes, Apply", (d2, w2) -> appManager.applyBackgroundRestriction(targetPackages, null))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else if (!packagesToRestrict.isEmpty()) {
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Background Restriction Warning")
                            .setMessage("Restricted apps may stop syncing, miss notifications, stop updating widgets, or fail to perform background tasks until you open them again.\n\nDo you want to continue?")
                            .setPositiveButton("Apply", (d2, w2) -> appManager.applyBackgroundRestriction(targetPackages, null))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    appManager.applyBackgroundRestriction(targetPackages, null);
                }
            });
            restrictionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void showSleepModeAppsDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Sleep Mode Apps")
                .setView(dialogView);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (d, w) -> d.dismiss());
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (d, w) -> {});

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
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAdapter.getFilter().filter(s);
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });

            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (d, w) -> {
                appManager.saveSleepModeApps(filterAdapter.getSelectedPackages());
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }
        binding.textRamThreshold.setText(getString(R.string.settings_ram_threshold_summary, threshold));
    }

    private void showRamThresholdDialog() {
        int current = sharedPreferences.getInt(KEY_RAM_THRESHOLD, DEFAULT_RAM_THRESHOLD_PERCENT);
        int selected = 1;
        for (int i = 0; i < RAM_THRESHOLD_VALUES.length; i++) {
            if (RAM_THRESHOLD_VALUES[i] == current) {
                selected = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Select RAM Threshold")
                .setSingleChoiceItems(getResources().getStringArray(R.array.settings_ram_threshold_labels), selected,
                        (dialog, which) -> {
                    sharedPreferences.edit().putInt(KEY_RAM_THRESHOLD, RAM_THRESHOLD_VALUES[which]).apply();
                    updateRamThresholdText(RAM_THRESHOLD_VALUES[which]);
                    dialog.dismiss();
                })
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL: " + url, e);
            Toast.makeText(this, R.string.url_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        binding = null;
    }

    private void setupFilterListeners(View dialogView, FilterAppsAdapter adapter) {
        CheckBox chkSystem = dialogView.findViewById(R.id.filter_chk_system);
        CheckBox chkUser = dialogView.findViewById(R.id.filter_chk_user);
        CheckBox chkRunning = dialogView.findViewById(R.id.filter_chk_running);
        android.widget.TextView btnClear = dialogView.findViewById(R.id.filter_btn_clear);

        android.widget.CompoundButton.OnCheckedChangeListener listener = (buttonView, isChecked) -> {
            adapter.setFilters(chkSystem.isChecked(), chkUser.isChecked(), chkRunning.isChecked());
        };

        chkSystem.setOnCheckedChangeListener(listener);
        chkUser.setOnCheckedChangeListener(listener);
        chkRunning.setOnCheckedChangeListener(listener);
        
        btnClear.setOnClickListener(v -> adapter.clearSelection());
    }

    private void showBackupRestoreDialog() {
        String[] options = { "Backup Settings", "Restore Settings" };
        new AlertDialog.Builder(this)
                .setTitle("Backup & Restore")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        createBackupLauncher.launch("appzuku_backup.json");
                    } else {
                        restoreBackupLauncher.launch(new String[]{"application/json"});
                    }
                })
                .show();
    }

    private void exportBackup(Uri uri) {
        executor.execute(() -> {
            String json = backupManager.createBackupJson();
            if (json == null) {
                handler.post(() -> Toast.makeText(this, "Failed to create backup data", Toast.LENGTH_SHORT).show());
                return;
            }

            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    handler.post(() -> Toast.makeText(this, "Backup saved successfully", Toast.LENGTH_SHORT).show());
                } else {
                    handler.post(() -> Toast.makeText(this, "Failed to write to file", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                handler.post(() -> Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void importBackup(Uri uri) {
        executor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }

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
                                Toast.makeText(
                                        this,
                                        "Restore saved settings. Grant Shizuku/Root to reapply background restrictions.",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, "Restore successful", Toast.LENGTH_SHORT).show();
                            }
                        };

                        if (appManager.canApplyBackgroundRestrictionNow()) {
                            appManager.applyBackgroundRestriction(restoredRestrictedApps, finishRestore);
                        } else {
                            finishRestore.run();
                        }
                    } else {
                        Toast.makeText(this, "Restore failed: Invalid data", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                handler.post(() -> Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showBackgroundRestrictionLogDialog() {
        SettingsListContent content = createSettingsListContent("No background restriction events logged yet.", false);
        SettingsSurfaceAdapter adapter = new SettingsSurfaceAdapter();
        content.listView.setAdapter(adapter);
        content.listView.setEmptyView(content.emptyView);
        content.listView.setOnItemClickListener((parent, view, position, id) -> {
            SettingsSurfaceRow row = adapter.getItem(position);
            if (row != null && row.packageName != null && row.packageName.contains(".")) {
                openAppInfo(row.packageName);
            }
        });
        content.loading.setVisibility(View.VISIBLE);
        content.listView.setVisibility(View.GONE);
        content.summaryText.setText("Loading...");

        AlertDialog dialog = createSettingsSurfaceDialog(
                "Restriction Log",
                "Recent background restriction results. Stored in cache and capped automatically.",
                content.rootView);
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Close", (d, w) -> d.dismiss());
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Clear", (d, w) -> {
        });
        dialog.show();
        styleDialogButtons(dialog);

        Runnable reloadLog = () -> executor.execute(() -> {
            List<SettingsSurfaceRow> rows = buildRestrictionLogRows(BackgroundRestrictionLog.readEntries(this));
            String summary = String.format(Locale.US, "Recent events: %d entries | Cache-backed and capped at 200",
                    rows.size());
            handler.post(() -> {
                adapter.setItems(rows);
                content.summaryText.setText(summary);
                content.loading.setVisibility(View.GONE);
                content.listView.setVisibility(View.VISIBLE);
                content.emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
        reloadLog.run();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            appManager.clearBackgroundRestrictionLog();
            reloadLog.run();
            Toast.makeText(this, "Restriction log cleared", Toast.LENGTH_SHORT).show();
        });
    }

    private AlertDialog createSettingsSurfaceDialog(String title, String subtitle, View contentView) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings_surface, null);
        TextView subtitleView = dialogView.findViewById(R.id.dialog_surface_subtitle);
        FrameLayout contentContainer = dialogView.findViewById(R.id.dialog_surface_content);
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.trim().isEmpty() ? View.GONE : View.VISIBLE);
        contentContainer.addView(contentView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(dialogView)
                .create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        return dialog;
    }

    private SettingsListContent createSettingsListContent(String emptyText, boolean showFilter) {
        View contentView = getLayoutInflater().inflate(R.layout.dialog_top_offenders, null);
        Spinner filterSpinner = contentView.findViewById(R.id.top_offenders_filter);
        TextView summaryText = contentView.findViewById(R.id.top_offenders_summary);
        ProgressBar loading = contentView.findViewById(R.id.top_offenders_loading);
        ListView listView = contentView.findViewById(R.id.top_offenders_list);
        TextView emptyView = contentView.findViewById(R.id.top_offenders_empty);
        filterSpinner.setVisibility(showFilter ? View.VISIBLE : View.GONE);
        emptyView.setText(emptyText);
        return new SettingsListContent(contentView, filterSpinner, summaryText, loading, listView, emptyView);
    }

    private List<SettingsSurfaceRow> buildTopOffenderRows(List<TopOffender> offenders) {
        List<SettingsSurfaceRow> rows = new ArrayList<>();
        for (int i = 0; i < offenders.size(); i++) {
            TopOffender offender = offenders.get(i);
            rows.add(new SettingsSurfaceRow(
                    "#" + (i + 1),
                    offender.appName,
                    offender.packageName,
                    String.format(Locale.US,
                            "Killed: %dx | Relaunched: %dx | Recovered: %s",
                            offender.killCount,
                            offender.relaunchCount,
                            formatRecoveredSize(offender.recoveredKb)),
                    "Score " + formatScore(offender.score),
                    offender.packageName));
        }
        return rows;
    }

    private List<SettingsSurfaceRow> buildKillHistoryRows(List<KillHistoryEntry> historyEntries) {
        List<SettingsSurfaceRow> rows = new ArrayList<>();
        for (int i = 0; i < historyEntries.size(); i++) {
            KillHistoryEntry entry = historyEntries.get(i);
            rows.add(new SettingsSurfaceRow(
                    "#" + (i + 1),
                    entry.appName,
                    entry.packageName,
                    entry.detail,
                    entry.badge,
                    entry.packageName));
        }
        return rows;
    }

    private List<SettingsSurfaceRow> buildRestrictionLogRows(List<BackgroundRestrictionLog.LogEntry> logEntries) {
        List<SettingsSurfaceRow> rows = new ArrayList<>();
        for (int i = 0; i < logEntries.size(); i++) {
            BackgroundRestrictionLog.LogEntry entry = logEntries.get(i);
            String title = entry.packageName == null || entry.packageName.equals("-")
                    ? humanizeLogAction(entry.action)
                    : entry.packageName;
            String subtitle = entry.timestamp;
            if (entry.action != null && !entry.action.trim().isEmpty()) {
                subtitle = subtitle.isEmpty()
                        ? humanizeLogAction(entry.action)
                        : subtitle + " | " + humanizeLogAction(entry.action);
            }
            rows.add(new SettingsSurfaceRow(
                    "#" + (i + 1),
                    title,
                    subtitle,
                    entry.detail,
                    humanizeLogOutcome(entry.outcome),
                    entry.packageName));
        }
        return rows;
    }

    private void bindOptionalText(TextView view, String text) {
        if (text == null || text.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(text);
    }

    private String humanizeLogAction(String action) {
        if (action == null || action.trim().isEmpty()) {
            return "Event";
        }
        String normalized = action.trim().replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String humanizeLogOutcome(String outcome) {
        if (outcome == null || outcome.trim().isEmpty()) {
            return "";
        }
        String normalized = outcome.trim().replace('-', ' ').replace('_', ' ');
        return normalized.toUpperCase(Locale.US);
    }

    private void styleDialogButtons(AlertDialog dialog) {
        int color = ContextCompat.getColor(this, R.color.dialog_button_text);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(color);
        }
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
        }
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
            startAutomationService();
            AutoKillWorker.schedule(this);
        } else {
            stopService(new Intent(this, ShappkyService.class));
            AutoKillWorker.cancel(this);
        }
    }
}
