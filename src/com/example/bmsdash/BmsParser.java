package com.example.bmsdash;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

/** BMS 协议解析器：把通知字节流按 DD...77 帧解析成 BmsData */
public class BmsParser {
    private final ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();

    public interface Listener {
        void onFrame(int cmd, BmsData data, String rawHex);
    }

    private final Listener listener;

    public BmsParser(Listener listener) { this.listener = listener; }

    public void reset() { frameBytes.reset(); }

    /** 喂入通知分片，内部按帧头+长度字段重组 */
    public void feed(byte[] val) {
        if (val == null || val.length == 0) return;
        for (byte bb : val) {
            int v = bb & 0xFF;
            byte[] cur = frameBytes.toByteArray();
            if (cur.length == 0) {
                if (v != 0xDD) continue;
            }
            frameBytes.write(v);
            cur = frameBytes.toByteArray();
            if (cur.length >= 4) {
                int len = ((cur[2] & 0xFF) << 8) | (cur[3] & 0xFF);
                int need = 4 + len + 3;
                if (cur.length >= need) {
                    byte[] frame = Arrays.copyOf(cur, Math.min(need, cur.length));
                    parse(frame);
                    frameBytes.reset();
                    if (cur.length > need) {
                        byte[] rest = Arrays.copyOfRange(cur, need, cur.length);
                        frameBytes.reset();
                        feed(rest);
                        return;
                    }
                }
            }
            if (frameBytes.size() > 1024) frameBytes.reset();
        }
    }

    private void parse(byte[] frame) {
        if (frame.length < 7) return;
        int cmd = frame[1] & 0xFF;
        int len = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (len > frame.length - 7) len = frame.length - 7;
        byte[] d = Arrays.copyOfRange(frame, 4, 4 + len);
        BmsData data = new BmsData();
        data.timestamp = System.currentTimeMillis();

        if (cmd == 0x03 && d.length >= 29) {
            data.hasRealtime = true;
            data.voltage = (((d[0] & 0xFF) << 8) | (d[1] & 0xFF)) / 100.0;
            int raw = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
            data.current = (raw >= 0x8000 ? raw - 0x10000 : raw) / 100.0;
            data.hasCurrent = true;
            // 嘉佰达 V4 字段布局
            if (d.length >= 10) data.cycles = ((d[8] & 0xFF) << 8) | (d[9] & 0xFF);
            if (d.length >= 16) {
                int balLo = ((d[12] & 0xFF) << 8) | (d[13] & 0xFF);
                int balHi = ((d[14] & 0xFF) << 8) | (d[15] & 0xFF);
                data.balanceOn = (balLo | balHi) != 0;
            }
            if (d.length >= 17) data.protectStatus = ((d[16] & 0xFF) << 8) | (d[17] & 0xFF);
            data.soc = d[19] & 0xFF;
            if (d.length >= 21) {
                int fet = d[20] & 0xFF;
                data.chargeMos = (fet & 0x01) != 0;
                data.dischargeMos = (fet & 0x02) != 0;
            }
            int n = d.length >= 23 ? (d[22] & 0xFF) : 0;
            double[] temps = new double[3];
            int cnt = 0;
            for (int i = 0; i < n && i < 3 && 23 + i * 2 + 1 < d.length; i++) {
                int t = ((d[23 + i * 2] & 0xFF) << 8) | (d[23 + i * 2 + 1] & 0xFF);
                temps[i] = t / 10.0 - 273.15;
                cnt++;
            }
            if (cnt > 0) {
                data.tempMos = temps[0];
                data.temp1 = cnt > 1 ? temps[1] : temps[0];
                data.temp2 = cnt > 2 ? temps[2] : temps[0];
                double lo = temps[0], hi = temps[0];
                for (int i = 1; i < cnt; i++) { lo = Math.min(lo, temps[i]); hi = Math.max(hi, temps[i]); }
                data.tempMin = lo;
                data.tempMax = hi;
            }
        } else if (cmd == 0x04 && d.length >= 48) {
            for (int i = 0; i < 24; i++) {
                data.cells[i] = ((d[i * 2] & 0xFF) << 8) | (d[i * 2 + 1] & 0xFF);
            }
            data.computeCells();
        } else if (cmd == 0x05) {
            // 设备名帧，忽略
        }
        if (listener != null) {
            listener.onFrame(cmd, data, hex(frame));
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.getDefault(), "%02X ", x & 0xFF));
        return sb.toString().trim();
    }
}
