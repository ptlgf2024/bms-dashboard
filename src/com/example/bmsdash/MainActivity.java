package com.example.bmsdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.util.Log;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BleManager.Callback {

    private static final int REQ_PERM = 1;
    private static final String PREF = "bmsdash_pref";
    private static final String KEY_MAC = "last_mac";
    private static final String KEY_FILTER = "filter";
    private static final String KEY_TH_DIFF = "th_diff";
    private static final String KEY_TH_TEMP = "th_temp";
    private static final String KEY_TH_CELL = "th_cell";
    private static final String KEY_TH_SOC = "th_soc";

    private BleManager ble;
    private HistoryStore store;
    private SharedPreferences pref;

    private GaugeView gauge;
    private BatteryArcView arc;
    private ChartView chartLive, chartHistory;
    private TextView tvConn, tvVoltage, tvChargeCur, tvDischargeCur, tvSoc, tvTemp, tvDiff, tvUpdate, tvMac;
    private TextView tvAlarm, tvHistory, tvHistCount, tvSavedMac;
    private GridLayout gridCells;

    private Button tabDash, tabHistory, tabSettings;
    private View pageDash, pageHistory, pageSettings;

    private final List<LeDevice> scanList = new ArrayList<>();
    private DeviceAdapter deviceAdapter;
    private AlertDialog scanDialog;
    private ProgressDialog connectingDialog;
    private boolean connecting = false;
    private boolean connected = false;

    private BmsData latest = new BmsData();
    private String lastUpdate = "--";
    private String connStatus = "未连接";
    private final Handler handler = new Handler();
    private boolean alarmVibrated = false;

    private int thDiff = 20, thCell = 3000, thSoc = 20;
    private double thTemp = 45;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pref = getSharedPreferences(PREF, MODE_PRIVATE);
        store = new HistoryStore(this);
        loadThresholds();

        gauge = (GaugeView) findViewById(R.id.gauge);
        arc = (BatteryArcView) findViewById(R.id.arc);
        chartLive = (ChartView) findViewById(R.id.chart_live);
        chartHistory = (ChartView) findViewById(R.id.chart_history);
        tvConn = (TextView) findViewById(R.id.tv_conn);
        tvVoltage = (TextView) findViewById(R.id.tv_voltage);
        tvChargeCur = (TextView) findViewById(R.id.tv_charge_cur);
        tvDischargeCur = (TextView) findViewById(R.id.tv_discharge_cur);
        tvSoc = (TextView) findViewById(R.id.tv_soc);
        tvTemp = (TextView) findViewById(R.id.tv_temp);
        tvDiff = (TextView) findViewById(R.id.tv_diff);
        tvUpdate = (TextView) findViewById(R.id.tv_update);
        tvMac = (TextView) findViewById(R.id.tv_mac);
        gridCells = (GridLayout) findViewById(R.id.grid_cells);
        tvAlarm = (TextView) findViewById(R.id.tv_alarm);
        tvHistory = (TextView) findViewById(R.id.tv_history);
        tvHistCount = (TextView) findViewById(R.id.tv_hist_count);
        tvSavedMac = (TextView) findViewById(R.id.tv_saved_mac);

        tabDash = (Button) findViewById(R.id.tab_dash);
        tabHistory = (Button) findViewById(R.id.tab_history);
        tabSettings = (Button) findViewById(R.id.tab_settings);
        pageDash = findViewById(R.id.page_dash);
        pageHistory = findViewById(R.id.page_history);
        pageSettings = findViewById(R.id.page_settings);

        ble = new BleManager(this, this);
        ble.setFilterEnabled(pref.getBoolean(KEY_FILTER, true));

        gauge.setRange(60, 90);

        tabDash.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { switchPage(0); } });
        tabHistory.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { switchPage(1); refreshHistory(); } });
        tabSettings.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { switchPage(2); refreshSettings(); } });

        findViewById(R.id.btn_connect).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (connected) {
                    ble.disconnect();
                } else {
                    String mac = pref.getString(KEY_MAC, null);
                    if (mac != null) connectSaved(mac, "正在连接 ");
                    else showScanDialog();
                }
            }
        });
        findViewById(R.id.btn_clear_hist).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                store.clear();
                refreshHistory();
                chartLive.setData(null);
                chartHistory.setData(null);
                toast("历史已清空");
            }
        });
        findViewById(R.id.btn_save_th).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveThresholds(); }
        });
        findViewById(R.id.btn_forget).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pref.edit().remove(KEY_MAC).apply();
                refreshSettings();
                toast("已清除，关闭自动连接");
            }
        });
        ((CheckBox) findViewById(R.id.cb_filter)).setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                ble.setFilterEnabled(checked);
                pref.edit().putBoolean(KEY_FILTER, checked).apply();
            }
        });

        ensurePermissions();
    }

    private void switchPage(int p) {
        pageDash.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pageHistory.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        tabDash.setTextColor(getResources().getColor(p == 0 ? R.color.primary : R.color.text_secondary));
        tabHistory.setTextColor(getResources().getColor(p == 1 ? R.color.primary : R.color.text_secondary));
        tabSettings.setTextColor(getResources().getColor(p == 2 ? R.color.primary : R.color.text_secondary));
    }

    // ---------- 权限 ----------
    private void ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (need.isEmpty()) { onReady(); }
        else requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == REQ_PERM) {
            boolean ok = g.length > 0;
            for (int r : g) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) onReady(); else toast("权限被拒绝，无法使用蓝牙");
        }
    }

    /** 标题栏连接状态 + 最后更新时间 */
    private void setConnStatus(String s) {
        connStatus = s;
        tvConn.setText(connStatus + (lastUpdate.equals("--") ? "" : "  ·  更新 " + lastUpdate));
    }

    private void onReady() {
        if (!ble.isBleReady()) { setConnStatus("蓝牙未开启"); toast("请先开启手机蓝牙"); return; }
        String mac = pref.getString(KEY_MAC, null);
        if (mac != null) {
            logd("自动连接上次设备：" + mac);
            connectSaved(mac, "正在自动连接 ");
        } else {
            setConnStatus("未连接");
            showScanDialog();
        }
    }

    /** 用已记住的 MAC 直接连接（不弹扫描框） */
    private void connectSaved(String mac, String prefix) {
        connecting = true;
        updateConnectButton();
        showConnecting("正在连接设备…");
        setConnStatus(prefix + mac);
        if (!ble.autoConnect(mac)) {
            connecting = false;
            dismissConnecting();
            updateConnectButton();
            setConnStatus("连接失败，请扫描选择设备");
        }
    }

    // ---------- 扫描对话框 ----------
    private void showScanDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_scan, null);
        ListView lv = (ListView) v.findViewById(R.id.lv_scan);
        deviceAdapter = new DeviceAdapter(this, scanList);
        lv.setAdapter(deviceAdapter);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View view, int i, long id) {
                LeDevice d = scanList.get(i);
                if (scanDialog != null) scanDialog.dismiss();
                connecting = true;
                updateConnectButton();
                showConnecting("正在连接设备…");
                ble.connect(d.device);
            }
        });
        scanDialog = new AlertDialog.Builder(this)
                .setView(v)
                .setTitle("选择嘉佰达BMS设备")
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { ble.stopScan(); }
                })
                .create();
        scanDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) { ble.stopScan(); }
        });
        scanList.clear();
        deviceAdapter.notifyDataSetChanged();
        scanDialog.show();
        ble.startScan();
    }

    // ---------- BleManager.Callback ----------
    public void onScanResult(final List<LeDevice> devices) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (deviceAdapter != null) {
                    scanList.clear();
                    scanList.addAll(devices);
                    deviceAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    public void onConnection(final boolean isConnected, final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                connected = isConnected;
                connecting = false;
                dismissConnecting();
                updateConnectButton();
                setConnStatus(msg);
                if (isConnected) {
                    String mac = ble.getTargetMac();
                    if (mac != null) {
                        pref.edit().putString(KEY_MAC, mac).apply();
                        refreshSettings();
                    }
                }
            }
        });
    }

    public void onData(final BmsData d) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                latest = d;
                lastUpdate = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                setConnStatus(connStatus);
                updateDashboard(d);
                store.append(d);
                chartLive.setData(store.load());
                checkAlarm(d);
                WidgetProvider.saveAndUpdate(MainActivity.this, d);
            }
        });
    }

    public void onLog(String msg) { logd(msg); }

    // ---------- 仪表盘更新 ----------
    private void updateDashboard(BmsData d) {
        gauge.setValue(d.voltage);
        arc.setSoc(d.soc);
        tvVoltage.setText("总电压\n" + (d.voltage > 0 ? String.format(Locale.getDefault(), "%.2f V", d.voltage) : "--"));
        tvSoc.setText("SOC\n" + (d.soc >= 0 ? d.soc + " %" : "--"));
        tvTemp.setText("温度\n" + (d.tempMax != 0 ? String.format(Locale.getDefault(), "%.1f ℃", d.tempMax) : "--"));
        tvDiff.setText("压差\n" + (d.diff > 0 ? d.diff + " mV" : "--"));
        tvUpdate.setText("更新\n" + lastUpdate);
        String mac = ble.getTargetMac();
        tvMac.setText("MAC\n" + (mac != null ? mac : "--"));

        String curText = String.format(Locale.getDefault(), "%.2f A", Math.abs(d.current));
        if (d.current > 0.005) {
            tvChargeCur.setText("充电\n" + curText);
            tvDischargeCur.setText("放电\n0.00 A");
        } else if (d.current < -0.005) {
            tvChargeCur.setText("充电\n0.00 A");
            tvDischargeCur.setText("放电\n" + curText);
        } else {
            tvChargeCur.setText("充电\n0.00 A");
            tvDischargeCur.setText("放电\n0.00 A");
        }

        boolean hasCells = false;
        for (int v : d.cells) if (v > 0) { hasCells = true; break; }
        if (hasCells) {
            gridCells.removeAllViews();
            for (int i = 0; i < 24; i++) {
                int mv = d.cells[i];
                boolean low = mv > 0 && mv < thCell;
                TextView cell = new TextView(this);
                cell.setText((i + 1) + "\n" + (mv > 0 ? String.format(Locale.getDefault(), "%.3f", mv / 1000.0) : "--"));
                cell.setBackgroundResource(low ? R.drawable.bg_cell_low : R.drawable.bg_cell);
                cell.setTextColor(getResources().getColor(low ? R.color.danger : R.color.cell_text));
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(11);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0;
                lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
                lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                lp.setMargins(3, 3, 3, 3);
                cell.setLayoutParams(lp);
                gridCells.addView(cell);
            }
        }
    }

    // ---------- 报警 ----------
    private void checkAlarm(BmsData d) {
        List<String> alarms = new ArrayList<>();
        if (d.diff > thDiff) alarms.add("压差过大 " + d.diff + "mV");
        if (d.tempMax > thTemp) alarms.add("温度过高 " + String.format(Locale.getDefault(), "%.1f℃", d.tempMax));
        if (d.cellMin > 0 && d.cellMin < thCell) alarms.add("单体过低 " + d.cellMin + "mV");
        if (d.soc >= 0 && d.soc < thSoc) alarms.add("电量偏低 " + d.soc + "%");

        if (alarms.isEmpty()) {
            tvAlarm.setText("状态：正常 ✓");
            tvAlarm.setTextColor(getResources().getColor(R.color.ok));
            alarmVibrated = false;
        } else {
            StringBuilder sb = new StringBuilder("⚠ 报警：");
            for (int i = 0; i < alarms.size(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(alarms.get(i));
            }
            tvAlarm.setText(sb.toString());
            tvAlarm.setTextColor(getResources().getColor(R.color.danger));
            if (!alarmVibrated) {
                try {
                    Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (vib != null) vib.vibrate(300);
                } catch (Exception ignored) {}
                alarmVibrated = true;
            }
        }
    }

    // ---------- 历史 ----------
    private void refreshHistory() {
        List<HistoryStore.Point> pts = store.load();
        tvHistCount.setText("历史记录：" + pts.size() + " 条");
        chartHistory.setData(pts);
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        for (int i = pts.size() - 1; i >= 0 && i >= pts.size() - 60; i--) {
            HistoryStore.Point p = pts.get(i);
            sb.append(fmt.format(new Date(p.time)))
              .append(String.format(Locale.getDefault(), "  %6.2fV  %3d%%  %4.1fC  Δ%d",
                      p.voltage, p.soc, p.tempMax, p.diff))
              .append("\n");
        }
        tvHistory.setText(sb.length() == 0 ? "暂无历史记录" : sb.toString());
    }

    // ---------- 设置 ----------
    private void loadThresholds() {
        thDiff = pref.getInt(KEY_TH_DIFF, 20);
        thTemp = Float.parseFloat(pref.getString(KEY_TH_TEMP, "45"));
        thCell = pref.getInt(KEY_TH_CELL, 3000);
        thSoc = pref.getInt(KEY_TH_SOC, 20);
    }

    private void saveThresholds() {
        try {
            thDiff = Integer.parseInt(((EditText) findViewById(R.id.et_th_diff)).getText().toString().trim());
            thTemp = Double.parseDouble(((EditText) findViewById(R.id.et_th_temp)).getText().toString().trim());
            thCell = Integer.parseInt(((EditText) findViewById(R.id.et_th_cell)).getText().toString().trim());
            thSoc = Integer.parseInt(((EditText) findViewById(R.id.et_th_soc)).getText().toString().trim());
            SharedPreferences.Editor e = pref.edit();
            e.putInt(KEY_TH_DIFF, thDiff);
            e.putString(KEY_TH_TEMP, String.valueOf(thTemp));
            e.putInt(KEY_TH_CELL, thCell);
            e.putInt(KEY_TH_SOC, thSoc);
            e.apply();
            toast("阈值已保存");
        } catch (Exception ex) {
            toast("阈值格式错误");
        }
    }

    private void refreshSettings() {
        ((EditText) findViewById(R.id.et_th_diff)).setText(String.valueOf(thDiff));
        ((EditText) findViewById(R.id.et_th_temp)).setText(String.valueOf(thTemp));
        ((EditText) findViewById(R.id.et_th_cell)).setText(String.valueOf(thCell));
        ((EditText) findViewById(R.id.et_th_soc)).setText(String.valueOf(thSoc));
        ((CheckBox) findViewById(R.id.cb_filter)).setChecked(ble.isFilterEnabled());
        String mac = pref.getString(KEY_MAC, null);
        tvSavedMac.setText("已记住设备：" + (mac != null ? mac + "（下次自动连接）" : "无"));
    }

    // ---------- 连接弹窗 / 按钮状态 ----------
    private void showConnecting(String msg) {
        if (connectingDialog == null) {
            connectingDialog = new ProgressDialog(this);
            connectingDialog.setCancelable(true);
            connectingDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override public void onCancel(DialogInterface dialog) {
                    connecting = false;
                    ble.disconnect();
                    updateConnectButton();
                }
            });
        }
        connectingDialog.setMessage(msg);
        if (!connectingDialog.isShowing()) connectingDialog.show();
    }

    private void dismissConnecting() {
        if (connectingDialog != null && connectingDialog.isShowing()) connectingDialog.dismiss();
    }

    private void updateConnectButton() {
        Button b = (Button) findViewById(R.id.btn_connect);
        if (connected) b.setText("断开连接");
        else if (connecting) b.setText("连接中…");
        else b.setText("连接设备");
    }

    // ---------- 工具 ----------
    private void logd(String s) {
        Log.d("BmsDash", s);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        dismissConnecting();
        if (ble != null) ble.disconnect();
    }
}
