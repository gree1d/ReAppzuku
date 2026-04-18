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
            binding.appRam.setText(app.getAppRam());
            binding.appIcon.setImageDrawable(app.getAppIcon());

            binding.whitelistIcon.setVisibility(app.isWhitelisted() ? View.VISIBLE : View.GONE);
            binding.linear1.setSelected(false);
            binding.linearOverflow.setVisibility(View.GONE);

            // ── Клики на весь item ────────────────────────────────────────────
            // Обработчики ставим на linear1 — он имеет background с ripple
            // и первым получает touch события. itemView их уже не видит.
            itemView.setOnClickListener(null);
            itemView.setOnLongClickListener(null);

            if (selectionMode) {
                // В режиме выделения: короткий клик = toggle select
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
                // Обычный режим: короткий клик = меню, длинный = войти в выделение
                binding.linear1.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        // Берём актуальный app из адаптера, а не захваченный в лямбде —
                        // после submitList/DiffUtil объект мог смениться
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
            // Alpha применяем на каждый дочерний вью по отдельности —
            // setAlpha на getRoot ненадёжен при recycling ViewHolder
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

            if (app.isProtected()) {
                binding.btnAppAction.setImageResource(R.drawable.ic_protected);
                binding.btnAppAction.setAlpha(alpha);
                binding.btnAppAction.setVisibility(View.VISIBLE);
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
                   oldItem.getAppRam().equals(newItem.getAppRam());
        }
    }
}
