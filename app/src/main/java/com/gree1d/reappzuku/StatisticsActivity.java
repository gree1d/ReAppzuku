package com.gree1d.reappzuku;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.gree1d.reappzuku.databinding.ActivityStatisticsBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.gree1d.reappzuku.AppConstants.*;

public class StatisticsActivity extends BaseActivity {
    private static final String TAG = "StatisticsActivity";
    private static final int TOP_OFFENDERS_LIMIT = 50;
    private static final long[] TOP_OFFENDER_FILTER_WINDOWS_MS = {
            STATS_HISTORY_DURATION_MS,
            24 * 60 * 60 * 1000L,
            7 * 24 * 60 * 60 * 1000L,
            -1L
    };

    private String[] topOffenderFilterLabels;

    private ActivityStatisticsBinding binding;
    private ShellManager shellManager;
    private BackgroundAppManager appManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStatisticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        topOffenderFilterLabels = getResources().getStringArray(R.array.settings_top_offender_filter_labels);

        shellManager = new ShellManager(this.getApplicationContext(), handler, executor);
        appManager = new BackgroundAppManager(this.getApplicationContext(), handler, executor, shellManager);

        setupToolbar();
        setupListeners();
        setupBottomNavigation();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        int accent = sharedPreferences.getInt(PreferenceKeys.KEY_ACCENT, ACCENT_SYSTEM);
        if (accent == ACCENT_SYSTEM) {
            binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_navy));
        }
    }

    private void setupBottomNavigation() {
        binding.bottomNavigation.setSelectedItemId(R.id.nav_statistics);
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_statistics) {
                return true;
            } else if (id == R.id.nav_main) {
                finish();
                return false;
            } else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                finish();
                return false;
            }
            return false;
        });
    }

    private void setupListeners() {
        binding.layoutStats.setOnClickListener(v -> showStatsDialog());
        binding.layoutTopOffenders.setOnClickListener(v -> showTopOffendersDialog());
        binding.layoutRestrictionLog.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutRestrictionLog.setOnClickListener(v -> showBackgroundRestrictionLogDialog());
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
            java.text.DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(this);

            for (com.gree1d.reappzuku.db.AppStats stats : statsList) {
                if (stats == null || stats.packageName == null) continue;
                if (stats.killCount <= 0 && stats.relaunchCount <= 0) continue;

                if (stats.relaunchCount > RELAUNCH_GREEDY_THRESHOLD) {
                    highRelaunchPackages.add(stats.packageName);
                }

                List<String> detailParts = new ArrayList<>();
                if (stats.killCount > 0) {
                    String killDetail = getString(R.string.stats_kill_detail, stats.killCount);
                    if (stats.lastKillTime > 0) {
                        killDetail += getString(R.string.stats_last_kill_time,
                                timeFormat.format(new java.util.Date(stats.lastKillTime)));
                    }
                    detailParts.add(killDetail);
                }
                if (stats.relaunchCount > 0) {
                    String relaunchDetail = getString(R.string.stats_relaunch_detail, stats.relaunchCount);
                    if (stats.lastRelaunchTime > 0) {
                        relaunchDetail += getString(R.string.stats_last_relaunch_time,
                                timeFormat.format(new java.util.Date(stats.lastRelaunchTime)));
                    }
                    detailParts.add(relaunchDetail);
                }
                if (stats.totalRecoveredKb > 0) {
                    detailParts.add(getString(R.string.stats_recovered_ram, formatRecoveredSize(stats.totalRecoveredKb)));
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
            String summary = getString(R.string.stats_summary_12h,
                    rows.size(), totalKills, totalRelaunches, formatRecoveredSize(totalRecoveredKb));

            handler.post(() -> {
                SettingsListContent content = createSettingsListContent(
                        getString(R.string.stats_no_activity_12h), false);
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

                AlertDialog dialog = createSettingsSurfaceDialog(
                        getString(R.string.settings_kill_history_title),
                        getString(R.string.stats_dialog_subtitle),
                        content.rootView);
                dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_close), (d, w) -> d.dismiss());
                if (appManager.supportsBackgroundRestriction() && !highRelaunchPackages.isEmpty()) {
                    dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.stats_restrict_high_relaunch), (d, w) -> {
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
        SettingsListContent content = createSettingsListContent(
                getString(R.string.stats_top_offenders_empty), true);
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
                this, android.R.layout.simple_spinner_item, topOffenderFilterLabels);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(filterAdapter);

        AlertDialog dialog = createSettingsSurfaceDialog(
                getString(R.string.settings_top_offenders_title),
                getString(R.string.stats_top_offenders_dialog_subtitle),
                content.rootView);
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_close), (d, w) -> d.dismiss());
        dialog.show();
        styleDialogButtons(dialog);

        filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadTopOffenders(position, offendersAdapter, summaryText, loading, listView, emptyView);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadTopOffenders(int filterIndex, SettingsSurfaceAdapter adapter, TextView summaryText,
                                  ProgressBar loading, ListView listView, TextView emptyView) {
        if (filterIndex < 0 || filterIndex >= TOP_OFFENDER_FILTER_WINDOWS_MS.length) filterIndex = 0;

        final int selectedFilterIndex = filterIndex;
        loading.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        summaryText.setText(getString(R.string.stats_loading));

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

            String summary = getString(R.string.stats_top_offenders_summary,
                    topOffenderFilterLabels[selectedFilterIndex],
                    offenders.size(), totalKills, totalRelaunches,
                    formatRecoveredSize(totalRecoveredKb));

            handler.post(() -> {
                if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return;
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
            if (stats == null || stats.packageName == null) continue;
            if (stats.killCount <= 0 && stats.relaunchCount <= 0 && stats.totalRecoveredKb <= 0) continue;

            String appName = resolveStatsAppName(stats, appStatsDao);
            double score = (stats.killCount * 1.0) + (stats.relaunchCount * 2.0) + (stats.totalRecoveredKb / 102400.0);
            offenders.add(new TopOffender(appName, stats.packageName, stats.killCount,
                    stats.relaunchCount, stats.totalRecoveredKb, score));
        }

        Collections.sort(offenders, (a, b) -> {
            int c = Double.compare(b.score, a.score);
            if (c != 0) return c;
            c = Integer.compare(b.killCount, a.killCount);
            if (c != 0) return c;
            c = Integer.compare(b.relaunchCount, a.relaunchCount);
            if (c != 0) return c;
            return Long.compare(b.recoveredKb, a.recoveredKb);
        });

        return offenders.size() > TOP_OFFENDERS_LIMIT
                ? new ArrayList<>(offenders.subList(0, TOP_OFFENDERS_LIMIT))
                : offenders;
    }

    private void showBackgroundRestrictionLogDialog() {
        SettingsListContent content = createSettingsListContent(
                getString(R.string.settings_restriction_log_empty), false);
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
        content.summaryText.setText(getString(R.string.stats_loading));

        AlertDialog dialog = createSettingsSurfaceDialog(
                getString(R.string.settings_restriction_log_title),
                getString(R.string.settings_restriction_log_dialog_subtitle),
                content.rootView);
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.dialog_close), (d, w) -> d.dismiss());
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.settings_restriction_log_clear), (d, w) -> {});
        dialog.show();
        styleDialogButtons(dialog);

        Runnable reloadLog = () -> executor.execute(() -> {
            List<SettingsSurfaceRow> rows = buildRestrictionLogRows(BackgroundRestrictionLog.readEntries(this));
            String summary = getString(R.string.settings_restriction_log_summary, rows.size());
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
            Toast.makeText(this, getString(R.string.settings_restriction_log_cleared), Toast.LENGTH_SHORT).show();
        });
    }

    private String resolveStatsAppName(com.gree1d.reappzuku.db.AppStats stats,
                                       com.gree1d.reappzuku.db.AppStatsDao appStatsDao) {
        if (stats.appName != null && !stats.appName.trim().isEmpty()) return stats.appName;
        try {
            android.content.pm.ApplicationInfo appInfo = getPackageManager().getApplicationInfo(stats.packageName, 0);
            CharSequence label = getPackageManager().getApplicationLabel(appInfo);
            if (label != null) {
                String name = label.toString();
                stats.appName = name;
                appStatsDao.updateAppName(stats.packageName, name);
                return name;
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        return stats.packageName;
    }

    private String formatRecoveredSize(long kb) {
        if (kb < 1024) return kb + " KB";
        if (kb < 1024 * 1024) return String.format(Locale.US, "%.2f MB", kb / 1024f);
        return String.format(Locale.US, "%.2f GB", kb / (1024f * 1024f));
    }

    private void openAppInfo(String packageName) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.settings_open_app_info_error), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Inner helpers (moved from SettingsActivity) ----

    private static class TopOffender {
        final String appName, packageName;
        final int killCount, relaunchCount;
        final long recoveredKb;
        final double score;
        TopOffender(String appName, String packageName, int killCount, int relaunchCount, long recoveredKb, double score) {
            this.appName = appName; this.packageName = packageName;
            this.killCount = killCount; this.relaunchCount = relaunchCount;
            this.recoveredKb = recoveredKb; this.score = score;
        }
    }

    private static class KillHistoryEntry {
        final String appName, packageName, detail, badge;
        final long lastEventTime;
        KillHistoryEntry(String appName, String packageName, String detail, String badge, long lastEventTime) {
            this.appName = appName; this.packageName = packageName;
            this.detail = detail; this.badge = badge; this.lastEventTime = lastEventTime;
        }
    }

    static class SettingsSurfaceRow {
        final String leadingText, title, subtitle, detail, badge, packageName;
        SettingsSurfaceRow(String leadingText, String title, String subtitle, String detail, String badge, String packageName) {
            this.leadingText = leadingText; this.title = title; this.subtitle = subtitle;
            this.detail = detail; this.badge = badge; this.packageName = packageName;
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
            this.rootView = rootView; this.filterSpinner = filterSpinner;
            this.summaryText = summaryText; this.loading = loading;
            this.listView = listView; this.emptyView = emptyView;
        }
    }

    private class SettingsSurfaceAdapter extends BaseAdapter {
        private final List<SettingsSurfaceRow> items = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(StatisticsActivity.this);

        void setItems(List<SettingsSurfaceRow> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public SettingsSurfaceRow getItem(int pos) { return (pos >= 0 && pos < items.size()) ? items.get(pos) : null; }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View view = convertView != null ? convertView : inflater.inflate(R.layout.item_top_offender, parent, false);
            SettingsSurfaceRow item = getItem(position);
            if (item == null) return view;
            bindOptionalText((TextView) view.findViewById(R.id.offender_rank), item.leadingText);
            ((TextView) view.findViewById(R.id.offender_name)).setText(item.title);
            bindOptionalText((TextView) view.findViewById(R.id.offender_package), item.subtitle);
            bindOptionalText((TextView) view.findViewById(R.id.offender_metrics), item.detail);
            bindOptionalText((TextView) view.findViewById(R.id.offender_score), item.badge);
            return view;
        }
    }

    private void bindOptionalText(TextView view, String text) {
        if (text == null || text.trim().isEmpty()) { view.setVisibility(View.GONE); view.setText(""); return; }
        view.setVisibility(View.VISIBLE);
        view.setText(text);
    }

    private AlertDialog createSettingsSurfaceDialog(String title, String subtitle, View contentView) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_settings_surface, null);
        TextView subtitleView = dialogView.findViewById(R.id.dialog_surface_subtitle);
        FrameLayout contentContainer = dialogView.findViewById(R.id.dialog_surface_content);
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.trim().isEmpty() ? View.GONE : View.VISIBLE);
        contentContainer.addView(contentView);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(dialogView).create();
        dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this, R.color.background_primary)));
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
            TopOffender o = offenders.get(i);
            rows.add(new SettingsSurfaceRow("#" + (i + 1), o.appName, o.packageName,
                    getString(R.string.stats_offender_metrics, o.killCount, o.relaunchCount, formatRecoveredSize(o.recoveredKb)),
                    getString(R.string.stats_offender_score, String.format(Locale.US, "%.1f", o.score)),
                    o.packageName));
        }
        return rows;
    }

    private List<SettingsSurfaceRow> buildKillHistoryRows(List<KillHistoryEntry> entries) {
        List<SettingsSurfaceRow> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            KillHistoryEntry e = entries.get(i);
            rows.add(new SettingsSurfaceRow("#" + (i + 1), e.appName, e.packageName, e.detail, e.badge, e.packageName));
        }
        return rows;
    }

    private List<SettingsSurfaceRow> buildRestrictionLogRows(List<BackgroundRestrictionLog.LogEntry> logEntries) {
        List<SettingsSurfaceRow> rows = new ArrayList<>();
        for (int i = 0; i < logEntries.size(); i++) {
            BackgroundRestrictionLog.LogEntry entry = logEntries.get(i);
            String title = (entry.packageName == null || entry.packageName.equals("-"))
                    ? humanizeLogAction(entry.action) : entry.packageName;
            String subtitle = entry.timestamp;
            if (entry.action != null && !entry.action.trim().isEmpty()) {
                subtitle = subtitle.isEmpty() ? humanizeLogAction(entry.action)
                        : subtitle + " | " + humanizeLogAction(entry.action);
            }
            String detail = humanizeLogOutcome(entry.outcome);
            if (entry.detail != null && !entry.detail.trim().isEmpty()) {
                detail = detail.isEmpty() ? entry.detail : detail + "  |  " + entry.detail;
            }
            rows.add(new SettingsSurfaceRow("#" + (i + 1), title, subtitle, detail,
                    resolveRestrictionTypeBadge(entry.action), entry.packageName));
        }
        return rows;
    }

    private String resolveRestrictionTypeBadge(String action) {
        if (action == null) return "";
        switch (action.trim().toLowerCase()) {
            case "restrict-hard": case "reapply-hard": return getString(R.string.restriction_badge_hard);
            case "restrict-soft": case "reapply-soft": case "restrict": return getString(R.string.restriction_badge_soft);
            case "allow": return getString(R.string.restriction_badge_removed);
            case "reapply": return getString(R.string.restriction_badge_retry);
            default: return "";
        }
    }

    private String humanizeLogAction(String action) {
        if (action == null || action.trim().isEmpty()) return getString(R.string.log_action_event);
        String n = action.trim().replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private String humanizeLogOutcome(String outcome) {
        if (outcome == null || outcome.trim().isEmpty()) return "";
        switch (outcome.trim().toLowerCase()) {
            case "ok": return getString(R.string.log_outcome_ok);
            case "verified": return getString(R.string.log_outcome_verified);
            case "failed": return getString(R.string.log_outcome_failed);
            case "skipped": return getString(R.string.log_outcome_skipped);
            case "verify-failed": return getString(R.string.log_outcome_verify_failed);
            case "verify-unavailable": return getString(R.string.log_outcome_verify_unavailable);
            case "battery-whitelist-removed": return getString(R.string.log_outcome_battery_whitelist_removed);
            case "battery-whitelist-restored": return getString(R.string.log_outcome_battery_whitelist_restored);
            default:
                String n = outcome.trim().replace('-', ' ').replace('_', ' ');
                return n.toUpperCase(Locale.US);
        }
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        binding = null;
    }
}
