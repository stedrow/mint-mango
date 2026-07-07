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
    private final RectF rect = new RectF(); // reusable arc bounds (avoid per-frame allocation)

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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = 10f;
        rect.set(padding, padding, w - padding, h - padding);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawArc(rect, 0, 360, true, paintBg);
        float sweepAngle = percentage * 360f;
        canvas.drawArc(rect, -90, sweepAngle, true, paintUsed);
    }
}