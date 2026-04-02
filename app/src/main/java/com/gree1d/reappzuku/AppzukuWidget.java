package com.gree1d.reappzuku;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

public class AppzukuWidget extends AppWidgetProvider {
    private static final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

        // Intent for clean action
        Intent intent = new Intent(context, ShappkyService.class);
        intent.setAction("TRIGGER_KILL");

        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pendingIntent = PendingIntent.getForegroundService(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getService(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        views.setOnClickPendingIntent(R.id.widget_button_clean, pendingIntent);

        // Update RAM text in background to avoid ANR
        new Thread(() -> {
            long totalRam = 0;
            long availableRam = 0;
            try (java.io.RandomAccessFile reader = new java.io.RandomAccessFile("/proc/meminfo", "r")) {
                totalRam = Long.parseLong(reader.readLine().replaceAll("\\D+", "")) / 1024;
                reader.readLine(); // Free
                availableRam = Long.parseLong(reader.readLine().replaceAll("\\D+", "")) / 1024;
            } catch (Exception ignored) {
            }

            if (totalRam > 0) {
                views.setTextViewText(R.id.widget_ram_text,
                        "RAM: " + (totalRam - availableRam) + "MB / " + totalRam + "MB");
            }
            handler.post(() -> appWidgetManager.updateAppWidget(appWidgetId, views));
        }).start();
    }
}
