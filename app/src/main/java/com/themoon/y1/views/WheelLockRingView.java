package com.themoon.y1.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.animation.DecelerateInterpolator;
import android.view.View;

/**
 * Segmented "donut" progress ring for the wheel-unlock gesture. Draws a
 * continuous animated arc (smooth fill) with thin radial dividers cut on top
 * so it reads visually as N discrete wedges filling one by one.
 */
public class WheelLockRingView extends View {

    private final Paint trackPaint;
    private final Paint progressPaint;
    private final Paint dividerPaint;
    private final RectF rectF = new RectF();

    private int total = 8;
    private float displayedProgress = 0f; // animated, fractional
    private ValueAnimator animator;

    public WheelLockRingView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(5 * density);
        trackPaint.setColor(0xFFFFFFFF);
        trackPaint.setAlpha(45);

        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(5 * density);
        progressPaint.setStrokeCap(Paint.Cap.BUTT);
        progressPaint.setColor(0xFF6FE3B4); // 은은한 민트 그린 — 이 오버레이는 항상 검정 배경이라 테마색과 무관하게 고정

        dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(2.5f * density);
        dividerPaint.setColor(0xDD000000); // 오버레이 배경색과 동일 — 링을 조각처럼 "잘라내는" 효과
    }

    public void setSegments(int total) {
        this.total = Math.max(1, total);
        invalidate();
    }

    /** Animates smoothly from whatever's currently displayed to {@code progress}. */
    public void setProgress(int progress) {
        float target = Math.max(0, Math.min(total, progress));
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(displayedProgress, target);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            displayedProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /** Snaps instantly with no animation (used when arming the lock fresh). */
    public void resetProgress() {
        if (animator != null) animator.cancel();
        displayedProgress = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float stroke = Math.min(w, h) * 0.045f;
        trackPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);

        float halfStroke = stroke / 2f;
        rectF.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

        canvas.drawArc(rectF, -90, 360, false, trackPaint);
        canvas.drawArc(rectF, -90, 360f * displayedProgress / total, false, progressPaint);

        // 세그먼트 경계마다 배경색 방사형 선을 그어 조각(웨지)처럼 보이게 자릅니다.
        float cx = w / 2f, cy = h / 2f;
        float outer = Math.min(w, h) / 2f;
        float inner = outer - stroke;
        for (int i = 0; i < total; i++) {
            double angle = Math.toRadians(-90 + 360.0 * i / total);
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            canvas.drawLine(cx + inner * cos, cy + inner * sin, cx + outer * cos, cy + outer * sin, dividerPaint);
        }
    }
}
