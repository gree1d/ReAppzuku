package com.gree1d.reappzuku;

import android.content.Intent;
import android.graphics.Color;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.gree1d.reappzuku.databinding.ActivityStatisticsBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.gree1d.reappzuku.PreferenceKeys.*;
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

    private static final int[] CHART_PERIODS_HOURS = { 2, 6, 12, 24 };

    /**
     * Real battery capacity in mAh, read from /sys/class/power_supply/battery/charge_full_design.
     * Falls back to charge_full, then to 4000 mAh if neither is readable.
     * Initialized once in onCreate().
     */
    private double batteryCapacityMah = 4000.0;

    /** Chart indices */
    private static final int CHART_BATTERY = 0;
    private static final int CHART_CPU     = 1;
    private static final int CHART_RAM     = 2;
    private static final int CHART_COUNT   = 3;

    /**
     * Multicolor palette for pie slices. Designed to look distinct at all 3 chart accent bases.
     * Last slot is always grey for "Others".
     */
    private static final int[] SLICE_PALETTE = {
        0xFFE53935, // red
        0xFF1E88E5, // blue
        0xFF43A047, // green
        0xFFFB8C00, // orange
        0xFF8E24AA, // purple
        0xFF00ACC1, // cyan
        0xFFFFB300, // amber
        0xFF00897B, // teal
        0xFFF06292, // pink
        0xFF6D4C41, // brown
        0xFF3949AB, // indigo
        0xFF7CB342, // light green
        0xFFBDBDBD, // grey (Others)
    };

    private String[] topOffenderFilterLabels;
    private String[] chartPeriodLabels;
    private int selectedPeriodIdx = 1;
    private int currentChartIdx = CHART_BATTERY;

    // Current period data — kept to reuse when switching charts
    private List<BatteryStatsManager.AppResourceStats> currentSorted = null;
    private double currentTotalHours = 0;

    private ActivityStatisticsBinding binding;
    private ShellManager shellManager;
    private BackgroundAppManager appManager;
    private BatteryStatsManager batteryStatsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStatisticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        topOffenderFilterLabels = getResources().getStringArray(R.array.settings_top_offender_filter_labels);
        chartPeriodLabels = getResources().getStringArray(R.array.chart_period_labels);

        shellManager        = new ShellManager(this.getApplicationContext(), handler, executor);
        appManager          = new BackgroundAppManager(this.getApplicationContext(), handler, executor, shellManager);
        batteryStatsManager = new BatteryStatsManager(this.getApplicationContext(), handler, executor, shellManager);

        setupToolbar();
        setupBottomNavigation();
        setupPeriodTabs();
        setupChartPager();
        setupListeners();

        batteryCapacityMah = batteryStatsManager.getBatteryCapacityMah();
        batteryStatsManager.takeSnapshotAsync(() -> loadCharts(CHART_PERIODS_HOURS[selectedPeriodIdx]));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        if (accent == ACCENT_SYSTEM) {
            binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_navy));
        }
        boolean isNewAccent = (accent == ACCENT_APRICOT || accent == ACCENT_SKY ||
                accent == ACCENT_PAPAYA || accent == ACCENT_LAVENDER ||
                accent == ACCENT_MINT || accent == ACCENT_PEACH ||
                accent == ACCENT_POWDER || accent == ACCENT_FOG);
        binding.toolbar.setTitleTextColor(isNewAccent ? Color.BLACK : Color.WHITE);
    }

    private void setupBottomNavigation() {
        binding.bottomNavigation.navIconMain.setSelected(false);
        binding.bottomNavigation.navIconSettings.setSelected(false);
        binding.bottomNavigation.navIconStatistics.setSelected(true);
        binding.bottomNavigation.navBtnMain.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            finish();
        });
        binding.bottomNavigation.navBtnSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        });
        binding.bottomNavigation.navBtnStatistics.setOnClickListener(v -> {});
    }

    private void setupPeriodTabs() {
        com.google.android.material.tabs.TabLayout tabs = binding.tabPeriodSelector;
        for (String label : chartPeriodLabels) tabs.addTab(tabs.newTab().setText(label));
        tabs.selectTab(tabs.getTabAt(selectedPeriodIdx));
        tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                selectedPeriodIdx = tab.getPosition();
                loadCharts(CHART_PERIODS_HOURS[selectedPeriodIdx]);
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });
    }

    private void setupChartPager() {
        binding.btnChartPrev.setOnClickListener(v -> navigateChart(-1));
        binding.btnChartNext.setOnClickListener(v -> navigateChart(+1));
        updateChartPagerUI();
    }

    private void navigateChart(int direction) {
        currentChartIdx = (currentChartIdx + direction + CHART_COUNT) % CHART_COUNT;
        updateChartPagerUI();
        if (currentSorted != null) {
            showActiveChart(currentSorted, currentTotalHours);
        }
    }

    private void updateChartPagerUI() {
        // Title + icon
        switch (currentChartIdx) {
            case CHART_BATTERY:
                binding.tvChartTitle.setText(getString(R.string.chart_title_battery));
                binding.ivChartIcon.setImageResource(R.drawable.ic_battery_chart);
                break;
            case CHART_CPU:
                binding.tvChartTitle.setText(getString(R.string.chart_title_cpu));
                binding.ivChartIcon.setImageResource(R.drawable.ic_cpu);
                break;
            case CHART_RAM:
                binding.tvChartTitle.setText(getString(R.string.chart_title_ram));
                binding.ivChartIcon.setImageResource(R.drawable.ic_ram);
                break;
        }
    }

    private void setupListeners() {
        binding.layoutStats.setOnClickListener(v -> showStatsDialog());
        binding.layoutTopOffenders.setOnClickListener(v -> showTopOffendersDialog());
        binding.layoutRestrictionLog.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutRestrictionLog.setOnClickListener(v -> showBackgroundRestrictionLogDialog());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chart loading
    // ─────────────────────────────────────────────────────────────────────────

    private void loadCharts(int hours) {
        showChartsLoading(true);
        batteryStatsManager.getStatsForPeriodAsync(hours, periodStats -> {
            showChartsLoading(false);
            if (!periodStats.hasData) {
                binding.cardNoData.setVisibility(View.VISIBLE);
                binding.tvNoDataHint.setText(periodStats.dataHint);
                binding.cardChartsPager.setVisibility(View.GONE);
                return;
            }
            binding.cardNoData.setVisibility(View.GONE);
            binding.cardChartsPager.setVisibility(View.VISIBLE);

            List<BatteryStatsManager.AppResourceStats> sorted = periodStats.sorted;
            currentSorted = sorted;
            currentTotalHours = periodStats.actualHours;

            // ── Info cards (period-aware) ──────────────────────────────────

            // Battery saved: sum mAh of all non-self apps killed (approximated as total
            // consumed by background apps) → converted to % of assumed capacity.
            double totalBatteryMah = 0;
            double totalRamMb = 0;
            double totalCpuFraction = 0;
            BatteryStatsManager.AppResourceStats selfStats = null;

            for (BatteryStatsManager.AppResourceStats s : sorted) {
                if (s.isSelf) { selfStats = s; continue; }
                totalBatteryMah  += s.batteryMah;
                totalRamMb       += s.ramMb;
                totalCpuFraction += s.cpuPct;
            }

            // Battery saved tile: show as % of assumed capacity, floored at 0.1
            final double finalBatMah = totalBatteryMah;
            double batPct = (totalBatteryMah / batteryCapacityMah) * 100.0;
            String batText = batPct >= 0.1
                    ? String.format(Locale.US, "%.1f%%", batPct)
                    : getString(R.string.stats_battery_trace);
            binding.infoBatterySavedValue.setText(batText);

            // RAM freed tile: sum of background RAM (already period-aware via PSS snapshots)
            binding.infoRamRecoveredValue.setText(formatRamMb(totalRamMb));

            // CPU reduced tile: sum cpu fraction × 100 → show as %
            String cpuText = String.format(Locale.US, "%.1f%%", totalCpuFraction);
            binding.infoCpuReducedValue.setText(cpuText);

            // ── Self overhead row ─────────────────────────────────────────
            if (selfStats != null) {
                double selfBatPct = (selfStats.batteryMah / batteryCapacityMah) * 100.0;
                binding.tvSelfBat.setText(String.format(Locale.US, "%.1f%%", selfBatPct));
                binding.tvSelfCpu.setText(String.format(Locale.US, "%.1f%%", selfStats.cpuPct));
                binding.tvSelfRam.setText(formatRamMb(selfStats.ramMb));
                binding.layoutSelfOverhead.setVisibility(View.VISIBLE);
            } else {
                binding.layoutSelfOverhead.setVisibility(View.GONE);
            }

            // ── Build all 3 charts ─────────────────────────────────────────
            buildPieChart(binding.chartBattery, binding.layoutBatteryOthers,
                    sorted, ChartMetric.BATTERY);
            buildPieChart(binding.chartCpu, binding.layoutCpuOthers,
                    sorted, ChartMetric.CPU);
            buildPieChart(binding.chartRam, binding.layoutRamOthers,
                    sorted, ChartMetric.RAM);

            // Update total label for current chart
            showActiveChart(sorted, periodStats.actualHours);
        });
    }

    private void showActiveChart(List<BatteryStatsManager.AppResourceStats> sorted, double actualHours) {
        binding.chartBattery.setVisibility(currentChartIdx == CHART_BATTERY ? View.VISIBLE : View.GONE);
        binding.chartCpu.setVisibility(currentChartIdx == CHART_CPU     ? View.VISIBLE : View.GONE);
        binding.chartRam.setVisibility(currentChartIdx == CHART_RAM     ? View.VISIBLE : View.GONE);

        double totalBat = 0, totalCpu = 0, totalRam = 0, peakRam = 0;
        int count = 0;
        for (BatteryStatsManager.AppResourceStats s : sorted) {
            totalBat += s.batteryMah;
            totalCpu += s.cpuPct;
            totalRam += s.ramMb;
            if (s.peakRamMb > peakRam) peakRam = s.peakRamMb;
            count++;
        }

        switch (currentChartIdx) {
            case CHART_BATTERY:
                binding.tvChartTotal.setText(
                        getString(R.string.stats_chart_total_battery, totalBat));
                break;
            case CHART_CPU:
                binding.tvChartTotal.setText(
                        String.format(Locale.US, "%.1f%%", totalCpu));
                break;
            case CHART_RAM:
                // Show both average and peak PSS
                binding.tvChartTotal.setText(String.format(Locale.US,
                        "Ср. %s / Пик %s",
                        formatRamMb(totalRam / Math.max(count, 1)),
                        formatRamMb(peakRam)));
                break;
        }
    }

    private void showChartsLoading(boolean loading) {
        binding.layoutChartsLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pie chart builder
    // ─────────────────────────────────────────────────────────────────────────

    private enum ChartMetric { BATTERY, CPU, RAM }

    private void buildPieChart(PieChart chart,
                                android.view.ViewGroup othersContainer,
                                List<BatteryStatsManager.AppResourceStats> sorted,
                                ChartMetric metric) {
        double total = 0;
        for (BatteryStatsManager.AppResourceStats s : sorted) total += metricValue(s, metric);
        if (total <= 0) return;

        List<BatteryStatsManager.AppResourceStats> byCurrent = new ArrayList<>(sorted);
        byCurrent.sort((a, b) -> Double.compare(metricValue(b, metric), metricValue(a, metric)));

        List<PieEntry> entries = new ArrayList<>();
        List<BatteryStatsManager.AppResourceStats> othersList = new ArrayList<>();
        double othersValue = 0;

        for (int i = 0; i < byCurrent.size(); i++) {
            BatteryStatsManager.AppResourceStats s = byCurrent.get(i);
            double val = metricValue(s, metric);
            float pct = (float)(val / total * 100);
            boolean forceShow = i < BatteryStatsManager.MIN_TOP_SLICES;
            if (forceShow || pct > BatteryStatsManager.OTHERS_THRESHOLD_PCT) {
                entries.add(new PieEntry((float) val, s.appName, s.packageName));
            } else {
                othersValue += val;
                othersList.add(s);
            }
        }
        if (othersValue > 0) {
            entries.add(new PieEntry((float) othersValue, getString(R.string.chart_others_label), "__others__"));
        }

        List<Integer> colors = buildMultiColors(entries.size());
        final double finalTotal = total;

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColors(colors);
        dataSet.setSliceSpace(2f);
        dataSet.setSelectionShift(8f);
        dataSet.setValueTextSize(0f);

        PieData data = new PieData(dataSet);
        chart.setData(data);
        chart.setDrawHoleEnabled(true);
        chart.setHoleColor(Color.TRANSPARENT);
        chart.setHoleRadius(48f);
        chart.setTransparentCircleRadius(52f);
        chart.setDrawCenterText(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setRotationEnabled(false);
        chart.setHighlightPerTapEnabled(true);
        chart.animateY(800, Easing.EaseInOutQuad);

        chart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                if (!(e instanceof PieEntry)) return;
                PieEntry pe = (PieEntry) e;
                Object tag = pe.getData();
                if ("__others__".equals(tag)) {
                    showOthersDialog(othersList, metric, finalTotal);
                } else {
                    String pkg = tag != null ? tag.toString() : pe.getLabel();
                    BatteryStatsManager.AppResourceStats found = findByPkg(sorted, pkg);
                    float pct = found != null ? (float)(metricValue(found, metric) / finalTotal * 100) : 0f;
                    String info = String.format(Locale.US, "%s\n%.1f%%  %s",
                            pe.getLabel(), pct, formatMetricValue(found, metric));
                    Toast.makeText(StatisticsActivity.this, info, Toast.LENGTH_SHORT).show();
                    openAppDetail(pkg, pe.getLabel());
                }
            }
            @Override public void onNothingSelected() {}
        });

        chart.invalidate();
        // Others list hidden in new design — shown via dialog on tap
        if (othersContainer != null) othersContainer.setVisibility(View.GONE);
    }

    private List<Integer> buildMultiColors(int count) {
        List<Integer> colors = new ArrayList<>();
        // Always put grey last (Others slot)
        int paletteSize = SLICE_PALETTE.length - 1; // exclude last grey
        for (int i = 0; i < count; i++) {
            if (i == count - 1 && count > 1) {
                // Last entry is "Others" → grey
                colors.add(SLICE_PALETTE[SLICE_PALETTE.length - 1]);
            } else {
                colors.add(SLICE_PALETTE[i % paletteSize]);
            }
        }
        return colors;
    }

    private void showOthersDialog(List<BatteryStatsManager.AppResourceStats> others,
                                   ChartMetric metric, double total) {
        StringBuilder sb = new StringBuilder();
        for (BatteryStatsManager.AppResourceStats s : others) {
            sb.append(String.format(Locale.US, "• %s  %.1f%%\n",
                    s.appName, metricValue(s, metric) / total * 100));
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.chart_others_dialog_title))
                .setMessage(sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openAppDetail(String packageName, String appName) {
        Intent intent = new Intent(this, AppResourceDetailActivity.class);
        intent.putExtra(AppResourceDetailActivity.EXTRA_PACKAGE_NAME, packageName);
        intent.putExtra(AppResourceDetailActivity.EXTRA_APP_NAME, appName);
        startActivity(intent);
    }

    private double metricValue(BatteryStatsManager.AppResourceStats s, ChartMetric m) {
        if (s == null) return 0;
        switch (m) {
            case BATTERY: return s.batteryMah;
            case CPU:     return s.cpuPct;
            case RAM:     return s.ramMb;
            default:      return 0;
        }
    }

    private String formatMetricValue(BatteryStatsManager.AppResourceStats s, ChartMetric m) {
        if (s == null) return "";
        switch (m) {
            case BATTERY: return String.format(Locale.US, "%.2f mAh", s.batteryMah);
            case CPU:     return String.format(Locale.US, "%.1f%%", s.cpuPct);
            case RAM:     return String.format(Locale.US, "Ср. %s / Пик %s",
                              formatRamMb(s.ramMb), formatRamMb(s.peakRamMb));
            default:      return "";
        }
    }

    private BatteryStatsManager.AppResourceStats findByPkg(
            List<BatteryStatsManager.AppResourceStats> list, String pkg) {
        for (BatteryStatsManager.AppResourceStats s : list) {
            if (s.packageName.equals(pkg)) return s;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dialogs
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

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

    private void openAppInfo(String packageName) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.settings_open_app_info_error), Toast.LENGTH_SHORT).show();
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

    private String formatRecoveredSize(long kb) {
        if (kb < 1024) return getString(R.string.unit_kb, kb);
        if (kb < 1024 * 1024) return getString(R.string.unit_mb_precise, kb / 1024f);
        return getString(R.string.unit_gb_precise, kb / (1024f * 1024f));
    }

    private String formatRamMb(double mb) {
        if (mb < 1024.0) return getString(R.string.unit_mb, (int) mb);
        return getString(R.string.unit_gb, mb / 1024.0);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        binding = null;
    }
}
