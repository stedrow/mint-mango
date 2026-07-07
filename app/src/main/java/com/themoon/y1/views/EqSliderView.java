package com.themoon.y1.views;

import com.themoon.y1.ThemeManager;

public class EqSliderView extends android.view.View {
    private android.graphics.Paint trackPaint, activeTrackPaint, thumbPaint, textPaint;
    private int min = -1500, max = 1500, level = 0;
    private boolean isFocused = false, isAdjusting = false;
    private int themeColor = 0xFF00FFFF;
    private String dbStr = "0"; // cached +dB display string

    public EqSliderView(android.content.Context context) {
        super(context);
        try { themeColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000; } catch(Exception e){}

        trackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(android.graphics.Paint.Style.FILL);
        trackPaint.setColor(0xFF555555);
        trackPaint.setStrokeWidth(6f);

        activeTrackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        activeTrackPaint.setStyle(android.graphics.Paint.Style.FILL);
        activeTrackPaint.setStrokeWidth(8f);

        thumbPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(android.graphics.Paint.Style.FILL);

        textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(22f);
    }

    public void setRange(int min, int max) { this.min = min; this.max = max; invalidate(); }
    public void setLevel(int level) { this.level = level; updateDbStr(); invalidate(); }

    private void updateDbStr() {
        dbStr = (level > 0 ? "+" : "") + (level / 100);
    }
    public void setFocused(boolean focused) { this.isFocused = focused; invalidate(); }
    public void setAdjusting(boolean adjusting) { this.isAdjusting = adjusting; invalidate(); }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float padY = 40f;
        float trackX = w / 2f;
        float trackHeight = h - (padY * 2);
        float trackTop = padY, trackBottom = h - padY;

        // Background track (gray line)
        canvas.drawLine(trackX, trackTop, trackX, trackBottom, trackPaint);
        // 0dB tick mark at dead center
        canvas.drawLine(trackX - 10f, trackTop + trackHeight/2f, trackX + 10f, trackTop + trackHeight/2f, trackPaint);

        // Calculate the position ratio for the current decibel level
        float ratio = (float) (level - min) / (max - min);
        float thumbY = trackBottom - (ratio * trackHeight);

        // Orange while adjusting, theme color while focused, light gray otherwise
        activeTrackPaint.setColor(isAdjusting ? 0xFFFF8800 : (isFocused ? themeColor : 0xFFAAAAAA));
        canvas.drawLine(trackX, trackTop + trackHeight/2f, trackX, thumbY, activeTrackPaint);

        // Round thumb handle
        thumbPaint.setColor(isAdjusting ? 0xFFFF8800 : (isFocused ? themeColor : 0xFFDDDDDD));
        canvas.drawCircle(trackX, thumbY, 15f, thumbPaint);

        // +dB text floating just above the thumb
        textPaint.setColor(0xFFFFFFFF);
        canvas.drawText(dbStr, trackX, thumbY - 25f, textPaint);
    }
}

