package com.example.bmsdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 电压表盘：半圆弧仪表盘 + 指针 */
public class GaugeView extends View {
    private Paint arcBg, arcVal, needle, text, labelPaint;
    private double value = 0, min = 60, max = 90;
    private String unit = "V";

    public GaugeView(Context c) { super(c); init(); }
    public GaugeView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        arcBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcBg.setStyle(Paint.Style.STROKE);
        arcBg.setColor(Color.rgb(207, 216, 220));
        arcVal = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcVal.setStyle(Paint.Style.STROKE);
        arcVal.setColor(Color.rgb(30, 136, 229));
        needle = new Paint(Paint.ANTI_ALIAS_FLAG);
        needle.setColor(Color.rgb(198, 40, 40));
        needle.setStrokeWidth(4);
        text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(33, 33, 33));
        text.setTextAlign(Paint.Align.CENTER);
        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.rgb(96, 125, 139));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRange(double min, double max) { this.min = min; this.max = max; invalidate(); }
    public void setValue(double v) { this.value = v; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * 0.62f;
        float radius = Math.min(w / 2f, h * 0.72f) - 16;
        float stroke = Math.max(8f, radius * 0.12f);
        arcBg.setStrokeWidth(stroke);
        arcVal.setStrokeWidth(stroke);

        RectF rect = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        int startAngle = 150, sweep = 240;
        c.drawArc(rect, startAngle, sweep, false, arcBg);

        double pct = (value - min) / (max - min);
        if (pct < 0) pct = 0; if (pct > 1) pct = 1;
        // 颜色随电压区间变化
        int color;
        if (pct < 0.25) color = Color.rgb(198, 40, 40);
        else if (pct < 0.4) color = Color.rgb(249, 168, 37);
        else color = Color.rgb(46, 125, 50);
        arcVal.setColor(color);
        c.drawArc(rect, startAngle, (float)(sweep * pct), false, arcVal);

        // 刻度
        for (int i = 0; i <= 6; i++) {
            double a = Math.toRadians(startAngle + sweep * i / 6.0);
            float r1 = radius - stroke * 0.9f, r2 = radius - stroke * 1.5f;
            float x1 = cx + (float)(r1 * Math.cos(a)), y1 = cy + (float)(r1 * Math.sin(a));
            float x2 = cx + (float)(r2 * Math.cos(a)), y2 = cy + (float)(r2 * Math.sin(a));
            c.drawLine(x1, y1, x2, y2, labelPaint);
        }

        // 指针
        double ang = Math.toRadians(startAngle + sweep * pct);
        float nx = cx + (float)((radius - stroke * 1.6) * Math.cos(ang));
        float ny = cy + (float)((radius - stroke * 1.6) * Math.sin(ang));
        c.drawLine(cx, cy, nx, ny, needle);
        c.drawCircle(cx, cy, stroke * 0.35f, needle);

        // 数值
        text.setTextSize(radius * 0.34f);
        c.drawText(String.format(java.util.Locale.getDefault(), "%.2f", value), cx, cy - radius * 0.28f, text);
        labelPaint.setTextSize(radius * 0.18f);
        c.drawText(unit, cx, cy - radius * 0.02f, labelPaint);
        c.drawText(String.format(java.util.Locale.getDefault(), "%.0f", min) + " - " + String.format(java.util.Locale.getDefault(), "%.0f", max) + " V",
                cx, cy + radius * 0.62f, labelPaint);
    }
}
