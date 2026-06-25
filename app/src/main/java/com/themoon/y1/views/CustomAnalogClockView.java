package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import com.themoon.y1.ThemeManager;

public class CustomAnalogClockView extends View {
    private Paint paint;
    private boolean isAttached;
    private int clockBgColor = 0; // 🚀 배경색을 저장할 변수 추가

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
    }

    // 🚀 배경색을 시계 내부로 전달받는 함수
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
        int radius = Math.min(cx, cy) - (int)(10 * getResources().getDisplayMetrics().density); // 패딩

        // 🚀 0. 시계 배경 채우기
        if (clockBgColor != 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(clockBgColor);
            canvas.drawCircle(cx, cy, radius, paint);
        }

        // 🚀 1. 시계 테두리 (크기에 비례하여 굵기 자동 조절!)
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.06f); // 반지름의 6% 굵기
        paint.setColor(ThemeManager.getTextColorPrimary());
        canvas.drawCircle(cx, cy, radius, paint);

        java.util.Calendar cal = java.util.Calendar.getInstance();
        float sec = cal.get(java.util.Calendar.SECOND);
        float min = cal.get(java.util.Calendar.MINUTE) + sec / 60f;
        float hr = (cal.get(java.util.Calendar.HOUR) % 12) + min / 60f;

        // 🚀 2. 시침 (반지름의 8% 굵기)
        paint.setStrokeWidth(radius * 0.08f);
        canvas.drawLine(cx, cy, cx + (float)Math.sin(Math.toRadians(hr * 30)) * radius * 0.5f, cy - (float)Math.cos(Math.toRadians(hr * 30)) * radius * 0.5f, paint);

        // 🚀 3. 분침 (반지름의 5% 굵기)
        paint.setStrokeWidth(radius * 0.05f);
        canvas.drawLine(cx, cy, cx + (float)Math.sin(Math.toRadians(min * 6)) * radius * 0.7f, cy - (float)Math.cos(Math.toRadians(min * 6)) * radius * 0.7f, paint);

        // 🚀 4. 초침 (반지름의 2% 굵기, 빨간색)
        paint.setStrokeWidth(radius * 0.02f);
        paint.setColor(android.graphics.Color.RED);
        canvas.drawLine(cx, cy, cx + (float)Math.sin(Math.toRadians(sec * 6)) * radius * 0.8f, cy - (float)Math.cos(Math.toRadians(sec * 6)) * radius * 0.8f, paint);
    }
}
