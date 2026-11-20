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

/** 保护事件记录：CSV 文本，一行一条带时间戳的保护触发/解除记录 */
public class ProtectionStore {
    private static final String FILE = "bms_protect_log.csv";
    private static final int MAX = 500;
    private final Context ctx;

    public ProtectionStore(Context ctx) { this.ctx = ctx; }

    public static class Event {
        public long time;
        public String text;
        public boolean active; // true=触发 false=解除
    }

    /** 追加一条事件，格式为 time,1|0,描述 */
    public synchronized void append(long time, boolean active, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            List<String> lines = readLines();
            String clean = text.replace(",", " ").replace("\n", " ").trim();
            lines.add(String.format(Locale.getDefault(), "%d,%d,%s", time, active ? 1 : 0, clean));
            while (lines.size() > MAX) lines.remove(0);
            writeLines(lines);
        } catch (Exception ignored) {}
    }

    /** 读取全部事件，最近的在最后 */
    public synchronized List<Event> load() {
        List<Event> out = new ArrayList<>();
        for (String line : readLines()) {
            String[] p = line.split(",", 3);
            if (p.length < 3) continue;
            try {
                Event e = new Event();
                e.time = Long.parseLong(p[0]);
                e.active = "1".equals(p[1]);
                e.text = p[2];
                out.add(e);
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
