package com.gree1d.reappzuku;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.SpannableString;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.gree1d.reappzuku.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import android.widget.PopupMenu;
import android.widget.Toast;
import android.net.Uri;
import androidx.appcompat.app.AlertDialog;

import rikka.shizuku.Shizuku;

import static com.gree1d.reappzuku.PreferenceKeys.*;
import static com.gree1d.reappzuku.AppConstants.*;

public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivity";
    private static final int NOTIFICATION_PERMISSION_CODE = 1;

    private ActivityMainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ShellManager shellManager;
    private BackgroundAppManager appManager;
    private AutoKillManager autoKillManager;
    private RamMonitor ramMonitor;
    private CpuMonitor cpuMonitor;
    private BackgroundAppsRecyclerViewAdapter listAdapter;
    private final List<AppModel> appsDataList = new ArrayList<>();
    private final List<AppModel> fullAppsList = new ArrayList<>();
    private String currentSearchQuery = "";
    private int currentSortMode = AppConstants.SORT_MODE_DEFAULT;
    private MenuItem selectAllMenuItem;

    private int appliedAccent;
    private boolean appliedIsAmoled;
    private int fabNavBarHeight = 0;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            loadBackgroundApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        NOTIFICATION_PERMISSION_CODE);
            }
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setTitleTextColor(Color.WHITE);
        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        boolean isAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        if (!isAmoled && accent == ACCENT_SYSTEM) {
            binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_navy));
        }
        appliedAccent = accent;
        appliedIsAmoled = isAmoled;

        shellManager = new ShellManager(this, handler, executor);
        appManager = new BackgroundAppManager(this, handler, executor, shellManager);
        autoKillManager = new AutoKillManager(this, handler, executor, shellManager, appManager.getCurrentAppsList());
        ramMonitor = new RamMonitor(this, handler, binding.ramUsage, binding.ramUsageText);
        cpuMonitor = new CpuMonitor(handler, executor, shellManager);
        cpuMonitor.setOnCpuUpdateListener(() -> {
            if (currentSortMode == AppConstants.SORT_MODE_CPU_DESC
                    || currentSortMode == AppConstants.SORT_MODE_CPU_ASC) {
                appManager.sortAppList(appsDataList, currentSortMode);
            }
            android.util.Log.d("CpuSort", "mode=" + currentSortMode + " appsDataList.size=" + appsDataList.size());
            for (int i = 0; i < Math.min(4, appsDataList.size()); i++) {
                AppModel a = appsDataList.get(i);
                android.util.Log.d("CpuSort", i + ": " + a.getAppName() + " cpu=" + a.getCpuUsage());
            }
            listAdapter.updateCpu(appsDataList);
        });

        listAdapter = new BackgroundAppsRecyclerViewAdapter(this);
        binding.recyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        binding.recyclerView.setAdapter(listAdapter);

        cpuMonitor.setAppsList(fullAppsList);


        setupListeners();
        setupBottomNavigation();
        setupFabInsets();


        binding.swiperefreshlayout1.post(this::recalculateListHeight);
        loadSettingsAndApplyToManager();

        shellManager.setShizukuPermissionListener(shizukuPermissionListener);
        shellManager.checkShellPermissions();
        loadBackgroundApps();
        ramMonitor.startMonitoring();
    }

    private void recalculateListHeight() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int appNavBarPx = (int) (64 * getResources().getDisplayMetrics().density);

        int systemNavHeight = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(binding.swiperefreshlayout1);
        if (insets != null) {
            systemNavHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
        }

        int[] srlPos = new int[2];
        binding.swiperefreshlayout1.getLocationOnScreen(srlPos);
        int srlTop = srlPos[1];

        int desiredHeight = screenHeight - appNavBarPx - systemNavHeight - srlTop;
        if (desiredHeight > 0) {
            android.view.ViewGroup.LayoutParams params = binding.swiperefreshlayout1.getLayoutParams();
            params.height = desiredHeight;
            binding.swiperefreshlayout1.setLayoutParams(params);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            binding.swiperefreshlayout1.post(this::recalculateListHeight);
        }
    }

    private void setupFabInsets() {


        ViewCompat.setOnApplyWindowInsetsListener(binding.fab, (v, insets) -> {
            fabNavBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            return insets;
        });
    }


    private void applyFabMargin() {
        final int baseMarginPx = (int) (72 * getResources().getDisplayMetrics().density);
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams params =
                (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) binding.fab.getLayoutParams();
        params.bottomMargin = baseMarginPx + fabNavBarHeight;
        binding.fab.setLayoutParams(params);
    }

    private void setupBottomNavigation() {
        binding.bottomNavigation.navIconMain.setSelected(true);
        binding.bottomNavigation.navIconSettings.setSelected(false);
        binding.bottomNavigation.navIconStatistics.setSelected(false);
        binding.bottomNavigation.navBtnMain.setOnClickListener(v -> {});
        binding.bottomNavigation.navBtnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        binding.bottomNavigation.navBtnStatistics.setOnClickListener(v ->
                startActivity(new Intent(this, StatisticsActivity.class)));
        applyNavBarInsets(binding.bottomNavigation.getRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupBottomNavigation();

        int newAccent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        boolean newIsAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        if (newAccent != appliedAccent || newIsAmoled != appliedIsAmoled) {
            recreate();
            return;
        }
        loadSettingsAndApplyToManager();
        ensureServiceRunning();
        loadBackgroundApps();
        cpuMonitor.startMonitoring();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cpuMonitor.stopMonitoring();
    }


    private void ensureServiceRunning() {
        if (sharedPreferences.getBoolean(KEY_AUTO_KILL_ENABLED, false)
                && !ShappkyService.isRunning()) {
            Intent intent = new Intent(this, ShappkyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        }
    }

    private void loadSettingsAndApplyToManager() {
        boolean showSystemApps = sharedPreferences.getBoolean(KEY_SHOW_SYSTEM_APPS, false);
        boolean showPersistentApps = sharedPreferences.getBoolean(KEY_SHOW_PERSISTENT_APPS, false);
        currentSortMode = sharedPreferences.getInt(KEY_SORT_MODE, AppConstants.SORT_MODE_DEFAULT);
        appManager.setShowSystemApps(showSystemApps);
        appManager.setShowPersistentApps(showPersistentApps);
    }

    private void setupListeners() {
        binding.swiperefreshlayout1.setOnRefreshListener(this::loadBackgroundApps);
        binding.fab.setOnClickListener(view -> killSelectedApps());

        listAdapter.setOnAppActionListener(new BackgroundAppsRecyclerViewAdapter.OnAppActionListener() {
            @Override
            public void onKillApp(AppModel app, int position) {
                autoKillManager.killApp(app.getPackageName(), MainActivity.this::loadBackgroundApps);
            }

            @Override
            public void onToggleWhitelist(AppModel app, int position) {
                boolean isNowWhitelisted = autoKillManager.toggleWhitelist(app.getPackageName());
                app.setWhitelisted(isNowWhitelisted);
                listAdapter.notifyItemChanged(position);

                String message = isNowWhitelisted
                        ? getString(R.string.main_added_to_whitelist)
                        : getString(R.string.main_removed_from_whitelist);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAppClick(AppModel app, int position) {
                if (app.isProtected() || app.isWhitelisted()) {
                    return;
                }
                app.setSelected(!app.isSelected());
                boolean hasSelection = fullAppsList.stream().anyMatch(AppModel::isSelected);
                if (!listAdapter.refreshSelectionMode(hasSelection)) {
                    listAdapter.notifyItemChanged(position);
                }
                updateSelectMenuVisibility();
            }

            @Override
            public void onOverflowClick(AppModel app, View anchor) {
                showAppOptionsMenu(app, anchor);
            }
        });
    }

    private void showAppOptionsMenu(AppModel app, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_app_options, popup.getMenu());

        if (app.isProtected()) {
            Menu menu = popup.getMenu();
            menu.findItem(R.id.action_add_to_list).setVisible(false);
            menu.findItem(R.id.action_uninstall).setVisible(false);
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_app_info) {
                    openAppInfo(app.getPackageName());
                    return true;
                } else if (id == R.id.action_app_triggers) {
                    showAppTriggersDialog(app);
                    return true;
                }
                return false;
            });
            popup.show();
            return;
        }

        if (app.isSystemApp()) {
            popup.getMenu().findItem(R.id.action_uninstall).setVisible(false);
        }

        String packageName = app.getPackageName();
        popup.getMenu().findItem(R.id.action_whitelist).setChecked(
                appManager.getWhitelistedApps().contains(packageName));
        popup.getMenu().findItem(R.id.action_blacklist).setChecked(
                autoKillManager.getBlacklistedApps().contains(packageName));
        popup.getMenu().findItem(R.id.action_hidden).setChecked(
                appManager.getHiddenApps().contains(packageName));
        MenuItem restrictionItem = popup.getMenu().findItem(R.id.action_background_restriction);
        restrictionItem.setVisible(appManager.supportsBackgroundRestriction());
        restrictionItem.setChecked(app.isBackgroundRestrictionDesired());
        restrictionItem.setTitle(getBackgroundRestrictionMenuTitle(app));

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_app_info) {
                openAppInfo(packageName);
                return true;
            } else if (id == R.id.action_app_triggers) {
                showAppTriggersDialog(app);
                return true;
            } else if (id == R.id.action_uninstall) {
                showUninstallConfirmation(app);
                return true;
            } else if (id == R.id.action_whitelist) {
                toggleListMembership(app, "whitelist");
                return true;
            } else if (id == R.id.action_blacklist) {
                toggleListMembership(app, "blacklist");
                return true;
            } else if (id == R.id.action_hidden) {
                toggleListMembership(app, "hidden");
                return true;
            } else if (id == R.id.action_background_restriction) {
                toggleBackgroundRestriction(app);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void openAppInfo(String packageName) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.main_open_app_info_error), Toast.LENGTH_SHORT).show();
        }
    }

    private void showUninstallConfirmation(AppModel app) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_uninstall_title, app.getAppName()))
                .setMessage(getString(R.string.main_uninstall_message))
                .setPositiveButton(getString(R.string.main_uninstall_confirm), (dialog, which) -> {
                    autoKillManager.uninstallPackage(app.getPackageName(), this::loadBackgroundApps);
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }


    private void showAppTriggersDialog(AppModel app) {
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_app_triggers) + ": " + app.getAppName())
                .setMessage(getString(R.string.triggers_loading))
                .setCancelable(true)
                .create();
        loadingDialog.show();

        executor.execute(() -> {
            AppTriggersAnalyzer analyzer = new AppTriggersAnalyzer(MainActivity.this, shellManager);
            List<AppTriggersAnalyzer.TriggerInfo> triggers = analyzer.analyze(app.getPackageName());
            AppTriggersAnalyzer.AppStatus status = analyzer.resolveAppStatus(app.getPackageName());

            handler.post(() -> {
                loadingDialog.dismiss();
                if (isFinishing() || isDestroyed()) return;
                showTriggersResult(app, triggers, status);
            });
        });
    }

    private void showTriggersResult(AppModel app, List<AppTriggersAnalyzer.TriggerInfo> triggers,
                                     AppTriggersAnalyzer.AppStatus status) {

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_app_triggers, null);
        LinearLayout container = dialogView.findViewById(R.id.triggers_container);


        if (status != null) {
            String statusLabel;
            switch (status) {
                case ACTIVE:     statusLabel = getString(R.string.triggers_status_active);     break;
                case BACKGROUND: statusLabel = getString(R.string.triggers_status_background); break;
                default:         statusLabel = getString(R.string.triggers_status_cached);     break;
            }

            TextView statusView = new TextView(this);
            String labelPart = getString(R.string.triggers_status_label_prefix);
            String fullText = labelPart + " " + statusLabel;
            SpannableString spannable = new SpannableString(fullText);
            int primaryColor = resolveColorAttr(androidx.appcompat.R.attr.colorPrimary);
            spannable.setSpan(new ForegroundColorSpan(primaryColor), 0, labelPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            statusView.setText(spannable);
            statusView.setTextSize(13f);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            statusLp.setMargins(8, 4, 8, 16);
            statusView.setLayoutParams(statusLp);
            container.addView(statusView);
        }


        List<AppTriggersAnalyzer.TriggerInfo> activeNow = new ArrayList<>();
        List<AppTriggersAnalyzer.TriggerInfo> canWake   = new ArrayList<>();
        List<AppTriggersAnalyzer.TriggerInfo> other     = new ArrayList<>();
        for (AppTriggersAnalyzer.TriggerInfo t : triggers) {
            switch (t.group) {
                case ACTIVE_NOW: activeNow.add(t); break;
                case CAN_WAKE:   canWake.add(t);   break;
                default:         other.add(t);     break;
            }
        }

        if (!activeNow.isEmpty()) {
            addSectionHeader(container, getString(R.string.triggers_section_active_now));
            for (AppTriggersAnalyzer.TriggerInfo t : activeNow) addTriggerItem(container, t);
        }
        if (!canWake.isEmpty()) {
            addSectionHeader(container, getString(R.string.triggers_section_can_wake));
            for (AppTriggersAnalyzer.TriggerInfo t : canWake) addTriggerItem(container, t);
        }
        if (!other.isEmpty()) {
            addSectionHeader(container, getString(R.string.triggers_section_other));
            for (AppTriggersAnalyzer.TriggerInfo t : other) addTriggerItem(container, t);
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_app_triggers) + ": " + app.getAppName())
                .setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_close), null)
                .show();
    }

    private void addSectionHeader(LinearLayout container, String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(12f);
        header.setTextColor(resolveColorAttr(androidx.appcompat.R.attr.colorPrimary));
        header.setAllCaps(true);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(8, 24, 8, 4);
        header.setLayoutParams(lp);
        container.addView(header);
    }

    private void addTriggerItem(LinearLayout container, AppTriggersAnalyzer.TriggerInfo trigger) {
        View item = getLayoutInflater().inflate(R.layout.item_trigger, container, false);

        TextView categoryView    = item.findViewById(R.id.trigger_category);
        TextView detailView      = item.findViewById(R.id.trigger_detail);
        TextView explanationView = item.findViewById(R.id.trigger_explanation);
        TextView arrowView       = item.findViewById(R.id.trigger_arrow);
        View headerView          = item.findViewById(R.id.trigger_header);

        categoryView.setText(trigger.category);
        detailView.setText(trigger.detail);
        explanationView.setText(trigger.explanation);

        if (trigger.explanation == null || trigger.explanation.isEmpty()) {
            arrowView.setVisibility(View.INVISIBLE);
        } else {
            headerView.setOnClickListener(v -> {
                boolean isExpanded = explanationView.getVisibility() == View.VISIBLE;
                explanationView.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
                arrowView.setText(isExpanded ? "▶" : "▼");
            });
        }

        container.addView(item);
    }

    private int resolveColorAttr(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }


    private void toggleListMembership(AppModel app, String listType) {
        String packageName = app.getPackageName();
        Set<String> currentSet;
        String addedMsg, removedMsg;

        switch (listType) {
            case "whitelist":
                currentSet = appManager.getWhitelistedApps();
                addedMsg = getString(R.string.main_added_to_whitelist);
                removedMsg = getString(R.string.main_removed_from_whitelist);
                break;
            case "blacklist":
                currentSet = autoKillManager.getBlacklistedApps();
                addedMsg = getString(R.string.main_added_to_blacklist);
                removedMsg = getString(R.string.main_removed_from_blacklist);
                break;
            case "hidden":
                currentSet = appManager.getHiddenApps();
                addedMsg = getString(R.string.main_app_hidden);
                removedMsg = getString(R.string.main_app_visible);
                break;
            default:
                return;
        }

        boolean wasInList = currentSet.contains(packageName);
        if (wasInList) {
            currentSet.remove(packageName);
        } else {
            currentSet.add(packageName);
        }

        switch (listType) {
            case "whitelist":
                appManager.saveWhitelistedApps(currentSet);
                app.setWhitelisted(!wasInList);
                break;
            case "blacklist":
                autoKillManager.saveBlacklistedApps(currentSet);
                break;
            case "hidden":
                appManager.saveHiddenApps(currentSet);
                break;
        }

        int adapterPos = -1;
        for (int i = 0; i < appsDataList.size(); i++) {
            if (appsDataList.get(i).getPackageName().equals(packageName)) {
                adapterPos = i;
                break;
            }
        }
        if (adapterPos >= 0) {
            listAdapter.notifyItemChanged(adapterPos);
        }
        Toast.makeText(this, wasInList ? removedMsg : addedMsg, Toast.LENGTH_SHORT).show();
    }

    private void toggleBackgroundRestriction(AppModel app) {
        if (app.needsBackgroundRestrictionReapply()) {
            showOutOfSyncRestrictionDialog(app);
            return;
        }
        if (app.isBackgroundRestrictionExternal()) {
            showExternalRestrictionDialog(app);
            return;
        }
        boolean enableRestriction = !app.isBackgroundRestrictionDesired();
        if (enableRestriction && app.isSystemApp()) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.main_system_app_warning_title))
                    .setMessage(getString(R.string.main_system_app_restriction_warning))
                    .setPositiveButton(getString(R.string.dialog_apply), (dialog, which) -> applyBackgroundRestriction(app, true))
                    .setNegativeButton(getString(R.string.dialog_cancel), null)
                    .show();
            return;
        }
        applyBackgroundRestriction(app, enableRestriction);
    }

    private void showOutOfSyncRestrictionDialog(AppModel app) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_restriction_out_of_sync_title))
                .setMessage(getString(R.string.main_restriction_out_of_sync_message, app.getAppName()))
                .setPositiveButton(getString(R.string.main_restriction_resume), (dialog, which) -> applyBackgroundRestriction(app, true))
                .setNeutralButton(getString(R.string.main_restriction_remove_from_list), (dialog, which) -> applyBackgroundRestriction(app, false))
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    private void showExternalRestrictionDialog(AppModel app) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_restriction_external_title))
                .setMessage(getString(R.string.main_restriction_external_message, app.getAppName()))
                .setPositiveButton(getString(R.string.main_restriction_add_to_reappzuku), (dialog, which) -> applyBackgroundRestriction(app, true))
                .setNeutralButton(getString(R.string.main_restriction_remove), (dialog, which) -> applyBackgroundRestriction(app, false))
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    private void applyBackgroundRestriction(AppModel app, boolean enableRestriction) {
        appManager.setBackgroundRestricted(app.getPackageName(), enableRestriction, this::loadBackgroundApps);
    }

    private String getBackgroundRestrictionMenuTitle(AppModel app) {
        if (app.needsBackgroundRestrictionReapply()) {
            return getString(R.string.main_restriction_menu_out_of_sync);
        }
        if (app.isBackgroundRestrictionExternal()) {
            return getString(R.string.main_restriction_menu_external);
        }
        if (app.isBackgroundRestrictionDesired() && !app.isBackgroundRestrictionActualKnown()) {
            return getString(R.string.main_restriction_menu_saved);
        }
        return getString(R.string.main_restriction_menu_default);
    }


    private void loadBackgroundApps() {
        binding.swiperefreshlayout1.setRefreshing(true);

        final Set<String> selectedPackages = fullAppsList.stream()
                .filter(AppModel::isSelected)
                .map(AppModel::getPackageName)
                .collect(Collectors.toSet());

        appManager.loadBackgroundApps(result -> {
            fullAppsList.clear();
            fullAppsList.addAll(result);

            for (AppModel app : fullAppsList) {
                if (selectedPackages.contains(app.getPackageName()) && !app.isProtected()) {
                    app.setSelected(true);
                }
            }

            filterApps(currentSearchQuery);
            binding.runningApps.setText(getString(R.string.main_active_apps_count, fullAppsList.size()));
            binding.swiperefreshlayout1.setRefreshing(false);
            cpuMonitor.refreshAppsList(fullAppsList);
        });
    }

    private void filterApps(String query) {
        currentSearchQuery = query;
        appsDataList.clear();
        if (query == null || query.isEmpty()) {
            appsDataList.addAll(fullAppsList);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AppModel app : fullAppsList) {
                if (app.getAppName().toLowerCase().contains(lowerQuery) ||
                        app.getPackageName().toLowerCase().contains(lowerQuery)) {
                    appsDataList.add(app);
                }
            }
        }
        appManager.sortAppList(appsDataList, currentSortMode);
        listAdapter.submitList(new ArrayList<>(appsDataList));
        updateSelectMenuVisibility();
    }

    private void killSelectedApps() {
        List<String> packagesToKill = fullAppsList.stream()
                .filter(AppModel::isSelected)
                .map(AppModel::getPackageName)
                .collect(Collectors.toList());

        binding.fab.hide();

        for (AppModel app : fullAppsList) {
            app.setSelected(false);
        }
        listAdapter.submitList(new ArrayList<>(appsDataList));

        autoKillManager.killPackages(packagesToKill, () -> {
            loadBackgroundApps();
            applyFabMargin();
            binding.fab.show();
        });
    }

    private void updateFabText() {
        long count = fullAppsList.stream().filter(AppModel::isSelected).count();
        binding.fab.setText(count >= 2
                ? getString(R.string.fab_kill_apps)
                : getString(R.string.fab_kill_app));
    }

    private void updateSelectMenuVisibility() {
        boolean hasSelection = fullAppsList.stream().anyMatch(AppModel::isSelected);
        if (hasSelection) {
            applyFabMargin();
            binding.fab.show();
            updateFabText();
        } else {
            binding.fab.hide();
        }
    }

    private void selectAll() {
        for (AppModel app : fullAppsList) {
            if (!app.isProtected() && !app.isWhitelisted()) {
                app.setSelected(true);
            }
        }
        listAdapter.submitList(new ArrayList<>(appsDataList));
        updateSelectMenuVisibility();
        if (selectAllMenuItem != null) {
            selectAllMenuItem.setIcon(R.drawable.ic_unselect_all);
            selectAllMenuItem.setTitle(getString(R.string.menu_deselect_all));
            tintMenuItem(selectAllMenuItem);
        }
    }

    private void unselectAll() {
        for (AppModel app : fullAppsList) {
            app.setSelected(false);
        }
        listAdapter.submitList(new ArrayList<>(appsDataList));
        updateSelectMenuVisibility();
        if (selectAllMenuItem != null) {
            selectAllMenuItem.setIcon(R.drawable.ic_select_all);
            selectAllMenuItem.setTitle(getString(R.string.menu_select_all));
            tintMenuItem(selectAllMenuItem);
        }
    }

    private void tintMenuItem(MenuItem item) {
        if (item == null || item.getIcon() == null) return;
        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        boolean isNewAccent = (accent == ACCENT_APRICOT || accent == ACCENT_SKY ||
                accent == ACCENT_PAPAYA || accent == ACCENT_LAVENDER ||
                accent == ACCENT_MINT || accent == ACCENT_PEACH ||
                accent == ACCENT_POWDER || accent == ACCENT_FOG);
        item.getIcon().setTint(isNewAccent ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

        selectAllMenuItem = menu.findItem(R.id.action_select_all);

        applyToolbarIconTint(menu);

        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.main_search_hint));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filterApps(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterApps(newText);
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_select_all) {
            boolean hasSelection = fullAppsList.stream().anyMatch(AppModel::isSelected);
            if (hasSelection) {
                unselectAll();
            } else {
                selectAll();
            }
            return true;
        } else if (itemId == R.id.action_sort) {
            showSortDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_sort, null);
        android.widget.RadioGroup radioGroup = dialogView.findViewById(R.id.sort_radio_group);
        android.widget.CheckBox checkboxSystem = dialogView.findViewById(R.id.checkbox_show_system);
        android.widget.CheckBox checkboxPersistent = dialogView.findViewById(R.id.checkbox_show_persistent);

        int selectedRadioId;
        switch (currentSortMode) {
            case AppConstants.SORT_MODE_RAM_DESC:  selectedRadioId = R.id.sort_ram_desc;  break;
            case AppConstants.SORT_MODE_RAM_ASC:   selectedRadioId = R.id.sort_ram_asc;   break;
            case AppConstants.SORT_MODE_NAME_ASC:  selectedRadioId = R.id.sort_name_asc;  break;
            case AppConstants.SORT_MODE_NAME_DESC: selectedRadioId = R.id.sort_name_desc; break;
            case AppConstants.SORT_MODE_CPU_DESC:  selectedRadioId = R.id.sort_cpu_desc;  break;
            case AppConstants.SORT_MODE_CPU_ASC:   selectedRadioId = R.id.sort_cpu_asc;   break;
            case AppConstants.SORT_MODE_DEFAULT:
            default:                               selectedRadioId = R.id.sort_default;   break;
        }
        radioGroup.check(selectedRadioId);

        checkboxSystem.setChecked(sharedPreferences.getBoolean(KEY_SHOW_SYSTEM_APPS, false));
        checkboxPersistent.setChecked(sharedPreferences.getBoolean(KEY_SHOW_PERSISTENT_APPS, false));

        checkboxSystem.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !sharedPreferences.getBoolean("system_apps_warning_shown", false)) {
                buttonView.setChecked(false);
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.settings_system_apps_warning_title))
                        .setMessage(getString(R.string.settings_system_apps_warning_message))
                        .setPositiveButton(getString(R.string.settings_system_apps_i_understand), (d, w) -> {
                            sharedPreferences.edit()
                                    .putBoolean("system_apps_warning_shown", true)
                                    .apply();
                            buttonView.setChecked(true);
                        })
                        .setNegativeButton(getString(R.string.dialog_cancel), null)
                        .show();
            }
        });

        AlertDialog sortDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_apply), (dialog, which) -> {
                    int checkedId = radioGroup.getCheckedRadioButtonId();
                    int newSortMode = AppConstants.SORT_MODE_DEFAULT;
                    if (checkedId == R.id.sort_ram_desc)       newSortMode = AppConstants.SORT_MODE_RAM_DESC;
                    else if (checkedId == R.id.sort_ram_asc)   newSortMode = AppConstants.SORT_MODE_RAM_ASC;
                    else if (checkedId == R.id.sort_name_asc)  newSortMode = AppConstants.SORT_MODE_NAME_ASC;
                    else if (checkedId == R.id.sort_name_desc) newSortMode = AppConstants.SORT_MODE_NAME_DESC;
                    else if (checkedId == R.id.sort_cpu_desc)  newSortMode = AppConstants.SORT_MODE_CPU_DESC;
                    else if (checkedId == R.id.sort_cpu_asc)   newSortMode = AppConstants.SORT_MODE_CPU_ASC;

                    currentSortMode = newSortMode;

                    sharedPreferences.edit()
                            .putInt(KEY_SORT_MODE, newSortMode)
                            .putBoolean(KEY_SHOW_SYSTEM_APPS, checkboxSystem.isChecked())
                            .putBoolean(KEY_SHOW_PERSISTENT_APPS, checkboxPersistent.isChecked())
                            .apply();

                    loadSettingsAndApplyToManager();
                    loadBackgroundApps();
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create();

        sortDialog.show();

        boolean isDarkTheme = sharedPreferences.getBoolean(KEY_AMOLED, false)
                || sharedPreferences.getInt(KEY_THEME,
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                        == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
        if (isDarkTheme) {
            sortDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            sortDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        }
    }

    private void applyToolbarIconTint(Menu menu) {
        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        boolean isNewAccent = (accent == ACCENT_APRICOT || accent == ACCENT_SKY ||
                accent == ACCENT_PAPAYA || accent == ACCENT_LAVENDER ||
                accent == ACCENT_MINT || accent == ACCENT_PEACH ||
                accent == ACCENT_POWDER || accent == ACCENT_FOG);

        int color = isNewAccent ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;

        int[] iconIds = {R.id.action_search, R.id.action_sort, R.id.action_select_all};
        for (int id : iconIds) {
            MenuItem menuItem = menu.findItem(id);
            if (menuItem != null && menuItem.getIcon() != null) {
                menuItem.getIcon().setTint(color);
            }
        }
        binding.toolbar.setTitleTextColor(color);
    }

}
