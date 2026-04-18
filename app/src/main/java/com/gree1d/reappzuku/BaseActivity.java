package com.gree1d.reappzuku;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
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
                case ACCENT_INDIGO:    setTheme(R.style.AppTheme_AccentIndigo_Amoled);    break;
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

                case ACCENT_APRICOT:     setTheme(R.style.AppTheme_AccentApricot_Amoled);     break;
                case ACCENT_SKY:     setTheme(R.style.AppTheme_AccentSky_Amoled);     break;
                case ACCENT_PAPAYA:     setTheme(R.style.AppTheme_AccentPapaya_Amoled);     break;
                case ACCENT_LAVENDER:     setTheme(R.style.AppTheme_AccentLavender_Amoled);     break;
                case ACCENT_MINT:     setTheme(R.style.AppTheme_AccentMint_Amoled);     break;
                case ACCENT_PEACH:     setTheme(R.style.AppTheme_AccentPeach_Amoled);     break;
                case ACCENT_POWDER:     setTheme(R.style.AppTheme_AccentPowder_Amoled);     break;
                case ACCENT_FOG:     setTheme(R.style.AppTheme_AccentFog_Amoled);     break;
                default:               setTheme(R.style.AppTheme_Amoled);                 break; // ← дефолт = базовая AMOLED без акцента
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

                case ACCENT_APRICOT:     setTheme(R.style.AppTheme_AccentApricot);     break;
                case ACCENT_SKY:     setTheme(R.style.AppTheme_AccentSky);     break;
                case ACCENT_PAPAYA:     setTheme(R.style.AppTheme_AccentPapaya);     break;
                case ACCENT_LAVENDER:     setTheme(R.style.AppTheme_AccentLavender);     break;
                case ACCENT_MINT:     setTheme(R.style.AppTheme_AccentMint);     break;
                case ACCENT_PEACH:     setTheme(R.style.AppTheme_AccentPeach);     break;
                case ACCENT_POWDER:     setTheme(R.style.AppTheme_AccentPowder);     break;
                case ACCENT_FOG:     setTheme(R.style.AppTheme_AccentFog);     break;
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

        // Шаг 3: статус-бар — светлые иконки только если тема тёмная
        applyStatusBarAppearance(isAmoled, theme);
    }

    private void applyStatusBarAppearance(boolean isAmoled, int theme) {
        if (getWindow() == null) return;

        // Определяем светлая ли сейчас тема фактически
        boolean isCurrentlyLight;
        if (isAmoled) {
            isCurrentlyLight = false;
        } else if (theme == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            isCurrentlyLight = (nightMode != Configuration.UI_MODE_NIGHT_YES);
        } else {
            isCurrentlyLight = (theme == AppCompatDelegate.MODE_NIGHT_NO);
        }

        View decorView = getWindow().getDecorView();
        int flags = decorView.getSystemUiVisibility();
        if (isCurrentlyLight) {
            // Светлая тема — тёмные иконки статус-бара
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            // Тёмная тема — светлые иконки статус-бара
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decorView.setSystemUiVisibility(flags);
    }

    protected void applyTheme() {
        // Оставляем для обратной совместимости
    }
}
