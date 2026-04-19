package com.gree1d.reappzuku;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.gree1d.reappzuku.databinding.ItemBinding;

import java.util.List;

public class BackgroundAppsRecyclerViewAdapter extends ListAdapter<AppModel, BackgroundAppsRecyclerViewAdapter.ViewHolder> {

    private final Context context;
    private OnAppActionListener actionListener;

    private boolean selectionMode = false;

    public interface OnAppActionListener {
        void onKillApp(AppModel app, int position);
        void onToggleWhitelist(AppModel app, int position);
        void onAppClick(AppModel app, int position);
        void onOverflowClick(AppModel app, View anchor);
    }

    public BackgroundAppsRecyclerViewAdapter(Context context) {
        super(new AppDiffCallback());
        this.context = context;
    }

    public void setOnAppActionListener(OnAppActionListener listener) {
        this.actionListener = listener;
    }

    @Override
    public void submitList(List<AppModel> list) {
        boolean newSelectionMode = list != null && list.stream().anyMatch(AppModel::isSelected);
        if (newSelectionMode != selectionMode) {
            selectionMode = newSelectionMode;
            super.submitList(list, this::notifyDataSetChanged);
        } else {
            super.submitList(list);
        }
    }

    /**
     * Вызывается из MainActivity сразу после app.setSelected().
     * hasSelection берётся из fullAppsList — он всегда актуален,
     * в отличие от getCurrentList() который обновляется асинхронно.
     */
    public boolean refreshSelectionMode(boolean hasSelection) {
        if (hasSelection != selectionMode) {
            selectionMode = hasSelection;
            notifyDataSetChanged();
            return true;
        }
        return false;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBinding binding = ItemBinding.inflate(LayoutInflater.from(context), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), position);
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

            // Задача 2: подпись ОЗУ: "ОЗУ: 123 МБ"
            String ramText = app.getAppRam();
            if (ramText != null && !ramText.isEmpty()) {
                binding.appRam.setText(context.getString(R.string.app_ram_label, ramText));
            } else {
                binding.appRam.setText("");
            }

            binding.appIcon.setImageDrawable(app.getAppIcon());

            // Задача 1: бейджи System / Persistent
            binding.badgeSystem.setVisibility(app.isSystemApp() ? View.VISIBLE : View.GONE);
            binding.badgePersistent.setVisibility(app.isPersistentApp() ? View.VISIBLE : View.GONE);

            // Задача 3: иконка whitelist в строке названия
            binding.whitelistIcon.setVisibility(app.isWhitelisted() ? View.VISIBLE : View.GONE);

            // Задача 3: иконка protected в строке названия (рядом с whitelist)
            binding.protectedIcon.setVisibility(app.isProtected() ? View.VISIBLE : View.GONE);

            binding.linear1.setSelected(false);
            binding.linearOverflow.setVisibility(View.GONE);

            // ── Клики на весь item ────────────────────────────────────────────
            itemView.setOnClickListener(null);
            itemView.setOnLongClickListener(null);

            if (selectionMode) {
                binding.linear1.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        AppModel current = getItem(pos);
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
                        AppModel current = getItem(pos);
                        actionListener.onOverflowClick(current, v);
                    }
                });
                binding.linear1.setOnLongClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        AppModel current = getItem(pos);
                        if (!current.isProtected() && !current.isWhitelisted()) {
                            actionListener.onAppClick(current, pos);
                            return true;
                        }
                    }
                    return false;
                });
            }

            // ── Внешний вид ───────────────────────────────────────────────────
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
            binding.badgeSystem.setAlpha(alpha);
            binding.badgePersistent.setAlpha(alpha);

            if (app.isProtected()) {
                // Кнопку действия скрываем — protected уже показан иконкой в строке названия
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
                        actionListener.onAppClick(getItem(pos), pos);
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
                        actionListener.onKillApp(getItem(pos), pos);
                    }
                });
            }
        }
    }

    static class AppDiffCallback extends DiffUtil.ItemCallback<AppModel> {
        @Override
        public boolean areItemsTheSame(@NonNull AppModel oldItem, @NonNull AppModel newItem) {
            return oldItem.getPackageName().equals(newItem.getPackageName());
        }

        @Override
        public boolean areContentsTheSame(@NonNull AppModel oldItem, @NonNull AppModel newItem) {
            return oldItem.isSelected() == newItem.isSelected() &&
                   oldItem.isWhitelisted() == newItem.isWhitelisted() &&
                   oldItem.isProtected() == newItem.isProtected() &&
                   oldItem.isSystemApp() == newItem.isSystemApp() &&
                   oldItem.isPersistentApp() == newItem.isPersistentApp() &&
                   oldItem.getAppRam().equals(newItem.getAppRam());
        }
    }
}
