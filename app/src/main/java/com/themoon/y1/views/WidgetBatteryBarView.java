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

    public WidgetBatteryBarView(Context context) {
        super(context);
        bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(android.graphics.Paint.Style.FILL);
        progressPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(android.graphics.Paint.Style.FILL);
        textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        this.level = level; this.isCharging = isCharging; invalidate();
    }

    public void setColor(int color) {
        this.baseColor = color; invalidate();
    }

    // 🚀 [New] Function that lets an external caller force a specific text size
    public void setCustomTextSize(float size) {
        this.customTextSize = size; invalidate();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        int highlightColor;
        try { highlightColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000; }
        catch (Exception e) { highlightColor = baseColor; }

        if (isCharging) progressPaint.setColor(0xFF44FF44);
        else if (level <= 15) progressPaint.setColor(0xFFFF4444);
        else progressPaint.setColor(highlightColor);

        bgPaint.setColor(baseColor & 0x22FFFFFF);

        float radius = height / 2f;

        android.graphics.RectF bgRect = new android.graphics.RectF(0, 0, width, height);
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        float progressWidth = width * (level / 100f);
        android.graphics.RectF progressRect = new android.graphics.RectF(0, 0, progressWidth, height);
        canvas.drawRoundRect(progressRect, radius, radius, progressPaint);

        textPaint.setColor(0xFFFFFFFF);
        // 🚀 [Core logic] Uses the custom size if set, otherwise auto-scales proportionally to widget height as before!
        if (customTextSize > 0) textPaint.setTextSize(customTextSize);
        else textPaint.setTextSize(height * 0.6f);

        float textY = bgRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);

        String text = isCharging ? "⚡ " + level + "%" : level + "%";
        canvas.drawText(text, bgRect.centerX(), textY, textPaint);
    }
}

