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
    protected static final String KEY_THEME_COMPAT = "appTheme";
    protected SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        sharedPreferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);

        int accent = sharedPreferences.getInt(KEY_ACCENT, ACCENT_SYSTEM);
        boolean isAmoled = sharedPreferences.getBoolean(KEY_AMOLED, false);
        int theme = sharedPreferences.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        boolean isSystemTheme = (theme == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        // Шаг 1: применить акцент через setTheme() ДО super.onCreate()
        if (isSystemTheme || accent == ACCENT_SYSTEM) {
            // Системная тема — DynamicColors
            DynamicColors.applyToActivityIfAvailable(this);
        } else {
            // Пользовательский акцент — без DynamicColors
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

        // Шаг 2: AMOLED — применяем поверх акцента
        if (isAmoled) {
            getTheme().applyStyle(R.style.AppTheme_Amoled, true);
        }

        // Шаг 3: ночной режим
        if (isAmoled) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(theme);
        }

        super.onCreate(savedInstanceState);
    }

    protected void applyTheme() {
        // Оставляем для обратной совместимости
    }
}
