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

    public interface OnSelectionChangedListener {
        void onSelectionChanged();
    }

    private final List<AppModel> allApps;
    private List<AppModel> filteredApps;
    private final LayoutInflater inflater;
    private final Context context;
    private AppFilter filter;

    private boolean showSystem = false;
    private boolean showUser = true;
    private boolean showRunningOnly = false;
    private CharSequence lastConstraint = "";

    private final boolean restrictionMode;

    private final Map<String, BackgroundAppManager.RestrictionType> restrictionTypeMap;

    private final Map<String, Integer> manualOpsMaskMap;

    private OnSelectionChangedListener selectionChangedListener;

    public FilterAppsAdapter(Context context, List<AppModel> apps, Set<String> selectedApps) {
        this(context, apps, selectedApps, null, null, null, false);
    }

    public FilterAppsAdapter(Context context, List<AppModel> apps,
                             Set<String> selectedApps, Set<String> hardRestrictedApps) {
        this(context, apps, selectedApps, hardRestrictedApps, null, null, true);
    }

    public FilterAppsAdapter(Context context, List<AppModel> apps,
                             Set<String> selectedApps,
                             Set<String> hardRestrictedApps,
                             Set<String> manualRestrictedApps,
                             Map<String, Integer> initialMasks) {
        this(context, apps, selectedApps, hardRestrictedApps, manualRestrictedApps, initialMasks, true);
    }

    private FilterAppsAdapter(Context context, List<AppModel> apps,
                               Set<String> selectedApps,
                               Set<String> hardRestrictedApps,
                               Set<String> manualRestrictedApps,
                               Map<String, Integer> initialMasks,
                               boolean restrictionMode) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.restrictionMode = restrictionMode;
        this.restrictionTypeMap = new HashMap<>();
        this.manualOpsMaskMap = new HashMap<>();

        for (AppModel app : apps) {
            if (selectedApps.contains(app.getPackageName())) {
                app.setSelected(true);
            }
        }

        if (restrictionMode) {
            if (hardRestrictedApps != null) {
                for (String pkg : hardRestrictedApps) {
                    restrictionTypeMap.put(pkg, BackgroundAppManager.RestrictionType.HARD);
                }
            }
            if (manualRestrictedApps != null) {
                for (String pkg : manualRestrictedApps) {
                    restrictionTypeMap.put(pkg, BackgroundAppManager.RestrictionType.MANUAL);
                }
            }
            if (initialMasks != null) {
                manualOpsMaskMap.putAll(initialMasks);
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

    public Set<String> getSelectedPackages() {
        Set<String> selected = new HashSet<>();
        for (AppModel app : allApps) {
            if (app.isSelected()) selected.add(app.getPackageName());
        }
        return selected;
    }

    public Set<String> getHardRestrictedPackages() {
        Set<String> hard = new HashSet<>();
        for (AppModel app : allApps) {
            if (app.isSelected()
                    && restrictionTypeMap.get(app.getPackageName())
                       == BackgroundAppManager.RestrictionType.HARD) {
                hard.add(app.getPackageName());
            }
        }
        return hard;
    }

    public Set<String> getManualRestrictedPackages() {
        Set<String> manual = new HashSet<>();
        for (AppModel app : allApps) {
            if (app.isSelected()
                    && restrictionTypeMap.get(app.getPackageName())
                       == BackgroundAppManager.RestrictionType.MANUAL) {
                manual.add(app.getPackageName());
            }
        }
        return manual;
    }

    public Map<String, Integer> getManualOpsMasks() {
        return new HashMap<>(manualOpsMaskMap);
    }

    public void clearSelection() {
        for (AppModel app : allApps) {
            app.setSelected(false);
        }
        notifyDataSetChanged();
    }

    private void filterInitialList() {
        this.filteredApps.clear();
        for (AppModel app : allApps) {
            if (shouldShow(app)) this.filteredApps.add(app);
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

    @Override public int getCount() { return filteredApps.size(); }
    @Override public AppModel getItem(int position) { return filteredApps.get(position); }
    @Override public long getItemId(int position) { return position; }

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

        String statusText = app.getBackgroundRestrictionStatusText(context);
        if (statusText.isEmpty()) {
            holder.appStatus.setVisibility(View.GONE);
        } else {
            holder.appStatus.setVisibility(View.VISIBLE);
            holder.appStatus.setText(statusText);
        }

        holder.appIcon.setImageDrawable(app.getAppIcon());
        holder.checkBox.setChecked(app.isSelected());

        if (restrictionMode && holder.restrictionType != null) {
            if (app.isSelected()) {
                BackgroundAppManager.RestrictionType type =
                        restrictionTypeMap.getOrDefault(app.getPackageName(),
                                BackgroundAppManager.RestrictionType.SOFT);
                holder.restrictionType.setVisibility(View.VISIBLE);
                holder.restrictionType.setText(badgeLabel(type));
                holder.restrictionType.setOnClickListener(
                        v -> showRestrictionTypeDialog(app, holder.restrictionType));
            } else {
                holder.restrictionType.setVisibility(View.GONE);
                holder.restrictionType.setOnClickListener(null);
            }
        } else if (holder.restrictionType != null) {
            holder.restrictionType.setVisibility(View.GONE);
        }

        final ViewHolder h = holder;

        holder.checkBox.setOnClickListener(v -> {
            app.setSelected(h.checkBox.isChecked());
            if (restrictionMode && !app.isSelected()) {
                restrictionTypeMap.remove(app.getPackageName());
                manualOpsMaskMap.remove(app.getPackageName());
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
                manualOpsMaskMap.remove(app.getPackageName());
            }
            notifyDataSetChanged();
            notifySelectionChanged();
        });

        return convertView;
    }

    private String badgeLabel(BackgroundAppManager.RestrictionType type) {
        switch (type) {
            case HARD:   return context.getString(R.string.restriction_badge_hard);
            case MANUAL: return context.getString(R.string.restriction_badge_manual);
            default:     return context.getString(R.string.restriction_badge_soft);
        }
    }

    private void showRestrictionTypeDialog(AppModel app, TextView chipView) {
        BackgroundAppManager.RestrictionType current =
                restrictionTypeMap.getOrDefault(app.getPackageName(),
                        BackgroundAppManager.RestrictionType.SOFT);

        android.widget.LinearLayout container = new android.widget.LinearLayout(context);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(0, 16, 0, 8);

        android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(context);
        radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        int paddingH = (int) (context.getResources().getDisplayMetrics().density * 24);

        android.widget.RadioButton softBtn = new android.widget.RadioButton(context);
        softBtn.setId(View.generateViewId());
        softBtn.setText(context.getString(R.string.filter_restriction_soft_option));
        softBtn.setPadding(paddingH, 24, paddingH, 24);
        softBtn.setChecked(current == BackgroundAppManager.RestrictionType.SOFT);
        radioGroup.addView(softBtn);

        radioGroup.addView(makeDivider(paddingH));

        android.widget.RadioButton hardBtn = new android.widget.RadioButton(context);
        hardBtn.setId(View.generateViewId());
        hardBtn.setText(context.getString(R.string.filter_restriction_hard_option));
        hardBtn.setPadding(paddingH, 24, paddingH, 24);
        hardBtn.setChecked(current == BackgroundAppManager.RestrictionType.HARD);
        radioGroup.addView(hardBtn);

        radioGroup.addView(makeDivider(paddingH));

        android.widget.RadioButton manualBtn = new android.widget.RadioButton(context);
        manualBtn.setId(View.generateViewId());
        manualBtn.setText(context.getString(R.string.filter_restriction_manual_option));
        manualBtn.setPadding(paddingH, 24, paddingH, 24);
        manualBtn.setChecked(current == BackgroundAppManager.RestrictionType.MANUAL);
        radioGroup.addView(manualBtn);

        container.addView(radioGroup);

        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.filter_restriction_type_dialog_title))
                .setView(container)
                .setNegativeButton(context.getString(R.string.dialog_cancel), null)
                .setPositiveButton(context.getString(R.string.dialog_apply), (dialog, which) -> {
                    BackgroundAppManager.RestrictionType chosen;
                    if (hardBtn.isChecked()) {
                        chosen = BackgroundAppManager.RestrictionType.HARD;
                        restrictionTypeMap.put(app.getPackageName(), chosen);
                        manualOpsMaskMap.remove(app.getPackageName());
                        chipView.setText(badgeLabel(chosen));
                        notifySelectionChanged();
                    } else if (manualBtn.isChecked()) {
                        chosen = BackgroundAppManager.RestrictionType.MANUAL;
                        restrictionTypeMap.put(app.getPackageName(), chosen);
                        int existingMask = manualOpsMaskMap.getOrDefault(
                                app.getPackageName(), 0x01);
                        showManualOpsDialog(app, chipView, existingMask);
                    } else {
                        chosen = BackgroundAppManager.RestrictionType.SOFT;
                        restrictionTypeMap.remove(app.getPackageName());
                        manualOpsMaskMap.remove(app.getPackageName());
                        chipView.setText(badgeLabel(chosen));
                        notifySelectionChanged();
                    }
                })
                .show();
    }

    private void showManualOpsDialog(AppModel app, TextView chipView, int currentMask) {
        String[] ops = BackgroundAppManager.ALL_OPS;

        String[] labels = {
            context.getString(R.string.manual_op_run_any_in_background),
            context.getString(R.string.manual_op_run_in_background),
            context.getString(R.string.manual_op_start_foreground),
            context.getString(R.string.manual_op_fgs_from_background),
            context.getString(R.string.manual_op_wake_lock),
            context.getString(R.string.manual_op_alarm_wakeup),
            context.getString(R.string.manual_op_boot_completed),
            context.getString(R.string.manual_op_interact_across_profiles),
        };

        boolean[] checked = new boolean[ops.length];
        for (int i = 0; i < ops.length; i++) {
            checked[i] = (currentMask & (1 << i)) != 0;
        }

        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        android.widget.LinearLayout listLayout = new android.widget.LinearLayout(context);
        listLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int paddingH = (int) (context.getResources().getDisplayMetrics().density * 16);
        int paddingV = (int) (context.getResources().getDisplayMetrics().density * 4);

        CheckBox[] boxes = new CheckBox[ops.length];
        for (int i = 0; i < ops.length; i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(context);
            cb.setText(labels[i]);
            cb.setChecked(checked[i]);
            cb.setPadding(paddingH, paddingV * 3, paddingH, paddingV * 3);
            cb.setOnCheckedChangeListener((btn, isChecked) -> checked[idx] = isChecked);
            boxes[i] = cb;
            listLayout.addView(cb);
        }

        scrollView.addView(listLayout);

        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.filter_manual_ops_dialog_title,
                        app.getAppName()))
                .setView(scrollView)
                .setNegativeButton(context.getString(R.string.dialog_cancel), (d, w) -> {
                    if (!manualOpsMaskMap.containsKey(app.getPackageName())) {
                        restrictionTypeMap.remove(app.getPackageName());
                        chipView.setText(badgeLabel(BackgroundAppManager.RestrictionType.SOFT));
                        notifySelectionChanged();
                    }
                })
                .setPositiveButton(context.getString(R.string.dialog_apply), (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < ops.length; i++) {
                        if (checked[i]) mask |= (1 << i);
                    }
                    if (mask == 0) {
                        restrictionTypeMap.remove(app.getPackageName());
                        manualOpsMaskMap.remove(app.getPackageName());
                        chipView.setText(badgeLabel(BackgroundAppManager.RestrictionType.SOFT));
                    } else {
                        manualOpsMaskMap.put(app.getPackageName(), mask);
                        chipView.setText(badgeLabel(BackgroundAppManager.RestrictionType.MANUAL));
                    }
                    notifySelectionChanged();
                })
                .show();
    }

    private View makeDivider(int paddingH) {
        View divider = new View(context);
        android.widget.LinearLayout.LayoutParams p =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(paddingH, 4, paddingH, 4);
        divider.setLayoutParams(p);
        divider.setBackgroundColor(0x22888888);
        return divider;
    }

    private void notifySelectionChanged() {
        if (selectionChangedListener != null) selectionChangedListener.onSelectionChanged();
    }

    @Override
    public Filter getFilter() {
        if (filter == null) filter = new AppFilter();
        return filter;
    }

    private class AppFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            lastConstraint = constraint;
            FilterResults results = new FilterResults();
            List<AppModel> filteredList = new ArrayList<>();
            String filterString = (constraint != null && constraint.length() > 0)
                    ? constraint.toString().toLowerCase().trim() : "";
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

    public static class ViewHolder {
        public TextView appName;
        public TextView appStatus;
        public ImageView appIcon;
        public CheckBox checkBox;
        public TextView restrictionType;
    }
}
