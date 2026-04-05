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

        // Шаг 1: применить тему через setTheme() ДО super.onCreate()
        // AMOLED проверяем первым — иначе accent==ACCENT_SYSTEM съедает AMOLED
        if (isAmoled) {
            // AMOLED — всегда применяем AMOLED тему; акцент по умолчанию = Индиго
            switch (accent) {
                case ACCENT_CRIMSON:   setTheme(R.style.AppTheme_AccentCrimson_Amoled);   break;
                case ACCENT_FOREST:    setTheme(R.style.AppTheme_AccentForest_Amoled);    break;
                case ACCENT_SLATE:     setTheme(R.style.AppTheme_AccentSlate_Amoled);     break;
                case ACCENT_ROSE:      setTheme(R.style.AppTheme_AccentRose_Amoled);      break;
                case ACCENT_AMBER:     setTheme(R.style.AppTheme_AccentAmber_Amoled);     break;
                case ACCENT_TEAL:      setTheme(R.style.AppTheme_AccentTeal_Amoled);      break;
                case ACCENT_TERRACOTA: setTheme(R.style.AppTheme_AccentTerracota_Amoled); break;
                case ACCENT_MOCHA:     setTheme(R.style.AppTheme_AccentMocha_Amoled);     break;
                case ACCENT_OLIVE:     setTheme(R.style.AppTheme_AccentOlive_Amoled);     break;
                case ACCENT_STEEL:     setTheme(R.style.AppTheme_AccentSteel_Amoled);     break;
                default:               setTheme(R.style.AppTheme_AccentIndigo_Amoled);    break;
            }
        } else if (isSystemTheme || accent == ACCENT_SYSTEM) {
            // Системная тема — DynamicColors
            DynamicColors.applyToActivityIfAvailable(this);
        } else {
            // Обычный пользовательский акцент
            switch (accent) {
                case ACCENT_INDIGO:    setTheme(R.style.AppTheme_AccentIndigo);    break;
                case ACCENT_CRIMSON:   setTheme(R.style.AppTheme_AccentCrimson);   break;
                case ACCENT_FOREST:    setTheme(R.style.AppTheme_AccentForest);    break;
                case ACCENT_SLATE:     setTheme(R.style.AppTheme_AccentSlate);     break;
                case ACCENT_ROSE:      setTheme(R.style.AppTheme_AccentRose);      break;
                case ACCENT_AMBER:     setTheme(R.style.AppTheme_AccentAmber);     break;
                case ACCENT_TEAL:      setTheme(R.style.AppTheme_AccentTeal);      break;
                case ACCENT_TERRACOTA: setTheme(R.style.AppTheme_AccentTerracota); break;
                case ACCENT_MOCHA:     setTheme(R.style.AppTheme_AccentMocha);     break;
                case ACCENT_OLIVE:     setTheme(R.style.AppTheme_AccentOlive);     break;
                case ACCENT_STEEL:     setTheme(R.style.AppTheme_AccentSteel);     break;
                default:               setTheme(R.style.AppTheme_AccentIndigo);    break;
            }
        }

        // Шаг 2: ночной режим
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
