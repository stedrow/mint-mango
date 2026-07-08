package com.themoon.y1.managers;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.views.CircularBatteryView;
import com.themoon.y1.views.CustomAnalogClockView;
import com.themoon.y1.views.WidgetBatteryBarView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the theme-driven dynamic main menu: parses the current theme's JSON element list
 * (buttons + widgets), constructs every View from absolute-coordinate layout params, wires up
 * the focus chain and every main-menu button's action-routing switch (OPEN_PLAYER, OPEN_BROWSER,
 * OPEN_SETTINGS, etc.). Extracted from MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- reaches into MainActivity's widget-view fields (tvWidgetClock,
 * widgetBatteryView, ivWidgetAlbum, etc., all reset and rebuilt on every call) and touches nearly
 * every other subsystem via the button click listener's action switch, same as every other
 * screen builder, so it takes the MainActivity instance as a parameter.
 */
public class MainMenuManager {
    private static final String TAG = "MainMenuManager";
    private static MainMenuManager instance;

    private MainMenuManager() {}

    public static synchronized MainMenuManager getInstance() {
        if (instance == null) {
            instance = new MainMenuManager();
        }
        return instance;
    }

    public int parseGravity(MainActivity a, String gravityStr) {
        int g = android.view.Gravity.TOP | android.view.Gravity.LEFT; // default value
        if (gravityStr == null || gravityStr.isEmpty()) return g;
        gravityStr = gravityStr.toLowerCase();
        g = 0;
        if (gravityStr.contains("top")) g |= android.view.Gravity.TOP;
        if (gravityStr.contains("bottom")) g |= android.view.Gravity.BOTTOM;
        if (gravityStr.contains("center_vertical")) g |= android.view.Gravity.CENTER_VERTICAL;
        if (gravityStr.contains("left")) g |= android.view.Gravity.LEFT;
        if (gravityStr.contains("right")) g |= android.view.Gravity.RIGHT;
        if (gravityStr.contains("center_horizontal")) g |= android.view.Gravity.CENTER_HORIZONTAL;
        if (gravityStr.equals("center")) g = android.view.Gravity.CENTER; // perfectly centered

        if (g == 0) g = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        return g;
    }

    public android.widget.FrameLayout.LayoutParams createDynamicLayoutParams(MainActivity a, ThemeManager.MenuElement el, float density) {
        int w = el.width > 0 ? (int)(el.width * density) : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        int h = el.height > 0 ? (int)(el.height * density) : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(w, h);
        lp.gravity = parseGravity(a, el.gravity);

        // Intelligently apply the X, Y margins according to the alignment (gravity).
        if ((lp.gravity & android.view.Gravity.RIGHT) == android.view.Gravity.RIGHT) lp.rightMargin = (int)(el.x * density);
        else lp.leftMargin = (int)(el.x * density);

        if ((lp.gravity & android.view.Gravity.BOTTOM) == android.view.Gravity.BOTTOM) lp.bottomMargin = (int)(el.y * density);
        else lp.topMargin = (int)(el.y * density);

        return lp;
    }

    public android.graphics.drawable.GradientDrawable createDynamicButtonBackground(MainActivity a, int color, int elementRadius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        // If the JSON specifies an individual radius (not -1), use it; otherwise fall back to the theme default!
        float r = (elementRadius == -1 ? ThemeManager.getButtonRadius() : elementRadius) * a.getResources().getDisplayMetrics().density;
        shape.setCornerRadius(r);
        return shape;
    }

    public android.graphics.drawable.GradientDrawable createWidgetBackground(MainActivity a, String bgColorStr, int elementRadius) {
        if (bgColorStr == null || bgColorStr.trim().isEmpty()) return null;
        int color;
        try { color = android.graphics.Color.parseColor(bgColorStr.trim()); }
        catch (Exception e) { return null; }

        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        // If the JSON doesn't specify a radius, follow the theme's default radius.
        float r = (elementRadius == -1 ? ThemeManager.getButtonRadius() : elementRadius) * a.getResources().getDisplayMetrics().density;
        shape.setCornerRadius(r);
        return shape;
    }

    public void buildDynamicMainMenuUI(MainActivity a) {
        android.view.ViewGroup mainMenu = (android.view.ViewGroup) a.layoutMainMenu;
        // 🚀 [Status-bar shield activated!!]
        // Reads the 'top margin (status bar height)' that was in the existing XML skeleton.
        int safeTopPadding = mainMenu.getPaddingTop();

        // If the existing margin couldn't be read, force a fallback shield using Android's default status bar height of 24dp!
        if (safeTopPadding == 0) {
            safeTopPadding = (int)(24 * a.getResources().getDisplayMetrics().density);
        }

        // Reapply the padding as left(0), top(shield), right(0), bottom(0).
        mainMenu.setPadding(0, safeTopPadding, 0, 0);

        for (int i = 0; i < mainMenu.getChildCount(); i++) {
            mainMenu.getChildAt(i).setVisibility(View.GONE);
        }

        // 🚀 [Bug fix: ghost-view residue eliminated]
        android.view.View oldCanvas = mainMenu.findViewWithTag("dynamic_canvas");
        while (oldCanvas != null) {
            if (oldCanvas instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) oldCanvas).removeAllViews();
            }
            mainMenu.removeView(oldCanvas);
            oldCanvas = mainMenu.findViewWithTag("dynamic_canvas");
        }

        // 🚀 [Type-declaration restored] Add the canvas variable declaration back!
        android.widget.FrameLayout canvas = new android.widget.FrameLayout(a);
        canvas.setTag("dynamic_canvas");
        canvas.setBackgroundColor(ThemeManager.getOverlayBackgroundColor());

        // 🚀 [Core fix 3] Unseal so icons can pop out large even at the canvas level.
        canvas.setClipChildren(false);
        canvas.setClipToPadding(false);

        mainMenu.addView(canvas, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        a.tvWidgetClock = null; a.widgetBatteryView = null; a.ivWidgetAlbum = null;
        a.tvWidgetAlbumTitle = null; a.tvWidgetAlbumArtist = null; a.ivWidgetFocusImage = null; // 🚀 Added reset

        final float density = a.getResources().getDisplayMetrics().density;
        List<ThemeManager.MenuElement> elements = ThemeManager.getCurrentTheme().menuElements;

        List<ThemeManager.MenuElement> buttonElements = new ArrayList<>();
        List<ThemeManager.MenuElement> widgetElements = new ArrayList<>();

        for (ThemeManager.MenuElement el : elements) {
            if (el.type.equals("button")) buttonElements.add(el);
            else widgetElements.add(el);
        }

        java.util.Collections.sort(buttonElements, new java.util.Comparator<ThemeManager.MenuElement>() {
            @Override
            public int compare(ThemeManager.MenuElement e1, ThemeManager.MenuElement e2) {
                return e1.focusIndex - e2.focusIndex;
            }
        });

        // 🚀 [New unified vault] Central-command memory that stores the address and JSON info of every widget created
// 🚀 [Bug fix] Reset and reuse the existing global-variable vault! (removed the final local-variable declaration)
        a.widgetViewRegistry.clear();
        final java.util.HashMap<String, LinearLayout> listContainers = new java.util.HashMap<>();

        // 💡 Draw widgets
        for (ThemeManager.MenuElement el : widgetElements) {
            android.graphics.drawable.GradientDrawable widgetBg = createWidgetBackground(a, el.bgColor, el.radius);
            int p = (int)(el.padding * density);
            View createdWidgetView = null; // 🚀 Widget reference variable

            if (el.type.equals("list_box")) {
                final android.widget.ScrollView sv = new android.widget.ScrollView(a);
                sv.setLayoutParams(createDynamicLayoutParams(a, el, density));
                sv.setVerticalScrollBarEnabled(false);
                sv.setFocusable(false); sv.setFocusableInTouchMode(false);
                sv.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                if (widgetBg != null) sv.setBackground(widgetBg);
                sv.setVisibility(View.VISIBLE); // 🚀 Always keep the shell (list box) open.
                sv.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        android.view.ViewParent p = sv.getParent();
                        if (p instanceof android.view.View) ((android.view.View) p).invalidate();
                        sv.invalidate();
                    }
                });

                LinearLayout innerLayout = new LinearLayout(a);
                innerLayout.setOrientation(LinearLayout.VERTICAL);
                innerLayout.setPadding(p, p, p, p);
                sv.addView(innerLayout, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

                canvas.addView(sv);
                listContainers.put(el.id, innerLayout);
                createdWidgetView = sv;
            }
            else if (el.type.equals("box")) {
                ImageView boxView = new ImageView(a);
                boxView.setLayoutParams(createDynamicLayoutParams(a, el, density));
                if (widgetBg == null) widgetBg = createWidgetBackground(a, "#00000000", el.radius);
                boxView.setBackground(widgetBg);

                String imgName = (el.iconNormal != null && !el.iconNormal.isEmpty()) ? el.iconNormal : el.textNormal;
                if (imgName != null && !imgName.isEmpty() && !imgName.equals("New Item")) {
                    android.graphics.Bitmap bmp = ThemeManager.getCustomIcon(imgName, a, 0);
                    if (bmp != null) {
                        int maxTexSize = 2048;
                        if (bmp.getWidth() > maxTexSize || bmp.getHeight() > maxTexSize) {
                            float ratio = Math.min((float)maxTexSize / bmp.getWidth(), (float)maxTexSize / bmp.getHeight());
                            boxView.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(bmp, (int)(bmp.getWidth() * ratio), (int)(bmp.getHeight() * ratio), true));
                        } else boxView.setImageBitmap(bmp);
                        boxView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    }
                }
                canvas.addView(boxView);
                createdWidgetView = boxView;
            }
            else if (el.type.equals("widget_clock")) {
                a.tvWidgetClock = new TextView(a);
                a.tvWidgetClock.setGravity(android.view.Gravity.CENTER);
                a.tvWidgetClock.setLayoutParams(createDynamicLayoutParams(a, el, density));
                a.tvWidgetClock.setTextColor(ThemeManager.getTextColorPrimary());
                a.tvWidgetClock.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                a.currentClockSize = el.textSize > 0 ? el.textSize : 48f;
                a.tvWidgetClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, a.currentClockSize * density);
                if (widgetBg != null) a.tvWidgetClock.setBackground(widgetBg);
                a.tvWidgetClock.setPadding(p, p, p, p);

                canvas.addView(a.tvWidgetClock); // 🚀 Restored as a direct child of the canvas!
                createdWidgetView = a.tvWidgetClock;
            }
            else if (el.type.equals("widget_battery")) {
                a.widgetBatteryView = new WidgetBatteryBarView(a);
                a.widgetBatteryView.setLayoutParams(createDynamicLayoutParams(a, el, density));
                a.widgetBatteryView.setPadding(p, p, p, p);
                canvas.addView(a.widgetBatteryView);
                createdWidgetView = a.widgetBatteryView;
            }
            else if (el.type.equals("widget_album")) {
                LinearLayout albumContainer = new LinearLayout(a);
                a.layoutWidgetAlbumContainer = albumContainer;
                boolean isHorizontal = el.textPosition.equalsIgnoreCase("left") || el.textPosition.equalsIgnoreCase("right");
                albumContainer.setOrientation(isHorizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                albumContainer.setGravity(android.view.Gravity.CENTER);
                albumContainer.setLayoutParams(createDynamicLayoutParams(a, el, density));
                if (widgetBg != null) albumContainer.setBackground(widgetBg);
                albumContainer.setPadding(p, p, p, p);

                a.ivWidgetAlbum = new ImageView(a);
                a.ivWidgetAlbum.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int pSubtract = el.padding * 2;
                int imgSize = isHorizontal ? (int)((el.height - pSubtract) * density) : (int)((el.height - pSubtract) * 0.65f * density);
                if(imgSize <= 0) imgSize = (int)(110 * density);
                LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);

                LinearLayout textContainer = new LinearLayout(a);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                int textGravity = el.textAlign.equalsIgnoreCase("left") ? (android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL) : (el.textAlign.equalsIgnoreCase("right") ? (android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL) : android.view.Gravity.CENTER);
                textContainer.setGravity(textGravity);
                LinearLayout.LayoutParams textContainerLp = isHorizontal ? new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f) : new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);

                int safeWidth = el.width > 0 ? (int)(el.width * density) : (int)(200 * density);
                int availableWidth = isHorizontal ? (safeWidth - imgSize - (int)(15 * density) - (p * 2)) : (safeWidth - (p * 2));
                if (availableWidth <= 0) availableWidth = (int)(150 * density);
                LinearLayout.LayoutParams textViewLp = new LinearLayout.LayoutParams(availableWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

                a.tvWidgetAlbumTitle = new TextView(a);
                a.tvWidgetAlbumTitle.setLayoutParams(textViewLp); a.tvWidgetAlbumTitle.setGravity(textGravity);
                a.tvWidgetAlbumTitle.setSingleLine(true); a.tvWidgetAlbumTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                a.tvWidgetAlbumTitle.setTextColor(ThemeManager.getTextColorPrimary());
                a.tvWidgetAlbumTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
                a.tvWidgetAlbumTitle.setTextSize(el.textSize > 0 ? el.textSize : 16);
                textContainer.addView(a.tvWidgetAlbumTitle);

                a.tvWidgetAlbumArtist = new TextView(a);
                a.tvWidgetAlbumArtist.setLayoutParams(textViewLp); a.tvWidgetAlbumArtist.setGravity(textGravity);
                a.tvWidgetAlbumArtist.setSingleLine(true); a.tvWidgetAlbumArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
                a.tvWidgetAlbumArtist.setTextColor(ThemeManager.getTextColorSecondary());
                a.tvWidgetAlbumArtist.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
                a.tvWidgetAlbumArtist.setTextSize(el.textSecondarySize > 0 ? el.textSecondarySize : 12);
                textContainer.addView(a.tvWidgetAlbumArtist);

                if (el.textPosition.equalsIgnoreCase("left")) {
                    imgLp.leftMargin = (int)(15 * density); albumContainer.addView(textContainer, textContainerLp); albumContainer.addView(a.ivWidgetAlbum, imgLp);
                } else if (el.textPosition.equalsIgnoreCase("right")) {
                    textContainerLp.leftMargin = (int)(15 * density); albumContainer.addView(a.ivWidgetAlbum, imgLp); albumContainer.addView(textContainer, textContainerLp);
                } else if (el.textPosition.equalsIgnoreCase("top")) {
                    textContainerLp.bottomMargin = (int)(5 * density); albumContainer.addView(textContainer, textContainerLp); albumContainer.addView(a.ivWidgetAlbum, imgLp);
                } else {
                    textContainerLp.topMargin = (int)(5 * density); albumContainer.addView(a.ivWidgetAlbum, imgLp); albumContainer.addView(textContainer, textContainerLp);
                }

                canvas.addView(albumContainer); // 🚀 Restored as a direct child of the canvas!
                createdWidgetView = albumContainer;
            }
            else if (el.type.equals("widget_analog_clock")) {
                a.customAnalogClockView = new CustomAnalogClockView(a);
                a.customAnalogClockView.setLayoutParams(createDynamicLayoutParams(a, el, density));
                a.customAnalogClockView.setPadding(p, p, p, p);
                if (el.bgColor != null && !el.bgColor.trim().isEmpty()) {
                    try { a.customAnalogClockView.setClockBackgroundColor(android.graphics.Color.parseColor(el.bgColor.trim())); } catch (Exception e) { Log.d(TAG, "buildDynamicMainMenuUI failed", e); }
                }
                canvas.addView(a.customAnalogClockView);
                createdWidgetView = a.customAnalogClockView;
            }
            else if (el.type.equals("widget_circular_battery")) {
                a.customCircularBatteryView = new CircularBatteryView(a);
                a.customCircularBatteryView.setLayoutParams(createDynamicLayoutParams(a, el, density));
                a.customCircularBatteryView.setPadding(p, p, p, p);
                if (el.textSize > 0) a.customCircularBatteryView.setCustomTextSize(el.textSize * density);
                canvas.addView(a.customCircularBatteryView);
                createdWidgetView = a.customCircularBatteryView;
            }
            else if (el.type.equals("widget_focus_image")) {
                a.ivWidgetFocusImage = new ImageView(a); // 🚀 Slimmed down to a standalone ImageView skeleton for hybrid unification!
                a.ivWidgetFocusImage.setLayoutParams(createDynamicLayoutParams(a, el, density));
                a.ivWidgetFocusImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                if (widgetBg != null) a.ivWidgetFocusImage.setBackground(widgetBg);
                a.ivWidgetFocusImage.setPadding(p, p, p, p);

                canvas.addView(a.ivWidgetFocusImage);
                createdWidgetView = a.ivWidgetFocusImage;
            }

            // 🚀 Carefully registers this widget object and its theme JSON design info in the address book.
            if (createdWidgetView != null) {
                // ❌ [Fatal error cause removed] Fully prevents the app from crashing due to modifying a list mid-loop!
                a.widgetViewRegistry.put(createdWidgetView, el); // Store it in the correct vault.

                // 🚀 [Logic fix] If this isn't a parentId but rather a newly created visibleOnFocus (watch target), hide it by default!
                if (el.visibleOnFocus != null && !el.visibleOnFocus.trim().isEmpty()) {
                    createdWidgetView.setVisibility(View.GONE);
                }
            }
        }
        // 💡 Draw buttons
        List<LinearLayout> createdButtons = new ArrayList<>(); // 🚀 Upgraded from Button to LinearLayout

        // 🚀 [Hide-filtering engine] Completely excludes from the list any button the user chose to hide in settings!
        List<ThemeManager.MenuElement> visibleButtonElements = new ArrayList<>();
        for (ThemeManager.MenuElement el : buttonElements) {
            if (!a.prefs.getBoolean("hide_btn_" + el.id, false)) {
                visibleButtonElements.add(el);
            }
        }

        // Build the UI and wire up the focus chain (ID) using only the 'buttons set to show', not the full list.
        for (int i = 0; i < visibleButtonElements.size(); i++) {
            final ThemeManager.MenuElement el = visibleButtonElements.get(i);

            // 🚀 1. The overall container wrapping the button (LinearLayout)
            final LinearLayout btn = new LinearLayout(a);
            btn.setId(10000 + i);
            btn.setTag(el.action);
            btn.setSoundEffectsEnabled(false);
            btn.setFocusable(true);
            // Cold boot starts in touch mode (this is a touchscreen digitizer even though the
            // wheel drives normal navigation) -- requestFocus() silently fails on a merely
            // focusable() view while in touch mode, only exiting once a key event arrives. That's
            // why the main menu showed no highlight until navigating into a submenu and back
            // (a key event by then had already taken the device out of touch mode).
            btn.setFocusableInTouchMode(true);
            // 🚀 [Focus-vanish fix 3] Inject the clickable instinct, since Android ignores a button's existence when the clickable attribute is missing!
            btn.setClickable(true);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setOnLongClickListener(a.globalScreenOffLongClickListener);
            // 🚀 2. Left-side main text and icon view
            final TextView tvMain = new TextView(a);
            tvMain.setSingleLine(true);
            tvMain.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // 🚀 [Android bug fix] Physically eliminate the TextView's characteristic invisible ghost margin (~5px).
            tvMain.setIncludeFontPadding(false);
            tvMain.setPadding(0, 0, 0, 0);
            tvMain.setMinimumWidth(0);
            tvMain.setMinimumHeight(0);

            // 🚀 3. Right-side arrow and point text view
            final TextView tvRight = new TextView(a);
            tvRight.setSingleLine(true);
            tvRight.setIncludeFontPadding(false); // Match this here too.
            tvRight.setPadding(0, 0, 0, 0);

            final boolean isIconOnly = (el.textNormal == null || el.textNormal.trim().isEmpty());

            // 🚀 [Ultimate formula] Also fully blocks the crash where enlarged padding drives the icon size negative.
            final int calculatedIconSize;
            if (isIconOnly) {
                int w = el.width > 0 ? el.width : 50;
                int h = el.height > 0 ? el.height : 50;
                int p = (int)(el.padding * density);
                int tempSize = (int)(Math.min(w, h) * density) - (p * 2);
                // Guards so the icon keeps a minimum size of 10dp even if it would otherwise shrink too much.
                calculatedIconSize = tempSize > 0 ? tempSize : (int)(10 * density);
            } else {
                int h = el.height > 0 ? el.height : 50;
                calculatedIconSize = (int)(h * density * 0.5f);
            }

            int textGravity = android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL;
            if (el.textAlign != null && !el.textAlign.isEmpty()) {
                String ta = el.textAlign.toLowerCase();
                if (ta.equals("center")) textGravity = android.view.Gravity.CENTER;
                else if (ta.equals("right")) textGravity = android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL;
                else if (ta.equals("top")) textGravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                else if (ta.equals("bottom")) textGravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            } else {
                if (el.gravity.toLowerCase().contains("center")) textGravity = android.view.Gravity.CENTER;
            }

            if (isIconOnly) {
                btn.setGravity(android.view.Gravity.CENTER);
                int p = (int)(el.padding * density);
                btn.setPadding(p, p, p, p);
                tvMain.setGravity(android.view.Gravity.CENTER);
            } else {
                btn.setGravity(android.view.Gravity.CENTER_VERTICAL);
                tvMain.setGravity(textGravity);
                tvRight.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);

                // 🚀 [Fix 1] Text-only buttons now also properly get the padding value set in the editor!
                int customPad = (int)(el.padding * density);

                if (el.textAlign != null && (el.textAlign.equalsIgnoreCase("top") || el.textAlign.equalsIgnoreCase("bottom"))) {
                    // Use the user's value if set; otherwise apply the default of 15
                    int verticalPad = el.padding > 0 ? customPad : (int)(15 * density);
                    btn.setPadding(customPad, verticalPad, customPad, verticalPad);
                } else {
                    int horizontalPad = el.padding > 0 ? customPad : (int)(15 * density);
                    btn.setPadding(horizontalPad, customPad, horizontalPad, customPad);
                }
            }

            btn.setLayoutParams(createDynamicLayoutParams(a, el, density));
            tvMain.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
            tvRight.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);

            if (!isIconOnly) {
                // 🚀 [Font-size bug fix] Uses pixel (PX) units instead of Android's default (SP) unit, forcing the size to exactly match the editor!
                float mainSize = el.textSize > 0 ? el.textSize : 16; // Default to the same 16px as the editor
                float rightSize = el.textSecondarySize > 0 ? el.textSecondarySize : mainSize; // Supports an independent size for the right-side text

                tvMain.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, mainSize * density);
                tvRight.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, rightSize * density);
            }

            LinearLayout.LayoutParams lpMain;
            LinearLayout.LayoutParams lpRight;

            if (isIconOnly) {
                // 🚀 [Core fix 1] For icon-only buttons, completely remove the 10dp right margin (the "thief") so it keeps only its own size and stays centered!
                lpMain = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lpRight = new LinearLayout.LayoutParams(0, 0);
                tvRight.setVisibility(View.GONE); // Dismiss the ghost text view

                // 🚀 [Core fix 2] Unsealed so it isn't clipped by the padding line when the zoom animation fires!
                btn.setClipChildren(false);
                btn.setClipToPadding(false);
            } else {
                // Regular buttons keep a weight of 1.0f so the text pushes into the remaining space
                lpMain = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                lpRight = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lpRight.leftMargin = (int)(10 * density);
            }

            btn.addView(tvMain, lpMain);
            btn.addView(tvRight, lpRight);

            final Runnable setNormalState = new Runnable() {
                public void run() {
                    // 🚀 [Bug fix 1] Prioritize the individually specified background color (bgColor) from the editor over the theme's default background color, if present!
                    int normalBgColor = ThemeManager.getListButtonNormalBg();
                    if (el.bgColor != null && !el.bgColor.trim().isEmpty()) {
                        try { normalBgColor = android.graphics.Color.parseColor(el.bgColor.trim()); } catch (Exception e) { Log.d(TAG, "buildDynamicMainMenuUI failed", e); }
                    }

                    // 🚀 [Bug fix 2] Removed the forced transparent-color assignment for both icon-only and regular buttons — always paint the background color!
                    btn.setBackground(createDynamicButtonBackground(a, normalBgColor, el.radius));

                    if (isIconOnly) {
                        tvMain.setText("");
                    } else {
                        tvMain.setText(a.t(el.textNormal));
                        tvMain.setTextColor(ThemeManager.getTextColorPrimary());
                        tvRight.setText(el.textRight != null ? a.t(el.textRight) : "");

                        if (el.textRightColor != null && !el.textRightColor.isEmpty()) {
                            try { tvRight.setTextColor(android.graphics.Color.parseColor(el.textRightColor)); }
                            catch (Exception e) { tvRight.setTextColor(ThemeManager.getTextColorPrimary()); }
                        } else {
                            tvRight.setTextColor(ThemeManager.getTextColorPrimary());
                        }
                    }

                    if (el.iconNormal != null && !el.iconNormal.isEmpty()) {
                        // 🚀 [Core technique 1] Physically crop the bitmap itself at the pixel level so Android can't ignore and override the intended size!
                        android.graphics.Bitmap scaledBmp = a.getScaledThemedIcon(el.iconNormal, calculatedIconSize);
                        if (scaledBmp != null) {
                            android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(a.getResources(), scaledBmp);

                            d.setBounds(0, 0, calculatedIconSize, calculatedIconSize);
                            tvMain.setCompoundDrawables(d, null, null, null);

                            tvMain.setCompoundDrawablePadding(isIconOnly ? 0 : (int)(10 * density));
                        } else {
                            tvMain.setCompoundDrawables(null, null, null, null);
                        }
                    } else {
                        tvMain.setCompoundDrawables(null, null, null, null);
                    }
                    tvMain.setTranslationX(0);
                    tvMain.setTranslationY(0);
                    tvMain.setScaleX(1.0f);
                    tvMain.setScaleY(1.0f);
                }
            };
            setNormalState.run();

            btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        btn.setBackground(createDynamicButtonBackground(a, ThemeManager.getListButtonFocusedBg(), el.radius));

                        if (isIconOnly) {
                            tvMain.setText("");
                        } else {
                            // 🚀 Simultaneously change the main text and right-arrow colors on focus!
                            tvMain.setTextColor(ThemeManager.getListButtonFocusedTextColor());
// 🚀 Apply a dedicated focus color for the right-side text
                            if (el.textRightFocusedColor != null && !el.textRightFocusedColor.isEmpty()) {
                                try { tvRight.setTextColor(android.graphics.Color.parseColor(el.textRightFocusedColor)); }
                                catch (Exception e) { tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor()); }
                            } else {
                                tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                            }
                            if (el.textFocused != null && !el.textFocused.isEmpty()) tvMain.setText(a.t(el.textFocused));
                            else tvMain.setText(a.t(el.textNormal));
                        }

                        String targetIcon = (el.iconFocused != null && !el.iconFocused.isEmpty()) ? el.iconFocused : el.iconNormal;
                        if (targetIcon != null && !targetIcon.isEmpty()) {
                            // 🚀 [Core technique 2] On focus, likewise physically crop the bitmap before inserting it!
                            android.graphics.Bitmap scaledBmpF = a.getScaledThemedIcon(targetIcon, calculatedIconSize);
                            if (scaledBmpF != null) {
                                android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(a.getResources(), scaledBmpF);

                                d.setBounds(0, 0, calculatedIconSize, calculatedIconSize);
                                tvMain.setCompoundDrawables(d, null, null, null);
                            }
                        }
                        a.updateFocusPreviewLiveContent(el);
                        tvMain.animate()
                                .translationX(el.focusOffsetX * density)
                                .translationY(el.focusOffsetY * density)
                                .scaleX(el.focusScale).scaleY(el.focusScale)
                                .setDuration(150).start();

                    } else {
                        // Restore to the original state when focus leaves
                        tvMain.animate()
                                .translationX(0).translationY(0)
                                .scaleX(1.0f).scaleY(1.0f)
                                .setDuration(150).start();
                        setNormalState.run();
                    }
                }
            });

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    a.lastMainMenuFocusAction = el.action; // remember which main-menu button we left from, so back returns focus here
                    switch (el.action) {
                        case "OPEN_PLAYER": {
                            com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
                            boolean navidromeActive = am.isNavidromeMode && !am.navidromePlaylist.isEmpty();
                            if (a.currentPlaylist.isEmpty() && !navidromeActive) {
                                Toast.makeText(a, "No music is currently playing.", Toast.LENGTH_SHORT).show();
                            } else {
                                a.changeScreen(a.STATE_PLAYER);
                                if (navidromeActive) {
                                    // Restart the progress poller — it stops when the player screen is left
                                    a.progressHandler.removeCallbacks(a.updateProgressTask);
                                    a.progressHandler.post(a.updateProgressTask);
                                }
                            }
                            break;
                        }
// 🎵 Enter the music library
                        case "OPEN_COVER_FLOW":
                            a.currentBrowserMode = a.BROWSER_COVER_FLOW;
                            a.changeScreen(a.STATE_BROWSER);
                            break;
                        case "OPEN_BROWSER":
                            a.isAudiobookLibraryMode = false;
                            a.currentBrowserMode = a.BROWSER_ROOT;
                            if (a.customLibrary.isEmpty() && !a.isCustomScanning) a.startMediaLibraryScan();
                            a.changeScreen(a.STATE_BROWSER);
                            if (a.isCustomScanning && a.customLibrary.isEmpty()) a.showLoadingPopup();
                            break;

                        // 📚 Jump directly into the audiobook library (set the action to "OPEN_AUDIOBOOKS" in the theme settings to enable this!)
                        case "OPEN_AUDIOBOOKS":
                            a.isAudiobookLibraryMode = true;
                            a.currentBrowserMode = a.BROWSER_ROOT;
                            if (a.audiobookLibrary.isEmpty() && !a.isCustomScanning) a.startMediaLibraryScan();
                            a.changeScreen(a.STATE_BROWSER);
                            if (a.isCustomScanning && a.audiobookLibrary.isEmpty()) a.showLoadingPopup();
                            break;
                        case "OPEN_BLUETOOTH": a.changeScreen(a.STATE_BLUETOOTH); break;
                        case "OPEN_SETTINGS": a.changeScreen(a.STATE_SETTINGS); break;
                        case "OPEN_WEBSERVER": a.changeScreen(a.STATE_WEBSERVER); break;
// 🚀 [Radio revival] Turns on Android's built-in FM radio when the radio button is pressed in the theme!
                        case "OPEN_RADIO":
                            a.clickFeedback();
                            // 🚀 Instead of the clunky stock app, go directly into our own sleek built-in radio studio.
                            a.isNavigatingToSubMenu = true;
                            a.changeScreen(a.STATE_SETTINGS);
                            a.buildRadioUI();
                            a.isNavigatingToSubMenu = false;
                            break;
                        // 🚀🚀🚀 [New direct-shortcut actions start here!] 🚀🚀🚀
                        case "OPEN_ROOT_FOLDER":
                            a.currentBrowserMode = a.BROWSER_FOLDER;
                            a.currentFolder = new File("/storage/sdcard0"); // Force-move to the topmost root folder!
                            a.changeScreen(a.STATE_BROWSER);
                            break;
                        case "OPEN_WIFI": a.changeScreen(a.STATE_WIFI); break;
                        case "OPEN_NAVIDROME":
                            a.navidromeBrowseDepth = a.NAV_ARTISTS;
                            a.selectedNavidromeArtist = null;
                            com.themoon.y1.managers.NavidromeManager.getInstance().clearSelectedAlbum();
                            a.isNavidromeLetterView = false;
                            a.navidromeBackTarget = a.STATE_MENU;
                            a.changeScreen(a.STATE_NAVIDROME);
                            break;
                        case "OPEN_BRIGHTNESS": a.changeScreen(a.STATE_BRIGHTNESS); break;
                        case "OPEN_STORAGE_INFO": a.changeScreen(a.STATE_STORAGE); break;
                        case "OPEN_WIDGET_SETTINGS":
                            a.isNavigatingToSubMenu = true; a.changeScreen(a.STATE_SETTINGS); a.buildWidgetSettingsUI(); a.isNavigatingToSubMenu = false; break;
                        case "OPEN_BACKGROUND_SETTINGS":
                            a.isNavigatingToSubMenu = true; a.changeScreen(a.STATE_SETTINGS); a.buildBackgroundSettingsUI(); a.isNavigatingToSubMenu = false; break;
                        case "OPEN_THEME_SETTINGS":
                            a.isNavigatingToSubMenu = true; a.changeScreen(a.STATE_SETTINGS); a.buildThemeSelectorUI(); a.isNavigatingToSubMenu = false; break;
                        case "OPEN_TIME_SETTINGS":
                            java.util.Calendar c = java.util.Calendar.getInstance();
                            a.dtYear = c.get(java.util.Calendar.YEAR); a.dtMonth = c.get(java.util.Calendar.MONTH) + 1; a.dtDay = c.get(java.util.Calendar.DAY_OF_MONTH);
                            a.dtHour = c.get(java.util.Calendar.HOUR_OF_DAY); a.dtMinute = c.get(java.util.Calendar.MINUTE);
                            a.isNavigatingToSubMenu = true; a.changeScreen(a.STATE_SETTINGS); a.buildDateTimeUI(); a.isNavigatingToSubMenu = false; break;
                        // 🚀🚀🚀 [End of addition] 🚀🚀🚀

                        default: break;
                    }
                }
            });

            if (el.parentId != null && !el.parentId.isEmpty() && listContainers.containsKey(el.parentId)) {
                // 💡 1. If it belongs to a list box: adjust the attributes to match vertical (LinearLayout) alignment rules.
                LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        el.height > 0 ? (int)(el.height * density) : LinearLayout.LayoutParams.WRAP_CONTENT);
                // Inside a list, smartly reuse the Y value as a top margin (vertical gap) and the X value as horizontal spacing!
                listLp.setMargins((int)(el.x * density), (int)(el.y * density), (int)(el.x * density), 0);
                btn.setLayoutParams(listLp);

                // Instead of the canvas, it goes inside the parent group (list box)!
                listContainers.get(el.parentId).addView(btn);
            } else {
                // 💡 2. If it doesn't belong to anything: plug in the X, Y absolute coordinates directly onto the canvas as before.
                btn.setLayoutParams(createDynamicLayoutParams(a, el, density));
                canvas.addView(btn);
            }

            createdButtons.add(btn);

        }

        int totalBtns = createdButtons.size();
        for (int i = 0; i < totalBtns; i++) {
            LinearLayout currentBtn = createdButtons.get(i);
            // 🚀 [Loop-condition branch] Depending on the loop-scroll setting, either allow infinite wrapping at both ends or cut it off (View.NO_ID).
            int prevId = (i == 0) ? (a.isLoopScrollOn ? 10000 + totalBtns - 1 : View.NO_ID) : 10000 + i - 1;
            int nextId = (i == totalBtns - 1) ? (a.isLoopScrollOn ? 10000 : View.NO_ID) : 10000 + i + 1;

            currentBtn.setNextFocusUpId(prevId);
            currentBtn.setNextFocusLeftId(prevId);

            currentBtn.setNextFocusDownId(nextId);
            currentBtn.setNextFocusRightId(nextId);
        }

        a.refreshWidgets();

        // 🚀 [Bug fix] Once the screen assembly is completely done (50ms safety wait), forcefully focus button 0!
        if (!createdButtons.isEmpty()) {
            final LinearLayout firstBtn = createdButtons.get(0);
            firstBtn.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 🚀 [Culprit of the focus-vanish bug caught!] Guard added so focus is only pulled back while looking at the main screen!
                    // Only steal focus if nothing is already focused — otherwise this clobbers the
                    // back-navigation focus restore that changeScreen() already applied synchronously.
                    View cur = a.getCurrentFocus();
                    if (a.currentScreenState == a.STATE_MENU && (cur == null || cur.getVisibility() != View.VISIBLE)) {
                        firstBtn.requestFocus();

                        android.view.ViewParent parent = firstBtn.getParent();
                        if (parent != null && parent.getParent() instanceof android.widget.ScrollView) {
                            ((android.widget.ScrollView) parent.getParent()).scrollTo(0, 0);
                        }
                    }
                }
            }, 50);
        }
    }

}
