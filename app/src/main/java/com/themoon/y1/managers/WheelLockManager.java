package com.themoon.y1.managers;

import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import com.themoon.y1.MainActivity;
import com.themoon.y1.views.WheelLockRingView;

/**
 * "Wheel lock" pocket-misfire guard: once the screen wakes, all key input is absorbed until the
 * wheel has been turned WHEEL_UNLOCK_THRESHOLD clicks. Extracted from MainActivity; the overlay
 * View tree itself is still built in MainActivity.onCreate() (tightly coupled to root/font/t())
 * and handed to this manager via bindViews() once, since only the state machine below needed to
 * move to shrink MainActivity's dispatchKeyEvent() gate.
 */
public class WheelLockManager {
    private static WheelLockManager instance;

    // Half as many segments as the ring only sweeps a half circle (180°) instead of a full one --
    // keeps each wedge the same angular width as before, so filling the shorter arc only takes
    // half the wheel rotation instead of a full turn.
    private static final int WHEEL_UNLOCK_THRESHOLD = 4;
    private static final long WHEEL_UNLOCK_RELEASE_TIMEOUT_MS = 500;

    private boolean enabled = false; // Settings toggle (default OFF)
    private boolean active = false; // whether it is currently locked and waiting
    private int unlockProgress = 0;
    // Remembers the last tick direction (0 = none yet) so reversing direction doesn't count toward progress
    private int lastWheelDirection = 0;

    private LinearLayout overlay;
    private WheelLockRingView ring;

    // Timer that resets progress if the wheel stops turning (no further tick) before the unlock completes
    private final Handler handler = new Handler();
    private final Runnable releaseResetRunnable = new Runnable() {
        @Override
        public void run() {
            if (active && unlockProgress < WHEEL_UNLOCK_THRESHOLD) {
                unlockProgress = 0;
                lastWheelDirection = 0;
                if (ring != null) ring.resetProgress();
            }
        }
    };

    private WheelLockManager() {}

    public static synchronized WheelLockManager getInstance() {
        if (instance == null) {
            instance = new WheelLockManager();
        }
        return instance;
    }

    /** Called once from MainActivity.onCreate() after the overlay View tree is built. */
    public void bindViews(LinearLayout overlay, WheelLockRingView ring) {
        this.overlay = overlay;
        this.ring = ring;
        ring.setSegments(WHEEL_UNLOCK_THRESHOLD);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isActive() {
        return active;
    }

    /** Called right after the hardware screen wake (ACTION_SCREEN_ON) if enabled. */
    public void activate() {
        active = true;
        unlockProgress = 0;
        lastWheelDirection = 0;
        handler.removeCallbacks(releaseResetRunnable);
        if (overlay != null) {
            if (ring != null) ring.resetProgress();
            overlay.setVisibility(View.VISIBLE);
        }
    }

    public void deactivate() {
        active = false;
        unlockProgress = 0;
        lastWheelDirection = 0;
        handler.removeCallbacks(releaseResetRunnable);
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
        }
    }

    /**
     * Call from MainActivity.dispatchKeyEvent() while active, before any other key handling.
     * Absorbs every key; only wheel-turn codes (21/22) count toward unlocking. Always returns
     * true (consumed) since the caller's gate is "if (isActive()) return handleKeyEvent(...)".
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == 21 || keyCode == 22) {
                // Reversing direction invalidates prior progress -- restart the count in the new direction
                if (lastWheelDirection != 0 && lastWheelDirection != keyCode) {
                    unlockProgress = 0;
                }
                lastWheelDirection = keyCode;
                unlockProgress++;
                if (ring != null) ring.setProgress(unlockProgress);

                // While still turning, push the reset timer back; if the wheel stops before the
                // unlock completes (within the timeout), progress resets.
                handler.removeCallbacks(releaseResetRunnable);
                if (unlockProgress >= WHEEL_UNLOCK_THRESHOLD) {
                    deactivate();
                    if (MainActivity.instance != null) MainActivity.instance.clickFeedback();
                } else {
                    handler.postDelayed(releaseResetRunnable, WHEEL_UNLOCK_RELEASE_TIMEOUT_MS);
                }
            }
        }
        return true;
    }

    /** Call from MainActivity.onDestroy(). */
    public void cancelPendingReset() {
        handler.removeCallbacks(releaseResetRunnable);
    }
}
