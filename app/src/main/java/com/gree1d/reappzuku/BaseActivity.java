package com.gree1d.reappzuku;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;

import static com.gree1d.reappzuku.AppConstants.*;
import static com.gree1d.reappzuku.PreferenceKeys.*;

public abstract class BaseActivity extends AppCompatActivity {
    protected static final String PREFERENCES_NAME = "AppPreferences";
    protected static final String KEY_THEME = "appTheme";
    protected SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        sharedPreferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        applyAccent();
        applyTheme();
        super.onCreate(savedInstanceState);
    }

    protected void applyAccent() {
        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        if (accent == ACCENT_SYSTEM) {
            // Системный акцент — используем DynamicColors
            DynamicColors.applyToActivityIfAvailable(this);
        } else {
            // Пользовательский акцент — DynamicColors не применяем
            switch (accent) {
                case ACCENT_INDIGO:  setTheme(R.style.AppTheme_AccentIndigo);  break;
                case ACCENT_CRIMSON: setTheme(R.style.AppTheme_AccentCrimson); break;
                case ACCENT_FOREST:  setTheme(R.style.AppTheme_AccentForest);  break;
                case ACCENT_SLATE:   setTheme(R.style.AppTheme_AccentSlate);   break;
                case ACCENT_ROSE:    setTheme(R.style.AppTheme_AccentRose);    break;
                case ACCENT_AMBER:   setTheme(R.style.AppTheme_AccentAmber);   break;
                case ACCENT_TEAL:    setTheme(R.style.AppTheme_AccentTeal);    break;
                default:             setTheme(R.style.AppTheme_AccentIndigo);  break;
            }
        }
    }

    protected void applyTheme() {
        int theme = sharedPreferences.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (theme == -1) {
            // AMOLED — принудительно тёмная тема + чёрный фон
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            getTheme().applyStyle(R.style.AppTheme_Amoled, true);
        } else {
            AppCompatDelegate.setDefaultNightMode(theme);
        }
    }
}
