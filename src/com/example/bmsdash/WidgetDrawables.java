package com.example.bmsdash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Locale;

/** 桌面小部件用：把电压表 / 电量环绘制成 Bitmap（嘉佰达蓝底白字风格） */
public class WidgetDrawables {

    private static final int WHITE = Color.WHITE;
    private static final int WHITE_70 = Color.argb(180, 255, 255, 255);
    private static final int WHITE_35 = Color.argb(90, 255, 255, 255);

    public static Bitmap gauge(double voltage, boolean hasData) {
        int size = 220;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float cx = size / 2f, cy = size * 0.60f, r = size * 0.40f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(12);
        p.setColor(WHITE_35);
        RectF rect = new RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, 180, 180, false, p);

        float ratio = 0;
        if (hasData) {
            ratio = (float) ((voltage - 60) / 30.0);
            if (ratio < 0) ratio = 0;
            if (ratio > 1) ratio = 1;
            p.setStrokeWidth(12);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(WHITE);
            c.drawArc(rect, 180, 180 * ratio, false, p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(WHITE);
        p.setTextSize(40);
        c.drawText(hasData ? String.format(Locale.getDefault(), "%.2f", voltage) : "--", cx, cy - 16, p);
        p.setTextSize(16);
        p.setColor(WHITE_70);
        c.drawText("V", cx, cy + 8, p);
        p.setTextSize(14);
        c.drawText("总电压", cx, size - 10, p);
        return bmp;
    }

    public static Bitmap arc(int soc, boolean hasData) {
        int size = 220;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float cx = size / 2f, cy = size / 2f, r = size * 0.38f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(14);
        p.setColor(WHITE_35);
        RectF rect = new RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, 135, 270, false, p);

        if (hasData && soc >= 0) {
            float ratio = Math.max(0, Math.min(100, soc)) / 100f;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(WHITE);
            c.drawArc(rect, 135, 270 * ratio, false, p);
        }

        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(WHITE);
        p.setTextSize(46);
        c.drawText(hasData && soc >= 0 ? soc + "%" : "--", cx, cy + 10, p);
        p.setTextSize(15);
        p.setColor(WHITE_70);
        c.drawText("剩余电量", cx, cy - 34, p);
        return bmp;
    }
}
