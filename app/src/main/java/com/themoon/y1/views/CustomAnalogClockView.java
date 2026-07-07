package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import com.themoon.y1.ThemeManager;

public class CustomAnalogClockView extends View {
    private Paint paint;
    private boolean isAttached;
    private int clockBgColor = 0; // 🚀 Added a variable to store the background color
    private final float density; // cached display density (avoid per-frame lookup)
    private final java.util.Calendar cal = java.util.Calendar.getInstance(); // reused each frame

    private final Runnable ticker = new Runnable() {
        public void run() {
            invalidate();
            if (isAttached) postDelayed(this, 1000);
        }
    };

    public CustomAnalogClockView(Context context) {
        super(context);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        paint.setStrokeCap(Paint.Cap.ROUND);
        density = getResources().getDisplayMetrics().density;
    }

    // 🚀 Function that receives the background color into the clock
    public void setClockBackgroundColor(int color) {
        this.clockBgColor = color;
        invalidate();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); isAttached = true; ticker.run(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); isAttached = false; removeCallbacks(ticker); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int cx = w / 2, cy = h / 2;
        int radius = Math.min(cx, cy) - (int)(10 * density); // padding

        // 🚀 0. Fill the clock background
        if (clockBgColor != 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(clockBgColor);
            canvas.drawCircle(cx, cy, radius, paint);
        }

        // 🚀 1. Clock border (stroke width auto-scales proportionally to size!)
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.06f); // 6% of the radius
        paint.setColor(ThemeManager.getTextColorPrimary());
        canvas.drawCircle(cx, cy, radius, paint);

        cal.setTimeInMillis(System.currentTimeMillis());
        float sec = cal.get(java.util.Calendar.SECOND);
        float min = cal.get(java.util.Calendar.MINUTE) + sec / 60f;
        float hr = (cal.get(java.util.Calendar.HOUR) % 12) + min / 60f;

        // 🚀 2. Hour hand (8% of the radius)
        paint.setStrokeWidth(radius * 0.08f);
        canvas.drawLine(cx, cy, cx + (float)Math.sin(Math.toRadians(hr * 30)) * radius * 0.5f, cy - (float)Math.cos(Math.toRadians(hr * 30)) * radius * 0.5f, paint);

        // 🚀 3. Minute hand (5% of the radius)
        paint.setStrokeWidth(radius * 0.05f);
        canvas.drawLine(cx, cy, cx + (float)Math.sin(Math.toRadians(min * 6)) * radius * 0.7f, cy - (float)Math.cos(Math.toRadians(min * 6)) * radius * 0.7f, paint);

        // 🚀 4. Second hand (2% of the radius, red)
        paint.setStrokeWidth(radius * 0.02f);
        paint.setColor(android.graphics.Color.RED);
        canvas.drawLine(cx, cy, cx + (float)Math.sin(Math.toRadians(sec * 6)) * radius * 0.8f, cy - (float)Math.cos(Math.toRadians(sec * 6)) * radius * 0.8f, paint);
    }
}
