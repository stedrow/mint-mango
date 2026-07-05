package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class PieChartView extends View {
    private Paint paintBg;
    private Paint paintUsed;
    private float percentage = 0f;

    // 1. Default constructor used when directly creating with 'new PieChartView' in Java code
    public PieChartView(Context context) {
        super(context);
        init();
    }

    // 🚀 2. Required constructor the system looks for when inflating this view from an XML layout! (missing this caused a warning)
    public PieChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    // 🚀 3. A thorough constructor that also applies theme style attributes
    public PieChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintBg.setStyle(Paint.Style.FILL);
        paintBg.setColor(0x33FFFFFF);

        paintUsed = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintUsed.setStyle(Paint.Style.FILL);
    }

    public void setStorageData(long used, long total, int themeColor) {
        if (total > 0) percentage = (float) used / total;
        paintUsed.setColor(themeColor);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float padding = 10f;
        RectF rect = new RectF(padding, padding, width - padding, height - padding);

        canvas.drawArc(rect, 0, 360, true, paintBg);
        float sweepAngle = percentage * 360f;
        canvas.drawArc(rect, -90, sweepAngle, true, paintUsed);
    }
}