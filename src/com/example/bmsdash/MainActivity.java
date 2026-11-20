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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BleManager.Callback {

    private static final int REQ_PERM = 1;
    private static final String PREF = "bmsdash_pref";
    private static final String KEY_MAC = "last_mac";
    private static final String KEY_NAME = "last_name";
    private static final String KEY_FILTER = "filter";
    private static final String KEY_TH_DIFF = "th_diff";
    private static final String KEY_TH_TEMP = "th_temp";
    private static final String KEY_TH_CELL = "th_cell";
    private static final String KEY_TH_SOC = "th_soc";

    private BleManager ble;
    private HistoryStore store;
    private ProtectionStore protectStore;
    private SharedPreferences pref;

    private BatteryArcView arc;
    private ChartView chartLive, chartHistory;
    private TextView tvConn, tvVoltage, tvCurrent, tvPower, tvCycles, tvTemp, tvDiff, tvUpdate;
    private TextView tvCellMax, tvCellMin, tvCellAvg;
    private TextView tvChargeSw, tvDischargeSw, tvBalance, tvHeat, tvBattType, tvV12Live;
    private TextView tvAlarm, tvHistory, tvHistCount, tvSavedMac;
    private TextView tvDevName, tvModelParse, tvCapacity, tvMaker, tvBmsModel;
    private TextView tvV12Info, tvCellExtremes, tvProtectLog;
    private GridLayout gridCells;
    private java.util.Set<String> lastActiveAlarms = new java.util.HashSet<>();

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(getResources().getColor(android.R.color.white));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
        setContentView(R.layout.activity_main);

        pref = getSharedPreferences(PREF, MODE_PRIVATE);
        store = new HistoryStore(this);
        protectStore = new ProtectionStore(this);
        loadThresholds();

        arc = (BatteryArcView) findViewById(R.id.arc);
        chartLive = (ChartView) findViewById(R.id.chart_live);
        chartHistory = (ChartView) findViewById(R.id.chart_history);
        tvConn = (TextView) findViewById(R.id.tv_conn);
        tvVoltage = (TextView) findViewById(R.id.tv_voltage);
        tvCurrent = (TextView) findViewById(R.id.tv_current);
        tvPower = (TextView) findViewById(R.id.tv_power);
        tvCycles = (TextView) findViewById(R.id.tv_cycles);
        tvTemp = (TextView) findViewById(R.id.tv_temp);
        tvDiff = (TextView) findViewById(R.id.tv_diff);
        tvUpdate = (TextView) findViewById(R.id.tv_update);
        tvCellMax = (TextView) findViewById(R.id.tv_cell_max);
        tvCellMin = (TextView) findViewById(R.id.tv_cell_min);
        tvCellAvg = (TextView) findViewById(R.id.tv_cell_avg);
        tvChargeSw = (TextView) findViewById(R.id.tv_charge_sw);
        tvDischargeSw = (TextView) findViewById(R.id.tv_discharge_sw);
        tvBalance = (TextView) findViewById(R.id.tv_balance);
        tvHeat = (TextView) findViewById(R.id.tv_heat);
        tvBattType = (TextView) findViewById(R.id.tv_batt_type);
        tvV12Live = (TextView) findViewById(R.id.tv_v12_live);
        tvDevName = (TextView) findViewById(R.id.tv_dev_name);
        tvModelParse = (TextView) findViewById(R.id.tv_model_parse);
        tvCapacity = (TextView) findViewById(R.id.tv_capacity);
        tvMaker = (TextView) findViewById(R.id.tv_maker);
        tvBmsModel = (TextView) findViewById(R.id.tv_bms_model);
        tvV12Info = (TextView) findViewById(R.id.tv_v12_info);
        tvCellExtremes = (TextView) findViewById(R.id.tv_cell_extremes);
        tvProtectLog = (TextView) findViewById(R.id.tv_protect_log);
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

        tabDash.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { switchPage(0); } });
        tabSettings.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { switchPage(1); refreshSettings(); } });
        tabHistory.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { switchPage(2); refreshHistory(); } });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
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
        findViewById(R.id.btn_history_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View d = findViewById(R.id.history_detail);
                d.setVisibility(d.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        findViewById(R.id.btn_protect_log_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View d = findViewById(R.id.protect_log_detail);
                boolean show = d.getVisibility() != View.VISIBLE;
                d.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) refreshProtectLog();
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

        restoreCachedData();
        ensurePermissions();
    }

    /** 冷启动时恢复桌面卡片缓存的最后一次读数，避免显示 -- */
    private void restoreCachedData() {
        SharedPreferences wp = getSharedPreferences(WidgetProvider.CACHE, MODE_PRIVATE);
        float voltage = wp.getFloat("voltage", 0f);
        if (voltage <= 0) return;
        BmsData cached = new BmsData();
        cached.voltage = voltage;
        cached.soc = wp.getInt("soc", -1);
        cached.tempMax = wp.getFloat("temp", 0f);
        cached.tempMin = cached.tempMax;
        cached.tempMos = cached.tempMax;
        cached.temp1 = cached.tempMax;
        cached.temp2 = cached.tempMax;
        cached.diff = wp.getInt("diff", 0);
        cached.capacityMah = wp.getInt("capacity", -1);
        cached.cycles = wp.getInt("cycles", -1);
        cached.remainingCapacityMah = wp.getInt("remaining_cap", -1);
        cached.fullCapacityMah = wp.getInt("full_cap", -1);
        cached.humidity = wp.getInt("humidity", -1);
        cached.alarmStatus = wp.getInt("alarm", -1);
        cached.balanceCurrentMa = wp.getInt("balance_ma", -1);
        cached.softwareVersion = wp.getInt("software_ver", -1);
        cached.reportedCellCount = wp.getInt("cell_count", -1);
        cached.productionDate = wp.getInt("production_date", -1);
        String cellsStr = wp.getString("cells", "");
        if (!cellsStr.isEmpty()) {
            String[] parts = cellsStr.split(",");
            for (int i = 0; i < Math.min(24, parts.length); i++) {
                try { cached.cells[i] = Integer.parseInt(parts[i]); } catch (Exception ignored) {}
            }
            cached.computeCells();
        }
        cached.hasRealtime = true;
        cached.hasCurrent = true;
        long time = wp.getLong("time", 0L);
        if (time > 0) lastUpdate = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(time));
        latest = cached;
        updateDashboard(cached);
        checkAlarm(cached);
    }

    private void switchPage(int p) {
        pageDash.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        pageHistory.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        setTab(tabDash, p == 0, R.drawable.ic_tab_realtime_on, R.drawable.ic_tab_realtime);
        setTab(tabSettings, p == 1, R.drawable.ic_tab_param_on, R.drawable.ic_tab_param);
        setTab(tabHistory, p == 2, R.drawable.ic_tab_info_on, R.drawable.ic_tab_info);
    }

    private void setTab(Button tab, boolean on, int iconOn, int iconOff) {
        tab.setTextColor(getResources().getColor(on ? R.color.jbd_blue : R.color.text_secondary));
        tab.setCompoundDrawablesWithIntrinsicBounds(null,
                getResources().getDrawable(on ? iconOn : iconOff), null, null);
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

    /** 顶部连接状态 */
    private void setConnStatus(String s) {
        connStatus = s;
        tvConn.setText(connStatus);
    }

    /** 启动时把已缓存的全部 BMS 数据导出到 logcat（BmsV12 标签），便于离线分析 */
    private void dumpCacheToLog() {
        SharedPreferences wp = getSharedPreferences(WidgetProvider.CACHE, MODE_PRIVATE);
        java.util.Map<String, ?> all = wp.getAll();
        Log.d("BmsV12", "=== CACHE DUMP (" + all.size() + " entries) ===");
        java.util.List<String> keys = new java.util.ArrayList<>(all.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) {
            Object v = all.get(k);
            Log.d("BmsV12", "CACHE " + k + " = " + v);
        }
        Log.d("BmsV12", "=== CACHE DUMP END ===");
    }

    private void onReady() {
        dumpCacheToLog();
        if (!ble.isBleReady()) { setConnStatus("蓝牙未开启"); toast("请先开启手机蓝牙"); return; }
        String mac = pref.getString(KEY_MAC, null);
        if (mac != null) {
            logd("自动连接上次设备");
            connectSaved(mac, "正在自动连接 ");
        } else {
            setConnStatus("未连接");
            showScanDialog();
        }
    }

    /** 用已记住的设备直接连接（不弹扫描框），界面不展示 MAC */
    private void connectSaved(String mac, String prefix) {
        connecting = true;
        updateConnectButton();
        showConnecting("正在连接设备…");
        setConnStatus(prefix + "已记住的 BMS 设备");
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
                if (d.name != null && !d.name.isEmpty()) {
                    pref.edit().putString(KEY_NAME, d.name).apply();
                }
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
                String safe = msg == null ? "" : msg.replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}", "BMS 设备");
                setConnStatus(safe);
                if (isConnected) {
                    String mac = ble.getTargetMac();
                    if (mac != null) {
                        pref.edit().putString(KEY_MAC, mac).apply();
                        refreshSettings();
                    }
                    String nm = ble.getTargetName();
                    if (nm != null && !nm.isEmpty()) {
                        pref.edit().putString(KEY_NAME, nm).apply();
                    }
                    refreshBatteryInfo();
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

    public void onProbeResult(String result) {
        Log.d("BmsV12", result);
        runOnUiThread(new Runnable() {
            public void run() {
                refreshBatteryInfo();
            }
        });
    }

    // ---------- 仪表盘更新（嘉佰达 App 风格） ----------
    private void updateDashboard(BmsData d) {
        arc.setSoc(d.soc);
        tvVoltage.setText(String.format(Locale.getDefault(), "%.1f V\n总电压", d.voltage));
        tvCurrent.setText(String.format(Locale.getDefault(), "%.1f A\n电流", d.current));
        tvPower.setText(String.format(Locale.getDefault(), "%.0f W\n功率", d.power()));
        tvCycles.setText((d.cycles >= 0 ? String.valueOf(d.cycles) : "--") + "\n循环次数");
        tvDiff.setText(String.format(Locale.getDefault(), "%.3f V\n压差", d.diff / 1000.0));

        double avg = 0;
        int n = 0;
        for (int v : d.cells) if (v > 0) { avg += v; n++; }
        if (n > 0) avg /= n;
        tvCellMax.setText(d.cellMax > 0 ? String.format(Locale.getDefault(), "%.3f V\n最高电压", d.cellMax / 1000.0) : "-- V\n最高电压");
        tvCellMin.setText(d.cellMin > 0 ? String.format(Locale.getDefault(), "%.3f V\n最低电压", d.cellMin / 1000.0) : "-- V\n最低电压");
        tvCellAvg.setText(n > 0 ? String.format(Locale.getDefault(), "%.3f V\n平均电压", avg / 1000.0) : "-- V\n平均电压");

        tvTemp.setText("单位：℃  MOS: " + fmtTemp(d.tempMos) + "  T1: " + fmtTemp(d.temp1) + "  T2: " + fmtTemp(d.temp2));
        tvBalance.setText(d.hasRealtime ? "均衡状态：" + (d.balanceOn ? "开" : "关")
                : "均衡状态：" + (d.balanceCurrentMa >= 0 ? d.balanceCurrentMa + " mA" : "--"));
        tvHeat.setText(d.hasRealtime ? "加热状态：" + (d.heatingOn ? "开" : "关") : "加热状态：--");
        tvBattType.setText("限流模块：" + (d.hasRealtime ? (d.currentLimitOn ? "开" : "关") : "--"));
        tvChargeSw.setText(d.hasRealtime ? (d.chargeMos ? "开" : "关") : "--");
        tvDischargeSw.setText(d.hasRealtime ? (d.dischargeMos ? "开" : "关") : "--");
        tvUpdate.setText(d.hasRealtime ? lastUpdate : "");

        StringBuilder v12 = new StringBuilder();
        appendPair(v12, "剩余容量", d.remainingCapacityMah >= 0
                ? String.format(Locale.getDefault(), "%.2f Ah", d.remainingCapacityMah / 1000.0) : "--");
        appendPair(v12, "满充容量", d.fullCapacityMah >= 0
                ? String.format(Locale.getDefault(), "%.2f Ah", d.fullCapacityMah / 1000.0) : "--");
        appendPair(v12, "湿度", d.humidity >= 0 ? d.humidity + " %" : "--");
        appendPair(v12, "报警状态", d.alarmStatus >= 0 ? String.format("0x%04X", d.alarmStatus) : "--");
        appendPair(v12, "均衡电流", d.balanceCurrentMa >= 0 ? d.balanceCurrentMa + " mA" : "--");
        tvV12Live.setText(v12.toString());

        if (gridCells.getChildCount() == 0) {
            int hp = Math.round(76 * getResources().getDisplayMetrics().density);
            for (int i = 0; i < 24; i++) {
                CellBatteryView cell = new CellBatteryView(this);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0;
                lp.height = hp;
                lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                lp.setMargins(2, 3, 2, 3);
                cell.setLayoutParams(lp);
                gridCells.addView(cell);
            }
        }
        boolean hasCells = false;
        for (int v : d.cells) if (v > 0) { hasCells = true; break; }
        if (hasCells) {
            int hiIdx = -1, loIdx = -1, hi = 0, lo = Integer.MAX_VALUE;
            for (int i = 0; i < 24; i++) {
                int v = d.cells[i];
                if (v <= 0) continue;
                if (v > hi) { hi = v; hiIdx = i; }
                if (v < lo) { lo = v; loIdx = i; }
            }
            for (int i = 0; i < 24; i++) {
                int mark = (i == hiIdx) ? 1 : ((i == loIdx) ? 2 : 0);
                ((CellBatteryView) gridCells.getChildAt(i)).setCell(i + 1, d.cells[i], mark);
            }
            tvCellExtremes.setText(hiIdx == loIdx
                    ? String.format(Locale.getDefault(), "全部一致：%.3f V", hi / 1000.0)
                    : String.format(Locale.getDefault(),
                            "▲最高 %.3fV（%d串）   ▼最低 %.3fV（%d串）",
                            hi / 1000.0, hiIdx + 1, lo / 1000.0, loIdx + 1));
        }
    }

    private String fmtTemp(double t) {
        return Math.abs(t) > 0.05 ? String.format(Locale.getDefault(), "%.1f", t) : "--";
    }

    // ---------- 报警 ----------
    private void checkAlarm(BmsData d) {
        List<String> alarms = new ArrayList<>();
        // 嘉佰达协议注 1：保护状态位
        String[] PROTECT = {"单体过压", "单体欠压", "整组过压", "整组欠压", "充电过温", "充电低温",
                "放电过温", "放电低温", "充电过流", "放电过流", "短路", "前端IC错误", "软件锁定MOS"};
        for (int i = 0; i < PROTECT.length && i < 16; i++) {
            if ((d.protectStatus & (1 << i)) != 0) alarms.add(PROTECT[i]);
        }
        if (d.diff > thDiff) alarms.add("压差过大 " + d.diff + "mV");
        if (d.tempMax > thTemp) alarms.add("温度过高 " + String.format(Locale.getDefault(), "%.1f℃", d.tempMax));
        if (d.cellMin > 0 && d.cellMin < thCell) alarms.add("单体过低 " + d.cellMin + "mV");
        if (d.soc >= 0 && d.soc < thSoc) alarms.add("电量偏低 " + d.soc + "%");

        // 保护状态变化写入保护记录（只记录状态翻转，避免刷屏）
        if (d.hasRealtime) {
            java.util.Set<String> now = new java.util.HashSet<>(alarms);
            long ts = System.currentTimeMillis();
            for (String name : now) {
                if (!lastActiveAlarms.contains(name)) protectStore.append(ts, true, name);
            }
            for (String name : lastActiveAlarms) {
                if (!now.contains(name)) protectStore.append(ts, false, name);
            }
            lastActiveAlarms = now;
        }

        if (alarms.isEmpty()) {
            tvAlarm.setText(d.hasRealtime ? "电池状态：电池运行正常" : "电池状态：等待数据…");
            tvAlarm.setTextColor(getResources().getColor(R.color.ok));
            alarmVibrated = false;
        } else {
            StringBuilder sb = new StringBuilder("电池状态：");
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
        refreshBatteryInfo();
    }

    /** 电池基本信息：全部来自协议读取 / 本机缓存，不含任何推测的写操作结果 */
    private void refreshBatteryInfo() {
        SharedPreferences wp = getSharedPreferences(WidgetProvider.CACHE, MODE_PRIVATE);
        String name = pref.getString(KEY_NAME, null);
        if (name == null || name.isEmpty()) {
            // 自动连接时蓝牙广播名可能拿不到，用 V12 0x009E 读到的电池型号兜底
            name = wp.getString("probe_model", "");
        }
        if (name == null || name.isEmpty()) {
            name = wp.getString("probe_hw_version", "");
        }
        tvDevName.setText(name.isEmpty() ? "--" : name);
        tvModelParse.setText(parseDeviceModel(name));

        int cap = wp.getInt("capacity", -1);
        if (cap <= 0) {
            int regCap = wp.getInt("reg_0", -1);
            if (regCap > 0) cap = regCap * 10;
        }
        tvCapacity.setText(cap > 0
                ? String.format(Locale.getDefault(), "%.2f Ah（协议读取）", cap / 1000.0)
                : "--（未读到）");

        tvMaker.setText(fmtCache(wp.getString("probe_maker", "")));
        tvBmsModel.setText(fmtCache(wp.getString("probe_model", "")));

        StringBuilder sb = new StringBuilder();
        line(sb, "硬件版本", wp.getString("probe_hw_version", ""));
        line(sb, "BMS编码", wp.getString("probe_bms_code", ""));
        line(sb, "条码信息", wp.getString("probe_barcode", ""));
        line(sb, "硬件名称", wp.getString("probe_hw_name", ""));
        line(sb, "主控芯片", wp.getString("chip_type_name", ""));
        line(sb, "序列号", reg(wp, 6, ""));
        line(sb, "循环次数", reg(wp, 7, ""));
        int cells = wp.getInt("reg_31", -1);
        if (cells <= 0 && latest.reportedCellCount > 0) cells = latest.reportedCellCount;
        line(sb, "电池串数", cells > 0 ? String.valueOf(cells) : "");
        int fw = wp.getInt("reg_2", -1);
        line(sb, "满充电压", fw > 0 ? String.format(Locale.getDefault(), "%.2f V", fw / 1000.0) : "");
        int pwr = wp.getInt("reg_4", -1);
        line(sb, "系统功耗", pwr >= 0 ? pwr + " mA" : "");
        line(sb, "软件版本", latest.softwareVersion >= 0
                ? String.format(Locale.getDefault(), "%.1f", latest.softwareVersion / 16.0) : "");
        int pd = wp.getInt("reg_5", -1);
        if (pd > 0) {
            int day = pd & 0x1F, month = (pd >> 5) & 0x0F, year = 2000 + (pd >> 9);
            line(sb, "生产日期", String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month, day));
        } else line(sb, "生产日期", "");
        tvV12Info.setText(sb.length() == 0 ? "连接设备后自动读取" : sb.toString());
        refreshV12Params(wp);
        refreshInternalResistance(wp);
    }

    /** 0xFA 参数表：按类别分组、顺序显示（单位严格按协议） */
    private void refreshV12Params(SharedPreferences wp) {
        StringBuilder sb = new StringBuilder();

        section(sb, "一、容量与电池信息");
        line(sb, "标称容量", regAh(wp, 0));
        line(sb, "循环容量", regAh(wp, 1));
        int fullV = wp.getInt("reg_2", -1);
        line(sb, "满充电压(单体)", fullV > 0 ? String.format(Locale.getDefault(), "%.3f V", fullV / 1000.0) : "");
        int ventV = wp.getInt("reg_3", -1);
        line(sb, "放电终止电压(单体)", ventV > 0 ? String.format(Locale.getDefault(), "%.3f V", ventV / 1000.0) : "");
        line(sb, "循环次数", reg(wp, 7, " 次"));
        int pd = wp.getInt("reg_5", -1);
        String dateStr = "";
        if (pd > 0) {
            int day = pd & 0x1F, month = (pd >> 5) & 0x0F, year = 2000 + (pd >> 9);
            dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month, day);
        }
        line(sb, "生产日期", dateStr);
        line(sb, "软件版本", latest.softwareVersion >= 0
                ? String.format(Locale.getDefault(), "%.1f", latest.softwareVersion / 16.0) : "");
        line(sb, "序列号", reg(wp, 6, ""));
        StringBuilder id = new StringBuilder();
        for (int i = 170; i <= 175; i++) {
            int v = wp.getInt("reg_" + i, -1);
            if (v >= 0) id.append(String.format(Locale.getDefault(), "%04X", v & 0xFFFF));
        }
        if (id.length() > 0 && !id.toString().matches("0+")) line(sb, "唯一ID码", id.toString());

        section(sb, "二、电压保护");
        line(sb, "整组过压保护", reg10mV(wp, 16));
        line(sb, "整组过压恢复", reg10mV(wp, 17));
        line(sb, "整组欠压保护", reg10mV(wp, 18));
        line(sb, "整组欠压恢复", reg10mV(wp, 19));
        line(sb, "单体过压保护", regMv(wp, 20));
        line(sb, "单体过压恢复", regMv(wp, 21));
        line(sb, "单体欠压保护", regMv(wp, 22));
        line(sb, "单体欠压恢复", regMv(wp, 23));
        line(sb, "硬件过压保护", regMv(wp, 38));
        line(sb, "硬件欠压保护", regMv(wp, 39));

        section(sb, "三、电流保护");
        int chgOc = wp.getInt("reg_24", -1), dchOc = wp.getInt("reg_25", -1);
        if (chgOc >= 0) line(sb, "充电过流保护", String.format(Locale.getDefault(), "%.1f A", chgOc / 100.0));
        if (dchOc >= 0) {
            int a = (dchOc & 0x8000) != 0 ? 0x10000 - dchOc : dchOc;
            line(sb, "放电过流保护", String.format(Locale.getDefault(), "%.1f A", a / 100.0));
        }

        section(sb, "四、温度保护（保护值 / 恢复值）");
        tempPair(sb, wp, 8, 9, "充电高温");
        tempPair(sb, wp, 10, 11, "充电低温");
        tempPair(sb, wp, 12, 13, "放电高温");
        tempPair(sb, wp, 14, 15, "放电低温");

        section(sb, "五、均衡与硬件配置");
        line(sb, "均衡开启电压", regMv(wp, 26));
        line(sb, "均衡开启压差", regMv(wp, 27));
        int sense = wp.getInt("reg_28", -1);
        if (sense >= 0) {
            double mr = (sense & 0x8000) != 0 ? (sense & 0x7FFF) * 0.01 : sense * 0.1;
            line(sb, "采样电阻", String.format(Locale.getDefault(), "%.2f mΩ", mr));
        }
        line(sb, "电池串数", reg(wp, 31, " 串"));
        int pwr = wp.getInt("reg_29", -1);
        if (pwr >= 0) line(sb, "功能配置字", String.format(Locale.getDefault(), "0x%04X", pwr & 0xFFFF));
        int ntcCfg = wp.getInt("reg_30", -1);
        if (ntcCfg >= 0) line(sb, "温度探头配置", String.format(Locale.getDefault(), "0x%04X", ntcCfg & 0xFFFF));

        section(sb, "六、保护延时");
        int[] delayReg = {51, 50, 49, 48, 43, 45, 44, 46, 47, 52, 54, 53, 55};
        String[] delayName = {"单体过压", "单体欠压", "整组过压", "整组欠压", "短路恢复", "充电高温",
                "充电低温", "放电高温", "放电低温", "充电过流", "放电过流", "充电过流恢复", "放电过流恢复"};
        for (int i = 0; i < delayReg.length; i++) line(sb, delayName[i], reg(wp, delayReg[i], " s"));

        section(sb, "七、SOC 电压点");
        int[] socReg = {111, 106, 34, 107, 35, 108, 36, 109, 37, 110};
        int[] socPct = {100, 90, 80, 70, 60, 50, 40, 30, 20, 10};
        for (int i = 0; i < socReg.length; i++) line(sb, socPct[i] + "%", regMv(wp, socReg[i]));

        section(sb, "八、额定参数");
        int rv = wp.getInt("reg_114", -1), ri = wp.getInt("reg_115", -1), rp = wp.getInt("reg_116", -1);
        if (rv >= 0) line(sb, "额定电压", String.format(Locale.getDefault(), "%.1f V", rv / 10.0));
        if (ri >= 0) line(sb, "额定电流", ri + " A");
        if (rp >= 0) line(sb, "最大功率", rp + " W");
        int rcv = wp.getInt("reg_117", -1);
        if (rcv >= 0) line(sb, "额定充电电压", String.format(Locale.getDefault(), "%.1f V", rcv / 10.0));
        int rdi = wp.getInt("reg_118", -1), rci = wp.getInt("reg_119", -1);
        if (rdi >= 0) line(sb, "额定放电电流", rdi + " A");
        if (rci >= 0) line(sb, "额定充电电流", rci + " A");
        int rdp = wp.getInt("reg_120", -1);
        if (rdp >= 0) line(sb, "额定放电功率", rdp + " W");
        line(sb, "学习容量", regAh(wp, 112));
        line(sb, "校正间隔", reg(wp, 113, " s"));

        TextView tv = (TextView) findViewById(R.id.tv_v12_params);
        if (tv != null) tv.setText(sb.length() == 0 ? "连接设备后自动读取" : sb.toString());
    }

    private static void section(StringBuilder sb, String title) {
        if (sb.length() > 0) sb.append("\n");
        sb.append("【").append(title).append("】\n");
    }

    private static String regAh(SharedPreferences wp, int addr) {
        int v = wp.getInt("reg_" + addr, -1);
        return v < 0 ? "" : String.format(Locale.getDefault(), "%.2f Ah", v / 100.0);
    }
    private static String regMv(SharedPreferences wp, int addr) {
        int v = wp.getInt("reg_" + addr, -1);
        return v < 0 ? "" : String.format(Locale.getDefault(), "%d mV (%.3f V)", v, v / 1000.0);
    }
    private static String reg10mV(SharedPreferences wp, int addr) {
        int v = wp.getInt("reg_" + addr, -1);
        return v < 0 ? "" : String.format(Locale.getDefault(), "%.2f V", v / 100.0);
    }
    private static void tempPair(StringBuilder sb, SharedPreferences wp, int prot, int rel, String label) {
        int p = wp.getInt("reg_" + prot, -1), r = wp.getInt("reg_" + rel, -1);
        if (p < 0 && r < 0) return;
        String ps = p >= 0 ? String.format(Locale.getDefault(), "%.1f℃", p / 10.0 - 273.1) : "--";
        String rs = r >= 0 ? String.format(Locale.getDefault(), "%.1f℃", r / 10.0 - 273.1) : "--";
        line(sb, label, ps + " / " + rs);
    }

    /** 保护记录：设备历史累计次数(0xAA) + 本机监测到的触发/解除事件 */
    private void refreshProtectLog() {
        if (tvProtectLog == null) return;
        StringBuilder sb = new StringBuilder();

        // 第一部分：设备历史保护次数（蓝牙 0xAA 可读的唯一"历史"数据）
        SharedPreferences wp = getSharedPreferences(WidgetProvider.CACHE, MODE_PRIVATE);
        String[] cn = {"短路保护", "充电过流", "放电过流", "单体过压", "单体欠压", "充电高温",
                "充电低温", "放电高温", "放电低温", "整组过压", "整组欠压", "系统重启"};
        int total = 0, read = 0;
        for (int i = 0; i < cn.length; i++) {
            int v = wp.getInt("protect_count_" + i, -1);
            if (v >= 0) { total += v; read++; }
        }
        if (read > 0) {
            sb.append("【设备历史累计次数 · 蓝牙 0xAA】\n");
            for (int i = 0; i < cn.length; i++) {
                int v = wp.getInt("protect_count_" + i, -1);
                if (v >= 0 && v > 0) sb.append("  ").append(cn[i]).append(" ").append(v).append(" 次\n");
            }
            sb.append("  合计 ").append(total).append(" 次\n");
        }

        // 第二部分：本机运行时监测到的保护事件（带时间戳）
        java.util.List<ProtectionStore.Event> events = protectStore.load();
        sb.append("\n【本机监测事件】\n");
        if (events.isEmpty()) {
            sb.append("  暂无\n");
        } else {
            int start = Math.max(0, events.size() - 40);
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
            for (int i = events.size() - 1; i >= start; i--) {
                ProtectionStore.Event e = events.get(i);
                sb.append("  ").append(fmt.format(new java.util.Date(e.time)))
                  .append(" ").append(e.text).append("\n");
            }
            if (events.size() > 40) sb.append("  … 共 ").append(events.size()).append(" 条，仅显示最近 40 条\n");
        }
        tvProtectLog.setText(sb.toString());
    }

    /** 单体内阻（0xF6）：按实际串数生成两列表格，宽度均分真正对齐，单位 mΩ */
    private void refreshInternalResistance(SharedPreferences wp) {
        GridLayout grid = (GridLayout) findViewById(R.id.grid_internal_res);
        if (grid == null) return;
        grid.removeAllViews();
        int count = wp.getInt("cell_count", -1);
        if (count <= 0) count = latest.reportedCellCount > 0 ? latest.reportedCellCount : 24;
        for (int i = 1; i <= count; i++) {
            int v10 = wp.getInt("ir_" + i, Integer.MIN_VALUE);
            String val = v10 == Integer.MIN_VALUE ? "-- mΩ" : String.format(Locale.getDefault(), "%.1f mΩ", v10 / 10.0);
            TextView cell = new TextView(this);
            cell.setText(String.format(Locale.getDefault(), "%2d: %s", i, val));
            cell.setTextColor(getResources().getColor(R.color.text_primary));
            cell.setTextSize(11);
            cell.setTypeface(android.graphics.Typeface.MONOSPACE);
            cell.setPadding(2, 4, 2, 4);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            cell.setLayoutParams(lp);
            grid.addView(cell);
        }
    }

    private static String fmtCache(String v) {
        return v == null || v.isEmpty() ? "--（未读到）" : v;
    }

    private static String reg(SharedPreferences wp, int addr, String suffix) {
        int v = wp.getInt("reg_" + addr, -1);
        return v < 0 ? "" : String.valueOf(v) + suffix;
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) value = "--";
        sb.append(label).append("：").append(value).append("\n");
    }

    private static void appendPair(StringBuilder sb, String label, String value) {
        if (sb.length() > 0) sb.append("  ");
        sb.append(label).append(" ").append(value);
    }

    /**
     * 嘉佰达官方型号规则（规格书 JBD-SP04S005-L4S-150A-B-U-H 逐字段注释）：
     * JBD-DB24SA03-L24S-60A-60A-B-U
     *   JBD=厂商  DB24SA03=型号(系列+最大串数+版本)  L24S=电芯类型(L铁锂/NMC三元)+配置串数
     *   60A-60A=充/放电电流  B=蓝牙 U=UART R=RS485 C=CAN H=加热
     * 蓝牙名常省略连字符与重复电流，如 DB24SAO3L24S60ABU。
     */
    private String parseDeviceModel(String name) {
        if (name == null || name.length() < 8) return "--";
        String n = name.toUpperCase(Locale.getDefault()).replace("-", "");
        if (n.startsWith("JBD")) n = n.substring(3);
        Matcher m = Pattern.compile("^([A-Z]{2})(\\d+S)([A-Z][A-Z0-9]{1,2})(LFP|L|NMC|N)(\\d+S)(\\d+A)(\\d+A)?([A-Z]*)$").matcher(n);
        if (!m.matches()) return "命名含义未确认（名称：" + name + "）";
        StringBuilder sb = new StringBuilder();
        sb.append("系列 ").append(m.group(1))
          .append(" · 最大 ").append(m.group(2))
          .append(" · 版本 ").append(m.group(3));
        String chem = m.group(4);
        sb.append(" · ").append(chem.equals("L") || chem.equals("LFP") ? "磷酸铁锂" : "三元锂")
          .append(" ").append(m.group(5));
        sb.append(" · 电流 ").append(m.group(6));
        if (m.group(7) != null && !m.group(7).equals(m.group(6))) sb.append("/").append(m.group(7));
        String opt = m.group(8);
        if (!opt.isEmpty()) {
            StringBuilder f = new StringBuilder();
            for (int i = 0; i < opt.length(); i++) {
                if (i > 0) f.append("+");
                switch (opt.charAt(i)) {
                    case 'B': f.append("蓝牙"); break;
                    case 'U': f.append("UART"); break;
                    case 'R': f.append("RS485"); break;
                    case 'C': f.append("CAN"); break;
                    case 'H': f.append("加热"); break;
                    default: f.append(opt.charAt(i)); break;
                }
            }
            sb.append(" · 功能 ").append(f);
        }
        return sb.toString();
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
        tvSavedMac.setText("已记住设备：" + (mac != null ? "BMS 设备（下次自动连接）" : "无"));
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
        View b = findViewById(R.id.btn_connect);
        b.setContentDescription(connected ? "断开连接" : (connecting ? "连接中" : "蓝牙连接"));
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
