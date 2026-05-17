package com.gree1d.reappzuku;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gree1d.reappzuku.databinding.ItemBinding;

import java.util.ArrayList;
import java.util.List;

public class BackgroundAppsRecyclerViewAdapter extends RecyclerView.Adapter<BackgroundAppsRecyclerViewAdapter.ViewHolder> {

    private final Context context;
    private OnAppActionListener actionListener;
    private final List<AppModel> items = new ArrayList<>();
    private boolean selectionMode = false;

    public interface OnAppActionListener {
        void onKillApp(AppModel app, int position);
        void onToggleWhitelist(AppModel app, int position);
        void onAppClick(AppModel app, int position);
        void onOverflowClick(AppModel app, View anchor);
    }

    public BackgroundAppsRecyclerViewAdapter(Context context) {
        this.context = context;
    }

    public void setOnAppActionListener(OnAppActionListener listener) {
        this.actionListener = listener;
    }

    public void submitList(List<AppModel> list) {
        items.clear();
        if (list != null) items.addAll(list);
        selectionMode = items.stream().anyMatch(AppModel::isSelected);
        notifyDataSetChanged();
    }
    
    public void updateCpu(List<AppModel> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public List<AppModel> getCurrentList() {
        return items;
    }

    public boolean refreshSelectionMode(boolean hasSelection) {
        if (hasSelection != selectionMode) {
            selectionMode = hasSelection;
            notifyDataSetChanged();
            return true;
        }
        return false;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBinding binding = ItemBinding.inflate(LayoutInflater.from(context), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), position);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains("cpu")) {
            AppModel app = items.get(position);
            String cpuText = app.getCpuUsage();
            holder.binding.appCpu.setText(cpuText != null ? cpuText : "");
        } else {
            onBindViewHolder(holder, position);
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemBinding binding;

        ViewHolder(ItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(AppModel app, int position) {
            binding.appName.setText(app.getAppName());
            binding.appPkg.setText(app.getPackageName());

            String ramText = app.getAppRam();
            if (ramText != null && !ramText.isEmpty()) {
                binding.appRam.setText(context.getString(R.string.app_ram_label, ramText));
            } else {
                binding.appRam.setText("");
            }

            String cpuText = app.getCpuUsage();
            binding.appCpu.setText(cpuText != null ? cpuText : "");

            binding.appIcon.setImageDrawable(app.getAppIcon());

            boolean persistent = app.isPersistentApp();
            binding.badgePersistent.setVisibility(persistent ? View.VISIBLE : View.GONE);
            binding.badgeSystem.setVisibility(!persistent && app.isSystemApp() ? View.VISIBLE : View.GONE);

            binding.whitelistIcon.setVisibility(app.isWhitelisted() ? View.VISIBLE : View.GONE);
            binding.protectedIcon.setVisibility(app.isProtected() ? View.VISIBLE : View.GONE);

            binding.linear1.setSelected(false);
            binding.linearOverflow.setVisibility(View.GONE);

            itemView.setOnClickListener(null);
            itemView.setOnLongClickListener(null);

            if (selectionMode) {
                binding.linear1.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        AppModel current = items.get(pos);
                        if (!current.isProtected() && !current.isWhitelisted()) {
                            actionListener.onAppClick(current, pos);
                        }
                    }
                });
                binding.linear1.setOnLongClickListener(null);
            } else {
                binding.linear1.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        AppModel current = items.get(pos);
                        actionListener.onOverflowClick(current, v);
                    }
                });
                binding.linear1.setOnLongClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        AppModel current = items.get(pos);
                        if (!current.isProtected() && !current.isWhitelisted()) {
                            actionListener.onAppClick(current, pos);
                            return true;
                        }
                    }
                    return false;
                });
            }

            float alpha;
            if (app.isProtected()) {
                alpha = 0.4f;
            } else if (app.isWhitelisted()) {
                alpha = 0.85f;
            } else {
                alpha = 1.0f;
            }
            binding.appIcon.setAlpha(alpha);
            binding.appName.setAlpha(alpha);
            binding.appPkg.setAlpha(alpha);
            binding.appRam.setAlpha(alpha);
            binding.appCpu.setAlpha(alpha);
            binding.badgeSystem.setAlpha(alpha);
            binding.badgePersistent.setAlpha(alpha);

            if (app.isProtected()) {
                binding.btnAppAction.setVisibility(View.GONE);
                binding.btnAppAction.setClickable(false);
                binding.btnAppAction.setOnClickListener(null);

            } else if (app.isWhitelisted()) {
                binding.btnAppAction.setAlpha(alpha);
                binding.btnAppAction.setVisibility(View.GONE);
                binding.btnAppAction.setClickable(false);
                binding.btnAppAction.setOnClickListener(null);

            } else if (selectionMode) {
                binding.btnAppAction.setAlpha(1.0f);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setImageResource(
                        app.isSelected()
                                ? R.drawable.ic_checkbox_checked
                                : R.drawable.ic_checkbox_unchecked);
                binding.btnAppAction.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onAppClick(items.get(pos), pos);
                    }
                });

            } else {
                binding.btnAppAction.setAlpha(1.0f);
                binding.btnAppAction.setImageResource(R.drawable.ic_force_stop);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onKillApp(items.get(pos), pos);
                    }
                });
            }
        }
    }
}
