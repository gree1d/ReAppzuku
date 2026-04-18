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
        void onAppClick(AppModel app, int position);       // длинный клик → select
        void onOverflowClick(AppModel app, View anchor);   // короткий клик → menu
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

    public boolean refreshSelectionMode() {
        boolean newSelectionMode = getCurrentList().stream().anyMatch(AppModel::isSelected);
        if (newSelectionMode != selectionMode) {
            selectionMode = newSelectionMode;
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
            binding.appRam.setText(app.getAppRam());
            binding.appIcon.setImageDrawable(app.getAppIcon());

            binding.whitelistIcon.setVisibility(app.isWhitelisted() ? View.VISIBLE : View.GONE);
            binding.linear1.setSelected(false);

            if (selectionMode) {
                // ── Режим выделения ──────────────────────────────────────────
                // Короткий клик = toggle select (весь item + кнопка-чекбокс)
                // Длинный клик = ничего (уже в режиме выделения)
                binding.linear1.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION
                            && !app.isProtected() && !app.isWhitelisted()) {
                        actionListener.onAppClick(app, pos);
                    }
                });
                binding.linear1.setOnLongClickListener(null);

            } else {
                // ── Обычный режим ─────────────────────────────────────────────
                // Короткий клик = открыть menu_app_options
                // Длинный клик  = войти в режим выделения (select этого app)
                binding.linear1.setOnClickListener(v -> {
                    if (actionListener != null) {
                        actionListener.onOverflowClick(app, v);
                    }
                });
                binding.linear1.setOnLongClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && !app.isProtected()
                            && !app.isWhitelisted() && pos != RecyclerView.NO_POSITION) {
                        actionListener.onAppClick(app, pos);
                        return true;
                    }
                    return false;
                });
            }

            // btn_overflow больше не нужен как отдельный обработчик —
            // весь item открывает меню. Скрываем его чтобы не путал.
            binding.linearOverflow.setVisibility(View.GONE);

            if (app.isProtected()) {
                binding.getRoot().setAlpha(0.4f);
                binding.btnAppAction.setImageResource(R.drawable.ic_protected);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(false);

            } else if (app.isWhitelisted()) {
                binding.getRoot().setAlpha(0.85f);
                binding.btnAppAction.setVisibility(View.GONE);
                binding.btnAppAction.setClickable(false);

            } else if (selectionMode) {
                binding.getRoot().setAlpha(1.0f);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setImageResource(
                        app.isSelected()
                                ? R.drawable.ic_checkbox_checked
                                : R.drawable.ic_checkbox_unchecked);
                // Клик на чекбокс = то же что клик на item
                binding.btnAppAction.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onAppClick(app, pos);
                    }
                });

            } else {
                // Обычный режим: ic_force_stop — прямой kill без подтверждения
                binding.getRoot().setAlpha(1.0f);
                binding.btnAppAction.setImageResource(R.drawable.ic_force_stop);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onKillApp(app, pos);
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
                   oldItem.getAppRam().equals(newItem.getAppRam());
        }
    }
}
