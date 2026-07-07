package com.themoon.y1.views;

import android.content.Context;
import android.view.View;

import com.themoon.y1.ThemeManager;

public class WidgetBatteryBarView extends View {
    private android.graphics.Paint bgPaint, progressPaint, textPaint;
    private int level = 100;
    private boolean isCharging = false;
    private int baseColor = 0xFFFFFFFF;
    private float customTextSize = -1; // 🚀 [New] Custom text size variable

    // Reusable per-frame rectangles (avoid allocations in onDraw)
    private final android.graphics.RectF bgRect = new android.graphics.RectF();
    private final android.graphics.RectF progressRect = new android.graphics.RectF();
    private int highlightColor; // cached theme highlight color
    private String batteryText = "100%"; // cached formatted battery string

    public WidgetBatteryBarView(Context context) {
        super(context);
        bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(android.graphics.Paint.Style.FILL);
        progressPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(android.graphics.Paint.Style.FILL);
        textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        updateHighlightColor();
        updateBatteryText();
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        this.level = level; this.isCharging = isCharging;
        updateBatteryText();
        updateHighlightColor();
        invalidate();
    }

    public void setColor(int color) {
        this.baseColor = color;
        updateHighlightColor();
        invalidate();
    }

    // 🚀 [New] Function that lets an external caller force a specific text size
    public void setCustomTextSize(float size) {
        this.customTextSize = size;
        computeTextSize();
        invalidate();
    }

    private void updateHighlightColor() {
        try { highlightColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000; }
        catch (Exception e) { highlightColor = baseColor; }
    }

    private void updateBatteryText() {
        batteryText = isCharging ? "⚡ " + level + "%" : level + "%";
    }

    // 🚀 [Core logic] Uses the custom size if set, otherwise auto-scales proportionally to widget height as before!
    private void computeTextSize() {
        if (customTextSize > 0) textPaint.setTextSize(customTextSize);
        else textPaint.setTextSize(getHeight() * 0.6f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeTextSize();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        if (isCharging) progressPaint.setColor(0xFF44FF44);
        else if (level <= 15) progressPaint.setColor(0xFFFF4444);
        else progressPaint.setColor(highlightColor);

        bgPaint.setColor(baseColor & 0x22FFFFFF);

        float radius = height / 2f;

        bgRect.set(0, 0, width, height);
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        float progressWidth = width * (level / 100f);
        progressRect.set(0, 0, progressWidth, height);
        canvas.drawRoundRect(progressRect, radius, radius, progressPaint);

        textPaint.setColor(0xFFFFFFFF);

        float textY = bgRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);

        canvas.drawText(batteryText, bgRect.centerX(), textY, textPaint);
    }
}
