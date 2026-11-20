package com.example.bmsdash;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

import java.util.List;

/** 小部件刷新任务：用 JobScheduler 在后台连接 BMS 并更新小部件 */
public class WidgetRefreshJobService extends JobService {
    private BleManager ble;
    private final Handler handler = new Handler();
    private JobParameters params;
    private boolean finished = false;

    @Override
    public boolean onStartJob(JobParameters p) {
        Log.d("BmsDash", "W job start");
        params = p;
        SharedPreferences pref = getSharedPreferences("bmsdash_pref", Context.MODE_PRIVATE);
        String mac = pref.getString("last_mac", null);
        if (mac == null) {
            WidgetProvider.setStatus(this, "未绑定设备，请先在 App 中连接");
            cleanup();
            return false;
        }

        WidgetProvider.setStatus(this, "正在连接设备…");
        ble = new BleManager(this, new BleManager.Callback() {
            @Override public void onScanResult(List<LeDevice> devices) {}

            @Override public void onConnection(boolean ok, String msg) {
                if (finished) return;
                if (!ok) {
                    WidgetProvider.setStatus(WidgetRefreshJobService.this, msg);
                    done();
                }
            }

            @Override public void onData(BmsData d) {
                if (finished || d.voltage <= 0) return;
                WidgetProvider.saveAndUpdate(WidgetRefreshJobService.this, d);
                done();
            }

            @Override public void onLog(String msg) { Log.d("BmsDash", "W:" + msg); }
            @Override public void onProbeResult(String result) {}
        });

        if (!ble.autoConnect(mac)) {
            WidgetProvider.setStatus(this, "连接失败");
            cleanup();
            return false;
        }

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!finished) {
                    WidgetProvider.setStatus(WidgetRefreshJobService.this, "连接超时，请重试");
                    done();
                }
            }
        }, 20000);
        return true;
    }

    private void cleanup() {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (ble != null) ble.disconnect();
    }

    private void done() {
        cleanup();
        if (params != null) jobFinished(params, false);
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        cleanup();
        return false;
    }
}
