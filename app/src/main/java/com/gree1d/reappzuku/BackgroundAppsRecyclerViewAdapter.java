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

            // Status visualization
            binding.linear1.setSelected(app.isSelected());

            if (app.isProtected()) {
                binding.getRoot().setAlpha(0.4f);
                binding.btnAppAction.setImageResource(R.drawable.ic_protected);
                binding.btnAppAction.setVisibility(View.VISIBLE);
                binding.btnAppAction.setClickable(false);
                binding.linearOverflow.setVisibility(View.GONE);
            } else if (app.isWhitelisted()) {
                binding.getRoot().setAlpha(0.85f);
                binding.btnAppAction.setImageResource(R.drawable.ic_force_stop);
                binding.btnAppAction.setVisibility(View.GONE);
                binding.btnAppAction.setClickable(false);
                binding.linearOverflow.setVisibility(View.VISIBLE);
            } else {
                binding.getRoot().setAlpha(1.0f);
                binding.btnAppAction.setImageResource(R.drawable.ic_force_stop);
                binding.btnAppAction.setVisibility(app.isSelected() ? View.GONE : View.VISIBLE);
                binding.btnAppAction.setClickable(true);
                binding.btnAppAction.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (actionListener != null && pos != RecyclerView.NO_POSITION) {
                        actionListener.onKillApp(app, pos);
                    }
                });
                binding.linearOverflow.setVisibility(app.isSelected() ? View.GONE : View.VISIBLE);
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
