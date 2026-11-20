package com.example.bmsdash;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 蓝牙管理：扫描过滤、连接、自动重连、定时轮询 */
public class BleManager {
    public interface Callback {
        void onScanResult(List<LeDevice> devices);
        void onConnection(boolean connected, String msg);
        void onData(BmsData data);
        void onLog(String msg);
    }

    // 嘉佰达 BMS 指纹：设备名以 DB 开头（不区分大小写）
    private static final String NAME_PREFIX = "DB";
    private static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";
    private static final long POLL_INTERVAL = 60000L; // 60 秒轮询（电池数据变化慢，降低刷新频率）

    private final Context ctx;
    private final Callback cb;
    private final Handler handler = new Handler();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    private boolean filterEnabled = true;
    private boolean connected = false;
    private String targetMac = null;

    private final List<LeDevice> devices = new ArrayList<>();
    private final BmsParser parser;

    private BmsData current = new BmsData();
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (gatt != null) {
                readLiveData();
                handler.postDelayed(this, POLL_INTERVAL);
            }
        }
    };

    public BleManager(Context ctx, Callback cb) {
        this.ctx = ctx;
        this.cb = cb;
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = m != null ? m.getAdapter() : null;
        parser = new BmsParser(new BmsParser.Listener() {
            @Override public void onFrame(int cmd, BmsData d, String rawHex) {
                cb.onLog("[帧 " + String.format("%02X", cmd) + "] " + rawHex);
                if (cmd == 0x03) dumpFrame03(rawHex);
                current.merge(d);
                cb.onData(current);
            }
        });
    }

    /** 持久化命令03原始帧，供现场抓包分析电流等字段（最多保留 200 行） */
    private void dumpFrame03(String rawHex) {
        try {
            String line = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()) + " " + rawHex + "\n";
            java.io.File f = new java.io.File(ctx.getFilesDir(), "frame03.txt");
            String old = "";
            if (f.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                StringBuilder sb = new StringBuilder();
                String s;
                while ((s = br.readLine()) != null) sb.append(s).append("\n");
                br.close();
                old = sb.toString();
            }
            String all = old + line;
            if (all.length() > 40000) all = all.substring(all.length() - 30000);
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(all.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    public boolean isBleReady() { return adapter != null && adapter.isEnabled(); }
    public String getTargetMac() { return targetMac; }
    public boolean isConnected() { return connected; }
    public void setFilterEnabled(boolean e) { filterEnabled = e; }
    public boolean isFilterEnabled() { return filterEnabled; }

    private boolean hasPerm(String p) { return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    /** 是否嘉佰达 BMS 设备：设备名以 DB 开头（不区分大小写） */
    private boolean isTarget(LeDevice d) {
        String n = d.name == null ? "" : d.name.toUpperCase(Locale.getDefault());
        return n.startsWith(NAME_PREFIX);
    }

    public void startScan() {
        if (adapter == null || !adapter.isEnabled()) { cb.onLog("蓝牙未开启"); return; }
        if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { cb.onLog("无扫描权限"); return; }
        devices.clear();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { cb.onLog("无法获取扫描器"); return; }
        scanning = true;
        cb.onLog("开始扫描…" + (filterEnabled ? "（仅嘉佰达 BMS 设备）" : "（全部设备）"));
        scanner.startScan(scanCb);
    }

    public void stopScan() {
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        }
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int t, final ScanResult r) {
            final BluetoothDevice dev = r.getDevice();
            final int rssi = r.getRssi();
            String name = null;
            try { if (hasPerm(Manifest.permission.BLUETOOTH_CONNECT) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                name = dev.getName(); } catch (Exception ignored) {}
            final LeDevice d = new LeDevice(dev, name, rssi);
            if (filterEnabled && !isTarget(d)) return;
            handler.post(new Runnable() {
                @Override public void run() {
                    boolean found = false;
                    for (LeDevice x : devices) {
                        if (x.address.equals(d.address)) { x.rssi = d.rssi; found = true; break; }
                    }
                    if (!found) devices.add(d);
                    cb.onScanResult(new ArrayList<>(devices));
                }
            });
        }
        @Override public void onScanFailed(int code) { cb.onLog("扫描失败 " + code); }
    };

    public void connect(BluetoothDevice dev) {
        stopScan();
        targetMac = dev.getAddress();
        cb.onLog("连接 " + targetMac);
        try {
            gatt = dev.connectGatt(ctx, false, gattCb);
        } catch (Exception e) {
            cb.onConnection(false, "连接失败：" + e.getMessage());
        }
    }

    /** 自动连接上次记住的 MAC */
    public boolean autoConnect(String mac) {
        if (mac == null || adapter == null) return false;
        try {
            BluetoothDevice d = adapter.getRemoteDevice(mac);
            connect(d);
            return true;
        } catch (Exception e) {
            cb.onLog("自动连接失败：" + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        connected = false;
        handler.removeCallbacks(pollTask);
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        cb.onConnection(false, "已断开");
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cb.onLog("连接建立，正在发现服务…");
                connected = true;
                parser.reset();
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cb.onLog("连接断开 status=" + status);
                connected = false;
                handler.removeCallbacks(pollTask);
                cb.onConnection(false, "连接断开");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { cb.onLog("服务发现失败 " + status); return; }
            boolean valid = g.getService(java.util.UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")) != null;
            cb.onLog(valid ? "设备指纹验证通过（FF00 服务）" : "警告：未发现 FF00 服务");

            // 订阅 FF01 通知
            BluetoothGattCharacteristic notifyChar = findChar("0000ff01", BluetoothGattCharacteristic.PROPERTY_NOTIFY);
            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor d = notifyChar.getDescriptor(java.util.UUID.fromString(CCCD));
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    cb.onLog("订阅通知 " + (g.writeDescriptor(d) ? "已发起" : "失败"));
                } else cb.onLog("未找到 CCCD 描述符");
            } else cb.onLog("未找到 FF01 通知特征");

            // 延迟发命令，等订阅生效
            handler.postDelayed(new Runnable() {
                @Override public void run() { readLiveData(); }
            }, 500);

            handler.removeCallbacks(pollTask);
            handler.postDelayed(pollTask, POLL_INTERVAL);
            cb.onConnection(true, "已连接，开始读取数据");
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handleNotify(value);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            handleNotify(c.getValue());
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            cb.onLog("读特征 status=" + status);
            handleNotify(c.getValue());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            cb.onLog("命令写入回调 status=" + status);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            cb.onLog("订阅回调 status=" + status);
        }
    };

    private BluetoothGattCharacteristic findChar(String prefix, int prop) {
        if (gatt == null) return null;
        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (c.getUuid().toString().toLowerCase(Locale.getDefault()).startsWith(prefix)
                        && (c.getProperties() & prop) != 0) return c;
            }
        }
        return null;
    }

    /** 收到通知字节流，交给解析器 */
    private void handleNotify(byte[] value) {
        if (value == null || value.length == 0) return;
        cb.onLog("[通知 " + value.length + "B] " + BmsParser.hex(value));
        parser.feed(value);
    }

    /** 发 03（实时）+ 04（单体）命令，两条命令间隔 300ms */
    private void readLiveData() {
        if (gatt == null || !connected) return;
        sendCmd(0x03);
        handler.postDelayed(new Runnable() {
            @Override public void run() { sendCmd(0x04); }
        }, 300);
    }

    /** 直接发命令帧（不依赖写入回调驱动，避免队列卡死） */
    private void sendCmd(int code) {
        if (gatt == null || !connected) return;
        BluetoothGattCharacteristic c = findChar("0000ff02",
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
        if (c == null) { cb.onLog("未找到 FF02 写特征"); return; }
        int chk = 0x10000 - code;
        byte[] data = {(byte)0xDD, (byte)0xA5, (byte)code, 0x00,
                (byte)((chk >> 8) & 0xFF), (byte)(chk & 0xFF), (byte)0x77};
        if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }
        c.setValue(data);
        boolean ok = gatt.writeCharacteristic(c);
        cb.onLog("发命令 " + String.format("%02X", code) + (ok ? " 已发起" : " 失败"));
    }
}
