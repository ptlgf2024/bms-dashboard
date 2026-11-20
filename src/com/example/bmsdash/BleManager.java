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
import android.content.SharedPreferences;
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
        void onProbeResult(String result);
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
    private String targetName = null;

    private final List<LeDevice> devices = new ArrayList<>();
    private final BmsParser parser;

    private BmsData current = new BmsData();

    // V12 只读读取：独立帧缓冲，按 DD CMD STATUS LEN ... CHK 77 重组（区别于 03/04 的双字节长度帧）
    private int readingCmd = -1;
    private final java.io.ByteArrayOutputStream readBytes = new java.io.ByteArrayOutputStream();
    /** 批量只读寄存器：值=寄存器号/读取个数 */
    private static final int[][] V12_REGS = {
            {0x0000, 0x08}, // 0~7：标称容量/满充电压/功耗/生产日期/序列号/循环次数等
            {0x0008, 0x30}, // 8~55：保护阈值/温度/电流/均衡/功能配置等（含 31 串数）
            {0x0038, 0x10}, // 厂商信息 ASCII
            {0x0048, 0x10}, // BMS 编码 ASCII
            {0x0058, 0x10}, // 条码 ASCII
            {0x0068, 0x13}, // 104~122：SOC 电压点/学习容量/额定电压电流功率等
            {0x007B, 0x23}, // 123~157：保留/报警参数 1~35（全部读出以备展示）
            {0x009E, 0x0C}, // 电池型号 ASCII
            {0x00AA, 0x06}, // 170~175：唯一 ID 码（12 字节十六进制）
            {0x00B0, 0x08}, // 硬件名称 ASCII
    };
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
                // 只读参数帧由 feedRead 单独处理，不进入实时数据合并/历史记录
                if (cmd == 0x03 || cmd == 0x04) {
                    current.merge(d);
                    cb.onData(current);
                }
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
    public String getTargetName() { return targetName; }
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
        targetName = null;
        try {
            if (hasPerm(Manifest.permission.BLUETOOTH_CONNECT) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                targetName = dev.getName();
        } catch (Exception ignored) {}
        cb.onLog("连接设备" + (targetName != null ? "：" + targetName : ""));
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
            // 连接后自动读取 V12 只读参数（厂商/型号/保护次数/芯片类型等），无需手动探测
            handler.postDelayed(new Runnable() {
                @Override public void run() { readAllV12(); }
            }, 1500);

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
        if (readingCmd >= 0) feedRead(value);
        parser.feed(value);
    }

    /** 按 DD CMD STATUS LEN ... CHK 77 重组只读响应帧（0xFA / 0xAA / 0x05 / 0x00） */
    private void feedRead(byte[] value) {
        for (byte b : value) readBytes.write(b & 0xFF);
        byte[] cur = readBytes.toByteArray();
        // 容错重同步：跳过噪声，寻找 DD <期望命令>
        int start = -1;
        for (int i = 0; i + 1 < cur.length; i++) {
            if ((cur[i] & 0xFF) == 0xDD && (cur[i + 1] & 0xFF) == readingCmd) { start = i; break; }
        }
        if (start < 0) {
            if (cur.length > 256) readBytes.reset();
            return;
        }
        if (start > 0) {
            byte[] trimmed = java.util.Arrays.copyOfRange(cur, start, cur.length);
            readBytes.reset();
            for (byte b : trimmed) readBytes.write(b);
            cur = trimmed;
        }
        if (cur.length < 4) return;
        int len = cur[3] & 0xFF;
        if (len > 200) { readBytes.reset(); return; }
        int need = 4 + len + 3;
        if (cur.length < need) return;
        byte[] frame = java.util.Arrays.copyOf(cur, need);
        int cmd = readingCmd;
        readingCmd = -1;
        readBytes.reset();
        parseReadFrame(cmd, frame);
    }

    private void parseReadFrame(int cmd, byte[] frame) {
        int status = frame[2] & 0xFF;
        int len = frame[3] & 0xFF;
        String hex = BmsParser.hex(frame);
        if (status != 0x00) {
            cb.onProbeResult(String.format("命令 %02X 返回状态 0x%02X（不支持或失败）\n%s", cmd, status, hex));
            return;
        }
        switch (cmd) {
            case 0xFA: parseFaFrame(frame, len, hex); break;
            case 0x05: parseHwVersion(frame, len, hex); break;
            case 0xAA: parseProtectCounts(frame, len, hex); break;
            case 0x00: parseChipType(frame, len, hex); break;
            case 0xF6: parseInternalResistance(frame, len, hex); break;
            default: cb.onProbeResult(hex);
        }
    }

    /** 0xFA 寄存器读取：ASCII 型缓存为字符串，数值型缓存为 reg_<寄存器号> */
    private void parseFaFrame(byte[] frame, int len, String hex) {
        if (len < 3) { cb.onProbeResult("寄存器数据过短\n" + hex); return; }
        int reg = ((frame[4] & 0xFF) << 8) | (frame[5] & 0xFF);
        SharedPreferences.Editor e = ctx.getSharedPreferences(WidgetProvider.CACHE, Context.MODE_PRIVATE).edit();
        StringBuilder sb = new StringBuilder();
        String strKey = faStringKey(reg);
        if (strKey != null) {
            int strLen = frame[7] & 0xFF;
            StringBuilder val = new StringBuilder();
            if (strLen > 0 && strLen <= 63 && 8 + strLen <= 4 + len) {
                for (int i = 8; i < 8 + strLen; i++) {
                    int c = frame[i] & 0xFF;
                    if (c >= 0x20 && c <= 0x7E) val.append((char) c);
                }
            }
            if (val.length() == 0) {
                StringBuilder run = new StringBuilder(), best = new StringBuilder();
                for (int i = 6; i < 4 + len; i++) {
                    int c = frame[i] & 0xFF;
                    if (c >= 0x20 && c <= 0x7E) { run.append((char) c); if (run.length() > best.length()) best = new StringBuilder(run); }
                    else run.setLength(0);
                }
                val = best;
            }
            String value = val.toString().trim();
            e.putString(strKey, value);
            sb.append(String.format("寄存器 0x%04X：%s", reg, value.isEmpty() ? "(空)" : value));
        } else {
            int count = Math.min((len - 3) / 2, 95);
            for (int i = 0; i < count; i++) {
                int v = ((frame[7 + i * 2] & 0xFF) << 8) | (frame[8 + i * 2] & 0xFF);
                e.putInt("reg_" + (reg + i), v);
                if (i == 0) sb.append(String.format("寄存器 0x%04X = %d", reg, v));
            }
        }
        e.apply();
        sb.append("\n").append(hex);
        cb.onProbeResult(sb.toString());
    }

    private static String faStringKey(int reg) {
        switch (reg) {
            case 0x0038: return "probe_maker";
            case 0x0048: return "probe_bms_code";
            case 0x0058: return "probe_barcode";
            case 0x009E: return "probe_model";
            case 0x00B0: return "probe_hw_name";
            default: return null;
        }
    }

    /** 0xF6 单体内阻：每串 2 字节补码，单位 0.1mΩ */
    private void parseInternalResistance(byte[] frame, int len, String hex) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(WidgetProvider.CACHE, Context.MODE_PRIVATE).edit();
        StringBuilder sb = new StringBuilder("单体内阻（0.1mΩ）：\n");
        int n = Math.min(len / 2, 30);
        for (int i = 0; i < n; i++) {
            int raw = ((frame[4 + i * 2] & 0xFF) << 8) | (frame[5 + i * 2] & 0xFF);
            if (raw >= 0x8000) raw -= 0x10000;
            e.putInt("ir_" + (i + 1), raw);
            sb.append((i + 1)).append(":").append(raw).append(" ");
            if ((i + 1) % 6 == 0) sb.append("\n");
        }
        e.apply();
        cb.onProbeResult(sb.toString());
    }

    /** 0x05 硬件版本：ASCII 字符串 */
    private void parseHwVersion(byte[] frame, int len, String hex) {
        StringBuilder val = new StringBuilder();
        for (int i = 4; i < 4 + len && i < frame.length - 2; i++) {
            int c = frame[i] & 0xFF;
            if (c >= 0x20 && c <= 0x7E) val.append((char) c);
        }
        String value = val.toString().trim();
        ctx.getSharedPreferences(WidgetProvider.CACHE, Context.MODE_PRIVATE).edit()
                .putString("probe_hw_version", value).apply();
        cb.onProbeResult("硬件版本：" + (value.isEmpty() ? "(空)" : value) + "\n" + hex);
    }

    /** 0xAA 保护次数统计：12 组 2 字节大端计数 */
    private void parseProtectCounts(byte[] frame, int len, String hex) {
        String[] names = {"短路保护", "充电过流", "放电过流", "单体过压", "单体欠压", "充电高温",
                "充电低温", "放电高温", "放电低温", "整组过压", "整组欠压", "系统重启"};
        int count = Math.min(len / 2, names.length);
        SharedPreferences.Editor e = ctx.getSharedPreferences(WidgetProvider.CACHE, Context.MODE_PRIVATE).edit();
        StringBuilder sb = new StringBuilder("保护次数统计（0xAA）：\n");
        for (int i = 0; i < count; i++) {
            int v = ((frame[4 + i * 2] & 0xFF) << 8) | (frame[5 + i * 2] & 0xFF);
            e.putInt("protect_count_" + i, v);
            sb.append(names[i]).append(" ").append(v).append((i % 2 == 1) ? "\n" : "  ");
        }
        e.apply();
        cb.onProbeResult(sb.toString());
    }

    /** 0x00 主控芯片类型：0=TI 1=凹凸7717 2=松下49522 3=中颖309 4=中颖303 5=吉车DC10XX 6=OZ3714 */
    private void parseChipType(byte[] frame, int len, String hex) {
        int chip = len >= 1 ? (frame[4] & 0xFF) : -1;
        String[] names = {"TI 方案", "凹凸 7717", "松下 49522", "中颖 309", "中颖 303", "吉车 DC10XX", "OZ3714"};
        String name = (chip >= 0 && chip < names.length) ? names[chip] : ("未知(" + chip + ")");
        ctx.getSharedPreferences(WidgetProvider.CACHE, Context.MODE_PRIVATE).edit()
                .putInt("chip_type", chip).putString("chip_type_name", name).apply();
        cb.onProbeResult("主控芯片类型：" + name + "\n" + hex);
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

    /** 手动触发一次完整的 V12 只读数据读取 */
    public void readAllV12() {
        if (gatt == null || !connected) { cb.onProbeResult("未连接，无法读取 V12 数据"); return; }
        cb.onLog("开始 V12 只读数据批量读取");
        runV12Step(0);
    }

    /** 顺序发送只读命令，避免应答帧互相覆盖；每步间隔 700ms */
    private void runV12Step(final int step) {
        if (!connected) return;
        switch (step) {
            case 0: readOnly(0x00, null, 0, "芯片类型"); break;
            case 1: readOnly(0x05, null, 0, "硬件版本"); break;
            case 2: readOnly(0xAA, null, 0, "保护次数"); break;
            case 3: readOnly(0xF6, null, 0, "单体内阻"); break;
            default:
                int idx = step - 4;
                if (idx >= V12_REGS.length) {
                    cb.onProbeResult("V12 只读数据读取完成，结果已保存到信息页");
                    return;
                }
                readV12Register(V12_REGS[idx][0], V12_REGS[idx][1]);
                break;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { runV12Step(step + 1); }
        }, 700);
    }

    /** 0xFA 只读寄存器读取 */
    public void probeV12(int reg, int count) { readV12Register(reg, count); }

    private void readV12Register(int reg, int count) {
        byte[] payload = {(byte) ((reg >> 8) & 0xFF), (byte) (reg & 0xFF), (byte) (count & 0xFF)};
        readOnly(0xFA, payload, 3, "寄存器 0x" + Integer.toHexString(reg).toUpperCase(Locale.getDefault()));
    }

    /** 通用只读命令：DD A5 CMD LEN [payload] CHK_H CHK_L 77，校验和=0x10000-(CMD+LEN+payload) */
    private void readOnly(int cmd, byte[] payload, int len, String tag) {
        if (gatt == null || !connected) return;
        int sum = cmd + len;
        if (payload != null) for (byte b : payload) sum += b & 0xFF;
        int chk = (0x10000 - sum) & 0xFFFF;
        byte[] frame = new byte[7 + len];
        frame[0] = (byte) 0xDD; frame[1] = (byte) 0xA5; frame[2] = (byte) cmd; frame[3] = (byte) len;
        if (payload != null) System.arraycopy(payload, 0, frame, 4, len);
        frame[4 + len] = (byte) ((chk >> 8) & 0xFF);
        frame[5 + len] = (byte) (chk & 0xFF);
        frame[6 + len] = (byte) 0x77;
        readingCmd = cmd;
        readBytes.reset();
        writeRaw(frame, "只读 " + tag);
    }

    private void writeRaw(byte[] data, String tag) {
        BluetoothGattCharacteristic c = findChar("0000ff02",
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
        if (c == null) { cb.onLog("未找到 FF02 写特征"); return; }
        if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }
        c.setValue(data);
        boolean ok = gatt.writeCharacteristic(c);
        cb.onLog(tag + " " + BmsParser.hex(data) + (ok ? " 已发起" : " 失败"));
    }
}
