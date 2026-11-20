package com.example.bmsdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** 单串电池图标：嘉佰达 App 风格的竖直电池柱，内部按电压填充，上标序号、下标电压 */
public class CellBatteryView extends View {
    private Paint shell, fill, cap, text, markStroke;
    private int index = 1;
    private int mv = 0;
    private int mark = 0; // 1=最高电压 2=最低电压

    public CellBatteryView(Context c) { super(c); init(); }
    public CellBatteryView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        shell = new Paint(Paint.ANTI_ALIAS_FLAG);
        shell.setColor(Color.rgb(235, 241, 247));
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        cap = new Paint(Paint.ANTI_ALIAS_FLAG);
        cap.setColor(Color.rgb(170, 186, 202));
        text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextAlign(Paint.Align.CENTER);
        markStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        markStroke.setStyle(Paint.Style.STROKE);
        markStroke.setStrokeWidth(Math.max(1.5f, getResources().getDisplayMetrics().density * 1.5f));
    }

    /** mark: 0=无 1=最高电压 2=最低电压 */
    public void setCell(int index, int mv, int mark) {
        this.index = index;
        this.mv = mv;
        this.mark = mark;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float labelY = h * 0.17f;
        float bodyTop = h * 0.23f;
        float bodyBottom = h * 0.76f;
        float voltY = h * 0.96f;
        float inset = w * 0.17f;
        float radius = w * 0.11f;

        // 序号（最高 ▲ / 最低 ▼）
        String label = String.valueOf(index);
        if (mark == 1) { label = "▲" + index; text.setColor(Color.rgb(25, 118, 210)); }
        else if (mark == 2) { label = "▼" + index; text.setColor(Color.rgb(198, 40, 40)); }
        else text.setColor(Color.rgb(105, 120, 135));
        text.setTextSize(Math.max(8f, h * (mark == 0 ? 0.14f : 0.13f)));
        c.drawText(label, w / 2f, labelY, text);

        // 电池正极头
        float capW = w * 0.26f;
        c.drawRect(w / 2f - capW / 2, bodyTop - h * 0.045f,
                w / 2f + capW / 2, bodyTop + h * 0.01f, cap);

        // 电池外壳
        RectF shellRect = new RectF(inset, bodyTop, w - inset, bodyBottom);
        c.drawRoundRect(shellRect, radius, radius, shell);
        if (mark != 0) {
            markStroke.setColor(mark == 1 ? Color.rgb(25, 118, 210) : Color.rgb(198, 40, 40));
            c.drawRoundRect(shellRect, radius, radius, markStroke);
        }

        // 按电压填充：2.50V~3.65V 映射到 12%~100%
        if (mv > 0) {
            int color;
            if (mv < 3000) color = Color.rgb(244, 67, 54);
            else if (mv < 3200) color = Color.rgb(255, 167, 38);
            else color = Color.rgb(76, 175, 80);
            fill.setColor(color);
            float ratio = Math.max(0.12f, Math.min(1f, (mv - 2500f) / 1150f));
            float top = bodyBottom - (bodyBottom - bodyTop) * ratio;
            c.drawRoundRect(new RectF(inset, top, w - inset, bodyBottom), radius, radius, fill);
        }

        // 电压
        text.setColor(mv > 0 ? Color.rgb(45, 55, 65) : Color.rgb(150, 160, 170));
        text.setTextSize(Math.max(8f, h * 0.115f));
        c.drawText(mv > 0 ? String.format(Locale.getDefault(), "%.3f", mv / 1000.0) : "--",
                w / 2f, voltY, text);
    }
}
