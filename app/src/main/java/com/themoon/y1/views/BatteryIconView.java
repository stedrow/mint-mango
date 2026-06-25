package com.themoon.y1.views;

import android.content.Context;
import android.view.View;

public class BatteryIconView extends View {
    private android.graphics.Paint shellPaint, textPaint;
    private int level = 100;
    private boolean isCharging = false;
    private int color = 0xFFFFFFFF; // 기본 바탕색 (보통 흰색)

    public BatteryIconView(Context context) {
        super(context);

        // 배터리 바탕을 그리는 붓 (속을 꽉 채우기)
        shellPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        shellPaint.setStyle(android.graphics.Paint.Style.FILL);

        // 숫자를 그리는 붓 (검은색, 가운데 정렬, 굵게)
        textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFF000000); // 🚀 검은색 글씨!
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    public void setBatteryLevel(int level, boolean isCharging) {
        this.level = level;
        this.isCharging = isCharging;
        invalidate(); // 화면 새로고침
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        float pad = 2f;
        float terminalWidth = width * 0.08f;
        float shellWidth = width - terminalWidth - pad * 2;
        float shellHeight = height - pad * 2;

        // 🚀 스마트 컬러: 충전 중이면 초록색 바탕, 15% 이하면 빨간색 바탕, 평소엔 테마색(보통 흰색)
        if (isCharging) {
            shellPaint.setColor(0xFF44FF44);
        } else if (level <= 15) {
            shellPaint.setColor(0xFFFF4444);
        } else {
            shellPaint.setColor(color);
        }

        // 1. 꽉 찬 배터리 몸통 그리기
        android.graphics.RectF shell = new android.graphics.RectF(pad, pad, pad + shellWidth, pad + shellHeight);
        canvas.drawRoundRect(shell, 4f, 4f, shellPaint);

        // 2. 배터리 오른쪽 튀어나온 꼭지 그리기
        float terminalHeight = shellHeight * 0.4f;
        float terminalTop = pad + (shellHeight - terminalHeight) / 2;
        android.graphics.RectF terminal = new android.graphics.RectF(shell.right, terminalTop,
                shell.right + terminalWidth, terminalTop + terminalHeight);
        canvas.drawRoundRect(terminal, 2f, 2f, shellPaint);

        // 3. 배터리 몸통 정중앙에 숫자(잔량) 새기기
        textPaint.setTextSize(shellHeight * 0.95f); // 텍스트 크기를 배터리 높이에 꽉 차게 조절

        // 텍스트를 위아래 정중앙에 오도록 계산하는 공식
        float textY = shell.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);
        String levelText = String.valueOf(level);

        // 검은색 숫자를 배터리 몸통 한가운데에 찍어냅니다.
        canvas.drawText(levelText, shell.centerX(), textY, textPaint);
    }
}

