package com.themoon.y1.managers;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;

import java.util.Collections;
import java.util.Locale;

/**
 * Builds and drives the FM radio screen (player mode + settings sub-page) and the
 * wheel-driven frequency-adjust popup. Extracted from MainActivity's buildRadioUI(),
 * tuneToNextSavedRadioChannel(), updateRadioMainPlayerUI() and showRadioFreqPopup() verbatim.
 *
 * Unlike GaussianBlurManager/WidgetClockManager/WheelLockManager, this subsystem has no clean
 * field boundary of its own -- it reaches into MainActivity's shared settings-page scaffolding
 * (containerSettingsItems, currentSettingsDepth, the loading-popup views, createSettingRow(),
 * etc.) the same way every other Settings sub-page does. Per GOD_ACTIVITY_EXTRACTION.md this
 * whole area isn't a natural boundary; extracting just the radio slice still means calling back
 * into MainActivity for most of what it touches, so it takes the MainActivity instance as a
 * parameter (like GaussianBlurManager takes Context) instead of trying to own that state.
 */
public class FmRadioUiManager {
    private static final String TAG = "FmRadioUiManager";
    private static FmRadioUiManager instance;

    private FmRadioUiManager() {}

    public static synchronized FmRadioUiManager getInstance() {
        if (instance == null) {
            instance = new FmRadioUiManager();
        }
        return instance;
    }

    public void tuneToNextSavedChannel(MainActivity a, boolean isNext) {
        FmRadioManager fm = FmRadioManager.getInstance(a);
        if (a.savedRadioStations.isEmpty()) {
            Toast.makeText(a, a.t("No saved channels."), Toast.LENGTH_SHORT).show();
            return;
        }
        float target = a.savedRadioStations.get(0);
        if (isNext) {
            for (float f : a.savedRadioStations) {
                if (f > fm.currentFreq) { target = f; break; }
            }
        } else {
            target = a.savedRadioStations.get(a.savedRadioStations.size() - 1);
            for (int i = a.savedRadioStations.size() - 1; i >= 0; i--) {
                if (a.savedRadioStations.get(i) < fm.currentFreq) { target = a.savedRadioStations.get(i); break; }
            }
        }
        if (fm.isPowerUp) fm.tune(target);
        else fm.currentFreq = target;

        // 🚀 [Fix complete] Blocks full reloads and, on the player screen, only runs an ultra-fast partial refresh to prevent flicker!
        if (a.currentScreenState == MainActivity.STATE_SETTINGS) {
            if (a.isRadioUIShowing && !a.isRadioSettingsMode) {
                updateMainPlayerUI(a);
            } else {
                build(a);
            }
        }
    }

    public void updateMainPlayerUI(MainActivity a) {
        FmRadioManager fmManager = FmRadioManager.getInstance(a);

        // 1. Pinpoint-refresh only the main large frequency display text
        TextView tvFreq = (TextView) a.containerSettingsItems.findViewWithTag("radio_main_freq_text");
        if (tvFreq != null) {
            tvFreq.setText(String.format(Locale.US, "%.1f MHz", fmManager.currentFreq));
            tvFreq.setTextColor(fmManager.isPowerUp ? (ThemeManager.getListButtonFocusedBg() | 0xFF000000) : 0xFF888888);
        }

        // 2. Silently refresh only the background and text color of the pills inside the candy pouch
        if (a.layoutRadioCandyContainer != null) {
            int themeHighlightColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;
            float density = a.getResources().getDisplayMetrics().density;
            for (int i = 0; i < a.layoutRadioCandyContainer.getChildCount(); i++) {
                final View child = a.layoutRadioCandyContainer.getChildAt(i);
                if (child instanceof TextView && child.getTag() instanceof Float) {
                    TextView tvCandy = (TextView) child;
                    float stationFreq = (Float) child.getTag();
                    GradientDrawable candyBg = new GradientDrawable();
                    candyBg.setCornerRadius(20 * density);

                    if (Math.abs(fmManager.currentFreq - stationFreq) < 0.05f) {
                        candyBg.setColor(themeHighlightColor);
                        tvCandy.setTextColor(ThemeManager.getListButtonFocusedTextColor());

                        final LinearLayout candyContainer = a.layoutRadioCandyContainer;
                        candyContainer.post(new Runnable() {
                            @Override
                            public void run() {
                                ViewParent parent = candyContainer.getParent();
                                if (parent instanceof HorizontalScrollView) {
                                    HorizontalScrollView hsv = (HorizontalScrollView) parent;
                                    int scrollX = child.getLeft() - (hsv.getWidth() / 2) + (child.getWidth() / 2);

                                    // 🚀 [Core guard] If the scroll calculation goes negative, force it to 0 (leftmost) to permanently prevent the first channel from being cut off!
                                    if (scrollX < 0) scrollX = 0;

                                    hsv.smoothScrollTo(scrollX, 0);
                                }
                            }
                        });
                    } else {
                        candyBg.setColor(ThemeManager.getListButtonNormalBg());
                        tvCandy.setTextColor(ThemeManager.getTextColorSecondary());
                    }
                    tvCandy.setBackground(candyBg);
                }
            }
        }
    }

    public void build(MainActivity a) {
        a.currentSettingsDepth = 1;
        a.isRadioUIShowing = true; // 🚀 Tell the system I'm currently on the radio screen!
        a.containerSettingsItems.removeAllViews();

        // 🚀 Fully block/hide the ghost "Settings" title at the top
        ViewGroup settingsGroup = (ViewGroup) a.layoutSettingsMode;
        if (settingsGroup != null && settingsGroup.getChildCount() > 0 && settingsGroup.getChildAt(0) instanceof TextView) {
            settingsGroup.getChildAt(0).setVisibility(View.GONE);
        }

        final FmRadioManager fmManager = FmRadioManager.getInstance(a);

        if (a.savedRadioStations.isEmpty()) {
            try {
                String savedStationsStr = a.prefs.getString("radio_stations", "");
                if (!savedStationsStr.isEmpty()) {
                    for (String s : savedStationsStr.split(",")) a.savedRadioStations.add(Float.parseFloat(s));
                }
            } catch (Exception e) {
                Log.d(TAG, "build failed", e);
            }
        }

        final float density = a.getResources().getDisplayMetrics().density;

        // ==========================================================
        // 🎧 [Mode 1] Default player mode (🔮 neon highlight glow + bottom alignment)
        // ==========================================================
        if (!a.isRadioSettingsMode) {

            // Advanced outer frame panel setup
            FrameLayout freqPanel = new FrameLayout(a);
            LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panelLp.setMargins((int) (15 * density), (int) (30 * density), (int) (15 * density), (int) (15 * density));
            freqPanel.setLayoutParams(panelLp);

            GradientDrawable panelBg = new GradientDrawable();
            panelBg.setShape(GradientDrawable.RECTANGLE);
            panelBg.setCornerRadius(18 * density);

            int themeHighlightColor = ThemeManager.getListButtonFocusedBg() | 0xFF000000;

            if (fmManager.isPowerUp) {
                int backlitColor = (themeHighlightColor & 0x00FFFFFF) | 0x42000000;
                panelBg.setColor(backlitColor);
                panelBg.setStroke((int) (4 * density), themeHighlightColor);
            } else {
                panelBg.setColor(0x15FFFFFF);
                panelBg.setStroke((int) (1 * density), 0x33FFFFFF);
            }
            freqPanel.setBackground(panelBg);

            // Large digital frequency text view
            final TextView tvFreq = new TextView(a);
            tvFreq.setTag("radio_main_freq_text");
            tvFreq.setText(String.format(Locale.US, "%.1f MHz", fmManager.currentFreq));
            tvFreq.setTextColor(fmManager.isPowerUp ? themeHighlightColor : 0xFF888888);
            tvFreq.setTextSize(54);
            tvFreq.setGravity(Gravity.CENTER);
            tvFreq.setTypeface(ThemeManager.getCustomFont(), Typeface.BOLD);
            tvFreq.setPadding(0, (int) (38 * density), 0, (int) (38 * density));

            freqPanel.addView(tvFreq);
            a.containerSettingsItems.addView(freqPanel);

            // 🍬 Horizontally scrolling pill channel container
            if (!a.savedRadioStations.isEmpty()) {
                HorizontalScrollView hzScroll = new HorizontalScrollView(a);
                hzScroll.setHorizontalScrollBarEnabled(false);
                hzScroll.setClipChildren(false);
                hzScroll.setClipToPadding(false);
                // 💡 This option needs to be on for channels to center nicely when there are only a few (1-3)!
                hzScroll.setFillViewport(true);
                hzScroll.setPadding(0, 15, 0, 15);

                LinearLayout candyContainer = new LinearLayout(a);
                candyContainer.setOrientation(LinearLayout.HORIZONTAL);

                // 🚀 [Fix 1] Allow full CENTER alignment instead of just CENTER_VERTICAL.
                candyContainer.setGravity(Gravity.CENTER);

                for (int i = 0; i < a.savedRadioStations.size(); i++) {
                    float stationFreq = a.savedRadioStations.get(i);

                    TextView tvCandy = new TextView(a);
                    tvCandy.setText(String.format(Locale.US, "%.1f", stationFreq));
                    tvCandy.setTextSize(18f);
                    tvCandy.setGravity(Gravity.CENTER);
                    tvCandy.setPadding((int) (14 * density), (int) (6 * density), (int) (14 * density), (int) (6 * density));
                    tvCandy.setTypeface(ThemeManager.getCustomFont(), Typeface.BOLD);
                    tvCandy.setTag(stationFreq);

                    GradientDrawable candyBg = new GradientDrawable();
                    candyBg.setCornerRadius(20 * density);

                    if (Math.abs(fmManager.currentFreq - stationFreq) < 0.05f) {
                        candyBg.setColor(themeHighlightColor);
                        tvCandy.setTextColor(ThemeManager.getListButtonFocusedTextColor());

                        final View targetChild = tvCandy;
                        final HorizontalScrollView finalHzScroll = hzScroll;
                        hzScroll.post(new Runnable() {
                            @Override
                            public void run() {
                                int scrollX = targetChild.getLeft() - (finalHzScroll.getWidth() / 2) + (targetChild.getWidth() / 2);
                                if (scrollX < 0) scrollX = 0; // safety guard
                                finalHzScroll.scrollTo(scrollX, 0);
                            }
                        });
                    } else {
                        candyBg.setColor(ThemeManager.getListButtonNormalBg());
                        tvCandy.setTextColor(ThemeManager.getTextColorSecondary());
                    }

                    tvCandy.setBackground(candyBg);

                    LinearLayout.LayoutParams candyLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    candyLp.setMargins((int) (6 * density), 0, (int) (6 * density), 0);
                    tvCandy.setLayoutParams(candyLp);

                    candyContainer.addView(tvCandy);
                }

                // 🚀 [Fix 2 key point!] Completely drop MATCH_PARENT and CENTER_HORIZONTAL, switch to WRAP_CONTENT!
                // This way, even as more items are added, the left wall (0px point) doesn't collapse and it correctly scrolls to the right only.
                FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);

                // ❌ Absolutely forbidden: containerLp.gravity = Gravity.CENTER_HORIZONTAL;

                hzScroll.addView(candyContainer, containerLp);

                // 🚀 [Bug fix complete] Snap the fully assembled horizontal-scroll pouch onto the main screen!
                a.containerSettingsItems.addView(hzScroll);

                a.layoutRadioCandyContainer = candyContainer;
            }

            // Weighted spacer for the bottom control layout
            View spacer = new View(a);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 0, 1.0f);
            spacer.setLayoutParams(spacerLp);
            a.containerSettingsItems.addView(spacer);

            // 3. Settings-mode entry button (docked at the very bottom)
            Button btnSettings = a.createListButton(a.t("Radio Settings"));
            btnSettings.setGravity(Gravity.CENTER);

            LinearLayout.LayoutParams settingsLp = (LinearLayout.LayoutParams) btnSettings.getLayoutParams();
            if (settingsLp != null) {
                settingsLp.bottomMargin = (int) (15 * density);
                btnSettings.setLayoutParams(settingsLp);
            }

            btnSettings.setOnClickListener(v -> {
                a.clickFeedback();
                a.isRadioSettingsMode = true;
                build(a);
            });
            a.containerSettingsItems.addView(btnSettings);

            final LinearLayout container = a.containerSettingsItems;
            container.postDelayed(() -> {
                if (container.getChildCount() > 0) {
                    container.getChildAt(container.getChildCount() - 1).requestFocus();
                }
            }, 50);

        }
        // ==========================================================
        // ⚙️ [Mode 2] Settings sub-page mode (existing logic fully preserved)
        // ==========================================================
        else {
            Button btnClose = a.createListButton(a.t("Close Settings"));
            btnClose.setTextColor(0xFFFF8800);
            btnClose.setOnClickListener(v -> {
                a.clickFeedback();
                a.isRadioSettingsMode = false;
                a.isRadioAdjustingFreq = false;
                build(a);
            });
            a.containerSettingsItems.addView(btnClose);

            final LinearLayout btnPower = a.createSettingRow("Radio Power", fmManager.isPowerUp ? a.t("ON") : a.t("OFF"));
            btnPower.setOnLongClickListener(a.globalScreenOffLongClickListener);
            btnPower.setOnClickListener(v -> {
                a.clickFeedback();
                if (fmManager.isPowerUp) {
                    fmManager.powerDown();
                    a.isRadioAdjustingFreq = false;
                    a.updateGlobalStatusPlayIcon();
                    build(a);
                } else {
                    AudioPlayerManager am = AudioPlayerManager.getInstance();
                    if (am.isPlaying()) am.playOrPauseMusic();
                    // Give playback a moment to actually pause before the FM chip claims the audio
                    // session; posted with a delay instead of Thread.sleep so the UI thread isn't blocked.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        fmManager.powerUpAsync(fmManager.currentFreq, success -> {
                            if (success) a.activePlayer = 1;
                            else Toast.makeText(a, "Radio Error: " + fmManager.lastError, Toast.LENGTH_LONG).show();
                            a.updateGlobalStatusPlayIcon();
                            build(a);
                        });
                    }, 100);
                }
            });
            a.containerSettingsItems.addView(btnPower);

            String freqRightText = a.isRadioAdjustingFreq ? a.t("[ ADJUSTING ]") : a.t("Click to Tune");
            final LinearLayout btnTune = a.createSettingRow("Tune Frequency", freqRightText);
            if (a.isRadioAdjustingFreq) ((TextView) btnTune.getChildAt(1)).setTextColor(0xFFFF8800);
            btnTune.setOnLongClickListener(a.globalScreenOffLongClickListener);
            btnTune.setOnClickListener(v -> {
                a.clickFeedback();
                a.isRadioAdjustingFreq = !a.isRadioAdjustingFreq;
                build(a);
            });
            a.containerSettingsItems.addView(btnTune);

            boolean isSaved = a.savedRadioStations.contains(fmManager.currentFreq);
            final LinearLayout btnSaveFreq = a.createSettingRow("Save Channel", isSaved ? "★ " + a.t("SAVED") : "☆ " + a.t("SAVE"));
            if (isSaved) ((TextView) btnSaveFreq.getChildAt(1)).setTextColor(0xFFFF8800);
            btnSaveFreq.setOnLongClickListener(a.globalScreenOffLongClickListener);
            btnSaveFreq.setOnClickListener(v -> {
                a.clickFeedback();
                if (isSaved) {
                    a.savedRadioStations.remove(Float.valueOf(fmManager.currentFreq));
                    Toast.makeText(a, a.t("Removed from saved channels."), Toast.LENGTH_SHORT).show();
                } else {
                    a.savedRadioStations.add(fmManager.currentFreq);
                    Collections.sort(a.savedRadioStations);
                    Toast.makeText(a, a.t("Channel saved!"), Toast.LENGTH_SHORT).show();
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.savedRadioStations.size(); i++) {
                    sb.append(a.savedRadioStations.get(i));
                    if (i < a.savedRadioStations.size() - 1) sb.append(",");
                }
                a.prefs.edit().putString("radio_stations", sb.toString()).apply();
                build(a);
            });
            a.containerSettingsItems.addView(btnSaveFreq);

            final LinearLayout btnAutoScan = a.createSettingRow("Auto Scan All", a.t("Start") + " >");
            btnAutoScan.setOnLongClickListener(a.globalScreenOffLongClickListener);
            btnAutoScan.setOnClickListener(v -> {
                a.clickFeedback();
                if (!fmManager.isPowerUp) {
                    Toast.makeText(a, a.t("Please turn on Radio Power first!"), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (a.isRadioScanning) return;

                a.isRadioScanning = true;
                a.showLoadingPopup();

                new Thread(() -> {
                    float fakeFreq = 87.5f;
                    int progress = 0;
                    while (a.isRadioScanning) {
                        final int p = progress;
                        final float f = fakeFreq;
                        a.runOnUiThread(() -> {
                            if (a.pbLoadingProgress != null) {
                                a.pbLoadingProgress.setIndeterminate(false);
                                a.pbLoadingProgress.setProgress(p);
                            }
                            if (a.tvLoadingProgress != null) {
                                a.tvLoadingProgress.setText(String.format(Locale.US, a.t("Scanning FM Frequencies...\nSearching around %.1f MHz"), f));
                            }
                        });
                        try { Thread.sleep(70); } catch (Exception e) { Log.d(TAG, "build failed", e); }
                        progress += 1;
                        if (progress > 100) progress = 0;
                        fakeFreq += 0.1f;
                        if (fakeFreq > 108.0f) fakeFreq = 87.5f;
                    }
                }).start();

                new Thread(() -> {
                    final float[] foundStations = fmManager.autoScan();
                    a.isRadioScanning = false;

                    a.runOnUiThread(() -> {
                        if (a.layoutLoadingOverlay != null) a.layoutLoadingOverlay.setVisibility(View.GONE);
                        if (a.tvLoadingProgress != null) a.tvLoadingProgress.setText(a.t("Preparing to scan...\nPlease wait."));

                        if (foundStations != null && foundStations.length > 0) {
                            a.savedRadioStations.clear();
                            for (float f : foundStations) a.savedRadioStations.add(f);
                            Collections.sort(a.savedRadioStations);

                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < a.savedRadioStations.size(); i++) {
                                sb.append(a.savedRadioStations.get(i));
                                if (i < a.savedRadioStations.size() - 1) sb.append(",");
                            }
                            a.prefs.edit().putString("radio_stations", sb.toString()).apply();
                            Toast.makeText(a, a.t("Scan Complete!\nFound") + " " + foundStations.length + a.t("channels.\nTuning to") + " " + foundStations[0] + "MHz", Toast.LENGTH_LONG).show();
                            fmManager.tune(foundStations[0]);
                        } else {
                            Toast.makeText(a, a.t("No stations found.") + " (" + fmManager.lastError + ")", Toast.LENGTH_LONG).show();
                        }
                        build(a);
                    });
                }).start();
            });
            a.containerSettingsItems.addView(btnAutoScan);

            final LinearLayout btnSpeaker = a.createSettingRow("Audio Output", fmManager.isSpeakerOn ? a.t("Speaker") : a.t("Earphones"));
            btnSpeaker.setOnLongClickListener(a.globalScreenOffLongClickListener);
            btnSpeaker.setOnClickListener(v -> {
                a.clickFeedback();
                fmManager.setSpeaker(!fmManager.isSpeakerOn);
                build(a);
            });
            a.containerSettingsItems.addView(btnSpeaker);

            final LinearLayout container = a.containerSettingsItems;
            container.postDelayed(() -> {
                int targetIdx = a.lastRadioFocusIndex;
                if (a.isRadioAdjustingFreq) {
                    targetIdx = 2;
                }
                if (targetIdx >= 0 && targetIdx < container.getChildCount()) {
                    container.getChildAt(targetIdx).requestFocus();
                } else if (container.getChildCount() > 0) {
                    container.getChildAt(0).requestFocus();
                }
            }, 50);
        }
    }

    // 🚀 [New engine] Full-screen popup controller for real-time wheel-driven frequency adjustment
    private final Handler radioFreqHandler = new Handler();
    private MainActivity freqPopupActivity;
    private final Runnable hideRadioFreqTask = new Runnable() {
        @Override
        public void run() {
            MainActivity a = freqPopupActivity;
            if (a != null && a.layoutLoadingOverlay != null) {
                a.layoutLoadingOverlay.setVisibility(View.GONE); // Close the popup
                if (a.pbLoadingProgress != null) a.pbLoadingProgress.setVisibility(View.VISIBLE); // Restore the progress bar's original state
            }
        }
    };

    public void showFreqPopup(MainActivity a, float freq) {
        if (a.layoutLoadingOverlay != null) {
            freqPopupActivity = a;
            radioFreqHandler.removeCallbacks(hideRadioFreqTask);

            // 🚀 [Fix 1] Invisible-man bug fixed: force-restore the opacity back to 100% after the virtual blackout mode had set it to 0%!
            a.layoutLoadingOverlay.setAlpha(1.0f);
            a.layoutLoadingOverlay.setVisibility(View.VISIBLE);

            if (a.pbLoadingProgress != null) {
                a.pbLoadingProgress.setVisibility(View.VISIBLE);
                int progress = (int) (((freq - 87.5f) / 20.5f) * 100);
                a.pbLoadingProgress.setProgress(progress);
            }

            if (a.tvLoadingProgress != null) {
                a.tvLoadingProgress.setTextSize(24f);
                // 🚀 [Fix 2] Revive the text-output engine that had been accidentally commented out (//) and left dormant!
                a.tvLoadingProgress.setText(String.format(Locale.US, a.t("Tuning Frequency...\n\n%.1f MHz"), freq));
            }

            radioFreqHandler.postDelayed(hideRadioFreqTask, 1500);
        }
    }

    /** Call from MainActivity.onDestroy(). */
    public void cancelPendingReset() {
        radioFreqHandler.removeCallbacks(hideRadioFreqTask);
    }
}
