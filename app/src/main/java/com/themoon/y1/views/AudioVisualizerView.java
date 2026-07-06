package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

// Log-scale spectrum line styled after audioMotion-analyzer's default preset
// (as used by Feishin): mode 10 (line/area graph), frequencyScale log,
// gradient 'prism', fillAlpha 0 (line only), reflection, peak-hold line.
public class AudioVisualizerView extends View {
    private static final int POINTS = 80;
    private static final float MIN_FREQ = 20f;
    private static final float MAX_FREQ = 22050f;
    private static final float MIN_DB = -10f;
    private static final float MAX_DB = 50f;
    private static final float ATTACK = 0.5f;   // fraction of gap closed per frame when rising
    private static final float GRAVITY = 0.06f; // fraction of view height fallen per frame
    private static final float PEAK_GRAVITY = 0.02f;
    private static final int PEAK_HOLD_FRAMES = 20; // ~= peakHoldTime at typical capture rate

    // audioMotion-analyzer 'prism' gradient color stops, evenly spaced bottom-to-top.
    private static final int[] PRISM = {
            0xFFAA3355, 0xFFCC6666, 0xFFEE9944, 0xFFEEDD00, 0xFF99DD55,
            0xFF44DD88, 0xFF22CCBB, 0xFF00BBCC, 0xFF0099CC, 0xFF3366BB
    };

    private byte[] fftData;
    private int sampleRateHz = 44100;

    private final float[] levels = new float[POINTS];
    private final float[] peaks = new float[POINTS];
    private final int[] peakHold = new int[POINTS];

    private final Paint linePaint;
    private final Paint reflexPaint;
    private final Paint peakPaint;
    private final Path linePath = new Path();
    private final Path reflexPath = new Path();
    private final Path peakPath = new Path();

    private LinearGradient cachedGradient;
    private int cachedGradientHeight = -1;

    public AudioVisualizerView(Context context) {
        super(context);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        reflexPaint = new Paint(linePaint);
        reflexPaint.setAlpha(40); // audioMotion reflexAlpha ~0.1

        peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        peakPaint.setStyle(Paint.Style.STROKE);
        peakPaint.setStrokeWidth(2.5f);
        peakPaint.setColor(0x99FFFFFF);
    }

    // fft: raw FFT bytes from android.media.audiofx.Visualizer (re/im pairs, DC packed in fft[0]).
    // samplingRateMilliHz: sampling rate reported by the Visualizer callback, in milliHertz.
    public void updateVisualizer(byte[] fft, int samplingRateMilliHz) {
        if (!com.themoon.y1.managers.AudioPlayerManager.getInstance().isPlaying()) return;
        this.fftData = fft;
        this.sampleRateHz = samplingRateMilliHz / 1000;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        computeLevels(width, height);

        LinearGradient gradient = getGradient(height);
        linePaint.setShader(gradient);
        reflexPaint.setShader(gradient);

        buildPath(linePath, levels, width, height, false);
        buildPath(reflexPath, levels, width, height, true);
        buildPath(peakPath, peaks, width, height, false);

        canvas.drawPath(reflexPath, reflexPaint);
        canvas.drawPath(linePath, linePaint);
        canvas.drawPath(peakPath, peakPaint);
    }

    private LinearGradient getGradient(int height) {
        if (cachedGradient == null || cachedGradientHeight != height) {
            float[] positions = new float[PRISM.length];
            for (int i = 0; i < PRISM.length; i++) positions[i] = i / (float) (PRISM.length - 1);
            cachedGradient = new LinearGradient(0, height, 0, 0, PRISM, positions, Shader.TileMode.CLAMP);
            cachedGradientHeight = height;
        }
        return cachedGradient;
    }

    private void computeLevels(int width, int height) {
        int numBins = fftData != null ? fftData.length / 2 : 0;
        float maxFreq = Math.min(MAX_FREQ, sampleRateHz / 2f);
        float binWidth = numBins > 1 ? sampleRateHz / (float) (numBins * 2) : 1f;

        for (int p = 0; p < POINTS; p++) {
            float target = 0f;

            if (numBins > 1) {
                float freqLo = (float) (MIN_FREQ * Math.pow(maxFreq / MIN_FREQ, p / (float) POINTS));
                float freqHi = (float) (MIN_FREQ * Math.pow(maxFreq / MIN_FREQ, (p + 1) / (float) POINTS));
                int binLo = Math.max(1, Math.round(freqLo / binWidth));
                int binHi = Math.max(binLo + 1, Math.round(freqHi / binWidth));
                binHi = Math.min(binHi, numBins - 1);

                if (binLo < numBins - 1) {
                    float sum = 0f;
                    int count = 0;
                    for (int b = binLo; b < binHi; b++) {
                        float re = fftData[b * 2];
                        float im = fftData[b * 2 + 1];
                        sum += (float) Math.hypot(re, im);
                        count++;
                    }
                    float magnitude = count > 0 ? sum / count : 0f;
                    float db = (float) (20 * Math.log10(magnitude + 1));
                    target = (db - MIN_DB) / (MAX_DB - MIN_DB);
                    target = Math.max(0f, Math.min(1f, target));
                }
            }

            float targetHeight = target * height;
            if (targetHeight > levels[p]) {
                levels[p] += (targetHeight - levels[p]) * ATTACK;
            } else {
                levels[p] = Math.max(targetHeight, levels[p] - height * GRAVITY);
            }

            if (levels[p] >= peaks[p]) {
                peaks[p] = levels[p];
                peakHold[p] = PEAK_HOLD_FRAMES;
            } else if (peakHold[p] > 0) {
                peakHold[p]--;
            } else {
                peaks[p] = Math.max(levels[p], peaks[p] - height * PEAK_GRAVITY);
            }
        }
    }

    private void buildPath(Path path, float[] values, int width, int height, boolean mirrored) {
        path.reset();
        float stepX = width / (float) (POINTS - 1);
        float prevX = 0, prevY = mirrored ? height + values[0] * 0.4f : height - values[0];
        path.moveTo(prevX, prevY);
        for (int p = 1; p < POINTS; p++) {
            float x = p * stepX;
            float y = mirrored ? height + values[p] * 0.4f : height - values[p];
            float midX = (prevX + x) / 2f;
            float midY = (prevY + y) / 2f;
            path.quadTo(prevX, prevY, midX, midY);
            prevX = x;
            prevY = y;
        }
        path.lineTo(prevX, prevY);
    }
}
