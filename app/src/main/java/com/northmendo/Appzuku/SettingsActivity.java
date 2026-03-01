package com.northmendo.Appzuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
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

import com.northmendo.Appzuku.databinding.ActivitySettingsBinding;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.northmendo.Appzuku.PreferenceKeys.*;
import static com.northmendo.Appzuku.AppConstants.*;

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

        // Set version text
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            binding.textVersion.setText("Appzuku v" + versionName);
        } catch (Exception e) {
            binding.textVersion.setText("Appzuku");
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

        // Autostart Prevention
        binding.layoutAutostart.setOnClickListener(v -> showAutostartDialog());

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

        // Backup & Restore
        binding.layoutBackupRestore.setOnClickListener(v -> showBackupRestoreDialog());

        // GitHub
        binding.layoutGithub.setOnClickListener(v -> openUrl("https://github.com/northmendo/Appzuku"));

        // Check for Updates
        binding.layoutCheckUpdates.setOnClickListener(v -> openUrl("https://github.com/northmendo/Appzuku/releases"));

        // Donate
        binding.layoutDonate
                .setOnClickListener(v -> openUrl("https://www.paypal.com/donate/?hosted_button_id=DDJRFUXHSHRVN"));

        updateKillModeVisibility();
    }

    private void updateKillModeVisibility() {
        int mode = appManager.getKillMode();
        binding.textKillMode.setText(mode == 0 ? "Whitelist (Default)" : "Blacklist");
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
            // Get stats for last 12 hours
            long twelveHoursAgo = System.currentTimeMillis() - STATS_HISTORY_DURATION_MS;
            com.northmendo.Appzuku.db.AppStatsDao appStatsDao = com.northmendo.Appzuku.db.AppDatabase
                    .getInstance(this).appStatsDao();
            java.util.List<com.northmendo.Appzuku.db.AppStats> statsList = appStatsDao.getAllStatsSince(twelveHoursAgo);

            final List<String> highRelaunchPackages = new ArrayList<>();
            StringBuilder sb = new StringBuilder();

            if (statsList.isEmpty()) {
                sb.append("No activity in the last 12 hours.");
            } else {
                java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("h:mm a",
                        java.util.Locale.getDefault());

                for (com.northmendo.Appzuku.db.AppStats stats : statsList) {
                    if (stats.killCount > 0 || stats.relaunchCount > 0) {
                        String displayName = resolveStatsAppName(stats, appStatsDao);
                        sb.append("- ").append(displayName).append("\n");

                        // Kill stats
                        if (stats.killCount > 0) {
                            sb.append("   Killed: ").append(stats.killCount).append("x");
                            if (stats.lastKillTime > 0) {
                                sb.append(" (last: ").append(timeFormat.format(new java.util.Date(stats.lastKillTime)))
                                        .append(")");
                            }
                            sb.append("\n");
                        }

                        // Relaunch stats
                        if (stats.relaunchCount > 0) {
                            sb.append("   Relaunched: ").append(stats.relaunchCount).append("x");
                            if (stats.lastRelaunchTime > 0) {
                                sb.append(" (last: ")
                                        .append(timeFormat.format(new java.util.Date(stats.lastRelaunchTime)))
                                        .append(")");
                            }
                            sb.append("\n");

                            if (stats.relaunchCount > RELAUNCH_GREEDY_THRESHOLD) {
                                highRelaunchPackages.add(stats.packageName);
                            }
                        }
                        sb.append("\n");
                    }
                }
            }

            String message = sb.length() > 0 ? sb.toString().trim() : "No activity in the last 12 hours.";
            handler.post(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                        .setTitle("Kill History (Last 12h)")
                        .setMessage(message)
                        .setPositiveButton("OK", null);

                if (!highRelaunchPackages.isEmpty()) {
                    builder.setNeutralButton("Disable Autostart for Greedy Apps", (d, w) -> {
                        Set<String> currentDisabled = appManager.getAutostartDisabledApps();
                        currentDisabled.addAll(highRelaunchPackages);
                        appManager.saveAutostartDisabledApps(currentDisabled);
                        appManager.loadAutostartApps(allApps -> {
                            appManager.applyAutostartPrevention(allApps, currentDisabled);
                        });
                    });
                }
                builder.show();
            });
        });
    }

    private void showTopOffendersDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_top_offenders, null);
        Spinner filterSpinner = dialogView.findViewById(R.id.top_offenders_filter);
        TextView summaryText = dialogView.findViewById(R.id.top_offenders_summary);
        ProgressBar loading = dialogView.findViewById(R.id.top_offenders_loading);
        ListView listView = dialogView.findViewById(R.id.top_offenders_list);
        TextView emptyView = dialogView.findViewById(R.id.top_offenders_empty);

        TopOffendersAdapter offendersAdapter = new TopOffendersAdapter();
        listView.setAdapter(offendersAdapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            TopOffender offender = offendersAdapter.getItem(position);
            if (offender != null) {
                openAppInfo(offender.packageName);
            }
        });

        ArrayAdapter<String> filterAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, TOP_OFFENDER_FILTER_LABELS);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(filterAdapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Top Offenders")
                .setView(dialogView)
                .setNegativeButton("Close", null)
                .create();
        dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

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

    private void loadTopOffenders(int filterIndex, TopOffendersAdapter adapter, TextView summaryText,
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
            com.northmendo.Appzuku.db.AppStatsDao appStatsDao =
                    com.northmendo.Appzuku.db.AppDatabase.getInstance(this).appStatsDao();
            List<com.northmendo.Appzuku.db.AppStats> stats;
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
                adapter.setItems(offenders);
                summaryText.setText(summary);
                loading.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(offenders.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private List<TopOffender> buildTopOffenders(List<com.northmendo.Appzuku.db.AppStats> statsList,
                                                 com.northmendo.Appzuku.db.AppStatsDao appStatsDao) {
        List<TopOffender> offenders = new ArrayList<>();
        for (com.northmendo.Appzuku.db.AppStats stats : statsList) {
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

    private String resolveStatsAppName(com.northmendo.Appzuku.db.AppStats stats,
                                       com.northmendo.Appzuku.db.AppStatsDao appStatsDao) {
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

    private class TopOffendersAdapter extends BaseAdapter {
        private final List<TopOffender> items = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(SettingsActivity.this);

        void setItems(List<TopOffender> newItems) {
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
        public TopOffender getItem(int position) {
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

            TopOffender item = getItem(position);
            if (item == null) {
                return view;
            }

            TextView rankView = view.findViewById(R.id.offender_rank);
            TextView nameView = view.findViewById(R.id.offender_name);
            TextView packageView = view.findViewById(R.id.offender_package);
            TextView metricsView = view.findViewById(R.id.offender_metrics);
            TextView scoreView = view.findViewById(R.id.offender_score);

            rankView.setText("#" + (position + 1));
            nameView.setText(item.appName);
            packageView.setText(item.packageName);
            metricsView.setText(String.format(Locale.US,
                    "Killed: %dx | Relaunched: %dx | Recovered: %s",
                    item.killCount,
                    item.relaunchCount,
                    formatRecoveredSize(item.recoveredKb)));
            scoreView.setText("Score " + formatScore(item.score));

            return view;
        }
    }

    private void updateThemeText(int themeValue) {
        for (int i = 0; i < THEME_VALUES.length; i++) {
            if (THEME_VALUES[i] == themeValue) {
                binding.textTheme.setText(THEME_LABELS[i]);
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
        builder.setSingleChoiceItems(THEME_LABELS, selectedIndex, (dialog, which) -> {
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
        for (int i = 0; i < KILL_INTERVALS_MS.length; i++) {
            if (KILL_INTERVALS_MS[i] == intervalMs) {
                binding.textKillInterval.setText(KILL_INTERVAL_LABELS[i]);
                return;
            }
        }
        binding.textKillInterval.setText("Every " + (intervalMs / 1000) + " seconds");
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
        builder.setSingleChoiceItems(KILL_INTERVAL_LABELS, selectedIndex, (dialog, which) -> {
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

    private void showAutostartDialog() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_filter, null);
        ListView listView = dialogView.findViewById(R.id.filter_list_view);
        ProgressBar progressBar = dialogView.findViewById(R.id.filter_loading_progress);
        EditText searchBox = dialogView.findViewById(R.id.filter_search);
        LinearLayout filterOptions = dialogView.findViewById(R.id.filter_options_container);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Autostart Prevention")
                .setView(dialogView);

        AlertDialog autostartDialog = builder.create();
        autostartDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));

        autostartDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> dialog.dismiss());
        autostartDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
        });

        progressBar.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        autostartDialog.show();

        autostartDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        autostartDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));

        appManager.loadAutostartApps(allApps -> {
            Set<String> disabledApps = appManager.getAutostartDisabledApps();
            // Sync the model state for consistency, though UI uses the Set
            for (AppModel app : allApps) {
                if (disabledApps.contains(app.getPackageName())) {
                    app.setAutostartBlocked(true);
                }
            }

            FilterAppsAdapter filterAdapter = new FilterAppsAdapter(this, allApps, disabledApps);
            listView.setAdapter(filterAdapter);
            listView.setOnItemClickListener(null);

            progressBar.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
            searchBox.setVisibility(View.VISIBLE);
            filterOptions.setVisibility(View.VISIBLE);
            
            setupFilterListeners(dialogView, filterAdapter);
            
            appManager.updateRunningState(allApps, () -> {
                if (!autostartDialog.isShowing()) return;
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

            autostartDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Save", (dialog, which) -> {
                Set<String> packagesToDisable = filterAdapter.getSelectedPackages();

                // Count system apps in selection
                int systemAppCount = 0;
                for (AppModel app : allApps) {
                    if (packagesToDisable.contains(app.getPackageName()) && app.isSystemApp()) {
                        systemAppCount++;
                    }
                }

                // Show warning if system apps are selected
                if (systemAppCount > 0) {
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("âš ï¸ System Apps Selected")
                            .setMessage("You have selected " + systemAppCount
                                    + " system app(s) to block from auto-starting.\n\nBlocking system apps may cause device instability or prevent critical functions from working properly.\n\nDo you want to continue?")
                            .setPositiveButton("Yes, Apply", (d2, w2) -> {
                                appManager.saveAutostartDisabledApps(packagesToDisable);
                                appManager.applyAutostartPrevention(allApps, packagesToDisable);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    appManager.saveAutostartDisabledApps(packagesToDisable);
                    appManager.applyAutostartPrevention(allApps, packagesToDisable);
                }
            });
            autostartDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.dialog_button_text));
        });
    }

    private void updateRamThresholdText(int threshold) {
        binding.textRamThreshold.setText("Kill only when RAM usage > " + threshold + "%");
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
                .setSingleChoiceItems(RAM_THRESHOLD_LABELS, selected, (dialog, which) -> {
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
                        applyAutomationStateFromPreferences();
                        loadSettings();
                        updateKillModeVisibility();
                        Toast.makeText(this, "Restore successful", Toast.LENGTH_SHORT).show();
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
