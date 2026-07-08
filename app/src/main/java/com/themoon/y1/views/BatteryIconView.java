package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

public class BatteryIconView extends View {
    private int level = 100;
    private boolean isCharging = false;
    private int color = Color.WHITE;
    private boolean showPercent = false;

    private Paint paintFill, paintStroke, paintText;
    private RectF rectShell, rectFill, rectTerminal;
    private Rect textBounds = new Rect();

    public BatteryIconView(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Paint for the inner fill
        paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Paint for the outer shell (border)
        paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeWidth(3f); // border thickness

        // Paint for the percentage number
        paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintText.setTextAlign(Paint.Align.CENTER);
        paintText.setFakeBoldText(true);

        rectShell = new RectF();
        rectFill = new RectF();
        rectTerminal = new RectF();
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        this.level = level;
        this.isCharging = isCharging;
        invalidate(); // redraw immediately whenever the value changes!
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    public void setShowPercent(boolean showPercent) {
        this.showPercent = showPercent;
        invalidate();
    }

    public void setTypeface(android.graphics.Typeface typeface) {
        paintText.setTypeface(typeface);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float terminalWidth = w * 0.08f; // length of the terminal bump on the right
        float shellWidth = w - terminalWidth;

        // 🚀 1. Battery icon is always green, regardless of theme color or charge state.
        int currentColor = 0xFF4CAF50;

        paintStroke.setColor(currentColor);
        paintFill.setColor(currentColor);

        // 🚀 2. Draw the battery's outer shell
        rectShell.set(2f, 2f, shellWidth - 2f, h - 2f);
        canvas.drawRoundRect(rectShell, 5f, 5f, paintStroke);

        // 🚀 3. Draw the (+) terminal on the right
        float terminalHeight = h * 0.4f;
        rectTerminal.set(shellWidth, (h - terminalHeight) / 2f, w - 2f, (h + terminalHeight) / 2f);
        canvas.drawRoundRect(rectTerminal, 2f, 2f, paintFill);

        // 🚀 4. Fill the inner area proportionally to the remaining capacity!
        float padding = 2f; // flush with the border stroke, no black gap ring
        float maxFillWidth = shellWidth - (padding * 2f); // width at 100%
        float currentFillWidth = maxFillWidth * (level / 100f); // trim the width to the current percentage

        // Only draw the fill when the remaining level is greater than 0.
        if (currentFillWidth > 0) {
            rectFill.set(padding, padding, padding + currentFillWidth, h - padding);
            canvas.drawRoundRect(rectFill, 2f, 2f, paintFill);
        }

        // Percentage number centered in the shell, solid white.
        if (showPercent) {
            String text = String.valueOf(level);
            paintText.setTextSize(h * 0.6f);
            paintText.setColor(Color.WHITE);
            paintText.getTextBounds(text, 0, text.length(), textBounds);
            float cx = shellWidth / 2f;
            float cy = h / 2f - textBounds.exactCenterY();
            canvas.drawText(text, cx, cy, paintText);
        }
    }
}