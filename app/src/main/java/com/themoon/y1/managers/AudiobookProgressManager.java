package com.themoon.y1.managers;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;

/**
 * Renders an audiobook chapter list row's inline progress bar (a clipped translucent overlay on
 * the button background) and keeps it redrawn with the right focus-state color whenever the row
 * gains/loses focus. Extracted verbatim from MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- pure View/theme rendering with no state of its own -- so it takes
 * the MainActivity instance as a parameter. MainActivity keeps a thin pass-through method for
 * setupAudiobookProgress() since MusicBrowserManager and SongListAdapter call it by name.
 * applyProgressBackground() had no external callers and stayed private to the manager.
 */
public class AudiobookProgressManager {
    private static AudiobookProgressManager instance;

    private AudiobookProgressManager() {}

    public static synchronized AudiobookProgressManager getInstance() {
        if (instance == null) {
            instance = new AudiobookProgressManager();
        }
        return instance;
    }

    public void setupAudiobookProgress(final MainActivity a, final android.widget.Button btn, final int pos, final int dur) {
        // Draw when it first appears on screen
        applyProgressBackground(a, btn, pos, dur, btn.hasFocus());

        // 💡 Overwrites the button's original plain solid-color focus listener with a 'progress-dedicated listener'!
        btn.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(android.view.View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    // Redraw the progress with the on-focus color
                    applyProgressBackground(a, btn, pos, dur, true);
                } else {
                    btn.setTextColor(ThemeManager.getTextColorPrimary());
                    // Redraw the progress with the normal off-focus color (to prevent it from disappearing!)
                    applyProgressBackground(a, btn, pos, dur, false);
                }
            }
        });
    }

    // 🚀 [New 2] Progress-rendering function that smartly adjusts color based on focus state (isFocused)
    private void applyProgressBackground(MainActivity a, android.widget.Button btn, int currentMs, int totalMs, boolean isFocused) {
        if (currentMs <= 0 || totalMs <= 0) return;

        int progressPercent = (int) (((float) currentMs / totalMs) * 10000);
        if (progressPercent > 10000) progressPercent = 10000;

        int baseColor = isFocused ? ThemeManager.getListButtonFocusedBg() : ThemeManager.getListButtonNormalBg();
        android.graphics.drawable.Drawable baseBg = a.createButtonBackground(baseColor);

        int progressColor;
        if (isFocused) {
            progressColor = 0x66FFFFFF; // When the wheel lands on it: an eye-catching translucent white
        } else {
            progressColor = (ThemeManager.getListButtonFocusedBg() & 0x00FFFFFF) | 0x44000000; // Normally: a translucent theme color
        }
        android.graphics.drawable.Drawable progressBg = a.createButtonBackground(progressColor);

        android.graphics.drawable.ClipDrawable clipProgress = new android.graphics.drawable.ClipDrawable(progressBg, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        clipProgress.setLevel(progressPercent);

        android.graphics.drawable.LayerDrawable layerBg = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{baseBg, clipProgress});

        // 🚀 [Margin-vanish bug fully blocked!] Safely remember the existing padding before changing the background.
        int pLeft = btn.getPaddingLeft();
        int pTop = btn.getPaddingTop();
        int pRight = btn.getPaddingRight();
        int pBottom = btn.getPaddingBottom();

        btn.setBackground(layerBg); // 🚨 Android zeroes out the padding here!

        btn.setPadding(pLeft, pTop, pRight, pBottom); // 💡 Instantly restore the padding that was wiped out, back to 100%!

        // Handling to prevent text clipping and duplicate display
        String originalText = btn.getText().toString();
        if (originalText.contains("  ⏱")) {
            originalText = originalText.substring(0, originalText.indexOf("  ⏱"));
        }

        // 🚀 [Fix] 20 characters is too short and leaves the right side looking empty. Generously extended it to 45 characters!
        int maxLength = 45;
        if (originalText.length() > maxLength) {
            originalText = originalText.substring(0, maxLength) + "...";
        }

        int min = (currentMs / 1000) / 60;
        int maxMin = (totalMs / 1000) / 60;
        btn.setText(originalText + "  ⏱ [" + min + "m / " + maxMin + "m]");
    }
}
