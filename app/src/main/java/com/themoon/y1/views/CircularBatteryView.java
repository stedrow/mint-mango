package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.themoon.y1.ThemeManager;

public class CircularBatteryView extends View {
    private Paint trackPaint, progressPaint, textPaint;
    private int level = 100;
    private boolean isCharging = false;
    private RectF rectF = new RectF();
    private String levelStr = "100"; // cached level text (avoid per-frame String.valueOf)

    public CircularBatteryView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;

        // Background gray track
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(8 * density);
        trackPaint.setColor(ThemeManager.getTextColorSecondary()); // Applies the theme's secondary color
        trackPaint.setAlpha(60);

        // Gauge bar
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(8 * density);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(ThemeManager.getTextColorPrimary()); // Applies the theme's main color

        // Centered numeric text
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(ThemeManager.getTextColorPrimary());
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD));
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        this.level = level;
        this.isCharging = isCharging;
        this.levelStr = String.valueOf(level);
        invalidate();
    }
    public void setCustomTextSize(float size) { textPaint.setTextSize(size); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 🚀 Auto-adjusts the ring's stroke width proportionally to the view size! (8% of total width)
        float stroke = Math.min(w, h) * 0.08f;
        trackPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);

        float halfStroke = stroke / 2f;
        rectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();

        // 🚀 Smart color logic: green while charging, red at 15% or below!
        if (isCharging) {
            progressPaint.setColor(0xFF44FF44);
        } else if (level <= 15) {
            progressPaint.setColor(0xFFFF4444);
        } else {
            progressPaint.setColor(ThemeManager.getTextColorPrimary());
        }

        // Draw the background circle
        canvas.drawArc(rectF, 0, 360, false, trackPaint);
        // Draw the arc proportional to the remaining level
        canvas.drawArc(rectF, -90, 360f * level / 100f, false, progressPaint);

        // Place the text in the center
        float textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText(levelStr, w / 2f, textY, textPaint);
    }
}

