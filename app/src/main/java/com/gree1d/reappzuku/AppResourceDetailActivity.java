package com.gree1d.reappzuku;

import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.tabs.TabLayout;
import com.gree1d.reappzuku.databinding.ActivityAppResourceDetailBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays per-hour resource consumption graphs for a single app.
 *
 * Launched from StatisticsActivity when the user taps a slice in a pie chart.
 * Extras:
 *   EXTRA_PACKAGE_NAME  — package name of the target app
 *   EXTRA_APP_NAME      — display name (to avoid a PM lookup on the main thread)
 *
 * Shows three line charts:
 *   • Battery (mAh per hour)
 *   • CPU (% per hour)
 *   • RAM (avg PSS MB per snapshot in the hour)
 *
 * Period tabs: 2h / 6h / 12h / 24h
 */
public class AppResourceDetailActivity extends BaseActivity {

    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    public static final String EXTRA_APP_NAME     = "extra_app_name";

    private static final int[] PERIODS_HOURS       = { 2, 6, 12, 24 };
    private static final String[] PERIOD_LABELS    = { "2ч", "6ч", "12ч", "24ч" };

    private ActivityAppResourceDetailBinding binding;
    private BatteryStatsManager batteryStatsManager;
    private ShellManager shellManager;
    private final Handler handler         = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private String packageName;
    private String appName;
    private int selectedPeriodIdx = 1; // default 6h

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppResourceDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        appName     = getIntent().getStringExtra(EXTRA_APP_NAME);
        if (packageName == null) { finish(); return; }

        shellManager        = new ShellManager(getApplicationContext(), handler, executor);
        batteryStatsManager = new BatteryStatsManager(getApplicationContext(), handler, executor, shellManager);

        setupToolbar();
        setupAppCard();
        setupPeriodTabs();
        loadCharts(PERIODS_HOURS[selectedPeriodIdx]);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(appName != null ? appName : packageName);
        }

        int accent = sharedPreferences.getInt(PreferenceKeys.KEY_ACCENT, AppConstants.ACCENT_SYSTEM);
        if (accent == AppConstants.ACCENT_SYSTEM) {
            binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_navy));
        }
        boolean isNewAccent = (accent == AppConstants.ACCENT_APRICOT || accent == AppConstants.ACCENT_SKY ||
                accent == AppConstants.ACCENT_PAPAYA   || accent == AppConstants.ACCENT_LAVENDER ||
                accent == AppConstants.ACCENT_MINT     || accent == AppConstants.ACCENT_PEACH ||
                accent == AppConstants.ACCENT_POWDER   || accent == AppConstants.ACCENT_FOG);
        binding.toolbar.setTitleTextColor(isNewAccent ? Color.BLACK : Color.WHITE);
    }

    private void setupAppCard() {
        binding.tvAppName.setText(appName != null ? appName : packageName);
        binding.tvPackageName.setText(packageName);
        try {
            Drawable icon = getPackageManager().getApplicationIcon(packageName);
            binding.ivAppIcon.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
    }

    private void setupPeriodTabs() {
        for (String label : PERIOD_LABELS) {
            binding.tabDetailPeriod.addTab(binding.tabDetailPeriod.newTab().setText(label));
        }
        binding.tabDetailPeriod.selectTab(binding.tabDetailPeriod.getTabAt(selectedPeriodIdx));
        binding.tabDetailPeriod.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                selectedPeriodIdx = tab.getPosition();
                loadCharts(PERIODS_HOURS[selectedPeriodIdx]);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chart loading
    // ─────────────────────────────────────────────────────────────────────────

    private void loadCharts(int hours) {
        binding.layoutDetailLoading.setVisibility(View.VISIBLE);
        binding.cardDetailBattery.setVisibility(View.GONE);
        binding.cardDetailCpu.setVisibility(View.GONE);
        binding.cardDetailRam.setVisibility(View.GONE);

        batteryStatsManager.getHourlyStatsAsync(packageName, hours, result -> {
            binding.layoutDetailLoading.setVisibility(View.GONE);

            // Always apply partial data warning first — regardless of whether points
            // is empty or not. When isPartialData=true but points is empty it means
            // data is accumulating but we haven't collected two snapshots yet.
            if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(null);
            if (result.isPartialData) {
                binding.tvDetailPartialWarning.setText(
                        getString(R.string.stats_partial_data_warning));
                binding.tvDetailPartialWarning.setVisibility(View.VISIBLE);
            } else {
                binding.tvDetailPartialWarning.setVisibility(View.GONE);
            }

            if (result.points == null || result.points.isEmpty()) {
                // isPartialData warning is already visible if applicable.
                // No chart to draw — keep cards hidden.
                return;
            }

            binding.cardDetailBattery.setVisibility(View.VISIBLE);
            binding.cardDetailCpu.setVisibility(View.VISIBLE);
            binding.cardDetailRam.setVisibility(View.VISIBLE);

            List<BatteryStatsManager.HourlyPoint> points = result.points;
            String[] labels  = new String[points.size()];
            float[]  battery = new float[points.size()];
            float[]  cpu     = new float[points.size()];
            float[]  ram     = new float[points.size()];

            double sumBattery = 0, sumCpu = 0, sumRam = 0;
            for (int i = 0; i < points.size(); i++) {
                BatteryStatsManager.HourlyPoint p = points.get(i);
                labels[i]  = p.hourLabel;
                battery[i] = (float) p.batteryMah;
                cpu[i]     = (float) p.cpuPercent;
                ram[i]     = (float) p.ramMb;
                sumBattery += p.batteryMah;
                sumCpu     += p.cpuPercent;
                sumRam     += p.ramMb;
            }

            int n = points.size();
            binding.tvDetailBatteryAvg.setText(getString(R.string.detail_avg_battery,
                    sumBattery / n));
            binding.tvDetailCpuAvg.setText(getString(R.string.detail_avg_cpu,
                    sumCpu / n));
            binding.tvDetailRamAvg.setText(getString(R.string.detail_avg_ram,
                    sumRam / n));

            int colorBattery = ContextCompat.getColor(this, R.color.stats_battery);
            int colorCpu     = ContextCompat.getColor(this, R.color.stats_cpu);
            int colorRam     = ContextCompat.getColor(this, R.color.stats_ram);

            buildLineChart(binding.chartDetailBattery, labels, battery, colorBattery,
                    getString(R.string.unit_mah));
            buildLineChart(binding.chartDetailCpu,     labels, cpu,     colorCpu,
                    getString(R.string.unit_percent));
            buildLineChart(binding.chartDetailRam,     labels, ram,     colorRam,
                    getString(R.string.unit_mb_short));
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Line chart builder
    // ─────────────────────────────────────────────────────────────────────────

    private void buildLineChart(LineChart chart, String[] labels, float[] values,
                                 int lineColor, String yUnit) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < values.length; i++) entries.add(new Entry(i, values[i]));

        LineDataSet dataSet = new LineDataSet(entries, "");
        dataSet.setColor(lineColor);
        dataSet.setLineWidth(2.5f);
        dataSet.setCircleColor(lineColor);
        dataSet.setCircleRadius(3.5f);
        dataSet.setDrawCircleHole(false);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setCubicIntensity(0.3f);

        // Fill gradient below the line
        dataSet.setDrawFilled(true);
        dataSet.setFillAlpha(30);
        dataSet.setFillColor(lineColor);

        // Highlight tap
        dataSet.setHighLightColor(lineColor);
        dataSet.setHighlightLineWidth(1.2f);
        dataSet.enableDashedHighlightLine(8f, 4f, 0f);

        LineData lineData = new LineData(dataSet);

        // X Axis
        XAxis xAxis = chart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setTextSize(10f);
        xAxis.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setLabelRotationAngle(-30f);

        // Y Axis (left)
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setTextSize(10f);
        leftAxis.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(0x1A808080);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f %s", value, yUnit);
            }
        });

        chart.getAxisRight().setEnabled(false);
        chart.setData(lineData);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(false);
        chart.setPinchZoom(false);
        chart.setDrawBorders(false);
        chart.setExtraBottomOffset(10f);
        chart.setExtraLeftOffset(4f);
        chart.animateX(600);
        chart.invalidate();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        binding = null;
    }
}
