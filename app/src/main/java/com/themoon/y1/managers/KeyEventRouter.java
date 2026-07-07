package com.themoon.y1.managers;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.themoon.y1.MainActivity;

/**
 * Routes hardware key/wheel input (dispatchKeyEvent/onKeyDown/onKeyUp/onKeyLongPress) for the
 * whole launcher. Extracted verbatim from MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * This is the connective tissue between nearly every other subsystem (volume, browser
 * navigation, settings depth, radio, Navidrome, web server, wheel-lock, screen-off), so unlike
 * the earlier extractions there is no clean field boundary here either -- same as
 * FmRadioUiManager, it takes the MainActivity instance as a parameter and reaches back into
 * MainActivity's state directly rather than owning any of it itself. MainActivity keeps the
 * four Activity-lifecycle override methods (Android calls these directly on the Activity) as
 * one-line delegates, plus superOnKeyDown()/superDispatchKeyEvent()/superOnKeyUp()/
 * superOnKeyLongPress() helpers since this class isn't an Activity subclass and can't call
 * super.onKeyDown() etc. itself.
 */
public class KeyEventRouter {
    private static final String TAG = "KeyEventRouter";
    private static KeyEventRouter instance;

    private KeyEventRouter() {}

    public static synchronized KeyEventRouter getInstance() {
        if (instance == null) {
            instance = new KeyEventRouter();
        }
        return instance;
    }

    public boolean dispatchKeyEvent(MainActivity a, KeyEvent event) {
        // 🚀 [Wheel lock] Nothing gets through except turning the wheel (21/22) — whatever button
        // gets pressed and however it happens inside a pocket, it's all absorbed here.
        if (WheelLockManager.getInstance().isActive()) {
            return WheelLockManager.getInstance().handleKeyEvent(event);
        }
        if (a.isFakeScreenOff) {
            int keyCode = event.getKeyCode();

            // 💡 1. The moment the button is pressed (ACTION_DOWN)
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // Silently isolate and block the leftover repeated signal that occurs when the hand hasn't lifted yet after a long press
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.getRepeatCount() > 0) {
                    return true;
                }

                // 🚀 [Screen-off control integration] While in virtual-blackout state, pressing left/right changes the frequency without waking the screen, keeping it off!
                if (a.isScreenOffControlEnabled && a.activePlayer == 1) {
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                        a.tuneToNextSavedRadioChannel(true);
                        a.clickFeedback();
                        return true; // 💡 Break the signal here so it doesn't fall through to the screen-wake routine.
                    }
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                        a.tuneToNextSavedRadioChannel(false);
                        a.clickFeedback();
                        return true;
                    }
                }

                // If any other key is pressed, kick off the screen-wake steering!
                a.isFakeScreenOff = false;
                a.autoManageWifiPower(false); // 🚀 [Exiting power-saving mode]
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Instantly restore the hardware backlight brightness
                        try {
                            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                            lp.screenBrightness = a.currentSystemBrightness / 255.0f;
                            a.getWindow().setAttributes(lp);
                        } catch (Exception e) {
                            Log.d(TAG, "dispatchKeyEvent failed", e);
                        }

                        // Run a smooth cinematic fade-in animation
                        // (vsync-synced property animator instead of a manual 25ms Handler loop)
                        a.layoutLoadingOverlay.animate().cancel();
                        a.layoutLoadingOverlay.animate()
                                .alpha(0.0f)
                                .setDuration(325)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (a.isFinishing() || a.isDestroyed()) return;
                                        a.layoutLoadingOverlay.setVisibility(View.GONE);
                                        a.layoutLoadingOverlay.setBackgroundColor(0xDD000000); // Reset to the semi-transparent loading-screen color
                                        if (a.pbLoadingProgress != null) a.pbLoadingProgress.setVisibility(View.VISIBLE);
                                        if (a.currentScreenState == MainActivity.STATE_SETTINGS) a.buildRadioUI();
                                    }
                                })
                                .start();
                    }
                });
                a.clickFeedback();
            }

            // 🚀 [Core technique] All key actions (press, release — everything) that occur in the blackout state
            // are made to completely vanish here, never reaching lower views (like the radio option buttons)!
            return true;
        }
        return a.superDispatchKeyEvent(event);
    }

    public boolean onKeyDown(MainActivity a, int keyCode, KeyEvent event) {
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 20)
                isScreenOn = pm.isInteractive();
            else
                isScreenOn = pm.isScreenOn();
        } catch (Exception e) {
            Log.d(TAG, "onKeyDown failed", e);
        }

        boolean isWakingUp = !isScreenOn || ((event.getFlags() & KeyEvent.FLAG_WOKE_HERE) != 0)
                || (System.currentTimeMillis() - a.lastScreenOnTime < 500);

        if (isWakingUp) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) {
                    event.startTracking();
                }
                return true;
            }

            // 🚀 [Screen-off control radio interceptor inserted]
            if (a.isScreenOffControlEnabled && a.activePlayer == 1) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                    a.tuneToNextSavedRadioChannel(true);
                    a.clickFeedback();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                    a.tuneToNextSavedRadioChannel(false);
                    a.clickFeedback();
                    return true;
                }
            }

            if (a.isScreenOffControlEnabled && a.currentScreenState == MainActivity.STATE_PLAYER) {
                if (keyCode == 21) {
                    // 🚀 Guard: block volume adjustments for 0.3 seconds (300ms) right after a track skip!
                    if (System.currentTimeMillis() - a.lastTrackChangeTime > 300) {
                        a.adjustVolume(false);
                        a.clickFeedback();
                    }
                    return true;
                }
                if (keyCode == 22) {
                    if (System.currentTimeMillis() - a.lastTrackChangeTime > 300) {
                        a.adjustVolume(true);
                        a.clickFeedback();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
                    return a.handleMediaSeekKeyRepeat(event, -10000);
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
                    return a.handleMediaSeekKeyRepeat(event, 10000);
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86) {
                    return true;
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                event.startTracking(); // 🚀 [Core technique] Start watching (tracking) for a long press.
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == 86) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            return a.handleMediaSeekKeyRepeat(event, 10000);
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
            return a.handleMediaSeekKeyRepeat(event, -10000);
        }

        if (a.currentScreenState == MainActivity.STATE_WIFI_KEYBOARD) {
            if (keyCode == 21) {
                a.keyboardIndex = (a.keyboardIndex - 1 + a.KEYBOARD_CHARS.length) % a.KEYBOARD_CHARS.length;
                a.updateKeyboardUI();
                a.clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                a.keyboardIndex = (a.keyboardIndex + 1) % a.KEYBOARD_CHARS.length;
                a.updateKeyboardUI();
                a.clickFeedback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                a.changeScreen(MainActivity.STATE_WIFI);
                a.clickFeedback();
                return true;
            }
            return true;
        }

        if (a.currentScreenState == MainActivity.STATE_PLAYER) {
            if (keyCode == 21) {
                a.adjustVolume(false);
                a.clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                a.adjustVolume(true);
                a.clickFeedback();
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 🚀 [Return-path specified] Always go back precisely to the screen we came from, not the browser!
                a.changeScreen(a.backTargetForPlayer);
                a.clickFeedback();
                return true;
            }
            return true;
        }

        if (a.currentScreenState == MainActivity.STATE_BRIGHTNESS) {
            if (keyCode == 21) {
                a.currentSystemBrightness = Math.max(10, a.currentSystemBrightness - 15);
                a.updateBrightness(a.currentSystemBrightness);
                a.clickFeedback();
                return true;
            }
            if (keyCode == 22) {
                a.currentSystemBrightness = Math.min(255, a.currentSystemBrightness + 15);
                a.updateBrightness(a.currentSystemBrightness);
                a.clickFeedback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 🚀 [Return-path specified]
                a.changeScreen(a.backTargetForUtility);
                a.clickFeedback();
                return true;
            }
            return true;
        }

        if (a.currentScreenState == MainActivity.STATE_STORAGE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 🚀 [Return-path specified]
                a.changeScreen(a.backTargetForUtility);
                a.clickFeedback();
                return true;
            }
            return true;
        }

        if (a.currentScreenState == MainActivity.STATE_NAVIDROME) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19) {
                a.clickFeedback();
                if (a.navidromeBrowseDepth == MainActivity.NAV_SONGS) {
                    a.navidromeBrowseDepth = MainActivity.NAV_ALBUMS;
                    a.buildNavidromeUI();
                } else if (a.navidromeBrowseDepth == MainActivity.NAV_ALBUMS) {
                    a.navidromeBrowseDepth = MainActivity.NAV_ARTISTS;
                    a.selectedNavidromeArtist = null;
                    a.buildNavidromeUI();
                } else if (a.isNavidromeLetterView) {
                    // Letter picker → back to the artist list without refetching
                    a.tvNavidromePath.setText("NAVIDROME  ▸  Artists");
                    a.buildNavidromeArtistsUI(a.lastNavidromeArtists);
                } else {
                    if (a.navidromeBackTarget == MainActivity.STATE_BROWSER) a.lastBrowserFocusText = a.t("Navidrome");
                    a.changeScreen(a.navidromeBackTarget);
                }
                return true;
            }
        }

        if (a.currentScreenState == MainActivity.STATE_WEBSERVER) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                a.clickFeedback();
                if (a.isServerRunning) {
                    new android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(a.t("Server is Running"))
                            .setMessage(
                                    a.t("The Web Server is still active. Do you want to shut it down completely and exit?"))
                            .setPositiveButton(a.t("Stop Server"), new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    a.toggleWebServer();
                                    a.changeScreen(a.backTargetForUtility); // 🚀 Return!
                                }
                            })
                            .setNegativeButton(a.t("Keep Running"), new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    a.changeScreen(a.backTargetForUtility); // 🚀 Return!
                                }
                            })
                            .show();
                } else {
                    a.changeScreen(a.backTargetForUtility); // 🚀 Return!
                }
                return true;
            }
        }

        if (a.currentScreenState == MainActivity.STATE_MENU || a.currentScreenState == MainActivity.STATE_BROWSER
                || a.currentScreenState == MainActivity.STATE_SETTINGS || a.currentScreenState == MainActivity.STATE_BLUETOOTH
                || a.currentScreenState == MainActivity.STATE_WIFI || a.currentScreenState == MainActivity.STATE_NAVIDROME) {

            // 🚀 [Stock cover-flow wheel control fully overhauled]
            if (a.currentScreenState == MainActivity.STATE_BROWSER && a.currentBrowserMode == MainActivity.BROWSER_COVER_FLOW) {
                if (keyCode == 21) { // turning the wheel up (left)
                    a.scrollCoverFlow(false);
                    a.clickFeedback();
                    return true;
                }
                if (keyCode == 22) { // turning the wheel down (right)
                    a.scrollCoverFlow(true);
                    a.clickFeedback();
                    return true;
                }
            }

            // (existing code unchanged) 🚀 In addition to the existing BACK key, pressing the top button (19) always goes back one step...
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19) {
                a.clickFeedback();
                if (a.currentScreenState == MainActivity.STATE_BROWSER) {
                    if (a.isPickingBackground) {
                        if (a.currentFolder.getAbsolutePath().equals(a.rootFolder.getAbsolutePath())) {
                            a.isPickingBackground = false;
                            a.changeScreen(MainActivity.STATE_MENU);
                        } else {
                            a.currentFolder = a.currentFolder.getParentFile();
                            a.buildFileBrowserUI();
                        }
                    } else {
                        // 💡 [Bug fully fixed] Precisely remember (in lastBrowserFocusText) which room we just came out of!
                        if (a.currentBrowserMode == MainActivity.BROWSER_ROOT) {
                            a.changeScreen(MainActivity.STATE_MENU);
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_COVER_FLOW) {
                            // 🚀 [Smart direct-exit sensor installed]
                            // If there's no memory (lastBrowserFocusText) of the previous browser menu, then we came straight in from the main menu!
                            if (a.lastBrowserFocusText == null || a.lastBrowserFocusText.trim().isEmpty()) {
                                a.changeScreen(MainActivity.STATE_MENU); // 🟢 Return directly to the main screen immediately, just like the other shortcuts!
                            } else {
                                // If we came in through the proper path via the library menu, return to the parent menu as usual
                                a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                                a.buildFileBrowserUI();
                            }
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_FOLDER) {
                            // 🚀 [Bug fix] Smartly checks whether we've reached the top-level folder, according to the current mode (music/audiobook).
                            boolean isAtFolderRoot = false;
                            if (a.isAudiobookLibraryMode) {
                                if (a.currentFolder.getAbsolutePath().equals(a.audiobookRootFolder.getAbsolutePath())) {
                                    isAtFolderRoot = true;
                                }
                            } else {
                                if (a.currentFolder.getAbsolutePath().equals(a.rootFolder.getAbsolutePath())) {
                                    isAtFolderRoot = true;
                                }
                            }

                            // Pressing back at either mode's top-level folder or the device's overall root folder returns to the library main screen (BROWSER_ROOT)!
                            if (isAtFolderRoot || a.currentFolder.getAbsolutePath().equals("/storage/sdcard0")) {
                                a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                                a.lastBrowserFocusText = a.t("Folders");
                                a.buildFileBrowserUI();
                            } else {
                                String exitedName = a.currentFolder.getName(); // Remember the name of the folder we just left!
                                a.currentFolder = a.currentFolder.getParentFile();
                                if (a.currentFolder == null) {
                                    a.changeScreen(MainActivity.STATE_MENU);
                                } else {
                                    a.lastBrowserFocusText = exitedName;
                                    a.buildFileBrowserUI();
                                }
                            }
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_VIRTUAL_SONGS) {
                            // 🚀 [Path-recovery 3] If the tag is cover-flow, always send it back to the cover-flow screen.
                            if (a.virtualQueryType.equals("COVER_FLOW_ALBUM")) {
                                a.currentBrowserMode = MainActivity.BROWSER_COVER_FLOW;
                                a.buildCoverFlowUI();
                            } else {
                                // Existing general routing logic
                                a.currentBrowserMode = a.virtualQueryType.equals("ALL") ? MainActivity.BROWSER_ROOT
                                        : (a.virtualQueryType.equals("ARTIST") ? MainActivity.BROWSER_ARTISTS : MainActivity.BROWSER_ALBUMS);
                                if (a.currentBrowserMode == MainActivity.BROWSER_ROOT) {
                                    a.lastBrowserFocusText = a.t("All Songs");
                                    a.buildFileBrowserUI();
                                } else {
                                    a.buildVirtualCategories(a.virtualQueryType);
                                }
                            }
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_ARTISTS) {
                            a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                            a.lastBrowserFocusText = a.t("Artists");
                            a.buildFileBrowserUI();
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_FAVORITES) {
                            a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                            a.lastBrowserFocusText = a.t("My Favorites");
                            a.buildFileBrowserUI();
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_AUDIOBOOKS) {
                            a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                            a.lastBrowserFocusText = a.t("All Audiobooks");
                            a.buildFileBrowserUI();
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_PLAYLISTS) {
                            a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                            a.lastBrowserFocusText = a.t("Playlists");
                            a.buildFileBrowserUI();
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_M3U_SONGS) {
                            a.currentBrowserMode = MainActivity.BROWSER_PLAYLISTS;
                            a.buildM3uPlaylistUI();
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_ALBUMS) {
                            a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                            a.lastBrowserFocusText = a.t("Albums");
                            a.buildFileBrowserUI();
                        }
                        // 🚀 [Add the code below here to open up the exit path!]
                        else if (a.currentBrowserMode == MainActivity.BROWSER_YEARS) {
                            a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                            a.lastBrowserFocusText = a.t("Years"); // 💡 Set so the wheel focus automatically locks onto the 'Years' button when leaving!
                            a.buildFileBrowserUI();
                        } else if (a.currentBrowserMode == MainActivity.BROWSER_GENRES) {
                            a.currentBrowserMode = MainActivity.BROWSER_ROOT;
                            a.lastBrowserFocusText = a.t("Genres"); // 💡 Set so the wheel focus automatically locks onto the 'Genres' button when leaving!
                            a.buildFileBrowserUI();
                        }
                    }
                } else if (a.currentScreenState == MainActivity.STATE_BLUETOOTH || a.currentScreenState == MainActivity.STATE_WIFI) {
                    a.changeScreen(a.backTargetForUtility);
                } else if (a.currentScreenState == MainActivity.STATE_SETTINGS) {

                    // 🚀 [Step 1] If in the radio settings sub-page mode, first escape to the radio main player mode!
                    if (a.isRadioUIShowing && a.isRadioSettingsMode) {
                        a.isRadioSettingsMode = false;
                        a.isRadioAdjustingFreq = false;
                        a.buildRadioUI();
                        a.clickFeedback();
                        return true;
                    }

                    // 🚀 [Bug completely fixed] Pressing back on the radio main player screen instantly jumps back to the home (main) screen!
                    if (a.isRadioUIShowing) {
                        a.isRadioUIShowing = false;
                        a.isRadioSettingsMode = false;
                        a.isRadioAdjustingFreq = false;
                        a.applyThemeToMainMenu(); // 🚀 Added! Refresh when returning to main from radio
                        a.changeScreen(MainActivity.STATE_MENU);
                        a.clickFeedback();
                        return true;
                    }

                    a.isRadioUIShowing = false;

                    // 🚀 [Routing cleanup fully restored] Figures out the depth and returns to the correct parent menu accordingly!
                    if (a.currentSettingsDepth == 0) {
                        a.applyThemeToMainMenu(); // 🚀 Added! Fully refresh the main screen when fully exiting the settings window!
                        a.changeScreen(MainActivity.STATE_MENU);
                    } else if (a.currentSettingsDepth == 1) {
                        a.buildSettingsUI(); // If it's a group screen (depth 1), go back one step to the group-selector root!
                    } else if (a.currentSettingsDepth == 2) {
                        // A leaf sub-menu directly under a group (depth 2) — go back one step to that group!
                        a.routeBackToSettingsGroup();
                    } else if (a.currentSettingsDepth == 3) {
                        // Handling for exiting an even deeper window (depth 3), like EQ presets or the date/time picker
                        if (a.settingsSubMode == 2 || a.settingsSubMode == 3) {
                            a.buildEqualizerSettingsUI();
                        } else {
                            a.routeBackToSettingsGroup();
                        }
                    }
                    a.clickFeedback();
                    return true;
                }
                return true;
            }
            // 🚀 [Overwrite from here!] While the ultra-fast ListView is active, hand the wheel signal off to the system's native smooth-scroll engine!
            if (a.currentScreenState == MainActivity.STATE_BROWSER && a.listVirtualSongs != null
                    && a.listVirtualSongs.getVisibility() == View.VISIBLE) {

                long now = System.currentTimeMillis();
                if (now - a.lastWheelTime < 40 && a.wheelFastCount < 2) {
                    a.lastWheelTime = now;
                    return true;
                }
                boolean isFastScroll = false;

                // 💡 [Automatic engine] If the wheel spins 3+ clicks in a row within 0.05 seconds (50ms), trigger 'fast-jump mode'!
                if (now - a.lastWheelTime < 50) {
                    a.wheelFastCount++;
                    if (a.wheelFastCount >= 3)
                        isFastScroll = true;
                } else {
                    a.wheelFastCount = 0; // Reset instantly if turned slowly
                }
                a.lastWheelTime = now;

                if (isFastScroll && !a.currentScrollIndexList.isEmpty()) {
                    // 🚀🚀 [Fast-jump mode] Scroll in big chunks by alphabet (first letter)!
                    int currentPos = a.listVirtualSongs.getSelectedItemPosition();
                    if (currentPos < 0)
                        currentPos = 0;
                    char currentChar = a.getInitialChar(a.currentScrollIndexList.get(currentPos));
                    int targetPos = currentPos;

                    if (keyCode == 22) { // wheel flicked down (find the next letter)
                        for (int i = currentPos + 1; i < a.currentScrollIndexList.size(); i++) {
                            if (a.getInitialChar(a.currentScrollIndexList.get(i)) != currentChar) {
                                targetPos = i;
                                break;
                            }
                        }
                    } else if (keyCode == 21) { // wheel flicked up (find the start of the previous letter)
                        char targetChar = currentChar;
                        boolean foundPrevChar = false;
                        for (int i = currentPos - 1; i >= 0; i--) {
                            char c = a.getInitialChar(a.currentScrollIndexList.get(i));
                            if (!foundPrevChar && c != currentChar) {
                                foundPrevChar = true;
                                targetChar = c;
                            }
                            if (foundPrevChar && c != targetChar) {
                                targetPos = i + 1;
                                break;
                            }
                            if (i == 0)
                                targetPos = 0;
                        }
                    }
                    a.listVirtualSongs.setSelection(targetPos);
                    a.clickFeedback();
                    return true;
                } else {
                    // 🐢🐢 [Normal-drive mode] Move slowly and precisely one track at a time, as usual!
                    if (keyCode == 21) {
                        int currentPos = a.listVirtualSongs.getSelectedItemPosition();
                        if (currentPos <= 0) {
                            // 🚀 [Loop-scroll condition control] Only jump instantly to the very last track when looping is enabled.
                            if (a.isLoopScrollOn) {
                                final int lastPos = a.listVirtualSongs.getCount() - 1;
                                a.listVirtualSongs.setSelection(lastPos);
                                a.listVirtualSongs.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        int visiblePos = lastPos - a.listVirtualSongs.getFirstVisiblePosition();
                                        if (visiblePos >= 0 && visiblePos < a.listVirtualSongs.getChildCount()) {
                                            a.listVirtualSongs.getChildAt(visiblePos).requestFocus();
                                        }
                                    }
                                });
                            }
                        } else {
                            a.listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                            a.listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
                        }
                        a.clickFeedback();
                        return true;
                    }
                    if (keyCode == 22) {
                        int currentPos = a.listVirtualSongs.getSelectedItemPosition();
                        if (currentPos == a.listVirtualSongs.getCount() - 1) {
                            // 🚀 [Loop-scroll condition control] Only jump back instantly to the very first track when looping is enabled.
                            if (a.isLoopScrollOn) {
                                a.listVirtualSongs.setSelection(0);
                                a.listVirtualSongs.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (a.listVirtualSongs.getChildCount() > 0)
                                            a.listVirtualSongs.getChildAt(0).requestFocus();
                                    }
                                });
                            }
                        } else {
                            a.listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                            a.listVirtualSongs.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
                        }
                        a.clickFeedback();
                        return true;
                    }
                }
            }
            View c = a.getCurrentFocus();
            if (c != null) {
                if (keyCode == 21) { // wheel turned up (UP)

                    // 🚀 [Radio wheel control] Flicker fully eliminated version
                    if (a.currentScreenState == MainActivity.STATE_SETTINGS && a.isRadioUIShowing) {
                        if (!a.isRadioSettingsMode) {
                            a.adjustVolume(false);
                            return true;
                        } else if (a.isRadioAdjustingFreq) {
                            FmRadioManager fm = FmRadioManager.getInstance(a);
                            float newFreq = fm.currentFreq - 0.1f;
                            if (newFreq < 87.5f) newFreq = 108.0f;
                            if (fm.isPowerUp) fm.tune(newFreq); else fm.currentFreq = newFreq;
                            a.showRadioFreqPopup(newFreq);
                            a.buildRadioUI();
                            return true;
                        }
                    }

                    // 🚀 [Main-menu control fully conquered] On the main screen, always force focus to move strictly through the index order we assembled!
                    if (a.currentScreenState == MainActivity.STATE_MENU) {
                        int targetId = c.getNextFocusUpId();
                        if (targetId != View.NO_ID) {
                            View target = a.findViewById(targetId);
                            if (target != null) {
                                target.requestFocus();
                                a.clickFeedback();
                                return true;
                            }
                        }
                    } else {
                        android.view.ViewGroup parent = (android.view.ViewGroup) c.getParent();
                        if (parent instanceof LinearLayout) {
                            int index = parent.indexOfChild(c);
                            boolean moved = false;
                            for (int i = index - 1; i >= 0; i--) {
                                View n = parent.getChildAt(i);
                                if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                    n.requestFocus();
                                    moved = true;
                                    break;
                                }
                            }
                            if (!moved && a.isLoopScrollOn) {
                                for (int i = parent.getChildCount() - 1; i > index; i--) {
                                    View n = parent.getChildAt(i);
                                    if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                        n.requestFocus();
                                        break;
                                    }
                                }
                            }
                        } else {
                            int targetId = c.getNextFocusUpId();
                            if (targetId != View.NO_ID) {
                                View target = a.findViewById(targetId);
                                if (target != null) {
                                    target.requestFocus();
                                    a.clickFeedback();
                                    return true;
                                }
                            }
                            View n = c.focusSearch(View.FOCUS_UP);
                            if (n != null) n.requestFocus();
                        }
                    }
                    a.clickFeedback();
                    return true;
                }
                if (keyCode == 22) { // wheel turned down (DOWN)

                    // 🚀 [Radio wheel control] Flicker fully eliminated version
                    if (a.currentScreenState == MainActivity.STATE_SETTINGS && a.isRadioUIShowing) {
                        if (!a.isRadioSettingsMode) {
                            a.adjustVolume(true);
                            return true;
                        } else if (a.isRadioAdjustingFreq) {
                            FmRadioManager fm = FmRadioManager.getInstance(a);
                            float newFreq = fm.currentFreq + 0.1f;
                            if (newFreq > 108.0f) newFreq = 87.5f;
                            if (fm.isPowerUp) fm.tune(newFreq); else fm.currentFreq = newFreq;
                            a.showRadioFreqPopup(newFreq);
                            a.buildRadioUI();
                            return true;
                        }
                    }

                    // 🚀 [Main-menu control fully conquered] On the main screen, always force focus to move strictly through the index order we assembled!
                    if (a.currentScreenState == MainActivity.STATE_MENU) {
                        int targetId = c.getNextFocusDownId();
                        if (targetId != View.NO_ID) {
                            View target = a.findViewById(targetId);
                            if (target != null) {
                                target.requestFocus();
                                a.clickFeedback();
                                return true;
                            }
                        }
                    } else {
                        android.view.ViewGroup parent = (android.view.ViewGroup) c.getParent();
                        if (parent instanceof LinearLayout) {
                            int index = parent.indexOfChild(c);
                            boolean moved = false;
                            for (int i = index + 1; i < parent.getChildCount(); i++) {
                                View n = parent.getChildAt(i);
                                if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                    n.requestFocus();
                                    moved = true;
                                    break;
                                }
                            }
                            if (!moved && a.isLoopScrollOn) {
                                for (int i = 0; i < index; i++) {
                                    View n = parent.getChildAt(i);
                                    if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                                        n.requestFocus();
                                        break;
                                    }
                                }
                            }
                        } else {
                            int targetId = c.getNextFocusDownId();
                            if (targetId != View.NO_ID) {
                                View target = a.findViewById(targetId);
                                if (target != null) {
                                    target.requestFocus();
                                    a.clickFeedback();
                                    return true;
                                }
                            }
                            View n = c.focusSearch(View.FOCUS_DOWN);
                            if (n != null) n.requestFocus();
                        }
                    }
                    a.clickFeedback();
                    return true;
                }
            } else {
                // 🚀 [Focus-jump bug fully resolved] Right after first entering the screen, focus is temporarily absent (null),
                // and this completely blocks the system from ambiguously warping to some bottom button the moment the user first clicks the wheel.
                if (keyCode == 21 || keyCode == 22) {
                    View firstBtn = a.findViewById(10000); // Target the unique ID of button 0 (Now Playing)
                    if (firstBtn != null) {
                        firstBtn.requestFocus(); // Force it back to button 0!
                        a.clickFeedback();
                        return true; // 💡 Consume the event here to stop it from jumping to the wrong button.
                    }
                }
            }
            return a.superOnKeyDown(keyCode, event);
        }

        return a.superOnKeyDown(keyCode, event);
    }

    public boolean onKeyUp(MainActivity a, int keyCode, KeyEvent event) {
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 20)
                isScreenOn = pm.isInteractive();
            else
                isScreenOn = pm.isScreenOn();
        } catch (Exception e) {
            Log.d(TAG, "onKeyUp failed", e);
        }

        boolean isWakingUp = !isScreenOn || ((event.getFlags() & KeyEvent.FLAG_WOKE_HERE) != 0)
                || (System.currentTimeMillis() - a.lastScreenOnTime < 500);

        if (isWakingUp) {
            return true;
        }

        // 💡 [Key blocking zone] On 'release' of a wheel action (21, 22) or back (BACK)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 21 || keyCode == 22) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // 🚀 [Guard] If a long press (screen-off or playlist popup) has already been handled, skip the short-click routine
            if (a.isLongPressConsumed) {
                a.isLongPressConsumed = false;
                return true;
            }

            if ((event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                // 🚀 [Smart short-click branching] On the player screen, the long-press was given to screen-off,
                // so a double-click opens the quick menu (playback/queue/Wi-Fi/Bluetooth shortcuts,
                // including favorite toggle) instead of firing play/pause a second time.
                if (a.currentScreenState == MainActivity.STATE_PLAYER) {
                    long now = System.currentTimeMillis();
                    if (now - a.lastCenterUpTime < 300) {
                        a.doubleClickHandler.removeCallbacks(a.singleClickRunnable);
                        a.lastCenterUpTime = 0; // Reset the timer
                        a.clickFeedback();
                        a.showQuickMenu();
                    } else {
                        a.lastCenterUpTime = now;
                        a.doubleClickHandler.postDelayed(a.singleClickRunnable, 300);
                    }
                } else {
                    // 🚀 On every other screen (main menu selection, settings logic, library lists, etc.),
                    // there's no 0.3-second wait — a full one-touch, lightning-fast click fires instantly, removing any lag.
                    try { a.handleCenterShortClick(); } catch (Exception e) { Log.d(TAG, "onKeyUp failed", e); }
                }
            }
            return true;
        }
        // 🚀 [Bug fully fixed] Smart, fully cleaned-up control scheme for the bottom hardware play/stop button!
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == 85 || keyCode == 86 || keyCode == 126 || keyCode == 127) {
            if (event.getRepeatCount() == 0) {
                FmRadioManager fm = FmRadioManager.getInstance(a);

                // 💡 [Top-priority rule] If the user is currently looking at the 'music player screen (STATE_PLAYER)',
                // open the gate so the "always play/pause the music player" command works regardless of the radio's state!
                if (a.currentScreenState == MainActivity.STATE_PLAYER) {
                    if (fm.isPowerUp) {
                        fm.powerDown(); // If the radio was making sound, silently turn it off first.
                    }
                    AudioPlayerManager.getInstance().playOrPauseMusic();
                    a.activePlayer = 0; // Force control back to the music player!
                }
                // 💡 On any other general screen (main menu, settings, etc.), follow the originally designed activePlayer rule.
                else if (a.activePlayer == 1) {
                    if (fm.isPowerUp) {
                        // (kept off intentionally, matches original)
                    } else {
                        // 🚀 [Error fix] Changed so playOrPauseMusic() only runs when music is actually playing!
                        AudioPlayerManager amInstance = AudioPlayerManager.getInstance();
                        if (amInstance.isPlaying()) {
                            amInstance.playOrPauseMusic();
                        }
                        // Give the music player a moment to actually pause before the FM chip claims the
                        // audio session; posted with a delay instead of Thread.sleep so the UI thread isn't blocked.
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            fm.powerUpAsync(fm.currentFreq, success -> {
                                if (!success) {
                                    Toast.makeText(a, "Radio Error: " + fm.lastError, Toast.LENGTH_SHORT).show();
                                }
                                a.updateGlobalStatusPlayIcon();
                                if (a.currentScreenState == MainActivity.STATE_SETTINGS) a.buildRadioUI();
                            });
                        }, 50);
                        a.clickFeedback();
                        return true;
                    }
                    if (a.currentScreenState == MainActivity.STATE_SETTINGS) a.buildRadioUI();
                } else {
                    AudioPlayerManager.getInstance().playOrPauseMusic();
                }

                a.updateGlobalStatusPlayIcon(); // Sync the top status bar's play/stop image
                a.clickFeedback();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            if (!a.isSeekPerformed) {
                if (a.activePlayer == 1) {
                    a.tuneToNextSavedRadioChannel(true); // 🚀 Just cleanly call the engine!
                } else {
                    AudioPlayerManager.getInstance().nextTrack();
                }
                a.clickFeedback();
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88) {
            if (!a.isSeekPerformed) {
                if (a.activePlayer == 1) {
                    a.tuneToNextSavedRadioChannel(false); // 🚀 Just cleanly call the engine!
                } else {
                    AudioPlayerManager.getInstance().prevTrack();
                }
                a.clickFeedback();
            }
            return true;
        }

        return a.superOnKeyUp(keyCode, event);
    }

    public boolean onKeyLongPress(MainActivity a, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            a.clickFeedback();
            a.isLongPressConsumed = true; // Blocks the short click from firing again on release

            // 🚀 [Branch 1] The main screen, player screen, settings screen, and other system windows always trigger screen-off!
            if (a.currentScreenState == MainActivity.STATE_MENU || a.currentScreenState == MainActivity.STATE_PLAYER || a.currentScreenState == MainActivity.STATE_SETTINGS
                    || a.currentScreenState == MainActivity.STATE_BLUETOOTH || a.currentScreenState == MainActivity.STATE_WIFI || a.currentScreenState == MainActivity.STATE_BRIGHTNESS
                    || a.currentScreenState == MainActivity.STATE_STORAGE || a.currentScreenState == MainActivity.STATE_WEBSERVER) {

                a.turnOffScreen();
                return true;
            }
            // 🚀 [Branch 2] Weighing up the exception handling when entering the library (Browser) screen
            else if (a.currentScreenState == MainActivity.STATE_BROWSER) {
                // Check whether the current browser is showing a screen that lists pure tracks/files
                boolean isFileVisible = (a.currentBrowserMode == MainActivity.BROWSER_FOLDER
                        || a.currentBrowserMode == MainActivity.BROWSER_VIRTUAL_SONGS
                        || a.currentBrowserMode == MainActivity.BROWSER_FAVORITES
                        || a.currentBrowserMode == MainActivity.BROWSER_M3U_SONGS
                        || a.currentBrowserMode == MainActivity.BROWSER_AUDIOBOOKS);

                if (isFileVisible) {
                    // 💡 [Per request] When files are visible, exclude it from the screen-off targets and keep the existing "playlist popup (long press)" behavior!
                    View c = a.getCurrentFocus();
                    if (c != null) {
                        c.performLongClick();
                    }
                } else {
                    // 💡 On a root menu or artist/album category window with no files visible, conveniently let screen-off work!
                    a.turnOffScreen();
                }
                return true;
            }
        }
        return a.superOnKeyLongPress(keyCode, event);
    }
}
