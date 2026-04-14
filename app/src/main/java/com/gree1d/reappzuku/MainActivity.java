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

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    private RamMonitor ramMonitor;
    private BackgroundAppsRecyclerViewAdapter listAdapter;
    private final List<AppModel> appsDataList = new ArrayList<>();
    private final List<AppModel> fullAppsList = new ArrayList<>();
    private String currentSearchQuery = "";
    private int currentSortMode = AppConstants.SORT_MODE_DEFAULT;
    private static final int POPUP_SELECT_ALL   = 1;
    private static final int POPUP_UNSELECT_ALL = 2;
    private static final int POPUP_SETTINGS     = 3;
    // Отслеживаем тему/акцент для автоматического recreate при возврате из Settings
    private int appliedAccent;
    private boolean appliedIsAmoled;

    // Handle Shizuku permission results
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            loadBackgroundApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request notification permission on Android 13+
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

        // Setup toolbar with colors from resources
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setTitleTextColor(Color.WHITE);
        // При системном акценте восстанавливаем захардкоженый синий цвет тулбара
        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        boolean isAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        if (!isAmoled && accent == ACCENT_SYSTEM) {
            binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_navy));
        }
        // Запоминаем тему/акцент для обнаружения смены при возврате из Settings
        appliedAccent = accent;
        appliedIsAmoled = isAmoled;

        // Initialize components
        shellManager = new ShellManager(this, handler, executor);
        appManager = new BackgroundAppManager(this, handler, executor, shellManager);
        ramMonitor = new RamMonitor(this, handler, binding.ramUsage, binding.ramUsageText);

        listAdapter = new BackgroundAppsRecyclerViewAdapter(this);
        binding.recyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        binding.recyclerView.setAdapter(listAdapter);

        // Configure listeners
        setupListeners();

        // Initialize SharedPreferences and load settings
        loadSettingsAndApplyToManager();

        // Initialize Shizuku and load apps
        shellManager.setShizukuPermissionListener(shizukuPermissionListener);
        shellManager.checkShellPermissions();
        loadBackgroundApps();
        ramMonitor.startMonitoring();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Если акцент или AMOLED изменились в SettingsActivity — пересоздаём Activity
        int newAccent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        boolean newIsAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        if (newAccent != appliedAccent || newIsAmoled != appliedIsAmoled) {
            recreate();
            return;
        }
        // Reload settings in case they changed in SettingsActivity
        loadSettingsAndApplyToManager();
        loadBackgroundApps();
    }

    private void loadSettingsAndApplyToManager() {
        boolean showSystemApps = sharedPreferences.getBoolean(KEY_SHOW_SYSTEM_APPS, false);
        boolean showPersistentApps = sharedPreferences.getBoolean(KEY_SHOW_PERSISTENT_APPS, false);
        currentSortMode = sharedPreferences.getInt(KEY_SORT_MODE, AppConstants.SORT_MODE_DEFAULT);
        appManager.setShowSystemApps(showSystemApps);
        appManager.setShowPersistentApps(showPersistentApps);
    }

    // Setup event listeners
    private void setupListeners() {
        binding.swiperefreshlayout1.setOnRefreshListener(this::loadBackgroundApps);
        binding.fab.setOnClickListener(view -> killSelectedApps());

        listAdapter.setOnAppActionListener(new BackgroundAppsRecyclerViewAdapter.OnAppActionListener() {
            @Override
            public void onKillApp(AppModel app, int position) {
                appManager.killApp(app.getPackageName(), MainActivity.this::loadBackgroundApps);
            }

            @Override
            public void onToggleWhitelist(AppModel app, int position) {
                boolean isNowWhitelisted = appManager.toggleWhitelist(app.getPackageName());
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
                listAdapter.notifyItemChanged(position);
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

        // Hide uninstall for system apps
        if (app.isSystemApp()) {
            popup.getMenu().findItem(R.id.action_uninstall).setVisible(false);
        }

        // Set checkmarks for current list memberships
        String packageName = app.getPackageName();
        popup.getMenu().findItem(R.id.action_whitelist).setChecked(
                appManager.getWhitelistedApps().contains(packageName));
        popup.getMenu().findItem(R.id.action_blacklist).setChecked(
                appManager.getBlacklistedApps().contains(packageName));
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
                    appManager.uninstallPackage(app.getPackageName(), this::loadBackgroundApps);
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
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
                currentSet = appManager.getBlacklistedApps();
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

        // Save the updated set
        switch (listType) {
            case "whitelist":
                appManager.saveWhitelistedApps(currentSet);
                app.setWhitelisted(!wasInList);
                break;
            case "blacklist":
                appManager.saveBlacklistedApps(currentSet);
                break;
            case "hidden":
                appManager.saveHiddenApps(currentSet);
                break;
        }

        listAdapter.submitList(new ArrayList<>(appsDataList));
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

    // Load background apps with selection preservation
    private void loadBackgroundApps() {
        binding.swiperefreshlayout1.setRefreshing(true);

        // Save currently selected packages before reload
        final Set<String> selectedPackages = fullAppsList.stream()
                .filter(AppModel::isSelected)
                .map(AppModel::getPackageName)
                .collect(Collectors.toSet());

        appManager.loadBackgroundApps(result -> {
            fullAppsList.clear();
            fullAppsList.addAll(result);

            // Restore selection state for apps that are still in the list
            for (AppModel app : fullAppsList) {
                if (selectedPackages.contains(app.getPackageName()) && !app.isProtected()) {
                    app.setSelected(true);
                }
            }

            filterApps(currentSearchQuery);
            binding.runningApps.setText(getString(R.string.main_active_apps_count, fullAppsList.size()));
            binding.swiperefreshlayout1.setRefreshing(false);

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
        // Apply current sort mode
        appManager.sortAppList(appsDataList, currentSortMode);
        listAdapter.submitList(new ArrayList<>(appsDataList));
        updateSelectMenuVisibility();
    }

    // Kill selected apps and manage FAB visibility
    private void killSelectedApps() {
        List<String> packagesToKill = fullAppsList.stream()
                .filter(AppModel::isSelected)
                .map(AppModel::getPackageName)
                .collect(Collectors.toList());

        // Hide FAB
        binding.fab.hide();

        // Clear selection
        for (AppModel app : fullAppsList) {
            app.setSelected(false);
        }
        listAdapter.submitList(new ArrayList<>(appsDataList));

        // Kill apps and show FAB on completion
        appManager.killPackages(packagesToKill, () -> {
            loadBackgroundApps();
            binding.fab.show();
        });
    }

    // Toggle between "Select All" and "Unselect All" based on whether any item is
    // selected
    private void updateSelectMenuVisibility() {
        boolean hasSelection = fullAppsList.stream().anyMatch(AppModel::isSelected);
        if (hasSelection) {
            binding.fab.show();
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
    }

    private void unselectAll() {
        for (AppModel app : fullAppsList) {
            app.setSelected(false);
        }
        listAdapter.submitList(new ArrayList<>(appsDataList));
        updateSelectMenuVisibility();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

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
        if (itemId == R.id.action_settings) {
            View anchor = binding.toolbar.findViewById(R.id.action_settings);
            showSettingsPopup(anchor);
            return true;
        } else if (itemId == R.id.action_sort) {
            showSortDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSettingsPopup(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean hasSelection = fullAppsList.stream().anyMatch(AppModel::isSelected);
        if (hasSelection) {
            popup.getMenu().add(0, POPUP_UNSELECT_ALL, 0, getString(R.string.menu_deselect_all));
        } else {
            popup.getMenu().add(0, POPUP_SELECT_ALL, 0, getString(R.string.menu_select_all));
        }
        popup.getMenu().add(0, POPUP_SETTINGS, 1, getString(R.string.settings_title));
        popup.setOnMenuItemClickListener(popupItem -> {
            int id = popupItem.getItemId();
            if (id == POPUP_SELECT_ALL) {
                selectAll();
                return true;
            } else if (id == POPUP_UNSELECT_ALL) {
                unselectAll();
                return true;
            } else if (id == POPUP_SETTINGS) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showSortDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_sort, null);
        android.widget.RadioGroup radioGroup = dialogView.findViewById(R.id.sort_radio_group);

        // Set current selection
        int selectedRadioId;
        switch (currentSortMode) {
            case AppConstants.SORT_MODE_RAM_DESC:
                selectedRadioId = R.id.sort_ram_desc;
                break;
            case AppConstants.SORT_MODE_RAM_ASC:
                selectedRadioId = R.id.sort_ram_asc;
                break;
            case AppConstants.SORT_MODE_NAME_ASC:
                selectedRadioId = R.id.sort_name_asc;
                break;
            case AppConstants.SORT_MODE_NAME_DESC:
                selectedRadioId = R.id.sort_name_desc;
                break;
            case AppConstants.SORT_MODE_DEFAULT:
            default:
                selectedRadioId = R.id.sort_default;
                break;
        }
        radioGroup.check(selectedRadioId);

        androidx.appcompat.app.AlertDialog sortDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_apply), (dialog, which) -> {
                    int checkedId = radioGroup.getCheckedRadioButtonId();
                    int newSortMode = AppConstants.SORT_MODE_DEFAULT;

                    if (checkedId == R.id.sort_ram_desc) {
                        newSortMode = AppConstants.SORT_MODE_RAM_DESC;
                    } else if (checkedId == R.id.sort_ram_asc) {
                        newSortMode = AppConstants.SORT_MODE_RAM_ASC;
                    } else if (checkedId == R.id.sort_name_asc) {
                        newSortMode = AppConstants.SORT_MODE_NAME_ASC;
                    } else if (checkedId == R.id.sort_name_desc) {
                        newSortMode = AppConstants.SORT_MODE_NAME_DESC;
                    }

                    currentSortMode = newSortMode;
                    sharedPreferences.edit().putInt(KEY_SORT_MODE, newSortMode).apply();
                    filterApps(currentSearchQuery); // Re-filter and sort
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create();

        sortDialog.show();

        boolean isDarkTheme = sharedPreferences.getBoolean(KEY_AMOLED, false)
                || sharedPreferences.getInt(KEY_THEME,
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                        == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
        if (isDarkTheme) {
            sortDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(Color.WHITE);
            sortDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(Color.WHITE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up resources
        shellManager.removeShizukuPermissionListener();
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        ramMonitor.stopMonitoring();
        binding = null;
    }
}
