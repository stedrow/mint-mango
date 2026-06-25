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

    public CircularBatteryView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;

        // 배경 회색 트랙
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(8 * density);
        trackPaint.setColor(ThemeManager.getTextColorSecondary()); // 테마 보조 색상 적용
        trackPaint.setAlpha(60);

        // 게이지 바
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(8 * density);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(ThemeManager.getTextColorPrimary()); // 테마 메인 색상 적용

        // 중앙 숫자 텍스트
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(ThemeManager.getTextColorPrimary());
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD));
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        this.level = level;
        this.isCharging = isCharging;
        invalidate();
    }
    public void setCustomTextSize(float size) { textPaint.setTextSize(size); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();

        // 🚀 테마 크기에 비례하여 원형 선 굵기 자동 조절! (전체 너비의 8%)
        float stroke = Math.min(w, h) * 0.08f;
        trackPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);

        float halfStroke = stroke / 2f;
        rectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

        // 🚀 스마트 컬러 로직: 충전 중이면 초록색, 15% 이하면 빨간색!
        if (isCharging) {
            progressPaint.setColor(0xFF44FF44);
        } else if (level <= 15) {
            progressPaint.setColor(0xFFFF4444);
        } else {
            progressPaint.setColor(ThemeManager.getTextColorPrimary());
        }

        // 배경 원 그리기
        canvas.drawArc(rectF, 0, 360, false, trackPaint);
        // 잔량만큼 호(Arc) 그리기
        canvas.drawArc(rectF, -90, 360f * level / 100f, false, progressPaint);

        // 중앙에 텍스트 배치
        float textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText(String.valueOf(level), w / 2f, textY, textPaint);
    }
}

