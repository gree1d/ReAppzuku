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
import static com.gree1d.reappzuku.PreferenceKeys.*;
import static com.gree1d.reappzuku.AppConstants.*;

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

    /** Period options for chart tabs, in hours. */
    private static final int[] CHART_PERIODS_HOURS = { 2, 6, 12, 24 };
    private static final String[] CHART_PERIOD_LABELS = { "2ч", "6ч", "12ч", "24ч" };

    private String[] topOffenderFilterLabels;
    private int selectedPeriodIdx = 1; // default 6h

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

        shellManager         = new ShellManager(this.getApplicationContext(), handler, executor);
        appManager           = new BackgroundAppManager(this.getApplicationContext(), handler, executor, shellManager);
        batteryStatsManager  = new BatteryStatsManager(this.getApplicationContext(), handler, executor, shellManager);

        setupToolbar();
        setupBottomNavigation();
        setupPeriodTabs();
        setupListListeners();
        loadInfoCards();

        // Trigger a snapshot in background (throttled internally — safe to call always)
        batteryStatsManager.takeSnapshotAsync(() -> loadCharts(CHART_PERIODS_HOURS[selectedPeriodIdx]));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup helpers
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
        for (String label : CHART_PERIOD_LABELS) tabs.addTab(tabs.newTab().setText(label));
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

    private void setupListListeners() {
        binding.layoutStats.setOnClickListener(v -> showStatsDialog());
        binding.layoutTopOffenders.setOnClickListener(v -> showTopOffendersDialog());
        binding.layoutRestrictionLog.setVisibility(
                appManager.supportsBackgroundRestriction() ? View.VISIBLE : View.GONE);
        binding.layoutRestrictionLog.setOnClickListener(v -> showBackgroundRestrictionLogDialog());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Info cards
    // ─────────────────────────────────────────────────────────────────────────

    private void loadInfoCards() {
        executor.execute(() -> {
            long since = System.currentTimeMillis() - STATS_HISTORY_DURATION_MS;
            com.gree1d.reappzuku.db.AppStatsDao dao =
                    com.gree1d.reappzuku.db.AppDatabase.getInstance(this).appStatsDao();
            List<com.gree1d.reappzuku.db.AppStats> stats = dao.getAllStatsSince(since);

            int totalKills = 0, totalRelaunches = 0;
            long totalRamKb = 0;
            for (com.gree1d.reappzuku.db.AppStats s : stats) {
                if (s == null) continue;
                totalKills      += s.killCount;
                totalRelaunches += s.relaunchCount;
                totalRamKb      += s.totalRecoveredKb;
            }
            final int fKills = totalKills, fRelaunches = totalRelaunches;
            final long fRamKb = totalRamKb;

            handler.post(() -> {
                binding.infoTotalKillsValue.setText(String.valueOf(fKills));
                binding.infoTotalRelaunchesValue.setText(String.valueOf(fRelaunches));
                binding.infoRamRecoveredValue.setText(formatRecoveredSize(fRamKb));
                // Self drain loaded alongside chart data
            });
        });
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
                setChartsVisible(false);
                return;
            }
            binding.cardNoData.setVisibility(View.GONE);
            setChartsVisible(true);

            List<BatteryStatsManager.AppResourceStats> sorted = periodStats.sorted;

            // Update self-drain card
            for (BatteryStatsManager.AppResourceStats s : sorted) {
                if (s.isSelf) {
                    binding.infoSelfDrainValue.setText(
                            String.format(Locale.US, "%.2f mAh/ч", s.batteryMah / periodStats.actualHours));
                    break;
                }
            }

            // Totals for header labels
            double totalBattery = 0, totalRam = 0, totalCpu = 0;
            for (BatteryStatsManager.AppResourceStats s : sorted) {
                totalBattery += s.batteryMah;
                totalRam     += s.ramMb;
                totalCpu     += s.cpuFraction;
            }
            binding.tvBatteryTotal.setText(String.format(Locale.US, "Всего %.1f mAh", totalBattery));
            binding.tvRamTotal.setText(String.format(Locale.US, "Ср. %.0f МБ", totalRam / Math.max(sorted.size(), 1)));
            binding.tvCpuTotal.setText(String.format(Locale.US, "%.0f%%", totalCpu * 100));

            // Build and render each chart
            buildPieChart(binding.chartBattery, binding.layoutBatteryOthers,
                    sorted, ChartMetric.BATTERY, ContextCompat.getColor(this, R.color.stats_battery));
            buildPieChart(binding.chartCpu, binding.layoutCpuOthers,
                    sorted, ChartMetric.CPU, ContextCompat.getColor(this, R.color.stats_cpu));
            buildPieChart(binding.chartRam, binding.layoutRamOthers,
                    sorted, ChartMetric.RAM, ContextCompat.getColor(this, R.color.stats_ram));
        });
    }

    private void showChartsLoading(boolean loading) {
        binding.layoutChartsLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void setChartsVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        binding.cardBatteryChart.setVisibility(v);
        binding.cardCpuChart.setVisibility(v);
        binding.cardRamChart.setVisibility(v);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pie chart builder
    // ─────────────────────────────────────────────────────────────────────────

    private enum ChartMetric { BATTERY, CPU, RAM }

    private void buildPieChart(PieChart chart,
                                android.view.ViewGroup othersContainer,
                                List<BatteryStatsManager.AppResourceStats> sorted,
                                ChartMetric metric,
                                int accentColor) {
        // Compute values
        double total = 0;
        for (BatteryStatsManager.AppResourceStats s : sorted) total += metricValue(s, metric);
        if (total <= 0) return;

        // Sort descending by this metric
        List<BatteryStatsManager.AppResourceStats> byCurrent = new ArrayList<>(sorted);
        byCurrent.sort((a, b) -> Double.compare(metricValue(b, metric), metricValue(a, metric)));

        // Slice split: always show top MIN_TOP_SLICES; then group ≤5% into "Others"
        List<PieEntry> entries   = new ArrayList<>();
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

        // Colors — gradient of the accent color
        List<Integer> colors = generatePieColors(accentColor, entries.size());

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColors(colors);
        dataSet.setSliceSpace(2f);
        dataSet.setSelectionShift(8f);
        dataSet.setValueTextSize(0f);   // hide labels on slices; shown via tooltip

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

        // Tap listener: show app name in a Toast (replace with bottom sheet if desired)
        chart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                if (!(e instanceof PieEntry)) return;
                PieEntry pe = (PieEntry) e;
                Object tag = pe.getData();
                if ("__others__".equals(tag)) {
                    // Show list of "others" apps in a small dialog
                    showOthersDialog(othersList, metric, total);
                } else {
                    // Navigate to per-app detail screen
                    String pkg = tag != null ? tag.toString() : pe.getLabel();
                    float pct  = (float)(metricValue(findByPkg(sorted, pkg), metric) / total * 100);
                    String info = String.format(Locale.US, "%s\n%.1f%%  %s",
                            pe.getLabel(), pct, formatMetricValue(findByPkg(sorted, pkg), metric));
                    Toast.makeText(StatisticsActivity.this, info, Toast.LENGTH_SHORT).show();
                    openAppDetail(pkg, pe.getLabel());
                }
            }

            @Override public void onNothingSelected() {}
        });

        chart.invalidate();

        // "Others" expandable list below the chart (collapsed by default)
        buildOthersRow(othersContainer, othersList, metric, total);
    }

    private void buildOthersRow(android.view.ViewGroup container,
                                 List<BatteryStatsManager.AppResourceStats> others,
                                 ChartMetric metric, double total) {
        container.removeAllViews();
        if (others.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);

        // Header button "Экономичные приложения (N)"
        TextView header = new TextView(this);
        header.setText(getString(R.string.chart_others_expand, others.size()));
        header.setTextSize(13f);
        header.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        header.setPadding(0, 0, 0, 0);
        container.addView(header);

        // List of others — each row
        for (BatteryStatsManager.AppResourceStats s : others) {
            TextView row = new TextView(this);
            row.setText(String.format(Locale.US, "• %s  %.1f%%",
                    s.appName, metricValue(s, metric) / total * 100));
            row.setTextSize(12f);
            row.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            row.setPadding(0, 4, 0, 0);
            row.setOnClickListener(v -> openAppDetail(s.packageName, s.appName));
            container.addView(row);
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation to detail screen
    // ─────────────────────────────────────────────────────────────────────────

    private void openAppDetail(String packageName, String appName) {
        Intent intent = new Intent(this, AppResourceDetailActivity.class);
        intent.putExtra(AppResourceDetailActivity.EXTRA_PACKAGE_NAME, packageName);
        intent.putExtra(AppResourceDetailActivity.EXTRA_APP_NAME, appName);
        startActivity(intent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chart utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    private double metricValue(BatteryStatsManager.AppResourceStats s, ChartMetric m) {
        if (s == null) return 0;
        switch (m) {
            case BATTERY: return s.batteryMah;
            case CPU:     return s.cpuFraction * 100;
            case RAM:     return s.ramMb;
            default:      return 0;
        }
    }

    private String formatMetricValue(BatteryStatsManager.AppResourceStats s, ChartMetric m) {
        if (s == null) return "";
        switch (m) {
            case BATTERY: return String.format(Locale.US, "%.2f mAh", s.batteryMah);
            case CPU:     return String.format(Locale.US, "%.1f%%", s.cpuFraction * 100);
            case RAM:     return String.format(Locale.US, "%.0f МБ", s.ramMb);
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

    /**
     * Generates N visually distinct colors derived from a base accent color.
     * First slice = full accent, then progressively lighter / desaturated.
     */
    private List<Integer> generatePieColors(int base, int count) {
        List<Integer> colors = new ArrayList<>();
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        for (int i = 0; i < count; i++) {
            float[] c = hsv.clone();
            if (i == count - 1 && count > 1) {
                // Last slice = "Others": grey
                colors.add(0xFFBDBDBD);
            } else {
                c[1] = Math.max(0.15f, hsv[1] - i * (0.55f / Math.max(count - 1, 1)));
                c[2] = Math.min(1.0f,  hsv[2] + i * (0.25f / Math.max(count - 1, 1)));
                colors.add(Color.HSVToColor(c));
            }
        }
        return colors;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Original dialog methods (unchanged from original StatisticsActivity)
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

            historyEntries.sort((a, b) -> Long.compare(b.lastEventTime, a.lastEventTime));

            final List<KillHistoryEntry> finalEntries = historyEntries;
            final int fKills = totalKills, fRelaunches = totalRelaunches;
            final long fRecoveredKb = totalRecoveredKb;

            handler.post(() -> {
                String subtitle = getString(R.string.stats_dialog_subtitle);
                SettingsListContent content = createSettingsListContent(
                        getString(R.string.stats_no_activity_12h), false);
                AlertDialog dialog = createSettingsSurfaceDialog(
                        getString(R.string.settings_kill_history_title), subtitle, content.rootView);
                SettingsSurfaceAdapter adapter = new SettingsSurfaceAdapter();
                content.listView.setAdapter(adapter);
                if (finalEntries.isEmpty()) {
                    content.emptyView.setVisibility(View.VISIBLE);
                    content.listView.setVisibility(View.GONE);
                } else {
                    content.emptyView.setVisibility(View.GONE);
                    content.listView.setVisibility(View.VISIBLE);
                    adapter.setItems(buildKillHistoryRows(finalEntries));
                }
                content.listView.setOnItemClickListener((parent, view, position, id) -> {
                    SettingsSurfaceRow row = adapter.getItem(position);
                    if (row != null && row.tag != null) openAppDetail(row.tag, row.title);
                });
                dialog.show();
                styleDialogButtons(dialog);
            });
        });
    }

    private void showTopOffendersDialog() {
        SettingsListContent content = createSettingsListContent(
                getString(R.string.stats_top_offenders_empty), true);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, topOffenderFilterLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        content.filterSpinner.setAdapter(spinnerAdapter);
        SettingsSurfaceAdapter adapter = new SettingsSurfaceAdapter();
        content.listView.setAdapter(adapter);

        AlertDialog dialog = createSettingsSurfaceDialog(
                getString(R.string.settings_top_offenders_title), null, content.rootView);

        content.filterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                loadTopOffenders(pos, adapter, content, dialog);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        content.listView.setOnItemClickListener((parent, view, position, id) -> {
            SettingsSurfaceRow row = adapter.getItem(position);
            if (row != null && row.tag != null) openAppDetail(row.tag, row.title);
        });

        dialog.show();
        styleDialogButtons(dialog);
        loadTopOffenders(0, adapter, content, dialog);
    }

    private void loadTopOffenders(int filterIdx, SettingsSurfaceAdapter adapter,
                                   SettingsListContent content, AlertDialog dialog) {
        long windowMs = TOP_OFFENDER_FILTER_WINDOWS_MS[filterIdx];
        content.loading.setVisibility(View.VISIBLE);
        content.listView.setVisibility(View.GONE);
        content.emptyView.setVisibility(View.GONE);

        executor.execute(() -> {
            long since = windowMs < 0 ? 0 : System.currentTimeMillis() - windowMs;
            com.gree1d.reappzuku.db.AppStatsDao dao = com.gree1d.reappzuku.db.AppDatabase
                    .getInstance(this).appStatsDao();
            List<com.gree1d.reappzuku.db.AppStats> stats = dao.getAllStatsSince(since);
            List<TopOffender> offenders = new ArrayList<>();

            for (com.gree1d.reappzuku.db.AppStats s : stats) {
                if (s == null || s.packageName == null) continue;
                double score = s.killCount * 1.0 + s.relaunchCount * 2.0 +
                        (s.totalRecoveredKb / 1024.0) * 0.1;
                if (score > 0) {
                    offenders.add(new TopOffender(resolveStatsAppName(s, dao),
                            s.packageName, s.killCount, s.relaunchCount, s.totalRecoveredKb, score));
                }
            }
            offenders.sort((a, b) -> Double.compare(b.score, a.score));
            if (offenders.size() > TOP_OFFENDERS_LIMIT) offenders = offenders.subList(0, TOP_OFFENDERS_LIMIT);

            int sumKills = 0, sumRelaunches = 0; long sumRamKb = 0;
            for (TopOffender o : offenders) { sumKills += o.killCount; sumRelaunches += o.relaunchCount; sumRamKb += o.recoveredKb; }
            String periodLabel = topOffenderFilterLabels.length > filterIdx ? topOffenderFilterLabels[filterIdx] : "";
            String summary = offenders.isEmpty() ? "" :
                    getString(R.string.stats_top_offenders_summary, periodLabel,
                            offenders.size(), sumKills, sumRelaunches, formatRecoveredSize(sumRamKb));
            List<SettingsSurfaceRow> rows = buildTopOffenderRows(offenders);
            final List<TopOffender> finalOffenders = offenders;

            handler.post(() -> {
                content.loading.setVisibility(View.GONE);
                content.summaryText.setText(summary);
                content.summaryText.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
                if (rows.isEmpty()) {
                    content.emptyView.setVisibility(View.VISIBLE);
                    content.listView.setVisibility(View.GONE);
                } else {
                    content.emptyView.setVisibility(View.GONE);
                    content.listView.setVisibility(View.VISIBLE);
                    adapter.setItems(rows);
                }
            });
        });
    }

    private void showBackgroundRestrictionLogDialog() {
        SettingsListContent content = createSettingsListContent(
                getString(R.string.settings_restriction_log_empty), false);
        SettingsSurfaceAdapter adapter = new SettingsSurfaceAdapter();
        content.listView.setAdapter(adapter);
        content.loading.setVisibility(View.VISIBLE);
        AlertDialog dialog = createSettingsSurfaceDialog(
                getString(R.string.settings_restriction_log_title), null, content.rootView);
        executor.execute(() -> {
            List<BackgroundRestrictionLog.LogEntry> logEntries =
                    BackgroundRestrictionLog.getInstance().getEntries();
            List<SettingsSurfaceRow> rows = buildRestrictionLogRows(logEntries);
            handler.post(() -> {
                content.loading.setVisibility(View.GONE);
                if (rows.isEmpty()) {
                    content.emptyView.setVisibility(View.VISIBLE);
                    content.listView.setVisibility(View.GONE);
                } else {
                    content.emptyView.setVisibility(View.GONE);
                    content.listView.setVisibility(View.VISIBLE);
                    adapter.setItems(rows);
                }
            });
        });
        dialog.show();
        styleDialogButtons(dialog);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal data classes
    // ─────────────────────────────────────────────────────────────────────────

    private static class KillHistoryEntry {
        final String appName, packageName, detail, badge;
        final long lastEventTime;
        KillHistoryEntry(String appName, String packageName, String detail, String badge, long t) {
            this.appName = appName; this.packageName = packageName;
            this.detail = detail; this.badge = badge; this.lastEventTime = t;
        }
    }

    private static class TopOffender {
        final String appName, packageName;
        final int killCount, relaunchCount;
        final long recoveredKb;
        final double score;
        TopOffender(String appName, String packageName, int kills, int relaunches, long kb, double score) {
            this.appName = appName; this.packageName = packageName;
            this.killCount = kills; this.relaunchCount = relaunches;
            this.recoveredKb = kb; this.score = score;
        }
    }

    private static class SettingsSurfaceRow {
        final String leadingText, title, subtitle, detail, badge, tag;
        SettingsSurfaceRow(String leadingText, String title, String subtitle,
                           String detail, String badge, String tag) {
            this.leadingText = leadingText; this.title = title; this.subtitle = subtitle;
            this.detail = detail; this.badge = badge; this.tag = tag;
        }
    }

    private static class SettingsListContent {
        final View rootView;
        final Spinner filterSpinner;
        final TextView summaryText, emptyView;
        final ProgressBar loading;
        final ListView listView;
        SettingsListContent(View rootView, Spinner filterSpinner, TextView summaryText,
                             ProgressBar loading, ListView listView, TextView emptyView) {
            this.rootView = rootView; this.filterSpinner = filterSpinner;
            this.summaryText = summaryText; this.loading = loading;
            this.listView = listView; this.emptyView = emptyView;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapters and builders (preserved from original)
    // ─────────────────────────────────────────────────────────────────────────

    private class SettingsSurfaceAdapter extends BaseAdapter {
        private final List<SettingsSurfaceRow> items = new ArrayList<>();
        private final LayoutInflater inflater = LayoutInflater.from(StatisticsActivity.this);
        void setItems(List<SettingsSurfaceRow> newItems) {
            items.clear(); if (newItems != null) items.addAll(newItems); notifyDataSetChanged();
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
        if (text == null || text.trim().isEmpty()) { view.setVisibility(View.GONE); return; }
        view.setVisibility(View.VISIBLE); view.setText(text);
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
                                        com.gree1d.reappzuku.db.AppStatsDao dao) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(stats.packageName, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return stats.packageName;
        }
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
            case "ok":                        return getString(R.string.log_outcome_ok);
            case "verified":                  return getString(R.string.log_outcome_verified);
            case "failed":                    return getString(R.string.log_outcome_failed);
            case "skipped":                   return getString(R.string.log_outcome_skipped);
            case "verify-failed":             return getString(R.string.log_outcome_verify_failed);
            case "verify-unavailable":        return getString(R.string.log_outcome_verify_unavailable);
            case "battery-whitelist-removed": return getString(R.string.log_outcome_battery_whitelist_removed);
            case "battery-whitelist-restored":return getString(R.string.log_outcome_battery_whitelist_restored);
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

    private String formatRecoveredSize(long kb) {
        if (kb >= 1024 * 1024) return String.format(Locale.US, "%.1f ГБ", kb / (1024.0 * 1024));
        if (kb >= 1024)        return String.format(Locale.US, "%.1f МБ", kb / 1024.0);
        return kb + " КБ";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        binding = null;
    }
}
