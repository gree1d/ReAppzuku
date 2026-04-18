package com.gree1d.reappzuku;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.gree1d.reappzuku.databinding.ItemBinding;

import java.util.List;

public class BackgroundAppsRecyclerViewAdapter extends ListAdapter<AppModel, BackgroundAppsRecyclerViewAdapter.ViewHolder> {

    private final Context context;
    private OnAppActionListener actionListener;

    // Флаг: есть ли хоть одно выбранное приложение в текущем списке
    private boolean anySelected = false;

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
        // Пересчитываем флаг до того, как список попадёт в DiffUtil
        anySelected = list != null && list.stream().anyMatch(AppModel::isSelected);
        super.submitList(list);
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

            binding.linear1.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                    actionListener.onAppClick(app, pos);
                }
            });

            binding.linear1.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (actionListener != null && !app.isProtected() && pos != RecyclerView.NO_POSITION) {
                    actionListener.onToggleWhitelist(app, pos);
                    return true;
                }
                return false;
            });

            binding.btnOverflow.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onOverflowClick(app, v);
                }
            });

            // Убираем фоновое выделение — оно было ненадёжным
            binding.linear1.setSelected(false);

            if (app.isProtected()) {
                // Protected: иконка замка, полупрозрачный, без действий
                binding.getRoot().setAlpha(0.4f);
                binding.btnAppAction.setImageResource(R.drawable.ic_protected);
                binding.btnAppAction.setColorFilter(null);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(false);
                binding.linearOverflow.setVisibility(View.GONE);

            } else if (app.isWhitelisted()) {
                // Whitelisted: ic_force_stop скрыт, только overflow
                binding.getRoot().setAlpha(0.85f);
                binding.btnAppAction.setColorFilter(null);
                binding.btnAppAction.setVisibility(View.GONE);
                binding.btnAppAction.setClickable(false);
                binding.linearOverflow.setVisibility(View.VISIBLE);

            } else if (app.isSelected()) {
                // Выбрано: красный чекбокс (заполненный)
                binding.getRoot().setAlpha(1.0f);
                Drawable checked = ContextCompat.getDrawable(context, android.R.drawable.checkbox_on_background);
                if (checked != null) {
                    checked = checked.mutate();
                    checked.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN);
                }
                binding.btnAppAction.setImageDrawable(checked);
                binding.btnAppAction.setColorFilter(null); // colorFilter уже в drawable
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setOnClickListener(v -> {
                    // Клик на чекбокс = снять выделение (как клик на item)
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onAppClick(app, pos);
                    }
                });
                binding.linearOverflow.setVisibility(View.GONE);

            } else if (anySelected) {
                // Есть выделенные, но этот не выделен: красный пустой чекбокс
                binding.getRoot().setAlpha(1.0f);
                Drawable unchecked = ContextCompat.getDrawable(context, android.R.drawable.checkbox_off_background);
                if (unchecked != null) {
                    unchecked = unchecked.mutate();
                    unchecked.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN);
                }
                binding.btnAppAction.setImageDrawable(unchecked);
                binding.btnAppAction.setColorFilter(null);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setOnClickListener(v -> {
                    // Клик на пустой чекбокс = выбрать это приложение
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onAppClick(app, pos);
                    }
                });
                binding.linearOverflow.setVisibility(View.GONE);

            } else {
                // Обычное состояние: ic_force_stop
                binding.getRoot().setAlpha(1.0f);
                binding.btnAppAction.setImageResource(R.drawable.ic_force_stop);
                binding.btnAppAction.setColorFilter(null);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onKillApp(app, pos);
                    }
                });
                binding.linearOverflow.setVisibility(View.VISIBLE);
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
