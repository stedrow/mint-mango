package com.themoon.y1.views;

import android.content.Context;
import android.view.View;

public class AudioVisualizerView extends View {
    private byte[] fftData;
    private float[] currentHeights; // 🚀 부드러운 움직임을 위한 이전 높이 기억 장치
    private android.graphics.Paint paint;
    private int barCount = 40; // 🚀 막대기 개수를 늘려서 옆으로 쫙 퍼지게!

    public AudioVisualizerView(Context context) {
        super(context);
        paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        currentHeights = new float[barCount];
    }

    public void updateVisualizer(byte[] fft, int color) {
        this.fftData = fft;
        paint.setColor(color);
        // invalidate() 대신 onDraw 내부에서 무한 루프를 돌려 60fps를 방어합니다!
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float barWidth = width / (float) barCount;
        paint.setStrokeWidth(barWidth * 0.4f); // 🚀 막대기 두께를 얇고 세련되게 (40%)

        if (fftData != null) {
            for (int i = 0; i < barCount && (i * 2 + 2) < fftData.length; i++) {
                byte rfk = fftData[i * 2 + 2];
                byte ifk = fftData[i * 2 + 3];
                float magnitude = (float) Math.hypot(rfk, ifk);

                // 🚀 1. 높이 제한: 아무리 소리가 커도 화면 높이의 85%를 넘지 못하게 캡을 씌웁니다.
                float targetHeight = Math.min(height * 0.85f, (magnitude * height) / 100f);

                // 🚀 2. 부드러운 보간: 목표 지점까지 한 번에 점프하지 않고 15%씩 스무스하게 따라갑니다.
                currentHeights[i] += (targetHeight - currentHeights[i]) * 0.15f;
            }
        }

        // 그려내기
        for (int i = 0; i < barCount; i++) {
            float x = i * barWidth + (barWidth / 2f);
            canvas.drawLine(x, height, x, height - currentHeights[i], paint);
        }

        // 🚀 3. 화면에 보일 때는 초당 60번(16ms) 강제 새로고침하여 버벅임을 없앱니다.
        if (getVisibility() == View.VISIBLE) {
            postInvalidateDelayed(16);
        }
    }
}

