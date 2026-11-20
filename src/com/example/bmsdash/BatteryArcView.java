package com.example.bmsdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 剩余电量环：嘉佰达 App 风格，蓝色卡片上的白色大环 + 中央百分比 */
public class BatteryArcView extends View {
    private Paint bg, fg, num, sub;
    private int soc = -1;
    private static final int START = 140, SWEEP = 260; // 开口向下的弧（顶部闭合）

    public BatteryArcView(Context c) { super(c); init(); }
    public BatteryArcView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setStyle(Paint.Style.STROKE);
        bg.setColor(Color.argb(90, 255, 255, 255));
        fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        fg.setStyle(Paint.Style.STROKE);
        fg.setStrokeCap(Paint.Cap.ROUND);
        fg.setColor(Color.WHITE);
        num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setColor(Color.WHITE);
        num.setTextAlign(Paint.Align.CENTER);
        num.setFakeBoldText(true);
        sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(Color.argb(220, 255, 255, 255));
        sub.setTextAlign(Paint.Align.CENTER);
    }

    public void setSoc(int v) { this.soc = v; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - 18;
        float stroke = Math.max(9f, radius * 0.16f);
        bg.setStrokeWidth(stroke);
        fg.setStrokeWidth(stroke);

        RectF rect = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        c.drawArc(rect, START, SWEEP, false, bg);

        if (soc >= 0) {
            int s = Math.max(0, Math.min(100, soc));
            c.drawArc(rect, START, SWEEP * s / 100f, false, fg);
        }

        String v = soc >= 0 ? soc + "" : "--";
        num.setTextSize(radius * 0.62f);
        c.drawText(v, cx - radius * 0.06f, cy + radius * 0.10f, num);
        sub.setTextSize(radius * 0.22f);
        c.drawText("%", cx + radius * 0.44f, cy - radius * 0.24f, sub);
        c.drawText("剩余电量", cx, cy + radius * 0.46f, sub);
    }
}
