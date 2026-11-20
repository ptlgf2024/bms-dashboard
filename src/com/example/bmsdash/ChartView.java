package com.example.bmsdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 曲线图：SOC / 电压随时间变化。左轴 SOC%，右轴 电压V，横轴 时间 */
public class ChartView extends View {
    private Paint grid, socPaint, voltPaint, text, socTitle, voltTitle;
    private List<HistoryStore.Point> data;
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat mdHmm = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public ChartView(Context c) { super(c); init(); }
    public ChartView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(Color.rgb(224, 224, 224));
        grid.setStrokeWidth(1);
        socPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        socPaint.setColor(Color.rgb(46, 125, 50));
        socPaint.setStrokeWidth(3);
        socPaint.setStyle(Paint.Style.STROKE);
        voltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        voltPaint.setColor(Color.rgb(30, 136, 229));
        voltPaint.setStrokeWidth(3);
        voltPaint.setStyle(Paint.Style.STROKE);
        text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(96, 125, 139));
        text.setTextSize(18);
        socTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        socTitle.setColor(Color.rgb(46, 125, 50));
        socTitle.setTextSize(18);
        socTitle.setFakeBoldText(true);
        voltTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        voltTitle.setColor(Color.rgb(30, 136, 229));
        voltTitle.setTextSize(18);
        voltTitle.setFakeBoldText(true);
    }

    public void setData(List<HistoryStore.Point> d) { this.data = d; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float padL = 50, padR = 52, padT = 26, padB = 26;
        float x0 = padL, x1 = w - padR;
        float yTop = padT, yBot = h - padB;
        float plotH = yBot - yTop;

        double vMin = 0, vMax = 100;
        if (data != null && data.size() >= 2) {
            vMin = Double.MAX_VALUE; vMax = -Double.MAX_VALUE;
            for (HistoryStore.Point p : data) {
                if (p.voltage > 0 && p.voltage < vMin) vMin = p.voltage;
                if (p.voltage > vMax) vMax = p.voltage;
            }
            if (vMax - vMin < 1) { vMin -= 0.5; vMax += 0.5; }
        }

        // 轴标题（顶部，颜色对应曲线）
        socTitle.setTextAlign(Paint.Align.LEFT);
        voltTitle.setTextAlign(Paint.Align.RIGHT);
        c.drawText("SOC%", 2, 18, socTitle);
        c.drawText("电压V", w - 2, 18, voltTitle);

        // 纵轴刻度：左 SOC% 0~100，右 电压V 自适应
        for (int i = 0; i <= 4; i++) {
            float y = yTop + plotH * i / 4f;
            c.drawLine(x0, y, x1, y, grid);
            int socVal = 100 - i * 25;
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(socVal), x0 - 6, y + 6, text);
            if (data != null && data.size() >= 2) {
                double vv = vMax - (vMax - vMin) * i / 4.0;
                text.setTextAlign(Paint.Align.LEFT);
                c.drawText(String.format(Locale.getDefault(), "%.1f", vv), x1 + 6, y + 6, text);
            }
        }

        if (data == null || data.size() < 2) {
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("历史数据不足（至少2次读数）", w / 2f, h / 2f, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }

        int n = data.size();
        for (int i = 0; i < n - 1; i++) {
            HistoryStore.Point a = data.get(i), b = data.get(i + 1);
            float xa = x0 + (x1 - x0) * i / (n - 1);
            float xb = x0 + (x1 - x0) * (i + 1) / (n - 1);
            float yaSoc = yTop + plotH * (1 - Math.max(0, Math.min(100, a.soc)) / 100f);
            float ybSoc = yTop + plotH * (1 - Math.max(0, Math.min(100, b.soc)) / 100f);
            if (a.soc >= 0 && b.soc >= 0) c.drawLine(xa, yaSoc, xb, ybSoc, socPaint);
            float yaV = yTop + plotH * (float)(1 - (a.voltage - vMin) / (vMax - vMin));
            float ybV = yTop + plotH * (float)(1 - (b.voltage - vMin) / (vMax - vMin));
            c.drawLine(xa, yaV, xb, ybV, voltPaint);
        }

        // 横轴时间刻度：起点 / 中点 / 终点
        text.setTextSize(18);
        long t0 = data.get(0).time, tl = data.get(n - 1).time;
        boolean sameDay = mdHmm.format(new Date(t0)).substring(0, 5)
                .equals(mdHmm.format(new Date(tl)).substring(0, 5));
        SimpleDateFormat fmt = sameDay ? hhmm : mdHmm;
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(fmt.format(new Date(t0)), x0, h - 6, text);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(fmt.format(new Date(data.get(n / 2).time)), (x0 + x1) / 2, h - 6, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmt.format(new Date(tl)), x1, h - 6, text);
        text.setTextAlign(Paint.Align.LEFT);
    }
}
