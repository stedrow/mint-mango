package com.themoon.y1.managers;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import com.themoon.y1.ThemeManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Renders the home-screen digital clock widget (time + date text, minute-granular). Extracted
 * from MainActivity's refreshWidgets(): this slice only ever touches its own cached-text/size
 * fields and the one TextView passed in, unlike the other widgets refreshWidgets() also updates
 * (battery, album art, analog clock) which still reach deep into MainActivity state.
 */
public class WidgetClockManager {
    private static WidgetClockManager instance;

    private static final SimpleDateFormat WIDGET_CLOCK_FORMAT_24 = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat WIDGET_CLOCK_FORMAT_12 = new SimpleDateFormat("hh:mm", Locale.US);
    private static final SimpleDateFormat WIDGET_DATE_FORMAT = new SimpleDateFormat("EEE, MMM dd", Locale.US);

    // Text is minute-granular and the size rarely changes -- only rebuild the spannable (and
    // retouch text size) when something actually changed, instead of allocating a
    // SpannableString + two spans every single second.
    private String lastWidgetClockText = null;
    private float lastWidgetClockSize = -1f;
    private float cachedDensity = 0f;

    private WidgetClockManager() {}

    public static synchronized WidgetClockManager getInstance() {
        if (instance == null) {
            instance = new WidgetClockManager();
        }
        return instance;
    }

    /** Updates visibility and (if changed) text of the digital clock widget. */
    public void update(TextView tvWidgetClock, ThemeManager.MenuElement el, boolean isWidgetClockOn,
                        boolean is24HourFormat, float currentClockSize, Date now, float density) {
        if (tvWidgetClock == null) return;

        if (el == null || el.visibleOnFocus == null || el.visibleOnFocus.trim().isEmpty()) {
            tvWidgetClock.setVisibility(isWidgetClockOn ? View.VISIBLE : View.GONE);
        }

        // Only refresh the time while it's VISIBLE, to avoid unnecessary load
        if (tvWidgetClock.getVisibility() != View.VISIBLE) return;

        SimpleDateFormat sdfTime = is24HourFormat ? WIDGET_CLOCK_FORMAT_24 : WIDGET_CLOCK_FORMAT_12;
        String timeStr = sdfTime.format(now);
        String dateStr = WIDGET_DATE_FORMAT.format(now);
        String fullText = timeStr + "\n" + dateStr;

        if (fullText.equals(lastWidgetClockText) && currentClockSize == lastWidgetClockSize) return;

        lastWidgetClockText = fullText;
        lastWidgetClockSize = currentClockSize;
        if (cachedDensity <= 0f) cachedDensity = density;
        tvWidgetClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, (currentClockSize * 2.1f) * cachedDensity);
        tvWidgetClock.setLineSpacing(0, 1.1f);

        SpannableString spannable = new SpannableString(fullText);
        spannable.setSpan(new RelativeSizeSpan(0.47f), timeStr.length() + 1, fullText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new StyleSpan(android.graphics.Typeface.NORMAL), timeStr.length() + 1, fullText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvWidgetClock.setText(spannable);
    }
}
