package com.example.bmsdash;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 本地历史存储：CSV 文本，一行一条记录 */
public class HistoryStore {
    private static final String FILE = "bms_history.csv";
    private static final int MAX = 2000; // 最多保留条数
    private final Context ctx;

    public HistoryStore(Context ctx) { this.ctx = ctx; }

    /** 一条历史记录 */
    public static class Point {
        public long time;
        public double voltage;
        public int soc;
        public double tempMax;
        public int diff;
        public int cellMin;
    }

    public synchronized void append(BmsData d) {
        if (d.voltage <= 0) return;
        try {
            List<String> lines = readLines();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.getDefault(), "%d,%.2f,%d,%.1f,%d,%d",
                    d.timestamp, d.voltage, d.soc, d.tempMax, d.diff, d.cellMin));
            lines.add(sb.toString());
            while (lines.size() > MAX) lines.remove(0);
            writeLines(lines);
        } catch (Exception ignored) {}
    }

    public synchronized List<Point> load() {
        List<Point> out = new ArrayList<>();
        for (String line : readLines()) {
            String[] p = line.split(",");
            if (p.length < 6) continue;
            try {
                Point pt = new Point();
                pt.time = Long.parseLong(p[0]);
                pt.voltage = Double.parseDouble(p[1]);
                pt.soc = Integer.parseInt(p[2]);
                pt.tempMax = Double.parseDouble(p[3]);
                pt.diff = Integer.parseInt(p[4]);
                pt.cellMin = Integer.parseInt(p[5]);
                out.add(pt);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public synchronized void clear() {
        try { ctx.deleteFile(FILE); } catch (Exception ignored) {}
    }

    private List<String> readLines() {
        List<String> lines = new ArrayList<>();
        try {
            FileInputStream in = ctx.openFileInput(FILE);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String l;
            while ((l = br.readLine()) != null) if (l.trim().length() > 0) lines.add(l.trim());
            br.close();
        } catch (Exception ignored) {}
        return lines;
    }

    private void writeLines(List<String> lines) {
        try {
            Writer w = new OutputStreamWriter(ctx.openFileOutput(FILE, Context.MODE_PRIVATE));
            for (String l : lines) w.write(l + "\n");
            w.flush();
            w.close();
        } catch (Exception ignored) {}
    }
}
