package com.gree1d.reappzuku;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FilterAppsAdapter extends BaseAdapter implements Filterable {

    /**
     * Callback invoked whenever the user changes a selection or restriction type.
     * Used by the dialog to switch the button label from "Сохранить" → "Применить".
     */
    public interface OnSelectionChangedListener {
        void onSelectionChanged();
    }

    private final List<AppModel> allApps;
    private List<AppModel> filteredApps;
    private final LayoutInflater inflater;
    private final Context context;
    private AppFilter filter;

    // Filter flags
    private boolean showSystem = false;
    private boolean showUser = true;
    private boolean showRunningOnly = false;
    private CharSequence lastConstraint = "";

    // Restriction-mode fields (only used in the background restriction dialog)
    private final boolean restrictionMode;
    // packageName → true means HARD restriction; false/absent means SOFT
    private final Map<String, Boolean> restrictionTypeMap;

    private OnSelectionChangedListener selectionChangedListener;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Standard constructor — used for whitelist / blacklist / hidden dialogs. */
    public FilterAppsAdapter(Context context, List<AppModel> apps, Set<String> selectedApps) {
        this(context, apps, selectedApps, null, false);
    }

    /**
     * Restriction-mode constructor — used exclusively for the background restriction dialog.
     *
     * @param selectedApps     packages that are currently restricted (checked)
     * @param hardRestrictedApps packages within selectedApps that use HARD restriction
     */
    public FilterAppsAdapter(Context context, List<AppModel> apps,
                             Set<String> selectedApps, Set<String> hardRestrictedApps) {
        this(context, apps, selectedApps, hardRestrictedApps, true);
    }

    private FilterAppsAdapter(Context context, List<AppModel> apps,
                               Set<String> selectedApps, Set<String> hardRestrictedApps,
                               boolean restrictionMode) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.restrictionMode = restrictionMode;
        this.restrictionTypeMap = new HashMap<>();

        // Mark selected apps
        for (AppModel app : apps) {
            if (selectedApps.contains(app.getPackageName())) {
                app.setSelected(true);
            }
        }

        // Build restriction type map
        if (restrictionMode && hardRestrictedApps != null) {
            for (String pkg : hardRestrictedApps) {
                restrictionTypeMap.put(pkg, true);
            }
        }

        Collections.sort(apps, (app1, app2) -> {
            if (app1.isSelected() != app2.isSelected()) {
                return app1.isSelected() ? -1 : 1;
            }
            if (app1.isSystemApp() != app2.isSystemApp()) {
                return app1.isSystemApp() ? 1 : -1;
            }
            return app1.getAppName().compareToIgnoreCase(app2.getAppName());
        });

        this.allApps = apps;
        this.filteredApps = new ArrayList<>();
        filterInitialList();
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    public Set<String> getSelectedPackages() {
        Set<String> selected = new HashSet<>();
        for (AppModel app : allApps) {
            if (app.isSelected()) {
                selected.add(app.getPackageName());
            }
        }
        return selected;
    }

    /** Returns the set of selected packages that have HARD restriction type. */
    public Set<String> getHardRestrictedPackages() {
        Set<String> hard = new HashSet<>();
        for (AppModel app : allApps) {
            if (app.isSelected() && Boolean.TRUE.equals(restrictionTypeMap.get(app.getPackageName()))) {
                hard.add(app.getPackageName());
            }
        }
        return hard;
    }

    public void clearSelection() {
        for (AppModel app : allApps) {
            app.setSelected(false);
        }
        notifyDataSetChanged();
    }

    // -----------------------------------------------------------------------
    // Filter support
    // -----------------------------------------------------------------------

    private void filterInitialList() {
        this.filteredApps.clear();
        for (AppModel app : allApps) {
            if (shouldShow(app)) {
                this.filteredApps.add(app);
            }
        }
    }

    public void setFilters(boolean showSystem, boolean showUser, boolean showRunningOnly) {
        this.showSystem = showSystem;
        this.showUser = showUser;
        this.showRunningOnly = showRunningOnly;
        getFilter().filter(lastConstraint);
    }

    private boolean shouldShow(AppModel app) {
        if (app.isSystemApp() && !showSystem) return false;
        if (!app.isSystemApp() && !showUser) return false;
        if (showRunningOnly && app.getAppRamBytes() <= 0) return false;
        return true;
    }

    // -----------------------------------------------------------------------
    // BaseAdapter
    // -----------------------------------------------------------------------

    @Override
    public int getCount() { return filteredApps.size(); }

    @Override
    public AppModel getItem(int position) { return filteredApps.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_filter_app, parent, false);
            holder = new ViewHolder();
            holder.appName        = convertView.findViewById(R.id.filter_app_name);
            holder.appStatus      = convertView.findViewById(R.id.filter_app_status);
            holder.appIcon        = convertView.findViewById(R.id.filter_app_icon);
            holder.checkBox       = convertView.findViewById(R.id.filter_app_checkbox);
            holder.restrictionType = convertView.findViewById(R.id.filter_restriction_type);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        AppModel app = getItem(position);
        holder.appName.setText(app.getAppName());

        String statusText = app.getBackgroundRestrictionStatusText();
        if (statusText.isEmpty()) {
            holder.appStatus.setVisibility(View.GONE);
        } else {
            holder.appStatus.setVisibility(View.VISIBLE);
            holder.appStatus.setText(statusText);
        }

        holder.appIcon.setImageDrawable(app.getAppIcon());
        holder.checkBox.setChecked(app.isSelected());

        // --- Restriction type chip (only in restriction mode, only for selected apps) ---
        if (restrictionMode && holder.restrictionType != null) {
            if (app.isSelected()) {
                boolean isHard = Boolean.TRUE.equals(restrictionTypeMap.get(app.getPackageName()));
                holder.restrictionType.setVisibility(View.VISIBLE);
                holder.restrictionType.setText(isHard ? "Жёсткое" : "Мягкое");
                holder.restrictionType.setOnClickListener(v -> showRestrictionTypeDialog(app, holder.restrictionType));
            } else {
                holder.restrictionType.setVisibility(View.GONE);
                holder.restrictionType.setOnClickListener(null);
            }
        } else if (holder.restrictionType != null) {
            holder.restrictionType.setVisibility(View.GONE);
        }

        // --- Row / checkbox click ---
        final ViewHolder h = holder;

        holder.checkBox.setOnClickListener(v -> {
            app.setSelected(holder.checkBox.isChecked());
            // When deselecting in restriction mode, remove hard assignment
            if (restrictionMode && !app.isSelected()) {
                restrictionTypeMap.remove(app.getPackageName());
            }
            notifyDataSetChanged();
            notifySelectionChanged();
        });

        convertView.setOnClickListener(v -> {
            boolean newState = !h.checkBox.isChecked();
            h.checkBox.setChecked(newState);
            app.setSelected(newState);
            if (restrictionMode && !newState) {
                restrictionTypeMap.remove(app.getPackageName());
            }
            notifyDataSetChanged();
            notifySelectionChanged();
        });

        return convertView;
    }

    // -----------------------------------------------------------------------
    // Restriction type dialog
    // -----------------------------------------------------------------------

    private void showRestrictionTypeDialog(AppModel app, TextView chipView) {
        boolean currentlyHard = Boolean.TRUE.equals(restrictionTypeMap.get(app.getPackageName()));
        int currentIndex = currentlyHard ? 1 : 0;

        String[] options = {
                "Мягкое (по умолчанию) — запрет фоновой работы (RUN_ANY_IN_BACKGROUND)",
                "Жёсткое — запрет запуска переднего плана (START_FOREGROUND)"
        };

        new AlertDialog.Builder(context)
                .setTitle("Тип ограничения")
                .setSingleChoiceItems(options, currentIndex, (dialog, which) -> {
                    boolean selectHard = (which == 1);
                    if (selectHard) {
                        restrictionTypeMap.put(app.getPackageName(), true);
                    } else {
                        restrictionTypeMap.remove(app.getPackageName());
                    }
                    chipView.setText(selectHard ? "Жёсткое" : "Мягкое");
                    dialog.dismiss();
                    notifySelectionChanged();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void notifySelectionChanged() {
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged();
        }
    }

    // -----------------------------------------------------------------------
    // Filterable
    // -----------------------------------------------------------------------

    @Override
    public Filter getFilter() {
        if (filter == null) {
            filter = new AppFilter();
        }
        return filter;
    }

    private class AppFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            lastConstraint = constraint;
            FilterResults results = new FilterResults();
            List<AppModel> filteredList = new ArrayList<>();

            String filterString = "";
            if (constraint != null && constraint.length() > 0) {
                filterString = constraint.toString().toLowerCase().trim();
            }

            for (AppModel app : allApps) {
                if (!shouldShow(app)) continue;
                if (filterString.isEmpty()
                        || app.getAppName().toLowerCase().contains(filterString)
                        || app.getPackageName().toLowerCase().contains(filterString)) {
                    filteredList.add(app);
                }
            }

            results.values = filteredList;
            results.count = filteredList.size();
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            filteredApps = (List<AppModel>) results.values;
            notifyDataSetChanged();
        }
    }

    // -----------------------------------------------------------------------
    // ViewHolder
    // -----------------------------------------------------------------------

    public static class ViewHolder {
        public TextView appName;
        public TextView appStatus;
        public ImageView appIcon;
        public CheckBox checkBox;
        public TextView restrictionType; // null when not in item_filter_app layout (safety)
    }
}
