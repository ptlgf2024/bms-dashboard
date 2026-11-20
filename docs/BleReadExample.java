package com.example.bmsdash;

import android.Manifest;
import android.app.Activity;
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
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * 嘉佰达 BMS 蓝牙连接与数据读取 示例程序（兼容嘉佰达软件板通用协议 V4）
 *
 * 演示：扫描设备（DB 开头）→ 连接 → 订阅 FF01 通知 → 发送 0x03/0x04 命令
 *       → 分帧重组 → 解析电压/电流/SOC/温度/24串单体电压
 *
 * 用法：把本类放到 src/com/example/bmsdash/ 下即可编译；或作为独立 Activity 运行。
 *      权限在 Manifest 中声明并在运行时申请（Android 12+）。
 */
public class BleReadExample extends Activity {

    private static final String UUID_SERVICE = "0000ff00-0000-1000-8000-00805f9b34fb";
    private static final String UUID_NOTIFY = "0000ff01-0000-1000-8000-00805f9b34fb";
    private static final String UUID_WRITE = "0000ff02-0000-1000-8000-00805f9b34fb";
    private static final String UUID_CCCD = "00002902-0000-1000-8000-00805f9b34fb";
    private static final long POLL_INTERVAL = 60000L; // 60 秒轮询

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notifyChar, writeChar;
    private final Handler handler = new Handler();
    private final ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();
    private TextView tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tvLog = new TextView(this);
        setContentView(tvLog);
        adapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        requestPermissions();
    }

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        // 简化示例：实际应检查 SDK 版本与授权结果后再扫描
        requestPermissions(perms, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] grant) {
        startScan();
    }

    // ---------- 1. 扫描设备 ----------
    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) { log("蓝牙未开启"); return; }
        scanner = adapter.getBluetoothLeScanner();
        scanner.startScan(scanCb);
        log("开始扫描…");
        handler.postDelayed(new Runnable() {
            public void run() { if (scanner != null) scanner.stopScan(scanCb); }
        }, 10000);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = dev.getName();
            // 过滤：名称以 DB 开头（不区分大小写）
            if (name == null || !name.toUpperCase(Locale.getDefault()).startsWith("DB")) return;
            log("发现设备：" + name + "  " + dev.getAddress());
            if (scanner != null) scanner.stopScan(scanCb);
            connect(dev);
        }
    };

    // ---------- 2. 连接 GATT ----------
    private void connect(BluetoothDevice dev) {
        log("连接 " + dev.getAddress());
        gatt = dev.connectGatt(this, false, gattCb);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("已连接，发现服务…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("断开连接 status=" + status);
                handler.removeCallbacks(pollTask);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("服务发现失败"); return; }
            BluetoothGattService svc = g.getService(java.util.UUID.fromString(UUID_SERVICE));
            if (svc == null) { log("未发现 FF00 服务，非目标设备"); return; }
            log("设备指纹验证通过（FF00 服务）");

            notifyChar = findChar(svc, UUID_NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY);
            writeChar = findChar(svc, UUID_WRITE,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
            if (notifyChar == null || writeChar == null) { log("特征值缺失"); return; }

            // 订阅通知：本地开启 + 写 CCCD
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(java.util.UUID.fromString(UUID_CCCD));
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(cccd);
                log("已订阅 FF01 通知");
            }

            // 延迟发命令，等订阅生效
            handler.postDelayed(new Runnable() {
                public void run() { readData(); }
            }, 500);
        }

        // Android 13+ 新回调（带 value 参数）
        @Override public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic c, byte[] value) {
            onNotify(value);
        }
        // 旧版本回调
        @Override public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic c) {
            onNotify(c.getValue());
        }
    };

    private BluetoothGattCharacteristic findChar(BluetoothGattService svc, String uuid, int prop) {
        for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
            if (c.getUuid().toString().equalsIgnoreCase(uuid) && (c.getProperties() & prop) != 0) return c;
        }
        return null;
    }

    // ---------- 3. 发送命令 ----------
    private void readData() {
        if (gatt == null || writeChar == null) return;
        sendCommand(0x03); // 实时数据
        handler.postDelayed(new Runnable() {
            public void run() { sendCommand(0x04); } // 单体电压
        }, 300);
        // 固定间隔轮询
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, POLL_INTERVAL);
    }

    private final Runnable pollTask = new Runnable() {
        public void run() { readData(); }
    };

    /** 构造命令帧 DD A5 CMD 00 CHK_H CHK_L 77，CHK = 0x10000 - CMD */
    private void sendCommand(int cmd) {
        if (gatt == null || writeChar == null) return;
        int chk = 0x10000 - cmd;
        byte[] frame = {
                (byte) 0xDD, (byte) 0xA5, (byte) cmd, 0x00,
                (byte) ((chk >> 8) & 0xFF), (byte) (chk & 0xFF), (byte) 0x77
        };
        if ((writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                && (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }
        writeChar.setValue(frame);
        boolean ok = gatt.writeCharacteristic(writeChar);
        log("发命令 " + String.format("%02X", cmd) + (ok ? " 成功" : " 失败"));
    }

    // ---------- 4. 接收通知并重组帧 ----------
    private void onNotify(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        for (byte bb : chunk) {
            int v = bb & 0xFF;
            byte[] cur = frameBuffer.toByteArray();
            if (cur.length == 0 && v != 0xDD) continue; // 丢弃噪声
            frameBuffer.write(v);
            cur = frameBuffer.toByteArray();
            if (cur.length >= 4) {
                int len = ((cur[2] & 0xFF) << 8) | (cur[3] & 0xFF);
                int need = 4 + len + 3; // 帧头4 + 数据LEN + 校验2 + 帧尾1
                if (cur.length >= need) {
                    byte[] frame = Arrays.copyOf(cur, need);
                    parseFrame(frame);
                    frameBuffer.reset();
                    if (cur.length > need) { // 粘包：剩余字节重新入队
                        frameBuffer.write(Arrays.copyOfRange(cur, need, cur.length));
                    }
                }
            }
            if (frameBuffer.size() > 1024) frameBuffer.reset(); // 防溢出
        }
    }

    // ---------- 5. 解析字段 ----------
    private void parseFrame(byte[] frame) {
        if (frame.length < 7) return;
        int cmd = frame[1] & 0xFF;
        int len = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (len > frame.length - 7) len = frame.length - 7;
        byte[] d = Arrays.copyOfRange(frame, 4, 4 + len);

        if (cmd == 0x03 && d.length >= 29) {
            double voltage = (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) / 100.0;
            int raw = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
            double current = (raw >= 0x8000 ? raw - 0x10000 : raw) / 100.0; // 正充电/负放电
            int soc = d[19] & 0xFF;
            double t1 = (((d[23] & 0xFF) << 8) | (d[24] & 0xFF)) / 10.0 - 273.15;
            double t2 = (((d[25] & 0xFF) << 8) | (d[26] & 0xFF)) / 10.0 - 273.15;
            double t3 = (((d[27] & 0xFF) << 8) | (d[28] & 0xFF)) / 10.0 - 273.15;
            log(String.format(Locale.getDefault(),
                    "实时: 电压 %.2f V, 电流 %.2f A, SOC %d%%, 温度 %.1f~%.1f ℃",
                    voltage, current, soc, Math.min(t1, Math.min(t2, t3)), Math.max(t1, Math.max(t2, t3))));
        } else if (cmd == 0x04 && d.length >= 48) {
            int[] cells = new int[24];
            int min = Integer.MAX_VALUE, max = 0;
            for (int i = 0; i < 24; i++) {
                cells[i] = ((d[i * 2] & 0xFF) << 8) | (d[i * 2 + 1] & 0xFF); // mV
                if (cells[i] < min) min = cells[i];
                if (cells[i] > max) max = cells[i];
            }
            log(String.format(Locale.getDefault(),
                    "单体: 24串 %d~%d mV, 压差 %d mV, 总压 %.2f V",
                    min, max, max - min, sum(cells) / 1000.0));
        } else if (cmd == 0x05) {
            String name = new String(d);
            log("设备名: " + name);
        }
    }

    private double sum(int[] cells) {
        int s = 0;
        for (int v : cells) s += v;
        return s;
    }

    private void log(String s) {
        tvLog.append(s + "\n");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (gatt != null) { gatt.disconnect(); gatt.close(); }
    }
}
