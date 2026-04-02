package com.gree1d.reappzuku;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;

public abstract class BaseActivity extends AppCompatActivity {
    protected static final String PREFERENCES_NAME = "AppPreferences";
    protected static final String KEY_THEME = "appTheme";
    protected SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        sharedPreferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        applyTheme();
        super.onCreate(savedInstanceState);
    }

    protected void applyTheme() {
        int theme = sharedPreferences.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(theme);
    }
}
