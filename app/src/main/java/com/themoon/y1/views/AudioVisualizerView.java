package com.themoon.y1.views;

import android.content.Context;
import android.view.View;

public class AudioVisualizerView extends View {
    private byte[] fftData;
    private float[] currentHeights; // 🚀 Stores the previous heights for smooth movement
    private android.graphics.Paint paint;
    private int barCount = 40; // 🚀 Increase the bar count so it spreads out wide!

    public AudioVisualizerView(Context context) {
        super(context);
        paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        currentHeights = new float[barCount];
    }

    public void updateVisualizer(byte[] fft, int color) {
        // Android's Visualizer engine keeps delivering FFT callbacks on its capture-rate timer
        // even while playback is paused, so guard here too rather than only stopping the old
        // self-perpetuating onDraw loop.
        if (!com.themoon.y1.managers.AudioPlayerManager.getInstance().isPlaying()) return;
        this.fftData = fft;
        paint.setColor(color);
        // Only redraws when new FFT data arrives (call frequency is already capped by the Visualizer's capture rate).
        postInvalidate();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float barWidth = width / (float) barCount;
        paint.setStrokeWidth(barWidth * 0.4f); // 🚀 Keep the bars thin and sleek (40%)

        if (fftData != null) {
            for (int i = 0; i < barCount && (i * 2 + 2) < fftData.length; i++) {
                byte rfk = fftData[i * 2 + 2];
                byte ifk = fftData[i * 2 + 3];
                float magnitude = (float) Math.hypot(rfk, ifk);

                // 🚀 1. Height cap: no matter how loud, the height never exceeds 85% of the view height.
                float targetHeight = Math.min(height * 0.85f, (magnitude * height) / 100f);

                // 🚀 2. Smooth interpolation: instead of jumping straight to the target, eases toward it 15% at a time.
                currentHeights[i] += (targetHeight - currentHeights[i]) * 0.15f;
            }
        }

        // Draw
        for (int i = 0; i < barCount; i++) {
            float x = i * barWidth + (barWidth / 2f);
            canvas.drawLine(x, height, x, height - currentHeights[i], paint);
        }

    }
}

