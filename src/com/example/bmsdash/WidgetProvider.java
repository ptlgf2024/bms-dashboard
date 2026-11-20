package com.example.bmsdash;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 桌面小部件：显示电压表盘、电量环、最后更新时间，点击刷新自动连接设备 */
public class WidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.example.bmsdash.REFRESH";
    public static final String CACHE = "widget_cache";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) updateOne(context, mgr, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        android.util.Log.d("BmsDash", "W onReceive " + intent.getAction());
        if (ACTION_REFRESH.equals(intent.getAction())) {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) {
                JobInfo job = new JobInfo.Builder(1001,
                        new ComponentName(context, WidgetRefreshJobService.class))
                        .setMinimumLatency(1)
                        .setOverrideDeadline(1500)
                        .build();
                int r = js.schedule(job);
                android.util.Log.d("BmsDash", "W job schedule result=" + r);
            }
        }
    }

    public static void updateAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;
        updateAll(context, mgr, new ComponentName(context, WidgetProvider.class));
    }

    private static void updateAll(Context context, AppWidgetManager mgr, ComponentName cn) {
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null) return;
        for (int id : ids) updateOne(context, mgr, id);
    }

    private static void updateOne(Context context, AppWidgetManager mgr, int id) {
        SharedPreferences p = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        float voltage = p.getFloat("voltage", 0f);
        int soc = p.getInt("soc", -1);
        float temp = p.getFloat("temp", 0f);
        int diff = p.getInt("diff", 0);
        String status = p.getString("status", "");
        long time = p.getLong("time", 0L);
        boolean hasData = voltage > 0;

        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        rv.setImageViewBitmap(R.id.widget_gauge, WidgetDrawables.gauge(voltage, hasData));
        rv.setImageViewBitmap(R.id.widget_arc, WidgetDrawables.arc(soc, hasData));
        rv.setTextViewText(R.id.widget_voltage, hasData
                ? String.format(Locale.getDefault(), "电压 %.2f V", voltage) : "电压 -- V");
        rv.setTextViewText(R.id.widget_soc, hasData
                ? String.format(Locale.getDefault(), "SOC %d %%", soc) : "SOC -- %");
        rv.setTextViewText(R.id.widget_temp, hasData
                ? String.format(Locale.getDefault(), "温度 %.1f ℃", temp) : "温度 -- ℃");
        rv.setTextViewText(R.id.widget_diff, hasData
                ? String.format(Locale.getDefault(), "压差 %d mV", diff) : "压差 -- mV");
        String timeText = time > 0
                ? "最后更新：" + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(time))
                : "最后更新：--";
        if (!status.isEmpty()) timeText = timeText + " · " + status;
        rv.setTextViewText(R.id.widget_time, timeText);

        Intent refresh = new Intent(context, WidgetProvider.class);
        refresh.setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_refresh, pi);
        mgr.updateAppWidget(id, rv);
    }

    /** App 读到数据时同步缓存并刷新小部件 */
    public static void saveAndUpdate(Context context, BmsData d) {
        SharedPreferences p = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        p.edit()
                .putFloat("voltage", (float) d.voltage)
                .putInt("soc", d.soc)
                .putFloat("temp", (float) d.tempMax)
                .putInt("diff", d.diff)
                .putInt("capacity", d.capacityMah)
                .putInt("cycles", d.cycles)
                .putInt("remaining_cap", d.remainingCapacityMah)
                .putInt("full_cap", d.fullCapacityMah)
                .putInt("humidity", d.humidity)
                .putInt("alarm", d.alarmStatus)
                .putInt("balance_ma", d.balanceCurrentMa)
                .putInt("software_ver", d.softwareVersion)
                .putInt("cell_count", d.reportedCellCount)
                .putInt("production_date", d.productionDate)
                .putString("cells", joinCells(d.cells))
                .putLong("time", System.currentTimeMillis())
                .putString("status", "")
                .apply();
        updateAll(context);
    }

    private static String joinCells(int[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int v : cells) sb.append(v).append(',');
        return sb.toString();
    }

    /** 更新状态提示（连接中 / 失败等） */
    public static void setStatus(Context context, String status) {
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                .putString("status", status).apply();
        updateAll(context);
    }
}
