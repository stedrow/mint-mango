package com.mintmango.y2.managers;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import com.mintmango.y2.MainActivity;
import com.mintmango.y2.R;
import com.mintmango.y2.ThemeManager;
import com.mintmango.y2.views.EqSliderView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds every screen in the Settings tree: the depth-0 group list, all six group screens
 * (Playback/Sound & Vibration/Connectivity/Display & Interface/Storage & Library/System), and
 * their leaf sub-pages (theme selector, language selector, update checker, vibration, widgets,
 * background picker, date/time, equalizer). Extracted verbatim from MainActivity per
 * GOD_ACTIVITY_EXTRACTION.md.
 *
 * Like FmRadioUiManager and KeyEventRouter, this subsystem has no clean field boundary --
 * nearly every setting reads or writes some other subsystem's state directly -- so it takes the
 * MainActivity instance as a parameter rather than owning any of this state itself. A number of
 * MainActivity fields/methods had their visibility widened from private to public so this class
 * can reach them (compile-error driven, no behavior changes). MainActivity keeps thin
 * pass-through methods under the original names for every method here, since these screens and
 * routeBackToSettingsGroup() all call each other recursively by name.
 */
public class SettingsUiManager {
    private static final String TAG = "SettingsUiManager";
    private static SettingsUiManager instance;

    private SettingsUiManager() {}

    public static synchronized SettingsUiManager getInstance() {
        if (instance == null) {
            instance = new SettingsUiManager();
        }
        return instance;
    }

    public void buildThemeSelectorUI(MainActivity a) {
        a.currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        File themeFolder = new File("/storage/sdcard0/Y1_Themes");
        ThemeManager.loadThemesFromStorage(themeFolder);

        a.containerSettingsItems.removeAllViews();

        // Turn each theme read from the SD card folder into a button.
        for (int i = 0; i < ThemeManager.availableThemes.size(); i++) {
            final int index = i;
            ThemeManager.ThemeData theme = ThemeManager.availableThemes.get(i);

            String prefix = (ThemeManager.getCurrentThemeIndex() == i) ? "✔ " : "   ";
            Button btn = a.createListButton(prefix + theme.name);

            if (ThemeManager.getCurrentThemeIndex() == i) {
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
                btn.setTextColor(0xFF00FF00); // Highlight the currently active theme in bold green!
            }

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    ThemeManager.setThemeIndex(index);
                    try {
                        SharedPreferences.Editor editor = a.prefs.edit();
                        editor.putInt("app_theme_index", index);
                        // Folder path survives a rescan even if listFiles() order shifts between
                        // boots (index alone doesn't -- see ThemeManager.resolveSavedThemeIndex).
                        editor.putString("app_theme_folder", theme.folderPath);
                        editor.putBoolean("reboot_to_theme", true);

                        // 🚀 [Smart automation] Scans the selected theme's parts (JSON) and automatically toggles the widget switches!
                        boolean hasClock = false, hasAnalog = false, hasBattery = false, hasCircular = false, hasAlbum = false, hasFocusImage = false; // 🚀 Added variable

                        for (ThemeManager.MenuElement el : theme.menuElements) {
                            if ("widget_clock".equals(el.type)) hasClock = true;
                            if ("widget_analog_clock".equals(el.type)) hasAnalog = true;
                            if ("widget_battery".equals(el.type)) hasBattery = true;
                            if ("widget_circular_battery".equals(el.type)) hasCircular = true;
                            if ("widget_album".equals(el.type)) hasAlbum = true;
                            if ("widget_focus_image".equals(el.type)) hasFocusImage = true; // 🚀 Added check
                        }

                        // Force-sync widgets included in the theme to 'ON' and ones not included to 'OFF'!
                        editor.putBoolean("widget_clock", hasClock);
                        editor.putBoolean("widget_analog_clock", hasAnalog);
                        editor.putBoolean("widget_battery", hasBattery);
                        editor.putBoolean("widget_circular_battery", hasCircular);
                        editor.putBoolean("widget_album", hasAlbum);
                        editor.putBoolean("widget_focus_image", hasFocusImage); // 🚀 Added save

                        editor.apply(); // Settings saved
                    } catch (Exception e) {
                        Log.d(TAG, "buildThemeSelectorUI failed", e);
                    }

                    a.recreate(); // Refresh the screen! (the new widget settings take effect immediately)
                }
            });
            a.containerSettingsItems.addView(btn);
        }

        a.containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Fixed the bug where focus escaped to index 1 (the second item), and went further to find and focus the theme I just selected!
                int selectedIdx = ThemeManager.getCurrentThemeIndex();

                if (a.containerSettingsItems.getChildCount() > selectedIdx && selectedIdx >= 0) {
                    a.containerSettingsItems.getChildAt(selectedIdx).requestFocus();
                }
                // If an error occurs for any reason, safely fall back to focusing index 0 (the first item).
                else if (a.containerSettingsItems.getChildCount() > 0) {
                    a.containerSettingsItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    }

    public void buildSettingsUI(MainActivity a) {
        a.currentSettingsDepth = 0; // 🚀 Main settings is depth 0

        // 🚀 [Safeguard] Fully clears the radio UI flag when entering the general settings screen.
        a.isRadioUIShowing = false;
        a.isRadioSettingsMode = false;

        // 🚀 [Added] Show the hidden top title text again when returning to the general settings screen.
        android.view.ViewGroup settingsGroup = (android.view.ViewGroup) a.layoutSettingsMode;
        if (settingsGroup != null && settingsGroup.getChildCount() > 0 && settingsGroup.getChildAt(0) instanceof TextView) {
            settingsGroup.getChildAt(0).setVisibility(View.VISIBLE);
        }

        final int targetFocusIndex = a.lastSettingsFocusIndex;
        a.containerSettingsItems.removeAllViews();

        LinearLayout btnGroupPlayback = a.createSettingRow(a.t("Playback"), "〉 ");
        btnGroupPlayback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildPlaybackGroupUI(a);
            }
        });
        a.containerSettingsItems.addView(btnGroupPlayback);

        LinearLayout btnGroupSound = a.createSettingRow(a.t("Sound & Vibration"), "〉 ");
        btnGroupSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildSoundVibrationGroupUI(a);
            }
        });
        a.containerSettingsItems.addView(btnGroupSound);

        LinearLayout btnGroupConnectivity = a.createSettingRow(a.t("Connectivity"), "〉 ");
        btnGroupConnectivity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildConnectivityGroupUI(a);
            }
        });
        a.containerSettingsItems.addView(btnGroupConnectivity);

        LinearLayout btnGroupDisplay = a.createSettingRow(a.t("Display & Interface"), "〉 ");
        btnGroupDisplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildDisplayInterfaceGroupUI(a);
            }
        });
        a.containerSettingsItems.addView(btnGroupDisplay);

        LinearLayout btnGroupStorage = a.createSettingRow(a.t("Storage & Library"), "〉 ");
        btnGroupStorage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildStorageLibraryGroupUI(a);
            }
        });
        a.containerSettingsItems.addView(btnGroupStorage);

        LinearLayout btnGroupSystem = a.createSettingRow(a.t("System"), "〉 ");
        btnGroupSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildSystemGroupUI(a);
            }
        });
        a.containerSettingsItems.addView(btnGroupSystem);

        // 🚀 [Fix] Force-move to the correct position using the uncorrupted, safely backed-up index (targetFocusIndex)!
        a.containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (targetFocusIndex >= 0 && targetFocusIndex < a.containerSettingsItems.getChildCount()) {
                    View target = a.containerSettingsItems.getChildAt(targetFocusIndex);
                    target.requestFocus();

                    // Force the ScrollView to find that button's position and scroll all the way down to it!
                    if (a.containerSettingsItems.getParent() instanceof android.widget.ScrollView) {
                        ((android.widget.ScrollView) a.containerSettingsItems.getParent())
                                .requestChildFocus(a.containerSettingsItems, target);
                    }

                    // Sync the variable state after the move is complete.
                    a.lastSettingsFocusIndex = targetFocusIndex;
                } else if (a.containerSettingsItems.getChildCount() > 0) {
                    a.containerSettingsItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    }

    public void routeBackToSettingsGroup(MainActivity a) {
        switch (a.currentSettingsGroup) {
            case MainActivity.GROUP_PLAYBACK: buildPlaybackGroupUI(a); break;
            case MainActivity.GROUP_SOUND: buildSoundVibrationGroupUI(a); break;
            case MainActivity.GROUP_CONNECTIVITY: buildConnectivityGroupUI(a); break;
            case MainActivity.GROUP_DISPLAY: buildDisplayInterfaceGroupUI(a); break;
            case MainActivity.GROUP_STORAGE: buildStorageLibraryGroupUI(a); break;
            case MainActivity.GROUP_SYSTEM: buildSystemGroupUI(a); break;
            default: buildSettingsUI(a);
        }
    }

    public void buildPlaybackGroupUI(MainActivity a) {
        a.currentSettingsDepth = 1;
        a.currentSettingsGroup = a.GROUP_PLAYBACK;
        a.containerSettingsItems.removeAllViews();

        final LinearLayout btnShuffle = a.createSettingRow("Shuffle Mode", a.isShuffleMode ? a.t("ON") : a.t("OFF"));
        btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isShuffleMode = !a.isShuffleMode;
                TextView tvStatus = (TextView) btnShuffle.getChildAt(1);
                tvStatus.setText(a.isShuffleMode ? a.t("ON") : a.t("OFF"));
                a.updatePlayerStatusIndicators();
                try {
                    a.prefs.edit().putBoolean("shuffle", a.isShuffleMode).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildPlaybackGroupUI failed", e);
                }

                if (!a.currentPlaylist.isEmpty() && !a.originalPlaylist.isEmpty()) {
                    File currentSong = a.currentPlaylist.get(a.currentIndex);
                    if (a.isShuffleMode) {
                        java.util.Collections.shuffle(a.currentPlaylist);
                    } else {
                        a.currentPlaylist.clear();
                        a.currentPlaylist.addAll(a.originalPlaylist);
                    }
                    a.currentIndex = a.currentPlaylist.indexOf(currentSong);
                    if (a.currentIndex == -1)
                        a.currentIndex = 0;
                }
            }
        });
        a.containerSettingsItems.addView(btnShuffle);

        final LinearLayout btnRepeat = a.createSettingRow("Repeat Mode", a.t(a.getRepeatModeText(a.repeatMode)));
        btnRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.repeatMode = (a.repeatMode + 1) % 3;
                TextView tvStatus = (TextView) btnRepeat.getChildAt(1);
                tvStatus.setText(a.t(a.getRepeatModeText(a.repeatMode)));
                a.updatePlayerStatusIndicators();
                try {
                    a.prefs.edit().putInt("repeat_mode", a.repeatMode).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildPlaybackGroupUI failed", e);
                }
            }
        });
        a.containerSettingsItems.addView(btnRepeat);

        String eqDisplayName = "Normal";
        if (a.currentEqProfile.startsWith("preset_")) {
            int pIdx = Integer.parseInt(a.currentEqProfile.replace("preset_", ""));
            if (pIdx < a.eqPresetNames.size()) eqDisplayName = a.t(a.eqPresetNames.get(pIdx));
        } else {
            eqDisplayName = a.currentEqProfile.replace("custom_", "");
        }
        final LinearLayout btnEq = a.createSettingRow("Equalizer & Audio Effects", eqDisplayName + " 〉");

        btnEq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildEqualizerSettingsUI(a);
            }
        });
        a.containerSettingsItems.addView(btnEq);

        final String[] speedLabels = {"1.0x (Normal)", "1.2x (Fast)", "1.5x (Faster)", "2.0x (Very Fast)"};
        final float[] speedValues = {1.0f, 1.2f, 1.5f, 2.0f};

        float currentSpd = com.mintmango.y2.managers.AudioPlayerManager.getInstance().getCurrentSpeed();
        int spdIdx = 0;
        for (int i=0; i<speedValues.length; i++) { if (speedValues[i] == currentSpd) spdIdx = i; }

        final LinearLayout btnSpeed = a.createSettingRow("Playback Speed", a.t(speedLabels[spdIdx]));
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                float current = com.mintmango.y2.managers.AudioPlayerManager.getInstance().getCurrentSpeed();
                int nextIdx = 0;
                for (int i=0; i<speedValues.length; i++) { if (speedValues[i] == current) nextIdx = (i + 1) % speedValues.length; }

                com.mintmango.y2.managers.AudioPlayerManager.getInstance().setPlaybackSpeed(speedValues[nextIdx]);

                TextView tvStatus = (TextView) btnSpeed.getChildAt(1);
                tvStatus.setText(a.t(speedLabels[nextIdx]));
                android.widget.Toast.makeText(a, a.t("Speed set to ") + a.t(speedLabels[nextIdx]), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        a.containerSettingsItems.addView(btnSpeed);

        final LinearLayout btnAutoFetch = a.createSettingRow("Auto Fetch Album Art", a.isAutoFetchEnabled ? a.t("ON") : a.t("OFF"));
        btnAutoFetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isAutoFetchEnabled = !a.isAutoFetchEnabled;
                ((TextView) btnAutoFetch.getChildAt(1)).setText(a.isAutoFetchEnabled ? a.t("ON") : a.t("OFF"));
                try {
                    a.prefs.edit().putBoolean("auto_fetch", a.isAutoFetchEnabled).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildPlaybackGroupUI failed", e);
                }
            }
        });
        a.containerSettingsItems.addView(btnAutoFetch);

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildSoundVibrationGroupUI(MainActivity a) {
        a.currentSettingsDepth = 1;
        a.currentSettingsGroup = a.GROUP_SOUND;
        a.containerSettingsItems.removeAllViews();

        final LinearLayout btnSound = a.createSettingRow("Button Sound", a.isSoundEffectEnabled ? a.t("ON") : a.t("OFF"));
        btnSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.isSoundEffectEnabled = !a.isSoundEffectEnabled;
                a.applySoundSetting();
                a.clickFeedback();
                TextView tvStatus = (TextView) btnSound.getChildAt(1);
                tvStatus.setText(a.isSoundEffectEnabled ? a.t("ON") : a.t("OFF"));
                try {
                    a.prefs.edit().putBoolean("sound", a.isSoundEffectEnabled).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildSoundVibrationGroupUI failed", e);
                }
            }
        });
        a.containerSettingsItems.addView(btnSound);

        final LinearLayout btnSpeakerDisable = a.createSettingRow("Disable Built-in Speaker", a.isSpeakerDisabled ? a.t("ON") : a.t("OFF"));
        btnSpeakerDisable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.isSpeakerDisabled = !a.isSpeakerDisabled;
                a.applySpeakerSetting();
                a.clickFeedback();
                TextView tvStatus = (TextView) btnSpeakerDisable.getChildAt(1);
                tvStatus.setText(a.isSpeakerDisabled ? a.t("ON") : a.t("OFF"));
                try {
                    a.prefs.edit().putBoolean("speaker_disabled", a.isSpeakerDisabled).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildSoundVibrationGroupUI failed", e);
                }
            }
        });
        a.containerSettingsItems.addView(btnSpeakerDisable);

        LinearLayout btnVibrateMenu = a.createSettingRow("Vibration", "〉 ");
        btnVibrateMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildVibrationSettingsUI(a);
            }
        });
        a.containerSettingsItems.addView(btnVibrateMenu);

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildConnectivityGroupUI(MainActivity a) {
        a.currentSettingsDepth = 1;
        a.currentSettingsGroup = a.GROUP_CONNECTIVITY;
        a.containerSettingsItems.removeAllViews();

        LinearLayout btnWifiMenu = a.createSettingRow(a.t("Wi-Fi"), "〉 ");
        btnWifiMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.changeScreen(a.STATE_WIFI);
                a.clickFeedback();
            }
        });
        a.containerSettingsItems.addView(btnWifiMenu);

        LinearLayout btnBtMenu = a.createSettingRow("Bluetooth", "〉 ");
        btnBtMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.changeScreen(a.STATE_BLUETOOTH);
                a.clickFeedback();
            }
        });
        a.containerSettingsItems.addView(btnBtMenu);

        LinearLayout btnServerMenu = a.createSettingRow(a.t("Web Server"), "〉 ");
        btnServerMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.changeScreen(a.STATE_WEBSERVER);
                a.clickFeedback();
            }
        });
        a.containerSettingsItems.addView(btnServerMenu);

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildDisplayInterfaceGroupUI(MainActivity a) {
        a.currentSettingsDepth = 1;
        a.currentSettingsGroup = a.GROUP_DISPLAY;
        a.containerSettingsItems.removeAllViews();

        final LinearLayout btnTheme = a.createSettingRow("Theme", ThemeManager.getCurrentTheme().name);
        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildThemeSelectorUI(a);
            }
        });
        a.containerSettingsItems.addView(btnTheme);

        LinearLayout btnBgMenu = a.createSettingRow("Background", "〉 ");
        btnBgMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildBackgroundSettingsUI(a);
            }
        });
        a.containerSettingsItems.addView(btnBgMenu);

        final LinearLayout btnMenuVisibility = a.createSettingRow("Main Menu Items", a.t("Edit") + " 〉");
        btnMenuVisibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildMainMenuVisibilitySettingsUI(a);

                a.containerSettingsItems.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (a.containerSettingsItems.getChildCount() > 0) {
                            a.containerSettingsItems.getChildAt(0).requestFocus();
                        }
                    }
                }, 50);
            }
        });
        a.containerSettingsItems.addView(btnMenuVisibility);

        final LinearLayout btnScreenOffCtrl = a.createSettingRow("Screen-Off Control",
                a.isScreenOffControlEnabled ? a.t("ON") : a.t("OFF"));
        btnScreenOffCtrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isScreenOffControlEnabled = !a.isScreenOffControlEnabled;
                TextView tvStatus = (TextView) btnScreenOffCtrl.getChildAt(1);
                tvStatus.setText(a.isScreenOffControlEnabled ? a.t("ON") : a.t("OFF"));
                try {
                    a.prefs.edit().putBoolean("screen_off_control", a.isScreenOffControlEnabled).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildDisplayInterfaceGroupUI failed", e);
                }
            }
        });
        a.containerSettingsItems.addView(btnScreenOffCtrl);

        final LinearLayout btnBatteryPercent = a.createSettingRow("Show Battery %",
                a.isShowBatteryPercent ? a.t("ON") : a.t("OFF"));
        btnBatteryPercent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isShowBatteryPercent = !a.isShowBatteryPercent;
                TextView tvStatus = (TextView) btnBatteryPercent.getChildAt(1);
                tvStatus.setText(a.isShowBatteryPercent ? a.t("ON") : a.t("OFF"));
                if (a.batteryIconView != null) a.batteryIconView.setShowPercent(a.isShowBatteryPercent);
                try {
                    a.prefs.edit().putBoolean("show_battery_percent", a.isShowBatteryPercent).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildDisplayInterfaceGroupUI failed", e);
                }
            }
        });
        a.containerSettingsItems.addView(btnBatteryPercent);


        final LinearLayout btnLoopScrollToggle = a.createSettingRow("Wheel Loop Scroll", a.isLoopScrollOn ? a.t("ON") : a.t("OFF"));
        btnLoopScrollToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isLoopScrollOn = !a.isLoopScrollOn;
                ((TextView) btnLoopScrollToggle.getChildAt(1)).setText(a.isLoopScrollOn ? a.t("ON") : a.t("OFF"));
                a.prefs.edit().putBoolean("loop_scroll_on", a.isLoopScrollOn).apply();
            }
        });
        a.containerSettingsItems.addView(btnLoopScrollToggle);

        final LinearLayout btnTimeout = a.createSettingRow("Screen Timeout", a.t(a.TIMEOUT_NAMES[a.currentTimeoutIndex]));
        btnTimeout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.currentTimeoutIndex = (a.currentTimeoutIndex + 1) % a.TIMEOUT_VALUES.length;
                ((TextView) btnTimeout.getChildAt(1)).setText(a.t(a.TIMEOUT_NAMES[a.currentTimeoutIndex]));

                try {
                    Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                            a.TIMEOUT_VALUES[a.currentTimeoutIndex]);
                } catch (Exception e) {
                    Log.d(TAG, "buildDisplayInterfaceGroupUI failed", e);
                }
                try {
                    a.prefs.edit().putInt("timeout_idx", a.currentTimeoutIndex).apply();
                } catch (Exception e) {
                    Log.d(TAG, "buildDisplayInterfaceGroupUI failed", e);
                }
            }
        });
        a.containerSettingsItems.addView(btnTimeout);

        LinearLayout btnBrightMenu = a.createSettingRow("Display Brightness", "〉 ");
        btnBrightMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.changeScreen(a.STATE_BRIGHTNESS);
                a.clickFeedback();
            }
        });
        a.containerSettingsItems.addView(btnBrightMenu);

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildStorageLibraryGroupUI(MainActivity a) {
        a.currentSettingsDepth = 1;
        a.currentSettingsGroup = a.GROUP_STORAGE;
        a.containerSettingsItems.removeAllViews();

        LinearLayout btnStorageMenu = a.createSettingRow("Storage", "〉 ");
        btnStorageMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.changeScreen(a.STATE_STORAGE);
                a.clickFeedback();
            }
        });
        a.containerSettingsItems.addView(btnStorageMenu);

        LinearLayout btnClearCache = a.createSettingRow("Clear Album Art & Info", "〉 ");
        btnClearCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(a.t("Clear Cache & Track Info"))
                        .setMessage(a.t("Delete all downloaded album covers and saved track information (Title/Artist)?"))
                        .setPositiveButton(a.t("Clear"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    File coverFolder = new File("/storage/sdcard0/Y1_Covers");
                                    int count = 0;
                                    if (coverFolder.exists()) {
                                        File[] files = coverFolder.listFiles();
                                        if (files != null) {
                                            for (File f : files) {
                                                if (f.isFile() && f.delete())
                                                    count++;
                                            }
                                        }
                                    }

                                    a.libraryCacheDb.clearAllMetaAndArt();

                                    Toast.makeText(a, "Deleted " + count + " covers & cleared track info.",
                                            Toast.LENGTH_SHORT).show();

                                    a.ivAlbumArt.setImageResource(R.drawable.default_album);
                                    a.ivPlayerBgBlur.setImageResource(0);
                                    a.lastAlbumArtBytes = null;

                                    if (!a.currentPlaylist.isEmpty()) {
                                        File currentFile = a.currentPlaylist.get(a.currentIndex);
                                        a.tvPlayerTitle.setText(currentFile.getName());
                                        a.tvPlayerArtist.setText("Unknown Artist");
                                    }

                                    a.updateMainMenuBackground();
                                    a.refreshNowPlayingPreview();
                                } catch (Exception e) {
                                    Toast.makeText(a, "Failed to clear cache.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton(a.t("Cancel"), null)
                        .show();
            }
        });
        a.containerSettingsItems.addView(btnClearCache);

        LinearLayout btnRebuildCache = a.createSettingRow("Rebuild Library Cache", "〉 ");
        btnRebuildCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(a.t("Rebuild Library Cache"))
                        .setMessage(a.t("Re-scan every song and re-read its tags from scratch? This is slower than a normal scan but fixes a stale or incorrect cache."))
                        .setPositiveButton(a.t("Rebuild"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (a.libraryCacheDb != null) a.libraryCacheDb.clear();
                                a.startMediaLibraryScan();
                            }
                        })
                        .setNegativeButton(a.t("Cancel"), null)
                        .show();
            }
        });
        a.containerSettingsItems.addView(btnRebuildCache);

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    /**
     * Our stand-in for the framework's long-press-power menu -- see PowerMenuInterceptor for how
     * it gets triggered. Same two power entries the stock menu has, plus the two radios, since
     * those are what anyone actually reaches for on this device.
     */
    public void showPowerMenu(final MainActivity a) {
        // ponytail: no confirmation step -- the stock menu it replaces doesn't have one either.
        com.mintmango.y2.managers.SongContextMenuManager.getInstance().showThemedOptionsDialog(
                a, null, null,
                new String[]{
                        "", // power_settings_new
                        "", // refresh
                        "", // wifi
                        "", // bluetooth
                        "" // settings
                },
                new String[]{ a.t("Power Off"), a.t("Reboot"), a.t("Wi-Fi"), "Bluetooth", a.t("Settings") },
                new Runnable[]{
                        new Runnable() { @Override public void run() {
                            powerAction(a, "reboot -p", "android.intent.action.ACTION_REQUEST_SHUTDOWN",
                                    a.t("System security prevents powering off directly from the app."));
                        } },
                        new Runnable() { @Override public void run() {
                            powerAction(a, "reboot", "android.intent.action.ACTION_REQUEST_REBOOT",
                                    a.t("System security prevents rebooting directly from the app."), true);
                        } },
                        new Runnable() { @Override public void run() { a.changeScreen(a.STATE_WIFI); } },
                        new Runnable() { @Override public void run() { a.changeScreen(a.STATE_BLUETOOTH); } },
                        new Runnable() { @Override public void run() { a.changeScreen(a.STATE_SETTINGS); } }
                });
    }

    private void powerAction(final MainActivity a, final String suCommand, final String fallbackAction,
            final String failureMessage) {
        powerAction(a, suCommand, fallbackAction, failureMessage, false);
    }

    private void powerAction(final MainActivity a, final String suCommand, final String fallbackAction,
            final String failureMessage, final boolean tryGracefulReboot) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (tryGracefulReboot) {
                    try {
                        // PowerManager.reboot() plays Android's normal graceful shutdown animation;
                        // "su -c reboot" resets the kernel directly and flickers the screen on its way down.
                        android.os.PowerManager pm = (android.os.PowerManager) a.getSystemService(android.content.Context.POWER_SERVICE);
                        pm.reboot(null);
                        return;
                    } catch (Exception e) {
                        Log.d(TAG, "graceful reboot failed, falling back to su", e);
                    }
                }
                try {
                    Process proc = Runtime.getRuntime().exec(new String[] { "su", "-c", suCommand });
                    proc.waitFor();
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(fallbackAction);
                        intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(intent);
                    } catch (Exception ex) {
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(a, failureMessage, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }
        }).start();
    }

    public void buildSystemGroupUI(MainActivity a) {
        a.currentSettingsDepth = 1;
        a.currentSettingsGroup = a.GROUP_SYSTEM;
        a.containerSettingsItems.removeAllViews();

        LinearLayout btnTime = a.createSettingRow("Date & Time", "〉");
        btnTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();

                java.util.Calendar c = java.util.Calendar.getInstance();
                a.dtYear = c.get(java.util.Calendar.YEAR);
                a.dtMonth = c.get(java.util.Calendar.MONTH) + 1;
                a.dtDay = c.get(java.util.Calendar.DAY_OF_MONTH);
                a.dtHour = c.get(java.util.Calendar.HOUR_OF_DAY);
                a.dtMinute = c.get(java.util.Calendar.MINUTE);

                buildDateTimeUI(a);
            }
        });
        a.containerSettingsItems.addView(btnTime);

        String myVersionName = "1.0";
        try {
            myVersionName = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            Log.d(TAG, "buildSystemGroupUI failed", e);
        }
        String displayLang = com.mintmango.y2.managers.LanguageManager.getInstance(a).currentLangFileName.replace(".json", "");
        LinearLayout btnLangMenu = a.createSettingRow("Language", displayLang);
        btnLangMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildLanguageSelectorUI(a);
            }
        });
        a.containerSettingsItems.addView(btnLangMenu);

        LinearLayout btnUpdateCheck = a.createSettingRow("System Update", "v" + myVersionName);
        btnUpdateCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildUpdateSettingsUI(a);
            }
        });
        a.containerSettingsItems.addView(btnUpdateCheck);

        LinearLayout btnReboot = a.createSettingRow(a.t("Reboot"), "〉 ");
        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(a.t("Reboot"))
                        .setMessage(a.t("Do you want to reboot the device?"))
                        .setPositiveButton(a.t("Reboot"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                powerAction(a, "reboot", "android.intent.action.ACTION_REQUEST_REBOOT",
                                        a.t("System security prevents rebooting directly from the app."), true);
                            }
                        })
                        .setNegativeButton(a.t("Cancel"), null)
                        .show();
            }
        });
        a.containerSettingsItems.addView(btnReboot);

        LinearLayout btnPowerOff = a.createSettingRow("Power Off", "〉 ");
        btnPowerOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(a.t("Power Off"))
                        .setMessage(a.t("Do you want to shut down the device?"))
                        .setPositiveButton(a.t("Shut Down"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                powerAction(a, "reboot -p", "android.intent.action.ACTION_REQUEST_SHUTDOWN",
                                        a.t("System security prevents powering off directly from the app."));
                            }
                        })
                        .setNegativeButton(a.t("Cancel"), null)
                        .show();
            }
        });
        a.containerSettingsItems.addView(btnPowerOff);

        LinearLayout btnSwitchRockbox = a.createSettingRow(a.t("Switch to Rockbox"), "〉 ");
        btnSwitchRockbox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();

                boolean isRockboxInstalled = false;
                try {
                    a.getPackageManager().getPackageInfo("org.rockbox", 0);
                    isRockboxInstalled = true;
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    isRockboxInstalled = false;
                }

                if (!isRockboxInstalled) {
                        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(a.t("Not Installed ⚠️"))
                                .setMessage(a.t("Rockbox is not installed on this device.\nPlease install the Rockbox app (.apk) first."))
                                .setPositiveButton(a.t("OK"), null)
                            .show();
                    return;
                }

                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(a.t("Switch to Rockbox"))
                        .setMessage(a.t("Do you want to switch to Rockbox instantly without rebooting?"))
                        .setPositiveButton(a.t("Switch"), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Toast.makeText(a, "Switching to Rockbox...", Toast.LENGTH_SHORT).show();

                                    String cmd = "pm enable org.rockbox && am start -n org.rockbox/.RockboxActivity && pm disable com.mintmango.y2";

                                    Runtime.getRuntime().exec(new String[] { "su", "-c", cmd });

                                } catch (Exception e) {
                                    Log.w(TAG, "applying the date/time failed", e);
                    Toast.makeText(a, a.t("Could not set the clock."), Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton(a.t("Cancel"), null)
                        .show();
            }
        });
        a.containerSettingsItems.addView(btnSwitchRockbox);

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildLanguageSelectorUI(MainActivity a) {
        a.currentSettingsDepth = 2;
        a.containerSettingsItems.removeAllViews();

        final com.mintmango.y2.managers.LanguageManager langMgr = com.mintmango.y2.managers.LanguageManager.getInstance(a);
        langMgr.loadAvailableLanguages(); // Re-scan the folder

        // 1. Default English button
        String enPrefix = langMgr.currentLangFileName.equals("English (Default)") ? "✔ " : "   ";
        Button btnEng = a.createListButton(enPrefix + "English (Default)");
        if (langMgr.currentLangFileName.equals("English (Default)")) { btnEng.setTextColor(0xFF00FF00); }
        btnEng.setOnClickListener(v -> {
            a.clickFeedback();
            a.prefs.edit().putString("app_language", "English (Default)").apply();
            langMgr.applyLanguage("English (Default)");
            a.recreate(); // Fully refresh the screen to apply immediately!
        });
        a.containerSettingsItems.addView(btnEng);

        // 2. List the JSON language packs read in from external storage
        for (final File f : langMgr.availableLangFiles) {
            String prefix = langMgr.currentLangFileName.equals(f.getName()) ? "✔ " : "   ";
            Button btnLang = a.createListButton(prefix + f.getName().replace(".json", ""));
            if (langMgr.currentLangFileName.equals(f.getName())) { btnLang.setTextColor(0xFF00FF00); }

            btnLang.setOnClickListener(v -> {
                a.clickFeedback();
                a.prefs.edit().putString("app_language", f.getName()).apply();
                langMgr.applyLanguage(f.getName());
                a.recreate(); // Reboot the activity immediately when the language changes!
            });
            a.containerSettingsItems.addView(btnLang);
        }

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildUpdateSettingsUI(MainActivity a) {
        a.currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        a.containerSettingsItems.removeAllViews();

        // 1. Get my device's current version
        String myVersionName = "1.0";
        int tempCode = 1;
        try {
            android.content.pm.PackageInfo pInfo = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            myVersionName = pInfo.versionName;
            tempCode = pInfo.versionCode;
        } catch (Exception e) {
            Log.d(TAG, "buildUpdateSettingsUI failed", e);
        }

        final int myVersionCode = tempCode;

        // 2. Current version display line
        LinearLayout rowCurrent = a.createSettingRow("Current Version", "v" + myVersionName);
        a.containerSettingsItems.addView(rowCurrent);

        // 3. Server version display line (initially shows Checking...)
        final LinearLayout rowServer = a.createSettingRow("Latest Version", "Checking...");
        a.containerSettingsItems.addView(rowServer);

        a.createCategoryHeader("━━━━━━━━━━━━━━");

        // 4. Bottom update-execution button (hidden until the server check completes)
        final Button btnExecuteUpdate = a.createListButton("🚀 " + a.t("DOWNLOAD & UPDATE"));;
        btnExecuteUpdate.setVisibility(View.GONE);
        a.containerSettingsItems.addView(btnExecuteUpdate);

        // 🚀 5. As soon as the screen opens, read the server's output-metadata.json in the background!
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL(a.METADATA_URL);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();

                    // 🚀 [Required 1] Break through GitHub's security (TLS 1.2): equip the secret weapon we built!
                    if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                        try {
                            ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new com.mintmango.y2.managers.NetworkTrustManager.TLSSocketFactory());
                        } catch (Exception e) {
                            Log.d(TAG, "buildUpdateSettingsUI failed", e);
                        }
                    }

                    conn.setInstanceFollowRedirects(false); // Turn off the default auto-follow so we can track redirects manually
                    conn.setConnectTimeout(5000);

                    // 🚀 [Required 2] Chase GitHub redirects (address forwarding) all the way to the end!
                    int status = conn.getResponseCode();
                    if (status == 301 || status == 302 || status == 303) {
                        String newUrl = conn.getHeaderField("Location");
                        conn = (java.net.HttpURLConnection) new java.net.URL(newUrl).openConnection();
                        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                            try {
                                ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(new com.mintmango.y2.managers.NetworkTrustManager.TLSSocketFactory());
                            } catch (Exception e) {
                                Log.d(TAG, "buildUpdateSettingsUI failed", e);
                            }
                        }
                    }

                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null)
                        sb.append(line);
                    in.close();

                    org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                    org.json.JSONArray elements = root.getJSONArray("elements");
                    org.json.JSONObject element = elements.getJSONObject(0);

                    final int serverVersionCode = element.getInt("versionCode");
                    final String serverVersionName = element.getString("versionName");
                    final String apkFileName = element.getString("outputFile");

                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Update the server version text (e.g. Checking... -> v1.2)
                            TextView tvServer = (TextView) rowServer.getChildAt(1);
                            tvServer.setText("v" + serverVersionName);

                            // 🚀 [Comparison] When an update is needed
                            if (serverVersionCode > myVersionCode) {
                                tvServer.setTextColor(0xFF00FF00); // Make the server version an eye-catching green!

                                btnExecuteUpdate.setVisibility(View.VISIBLE);
                                btnExecuteUpdate.setTextColor(0xFFFFFFFF);
                                btnExecuteUpdate.setTypeface(null, android.graphics.Typeface.BOLD);
                                btnExecuteUpdate.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        a.clickFeedback();
                                        String downloadUrl = a.SERVER_BASE_URL + apkFileName;
                                        a.downloadAndInstallApk(downloadUrl); // Call the download engine
                                    }
                                });
                            }
                            // 🚀 [Comparison] When already on the latest version
                            else {
                                tvServer.setTextColor(ThemeManager.getTextColorSecondary());

                                btnExecuteUpdate.setText("✔ " + a.t("ALREADY UP TO DATE"));
                                btnExecuteUpdate.setVisibility(View.VISIBLE);
                                btnExecuteUpdate.setTextColor(ThemeManager.getTextColorSecondary());
                                btnExecuteUpdate.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        a.clickFeedback();
                                        Toast.makeText(a, a.t("You are using the latest version."),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }
                    });
                } catch (Exception e) {
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView tvServer = (TextView) rowServer.getChildAt(1);
                            tvServer.setText(a.t("Network Error"));
                            tvServer.setTextColor(0xFFFF4444); // Red error indicator
                        }
                    });
                }
            }
        }).start();

        // Automatically focuses the second button (Current Version) on entry
        if (a.containerSettingsItems.getChildCount() > 0) {
            a.containerSettingsItems.getChildAt(0).requestFocus();
        }
    }

    public void buildVibrationSettingsUI(MainActivity a) {
        a.currentSettingsDepth = 2; // Tell the system we've left the main settings screen
        a.containerSettingsItems.removeAllViews();

        // 1. Vibration power switch
        final LinearLayout btnToggle = a.createSettingRow("Vibration Power", a.isVibrationEnabled ? a.t("ON") : a.t("OFF"));
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.isVibrationEnabled = !a.isVibrationEnabled;
                a.clickFeedback();
                ((TextView) btnToggle.getChildAt(1)).setText(a.isVibrationEnabled ? a.t("ON") : a.t("OFF"));
                try { a.prefs.edit().putBoolean("vibrate", a.isVibrationEnabled).apply(); } catch (Exception e) { Log.d(TAG, "buildVibrationSettingsUI failed", e); }
            }
        });
        a.containerSettingsItems.addView(btnToggle);

        // 2. Vibration intensity switch (cycles Weak -> Normal -> Strong)
        final LinearLayout btnStrength = a.createSettingRow("Vibration Strength", a.vibrationStrengthLabel());
        btnStrength.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.vibrationStrengthLevel = (a.vibrationStrengthLevel + 1) % a.VIBE_DURATIONS.length;

                // 💡 Buzz at the level just selected, so each step can be felt as you cycle through.
                a.previewVibration();

                ((TextView) btnStrength.getChildAt(1)).setText(a.vibrationStrengthLabel());
                try { a.prefs.edit().putInt("vibrate_strength_5", a.vibrationStrengthLevel).apply(); } catch (Exception e) { Log.d(TAG, "buildVibrationSettingsUI failed", e); }
            }
        });
        a.containerSettingsItems.addView(btnStrength);

        // Focus the first button on entering the menu!
        if (a.containerSettingsItems.getChildCount() > 0) {
            a.containerSettingsItems.getChildAt(0).requestFocus();
        }
    }

    public void buildWidgetSettingsUI(MainActivity a) {
        a.currentSettingsDepth = 1; // Tell the system we've left the main settings screen
        a.containerSettingsItems.removeAllViews();

        // 1. Existing: digital clock & date widget switch
        final LinearLayout btnClock = a.createSettingRow("Widget: Digital Clock", a.isWidgetClockOn ? a.t("ON") : a.t("OFF"));
        btnClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isWidgetClockOn = !a.isWidgetClockOn;
                ((TextView) btnClock.getChildAt(1)).setText(a.isWidgetClockOn ? a.t("ON") : a.t("OFF"));
                try { a.prefs.edit().putBoolean("widget_clock", a.isWidgetClockOn).apply(); } catch (Exception e) { Log.d(TAG, "buildWidgetSettingsUI failed", e); }
                a.refreshWidgets(); // Instantly update the widget screen when the switch is turned on!
            }
        });
        a.containerSettingsItems.addView(btnClock);

        // 2. New: analog clock widget switch
        final LinearLayout btnAnalogClock = a.createSettingRow("Widget: Analog Clock", a.isWidgetAnalogClockOn ? a.t("ON") : a.t("OFF"));
        btnAnalogClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isWidgetAnalogClockOn = !a.isWidgetAnalogClockOn;
                ((TextView) btnAnalogClock.getChildAt(1)).setText(a.isWidgetAnalogClockOn ? a.t("ON") : a.t("OFF"));
                try { a.prefs.edit().putBoolean("widget_analog_clock", a.isWidgetAnalogClockOn).apply(); } catch (Exception e) { Log.d(TAG, "buildWidgetSettingsUI failed", e); }
                a.refreshWidgets();
            }
        });
        a.containerSettingsItems.addView(btnAnalogClock);

        // 3. Existing: bar-style battery widget switch
        final LinearLayout btnBattery = a.createSettingRow("Widget: Battery Bar", a.isWidgetBatteryOn ? a.t("ON") : a.t("OFF"));
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isWidgetBatteryOn = !a.isWidgetBatteryOn;
                ((TextView) btnBattery.getChildAt(1)).setText(a.isWidgetBatteryOn ? a.t("ON") : a.t("OFF"));
                try { a.prefs.edit().putBoolean("widget_battery", a.isWidgetBatteryOn).apply(); } catch (Exception e) { Log.d(TAG, "buildWidgetSettingsUI failed", e); }
                a.refreshWidgets();
            }
        });
        a.containerSettingsItems.addView(btnBattery);

        // 4. New: circular battery widget switch
        final LinearLayout btnCircularBattery = a.createSettingRow("Widget: Circular Battery", a.isWidgetCircularBatteryOn ? a.t("ON") : a.t("OFF"));
        btnCircularBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isWidgetCircularBatteryOn = !a.isWidgetCircularBatteryOn;
                ((TextView) btnCircularBattery.getChildAt(1)).setText(a.isWidgetCircularBatteryOn ? a.t("ON") : a.t("OFF"));
                try { a.prefs.edit().putBoolean("widget_circular_battery", a.isWidgetCircularBatteryOn).apply(); } catch (Exception e) { Log.d(TAG, "buildWidgetSettingsUI failed", e); }
                a.refreshWidgets();
            }
        });
        a.containerSettingsItems.addView(btnCircularBattery);

        // 5. Existing: album art widget switch
        final LinearLayout btnAlbum = a.createSettingRow("Widget: Now Playing Album", a.isWidgetAlbumOn ? a.t("ON") : a.t("OFF"));
        btnAlbum.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isWidgetAlbumOn = !a.isWidgetAlbumOn;
                ((TextView) btnAlbum.getChildAt(1)).setText(a.isWidgetAlbumOn ? a.t("ON") : a.t("OFF"));
                try { a.prefs.edit().putBoolean("widget_album", a.isWidgetAlbumOn).apply(); } catch (Exception e) { Log.d(TAG, "buildWidgetSettingsUI failed", e); }
                a.refreshWidgets();
            }
        });
        a.containerSettingsItems.addView(btnAlbum);

        // 🚀 6. New: added dynamic focus-image widget switch!
        final LinearLayout btnFocusImage = a.createSettingRow("Widget: Dynamic Focus Image", a.isWidgetFocusImageOn ? a.t("ON") : a.t("OFF"));
        btnFocusImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isWidgetFocusImageOn = !a.isWidgetFocusImageOn;
                ((TextView) btnFocusImage.getChildAt(1)).setText(a.isWidgetFocusImageOn ? a.t("ON") : a.t("OFF"));
                try { a.prefs.edit().putBoolean("widget_focus_image", a.isWidgetFocusImageOn).apply(); } catch (Exception e) { Log.d(TAG, "buildWidgetSettingsUI failed", e); }
                a.refreshWidgets(); // Reflect it on screen immediately when the switch is turned on!
            }
        });
        a.containerSettingsItems.addView(btnFocusImage);

        // Automatically focuses the second item on entry.
        if (a.containerSettingsItems.getChildCount() > 0) {
            a.containerSettingsItems.getChildAt(0).requestFocus();
        }
    }

    public void buildBackgroundSettingsUI(MainActivity a) {
        a.currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        a.containerSettingsItems.removeAllViews();

        // 1. Set-new-background button
        LinearLayout btnSelectBg = a.createSettingRow("Select New Background", "〉 ");
        btnSelectBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.isPickingBackground = true;
                a.currentFolder = new File("/storage/sdcard0");
                a.changeScreen(a.STATE_BROWSER);
                Toast.makeText(a, a.t("Select a JPG/PNG image"), Toast.LENGTH_SHORT).show();
            }
        });
        a.containerSettingsItems.addView(btnSelectBg);

        // 🚀 2. Force the active theme's own background image (falls back to album blur if the theme has none)
        LinearLayout btnThemeBg = a.createSettingRow("Apply Theme Background", "〉 ");
        btnThemeBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                if (a.currentThemeHasBackground()) {
                    a.prefs.edit().putString("bg_path", "THEME_DEFAULT").apply();
                    Toast.makeText(a, a.t("Theme background applied."), Toast.LENGTH_SHORT).show();
                } else {
                    // The theme ships no background image — don't force a blank; drop back to album blur.
                    a.prefs.edit().remove("bg_path").apply();
                    Toast.makeText(a, a.t("This theme has no background. Switched to Album Blur."), Toast.LENGTH_SHORT).show();
                }
                a.updateMainMenuBackground(); // Render the change immediately
            }
        });
        a.containerSettingsItems.addView(btnThemeBg);

        // 3. Clear-existing-background button (returns to album-art blur mode)
        LinearLayout btnClearBg = a.createSettingRow("Clear Custom Background", "〉 ");
        btnClearBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                if (a.prefs.contains("bg_path")) {
                    a.prefs.edit().remove("bg_path").apply();
                    Toast.makeText(a, a.t("Custom background cleared."), Toast.LENGTH_SHORT).show();
                    a.updateMainMenuBackground(); // Instantly revert to the original theme background!
                } else {
                    Toast.makeText(a, a.t("No custom background set."), Toast.LENGTH_SHORT).show();
                }
            }
        });
        a.containerSettingsItems.addView(btnClearBg);

        // Automatically focuses the first button on entering the menu!
        if (a.containerSettingsItems.getChildCount() > 0) {
            a.containerSettingsItems.getChildAt(0).requestFocus();
        }
    }

    /** Whether the framework is syncing the clock itself (Settings.Global.AUTO_TIME). */
    private static boolean isAutoTimeOn(MainActivity a) {
        try {
            return android.provider.Settings.Global.getInt(
                    a.getContentResolver(), android.provider.Settings.Global.AUTO_TIME, 0) != 0;
        } catch (Exception e) {
            Log.d(TAG, "could not read auto_time", e);
            return false;
        }
    }

    /** Writing Global settings needs WRITE_SECURE_SETTINGS, held because this is a priv-app. */
    private static boolean setAutoTime(MainActivity a, boolean on) {
        try {
            return android.provider.Settings.Global.putInt(
                    a.getContentResolver(), android.provider.Settings.Global.AUTO_TIME, on ? 1 : 0);
        } catch (Exception e) {
            Log.w(TAG, "could not write auto_time", e);
            return false;
        }
    }

    /**
     * Zones offered by the picker. A full tzdata list is hundreds of entries to wheel through on a
     * device with no keyboard; these are the common ones, and the current zone is always shown
     * even when it isn't in this list (e.g. one set over adb).
     */
    private static final String[][] TIME_ZONES = {
            {"Pacific",        "America/Los_Angeles"},
            {"Mountain",       "America/Denver"},
            {"Central",        "America/Chicago"},
            {"Eastern",        "America/New_York"},
            {"UTC",            "UTC"},
            {"London",         "Europe/London"},
            {"Berlin / Paris", "Europe/Berlin"},
            {"Moscow",         "Europe/Moscow"},
            {"India",          "Asia/Kolkata"},
            {"Singapore",      "Asia/Singapore"},
            {"Tokyo / Seoul",  "Asia/Tokyo"},
            {"Sydney",         "Australia/Sydney"},
    };

    /** "Eastern" for a zone we list, otherwise the raw id, so the row always says something true. */
    private static String shortZoneLabel() {
        String current = java.util.TimeZone.getDefault().getID();
        for (String[] zone : TIME_ZONES) {
            if (zone[1].equals(current)) return zone[0];
        }
        return current;
    }

    private void showTimeZonePicker(final MainActivity a) {
        String current = java.util.TimeZone.getDefault().getID();
        String[] labels = new String[TIME_ZONES.length];
        Runnable[] actions = new Runnable[TIME_ZONES.length];
        for (int i = 0; i < TIME_ZONES.length; i++) {
            final String id = TIME_ZONES[i][1];
            // Offset alongside the name, since "Central" means different things to different people.
            java.util.TimeZone zone = java.util.TimeZone.getTimeZone(id);
            int minutes = zone.getOffset(System.currentTimeMillis()) / 60000;
            String offset = String.format(java.util.Locale.US, "GMT%s%d:%02d",
                    minutes < 0 ? "-" : "+", Math.abs(minutes) / 60, Math.abs(minutes) % 60);
            labels[i] = (id.equals(current) ? "\u2713  " : "     ") + TIME_ZONES[i][0] + "   " + offset;
            actions[i] = new Runnable() {
                @Override
                public void run() {
                    // setTimeZone needs SET_TIME, which the launcher holds as a priv-app; it writes
                    // persist.sys.timezone, so the choice survives a reboot.
                    try {
                        android.app.AlarmManager am = (android.app.AlarmManager)
                                a.getSystemService(android.content.Context.ALARM_SERVICE);
                        if (am != null) am.setTimeZone(id);
                    } catch (Exception e) {
                        Log.w(TAG, "setTimeZone failed", e);
                        Toast.makeText(a, a.t("Could not set the time zone."), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Repaint the clock now rather than on its next tick.
                    a.clockHandler.removeCallbacks(a.clockTask);
                    a.clockHandler.post(a.clockTask);
                    buildDateTimeUI(a);
                }
            };
        }
        SongContextMenuManager.getInstance().showThemedOptionsDialog(
                a, a.t("Time Zone"), null, labels, actions);
    }

    public void buildDateTimeUI(MainActivity a) {
        a.currentSettingsDepth = 2; // 🚀 Main settings is depth 0
        a.containerSettingsItems.removeAllViews();
        // 🚀 [Fix] Wrap the 12-hour/24-hour text with t() so it also goes through the translator!
        String formatRightText = a.is24HourFormat ? a.t("24 Hour") : a.t("12 Hour");
        final LinearLayout rowFormat = a.createSettingRow("Time Format", formatRightText);
        rowFormat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                a.is24HourFormat = !a.is24HourFormat; // toggle
                a.prefs.edit().putBoolean("is_24h_format", a.is24HourFormat).apply(); // save permanently

                // 💡 Force-nudge the runtime thread once so the clock hands update immediately
                a.clockHandler.removeCallbacks(a.clockTask);
                a.clockHandler.post(a.clockTask);

                buildDateTimeUI(a); // Refresh the settings screen
            }
        });
        a.containerSettingsItems.addView(rowFormat);

        // Timezone had no UI at all, and nothing sets it on this hardware: auto_time_zone is on,
        // but that is NITZ, which arrives over a cellular network this device does not have. So a
        // fresh unit sits on GMT with a correct clock displaying the wrong time -- which reads as
        // "the clock is broken" and sent us looking for an NTP problem that did not exist.
        // Automatic time is the framework's own NTP sync (NetworkTimeUpdateService), which keeps
        // this device within a second of UTC over Wi-Fi. Setting the clock by hand switches it off,
        // and until now nothing could switch it back -- so a manual set was a one-way door.
        final boolean autoTime = isAutoTimeOn(a);
        final LinearLayout rowAuto = a.createSettingRow("Automatic Time", autoTime ? a.t("ON") : a.t("OFF"));
        rowAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                if (!setAutoTime(a, !autoTime)) {
                    Toast.makeText(a, a.t("Could not change automatic time."), Toast.LENGTH_SHORT).show();
                    return;
                }
                buildDateTimeUI(a);
            }
        });
        a.containerSettingsItems.addView(rowAuto);

        final LinearLayout rowZone = a.createSettingRow("Time Zone", shortZoneLabel());
        rowZone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                showTimeZonePicker(a);
            }
        });
        a.containerSettingsItems.addView(rowZone);

        if (autoTime) {
            // Nothing else to offer: entering a date NTP will overwrite in minutes is a worse
            // outcome than not showing the rows at all.
            if (a.containerSettingsItems.getChildCount() > 0) {
                a.containerSettingsItems.getChildAt(0).requestFocus();
            }
            return;
        }

        final LinearLayout rowYear = a.createSettingRow("Year", String.valueOf(a.dtYear));
        rowYear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildDateTimeSelectorUI(a, "Year", 2020, 2035, a.dtYear);
            }
        });
        a.containerSettingsItems.addView(rowYear);

        final LinearLayout rowMonth = a.createSettingRow("Month", String.format(java.util.Locale.US, "%02d", a.dtMonth));
        rowMonth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildDateTimeSelectorUI(a, "Month", 1, 12, a.dtMonth);
            }
        });
        a.containerSettingsItems.addView(rowMonth);

        final LinearLayout rowDay = a.createSettingRow("Day", String.format(java.util.Locale.US, "%02d", a.dtDay));
        rowDay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildDateTimeSelectorUI(a, "Day", 1, 31, a.dtDay);
            }
        });
        a.containerSettingsItems.addView(rowDay);

        final LinearLayout rowHour = a.createSettingRow("Hour (24H)", String.format(java.util.Locale.US, "%02d", a.dtHour));
        rowHour.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildDateTimeSelectorUI(a, "Hour", 0, 23, a.dtHour);
            }
        });
        a.containerSettingsItems.addView(rowHour);

        final LinearLayout rowMinute = a.createSettingRow("Minute", String.format(java.util.Locale.US, "%02d", a.dtMinute));
        rowMinute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildDateTimeSelectorUI(a, "Minute", 0, 59, a.dtMinute);
            }
        });
        a.containerSettingsItems.addView(rowMinute);

        // Was a createCategoryHeader whose "title" was a row of box-drawing characters, standing in
        // for a divider -- it rendered as a stray dashed line. Nothing else in Settings separates
        // rows, so nothing separates these either.
        final LinearLayout rowApply = a.createSettingRow("Set Clock", "〉 ");
        rowApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                try {
                    // AlarmManager.setTime needs SET_TIME, which is signature|privileged -- granted
                    // now the launcher lives in /system/priv-app. The old path shelled out to
                    // "su -c date", and this firmware has no su at all, so applying the time
                    // always failed with "root access required" no matter what was entered.
                    java.util.Calendar target = java.util.Calendar.getInstance();
                    target.set(a.dtYear, a.dtMonth - 1, a.dtDay, a.dtHour, a.dtMinute, 0);
                    target.set(java.util.Calendar.MILLISECOND, 0);

                    android.app.AlarmManager am = (android.app.AlarmManager)
                            a.getSystemService(android.content.Context.ALARM_SERVICE);
                    if (am == null) throw new IllegalStateException("no AlarmManager");
                    am.setTime(target.getTimeInMillis());

                    // The framework re-syncs over NTP whenever auto_time is on, which would undo
                    // this within minutes; a manual set is a statement that the clock is ours.
                    setAutoTime(a, false);

                    // No TIME_CHANGED broadcast from here: it's a protected broadcast only the
                    // system may send, and trying threw a SecurityException *after* the clock had
                    // already been set -- so the catch below reported a failure that hadn't
                    // happened. AlarmManager.setTime makes the framework broadcast it anyway; all
                    // that's left is repainting our own clock rather than waiting for its tick.
                    a.clockHandler.removeCallbacks(a.clockTask);
                    a.clockHandler.post(a.clockTask);

                    Toast.makeText(a, "Time applied successfully!", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.w(TAG, "applying the date/time failed", e);
                    Toast.makeText(a, a.t("Could not set the clock."), Toast.LENGTH_SHORT).show();
                }

                // 🚀 [Focus bug fix 1] Force-purge the corrupted index to the 'Date & Time Settings' menu position (item #14)
                a.lastSettingsFocusIndex = 14;
                buildSettingsUI(a);

                // 🚀 [Focus bug fix 2] Add a tiny 50ms safety delay so focus locks in reliably once the UI has fully laid out.
                a.containerSettingsItems.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (a.containerSettingsItems != null && a.containerSettingsItems.getChildCount() > 0) {
                            a.containerSettingsItems.getChildAt(a.containerSettingsItems.getChildCount() - 1)
                                    .requestFocus();
                        }
                    }
                }, 50);
            }
        });
        a.containerSettingsItems.addView(rowApply);

        if (a.containerSettingsItems.getChildCount() > 0)
            a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildDateTimeSelectorUI(MainActivity a, final String type, int min, int max, int currentValue) {
        a.currentSettingsDepth = 3; // 🚀 Main settings is depth 0
        a.containerSettingsItems.removeAllViews();

        Button focusBtn = null;
        for (int i = min; i <= max; i++) {
            final int val = i;
            String displayVal = (type.equals("Minute") || type.equals("Hour") || type.equals("Month")
                    || type.equals("Day")) ? String.format(java.util.Locale.US, "%02d", val) : String.valueOf(val);
            Button btn = a.createListButton(displayVal);
            btn.setGravity(android.view.Gravity.CENTER); // Nicely center-aligned!

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    if (type.equals("Year"))
                        a.dtYear = val;
                    else if (type.equals("Month"))
                        a.dtMonth = val;
                    else if (type.equals("Day"))
                        a.dtDay = val;
                    else if (type.equals("Hour"))
                        a.dtHour = val;
                    else if (type.equals("Minute"))
                        a.dtMinute = val;
                    buildDateTimeUI(a); // Automatically returns to the previous screen once selected!
                }
            });
            a.containerSettingsItems.addView(btn);
            if (val == currentValue)
                focusBtn = btn;
        }

        // Auto-move focus to the currently configured time
        if (focusBtn != null)
            focusBtn.requestFocus();
        else if (a.containerSettingsItems.getChildCount() > 0)
            a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildEqualizerSettingsUI(MainActivity a) {
        a.currentSettingsDepth = 2;
        a.settingsSubMode = 2; // Activate the EQ sub-mode
        com.mintmango.y2.managers.AudioEffectManager.getInstance().loadAndSyncExternalEqProfiles();
        com.mintmango.y2.managers.AudioEffectManager.getInstance().ensureAudioEffectsReady();
        a.containerSettingsItems.removeAllViews();

        // 🚀 2. Show EQ on the sub-settings screen
        String activeName = "Normal";
        if (a.currentEqProfile.startsWith("preset_")) {
            int pIdx = Integer.parseInt(a.currentEqProfile.replace("preset_", ""));
            if (pIdx < a.eqPresetNames.size()) activeName = a.t(a.eqPresetNames.get(pIdx)); // 🚀 Translation applied
        } else {
            activeName = a.currentEqProfile.replace("custom_", ""); // 🚀 Translate the label
        }
        LinearLayout rowSelect = a.createSettingRow("EQ Profile / Preset", activeName + " 〉");

        // 🚀 [Bug fix] Attach the click event to the button we built and slot it onto the screen!
        rowSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                buildEqProfileSelectorUI(a); // 🚀 Open the hidden preset-selector list screen!
            }
        });
        a.containerSettingsItems.addView(rowSelect);

        // 2. 4-band bass booster
        final String[] steps = {"OFF", "Weak", "Normal", "Strong"};
        final LinearLayout rowBass = a.createSettingRow("Bass Boost", a.t(steps[a.currentBassBoostStep]));
        rowBass.setOnClickListener(v -> {
            a.clickFeedback();
            a.currentBassBoostStep = (a.currentBassBoostStep + 1) % 4;
            ((TextView) rowBass.getChildAt(1)).setText(a.t(steps[a.currentBassBoostStep])); // 🚀 a.t() applied!
            com.mintmango.y2.managers.AudioEffectManager.getInstance().applyAudioEffects();
            a.prefs.edit().putInt("bass_boost_step", a.currentBassBoostStep).apply();
        });
        a.containerSettingsItems.addView(rowBass);

        // 3. 4-band virtualizer (spatial effect)
        final LinearLayout rowVirt = a.createSettingRow("Virtualizer", a.t(steps[a.currentVirtualizerStep]));
        rowVirt.setOnClickListener(v -> {
            a.clickFeedback();
            a.currentVirtualizerStep = (a.currentVirtualizerStep + 1) % 4;
            ((TextView) rowVirt.getChildAt(1)).setText(a.t(steps[a.currentVirtualizerStep])); // 🚀 a.t() applied!
            com.mintmango.y2.managers.AudioEffectManager.getInstance().applyAudioEffects();
            a.prefs.edit().putInt("virtualizer_step", a.currentVirtualizerStep).apply();
        });
        a.containerSettingsItems.addView(rowVirt);

        // 4. Custom vault management panel
        a.createCategoryHeader("━ "+a.t("PROFILE MANAGEMENT")+" ━");
        if (a.currentEqProfile.startsWith("custom_")) {
            android.view.View btnSave = a.createListButtonWithIcon("\uE161", a.t("Save Current Configuration"));
            btnSave.setOnClickListener(v -> {
                a.clickFeedback();
                com.mintmango.y2.managers.AudioEffectManager.getInstance().saveCustomEqProfile(a.currentEqProfile.replace("custom_", ""));
                Toast.makeText(a, a.t("Configuration Saved!"), Toast.LENGTH_SHORT).show();
            });
            a.containerSettingsItems.addView(btnSave);

            android.view.View btnDel = a.createListButtonWithIcon("\uE872", a.t("Delete Current Profile"), 0xFFFF4444);

            btnDel.setOnClickListener(v -> {
                a.clickFeedback();
                com.mintmango.y2.managers.AudioEffectManager.getInstance().deleteCustomEqProfile(a.currentEqProfile.replace("custom_", ""));
                a.currentEqProfile = "preset_0";
                com.mintmango.y2.managers.AudioEffectManager.getInstance().applyEqProfile();
                buildEqualizerSettingsUI(a);
            });
            a.containerSettingsItems.addView(btnDel);
        }

        android.view.View btnCreate = a.createListButtonWithIcon("\uE145", a.t("Create New Profile"));
        btnCreate.setOnClickListener(v -> {
            a.clickFeedback();
            String listStr = a.prefs.getString("custom_eq_list", "");
            int count = 1;
            while (listStr.contains("User EQ " + count)) count++;
            String newName = "User EQ " + count;
            if (!listStr.isEmpty()) listStr += ",";
            listStr += newName;
            a.prefs.edit().putString("custom_eq_list", listStr).apply();

            a.currentEqProfile = "custom_" + newName;

            // 🚀 [Bug fix] So the cached values from previous edits don't carry over when creating a new profile,
            // force-format every frequency band to a clean 0 dB (flat default) state!
            short bands = (a.equalizer != null) ? a.equalizer.getNumberOfBands() : 5;
            for (short i = 0; i < bands; i++) {
                a.customBandLevels[i] = 0;
                if (a.equalizer != null) {
                    try { a.equalizer.setBandLevel(i, (short) 0); } catch (Exception e) { Log.d(TAG, "buildEqualizerSettingsUI failed", e); }
                }
            }

            com.mintmango.y2.managers.AudioEffectManager.getInstance().saveCustomEqProfile(newName);
            com.mintmango.y2.managers.AudioEffectManager.getInstance().exportEqProfileToFile(newName); // Also immediately export it as a standalone shareable file the moment it's created!
            com.mintmango.y2.managers.AudioEffectManager.getInstance().applyEqProfile();

            // 🚀 [UX overhaul] Boldly skip the main-screen reload step and warp straight into the graph studio!
            buildGraphicEqualizerUI(a);
        });
        a.containerSettingsItems.addView(btnCreate);

        // 🚀 5. Advanced Graphic Equalizer (Graphic EQ) studio entry button
        a.createCategoryHeader("━ "+a.t("GRAPHIC EQUALIZER")+" ━");
        LinearLayout btnGraphicEq = a.createSettingRow("Graphic Equalizer", a.t("Open Editor")+" 〉");
        btnGraphicEq.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                a.clickFeedback();
                if (a.currentEqProfile.startsWith("preset_")) {
                    android.widget.Toast.makeText(a, a.t("Please create a Custom Profile to edit!"), android.widget.Toast.LENGTH_LONG).show();
                } else {
                    buildGraphicEqualizerUI(a); // Enter the long-awaited Graphic EQ studio screen!
                }
            }
        });
        a.containerSettingsItems.addView(btnGraphicEq);

        if (a.containerSettingsItems.getChildCount() > 0) a.containerSettingsItems.getChildAt(0).requestFocus();
    }

    public void buildEqProfileSelectorUI(MainActivity a) {
        a.currentSettingsDepth = 3;
        a.containerSettingsItems.removeAllViews();

        // 🚀 3. Translate when pulling out the list
        if (a.equalizer != null) {
            for (int i = 0; i < a.eqPresetNames.size(); i++) {
                final int pIdx = i;
                final String pId = "preset_" + pIdx;
                String prefix = a.currentEqProfile.equals(pId) ? "✔ " : "   ";
                // 🚀 Intercept the English name pulled from the system and feed it straight into the translator!
                Button btn = a.createListButton(prefix + a.t(a.eqPresetNames.get(i)));
                if (a.currentEqProfile.equals(pId)) { btn.setTextColor(0xFF00FF00); btn.setTypeface(null, android.graphics.Typeface.BOLD); }
                btn.setOnClickListener(v -> { a.clickFeedback(); a.currentEqProfile = pId; com.mintmango.y2.managers.AudioEffectManager.getInstance().applyEqProfile(); buildEqualizerSettingsUI(a); });
                a.containerSettingsItems.addView(btn);
            }
        }

        a.createCategoryHeader("━ "+a.t("SELECT USER PROFILES")+" ━");
        String listStr = a.prefs.getString("custom_eq_list", "");
        if (!listStr.trim().isEmpty()) {
            for (final String prof : listStr.split(",")) {
                if (prof.trim().isEmpty()) continue;
                final String cId = "custom_" + prof;
                String prefix = a.currentEqProfile.equals(cId) ? "✔ " : "   ";
                Button btn = a.createListButton(prefix + prof);
                if (a.currentEqProfile.equals(cId)) { btn.setTextColor(0xFF00FF00); btn.setTypeface(null, android.graphics.Typeface.BOLD); }
                btn.setOnClickListener(v -> { a.clickFeedback(); a.currentEqProfile = cId; com.mintmango.y2.managers.AudioEffectManager.getInstance().applyEqProfile(); buildEqualizerSettingsUI(a); });
                a.containerSettingsItems.addView(btn);
            }
        } else {
            TextView tvEmpty = new TextView(a); tvEmpty.setText("   " + a.t("No custom profiles found."));
            tvEmpty.setTextColor(0xFF888888); tvEmpty.setPadding(20, 10, 20, 10);
            a.containerSettingsItems.addView(tvEmpty);
        }

        // 🚀 [Focus bug fix] Once the screen has fully rendered (50ms delay), explicitly force-assign focus.
        a.containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (a.containerSettingsItems.getChildCount() > 0) {
                    a.containerSettingsItems.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    }

    @SuppressLint("ResourceType") // 8000+n/8500 are dynamically-assigned view ids, not XML resources
    public void buildGraphicEqualizerUI(MainActivity a) {
        a.currentSettingsDepth = 3;
        a.settingsSubMode = 3;
        a.currentAdjustingBand = -1;

        a.containerSettingsItems.removeAllViews();
        a.createCategoryHeader("━ "+a.t("GRAPHIC EQUALIZER")+" ━");

        TextView tvTitle = new TextView(a);
        tvTitle.setText(a.t("Editing: ") + a.currentEqProfile.replace("custom_", "") + " (User)");
        tvTitle.setTextColor(0xFFFF8800);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 10, 0, 20);
        a.containerSettingsItems.addView(tvTitle);

        final android.widget.RelativeLayout eqContainer = new android.widget.RelativeLayout(a);
        eqContainer.setLayoutParams(new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int)(280 * a.getResources().getDisplayMetrics().density)));
        eqContainer.setGravity(android.view.Gravity.CENTER);

        if (a.equalizer != null) {
            final short bands = a.equalizer.getNumberOfBands();
            short[] range = a.equalizer.getBandLevelRange();
            int prevId = -1;

            for (short i = 0; i < bands; i++) {
                final short bandIdx = i;
                int freq = a.equalizer.getCenterFreq(bandIdx) / 1000;
                int currentLevel = a.customBandLevels[bandIdx];

                final LinearLayout bandLayout = new LinearLayout(a);
                bandLayout.setOrientation(LinearLayout.VERTICAL);
                bandLayout.setFocusable(true);
                bandLayout.setGravity(android.view.Gravity.CENTER);
                bandLayout.setId(8000 + i); // 8000, 8001, 8002... assigns a unique ID

                // 🚀 [Core technique 1] Force-specify the neighbor node to move to on wheel input, to prevent random system crashes!
                int nextFocusId = (i == bands - 1) ? 8500 : (8000 + i + 1); // if it's the last one, go to the close button (8500)
                int prevFocusId = (i == 0) ? 8500 : (8000 + i - 1);         // if it's the first one, go to the close button (8500)

                bandLayout.setNextFocusDownId(nextFocusId); // wheel down (move right)
                bandLayout.setNextFocusUpId(prevFocusId);   // wheel up (move left)

                android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams((int)(60 * a.getResources().getDisplayMetrics().density), android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                if (prevId != -1) {
                    lp.addRule(android.widget.RelativeLayout.RIGHT_OF, prevId);
                    lp.leftMargin = (int)(5 * a.getResources().getDisplayMetrics().density);
                }
                bandLayout.setLayoutParams(lp);
                prevId = 8000 + i;

                final EqSliderView slider = new EqSliderView(a);
                slider.setRange(range[0], range[1]);
                slider.setLevel(currentLevel);
                slider.setLayoutParams(new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

                TextView tvFreq = new TextView(a);
                tvFreq.setText(freq >= 1000 ? (freq/1000) + "k" : freq + "");
                tvFreq.setTextColor(0xFFFFFFFF);
                tvFreq.setTextSize(12f);
                tvFreq.setGravity(android.view.Gravity.CENTER);
                tvFreq.setPadding(0, 0, 0, 10);

                bandLayout.addView(slider);
                bandLayout.addView(tvFreq);

                bandLayout.setOnFocusChangeListener(new android.view.View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(android.view.View v, boolean hasFocus) {
                        if (hasFocus) {
                            bandLayout.setBackground(a.createButtonBackground(ThemeManager.getListButtonFocusedBg() & 0x66FFFFFF));
                        } else {
                            bandLayout.setBackgroundColor(0x00000000);
                            if (a.currentAdjustingBand == bandIdx) {
                                a.currentAdjustingBand = -1;
                                slider.setAdjusting(false);
                            }
                        }
                        slider.setFocused(hasFocus);
                    }
                });

                bandLayout.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        a.clickFeedback();
                        if (a.currentAdjustingBand == bandIdx) {
                            a.currentAdjustingBand = -1;
                            slider.setAdjusting(false);
                        } else {
                            if (a.currentAdjustingBand != -1) {
                                LinearLayout prevBand = (LinearLayout) eqContainer.findViewById(8000 + a.currentAdjustingBand);
                                if (prevBand != null) ((EqSliderView) prevBand.getChildAt(0)).setAdjusting(false);
                            }
                            a.currentAdjustingBand = bandIdx;
                            slider.setAdjusting(true);
                        }
                    }
                });

                bandLayout.setOnKeyListener(new android.view.View.OnKeyListener() {
                    @Override
                    public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && a.currentAdjustingBand == bandIdx) {
                            if (keyCode == 21 || keyCode == 22) {
                                int step = 100;
                                int level = a.customBandLevels[bandIdx];
                                if (keyCode == 21) level += step;
                                if (keyCode == 22) level -= step;

                                if (level > range[1]) level = range[1];
                                if (level < range[0]) level = range[0];

                                a.customBandLevels[bandIdx] = level;
                                try { a.equalizer.setBandLevel(bandIdx, (short) level); } catch (Exception e) { Log.d(TAG, "buildGraphicEqualizerUI failed", e); }
                                slider.setLevel(level);
                                com.mintmango.y2.managers.AudioEffectManager.getInstance().saveCustomEqProfile(a.currentEqProfile.replace("custom_", ""));
                                a.clickFeedback();
                                return true;
                            }
                        }
                        return false;
                    }
                });
                eqContainer.addView(bandLayout);
            }
        }

        a.containerSettingsItems.addView(eqContainer);

        // 🚀 [UX overhaul 1] Replaced the ambiguous "Done/Close" wording with the mixing-console-native term "Save"!
        Button btnClose = a.createListButton(a.t("Save Profile"));
        btnClose.setId(8500);

        btnClose.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                a.clickFeedback();
                String name = a.currentEqProfile.replace("custom_", "");
                com.mintmango.y2.managers.AudioEffectManager.getInstance().saveCustomEqProfile(name); // 1. Save to the internal local vault
                com.mintmango.y2.managers.AudioEffectManager.getInstance().exportEqProfileToFile(name); // 2. 🚀 [Key] Export it live to a separate external file (.json) for user sharing!
                com.mintmango.y2.managers.AudioEffectManager.getInstance().applyEqProfile(); // 3. Apply the sound pressure to the audio chipset live, immediately

                android.widget.Toast.makeText(a, a.t("File saved successfully!"), android.widget.Toast.LENGTH_SHORT).show();
                buildEqualizerSettingsUI(a); // 4. Returning to the previous page immediately syncs and displays the name we just set at the top-level router!
            }
        });

        btnClose.setOnKeyListener(new android.view.View.OnKeyListener() {
            @Override
            public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (a.equalizer != null) {
                        int totalBands = a.equalizer.getNumberOfBands();
                        if (keyCode == 21) {
                            android.view.View lastBand = eqContainer.findViewById(8000 + totalBands - 1);
                            if (lastBand != null) lastBand.requestFocus();
                            a.clickFeedback();
                            return true;
                        }
                        if (keyCode == 22) {
                            android.view.View firstBand = eqContainer.findViewById(8000);
                            if (firstBand != null) firstBand.requestFocus();
                            a.clickFeedback();
                            return true;
                        }
                    }
                }
                return false;
            }
        });
        a.containerSettingsItems.addView(btnClose);

        a.containerSettingsItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 🚀 [UX overhaul 2] Instead of the very top, magnetically snap focus straight to the first mixing fader (node 8000) so wheel input is ready to go!
                android.view.View firstBand = eqContainer.findViewById(8000);
                if (firstBand != null) {
                    firstBand.requestFocus();
                } else if (a.containerSettingsItems.getChildCount() > 2) {
                    a.containerSettingsItems.getChildAt(2).requestFocus();
                }
            }
        }, 50);
    }

    public void buildMainMenuVisibilitySettingsUI(MainActivity a) {
        a.currentSettingsDepth = 2;
        a.containerSettingsItems.removeAllViews();

        // ❌ Per the artist's request, completely and traceless removed the top category header text ("― SHOW / HIDE MENUS ―")!

        // 1. Fetch the current theme's main-menu buttons in order.
        List<ThemeManager.MenuElement> buttons = new ArrayList<>();
        for (ThemeManager.MenuElement el : ThemeManager.getCurrentTheme().menuElements) {
            if (el.type.equals("button")) buttons.add(el);
        }
        java.util.Collections.sort(buttons, new java.util.Comparator<ThemeManager.MenuElement>() {
            @Override
            public int compare(ThemeManager.MenuElement e1, ThemeManager.MenuElement e2) {
                return e1.focusIndex - e2.focusIndex;
            }
        });

        // 2. Attach a hide/show (HIDDEN/SHOW) switch to each button.
        for (int i = 0; i < buttons.size(); i++) {
            final ThemeManager.MenuElement el = buttons.get(i);
            final int currentItemIndex = i; // 🚀 Stamp the index of which row this button is

            final boolean isHidden = a.prefs.getBoolean("hide_btn_" + el.id, false);
            String btnName = (el.textNormal != null && !el.textNormal.trim().isEmpty()) ? el.textNormal : el.id;

            final LinearLayout row = a.createSettingRow(btnName, isHidden ? a.t("HIDDEN") : a.t("SHOW"));

            if (isHidden) ((TextView) row.getChildAt(1)).setTextColor(ThemeManager.getTextColorSecondary());
            else ((TextView) row.getChildAt(1)).setTextColor(ThemeManager.getTextColorPrimary());

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    boolean newState = !a.prefs.getBoolean("hide_btn_" + el.id, false);
                    a.prefs.edit().putBoolean("hide_btn_" + el.id, newState).apply();

                    // 🚀 [Fix 1] Instead of tearing down the whole screen, just swap the text of the row that was pressed!
                    TextView tvRight = (TextView) row.getChildAt(1);
                    tvRight.setText(newState ? a.t("HIDDEN") : a.t("SHOW"));

                    // 🚀 [Fix 2] Since wheel focus is currently on it, keep the focus color (usually black) as-is.
                    if (row.hasFocus()) {
                        tvRight.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    } else {
                        if (newState) tvRight.setTextColor(ThemeManager.getTextColorSecondary());
                        else tvRight.setTextColor(ThemeManager.getTextColorPrimary());
                    }

                    // 💡 [Fix 3] Leave the screen untouched and quietly re-assemble just the invisible main-menu blueprint in the background.
                  //  applyThemeToMainMenu();

                    // ❌ [Root cause removed] The re-call to 'buildMainMenuVisibilitySettingsUI(a)', which used to wipe and redraw the screen,
                    // and the delayed function (postDelayed) that used to force focus back in, have both been traceless removed!
                }
            });
            a.containerSettingsItems.addView(row);
        }

    }

}
