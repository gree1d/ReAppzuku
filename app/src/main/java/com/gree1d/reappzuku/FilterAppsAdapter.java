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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FilterAppsAdapter extends BaseAdapter implements Filterable {

    private final List<AppModel> allApps;
    private List<AppModel> filteredApps;
    private final LayoutInflater inflater;
    private AppFilter filter;
    
    // Filter flags
    private boolean showSystem = false;
    private boolean showUser = true;
    private boolean showRunningOnly = false;
    private CharSequence lastConstraint = "";

    public FilterAppsAdapter(Context context, List<AppModel> apps, Set<String> selectedApps) {
        this.inflater = LayoutInflater.from(context);

        // Mark selected apps first, then sort so selected appear at top
        for (AppModel app : apps) {
            if (selectedApps.contains(app.getPackageName())) {
                app.setSelected(true);
            }
        }

        Collections.sort(apps, (app1, app2) -> {
            // Selected apps first
            if (app1.isSelected() != app2.isSelected()) {
                return app1.isSelected() ? -1 : 1;
            }
            // Then user apps before system apps
            if (app1.isSystemApp() != app2.isSystemApp()) {
                return app1.isSystemApp() ? 1 : -1;
            }
            // Then alphabetically
            return app1.getAppName().compareToIgnoreCase(app2.getAppName());
        });

        this.allApps = apps;
        this.filteredApps = new ArrayList<>();
        filterInitialList();
    }
    
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
        // System/User filter
        if (app.isSystemApp() && !showSystem) return false;
        if (!app.isSystemApp() && !showUser) return false;
        
        // Running filter (assuming > 0 bytes means running)
        if (showRunningOnly && app.getAppRamBytes() <= 0) return false;
        
        return true;
    }

    @Override
    public int getCount() {
        return filteredApps.size();
    }

    @Override
    public AppModel getItem(int position) {
        return filteredApps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_filter_app, parent, false);
            holder = new ViewHolder();
            holder.appName = convertView.findViewById(R.id.filter_app_name);
            holder.appStatus = convertView.findViewById(R.id.filter_app_status);
            holder.appIcon = convertView.findViewById(R.id.filter_app_icon);
            holder.checkBox = convertView.findViewById(R.id.filter_app_checkbox);
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

        // Handle both checkbox clicks and row clicks uniformly
        View.OnClickListener toggleListener = v -> {
            boolean newState = !holder.checkBox.isChecked();
            holder.checkBox.setChecked(newState);
            app.setSelected(newState);
        };

        holder.checkBox.setOnClickListener(v -> {
            // Checkbox already toggled visually, just update model
            app.setSelected(holder.checkBox.isChecked());
        });

        convertView.setOnClickListener(toggleListener);

        return convertView;
    }

    public Set<String> getSelectedPackages() {
        Set<String> selected = new HashSet<>();
        // Return selected from ALL apps, not just filtered
        for (AppModel app : allApps) {
            if (app.isSelected()) {
                selected.add(app.getPackageName());
            }
        }
        return selected;
    }

    public void clearSelection() {
        for (AppModel app : allApps) {
            app.setSelected(false);
        }
        notifyDataSetChanged();
    }

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
            lastConstraint = constraint; // Store for re-filtering
            FilterResults results = new FilterResults();
            List<AppModel> filteredList = new ArrayList<>();
            
            String filterString = "";
            if (constraint != null && constraint.length() > 0) {
                 filterString = constraint.toString().toLowerCase().trim();
            }

            for (AppModel app : allApps) {
                // Check boolean filters first
                if (!shouldShow(app)) continue;

                // Check text filter
                if (filterString.isEmpty() || 
                    app.getAppName().toLowerCase().contains(filterString) ||
                    app.getPackageName().toLowerCase().contains(filterString)) {
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
    }
}
