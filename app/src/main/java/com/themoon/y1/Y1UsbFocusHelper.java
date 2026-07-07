package com.themoon.y1;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps the launcher in the foreground while a USB host (PC) is connected. Stock Y1 firmware
 * pops SystemUI's UsbStorageActivity over whatever app is running as soon as a host is
 * detected, silently killing playback UI / focus. Ported (simplified) from thesolarproject/solar's
 * Y1UsbFocusHelper: poll window focus while a host is connected and reclaim it with
 * moveTaskToFront + a HOME intent when SystemUI steals it.
 */
public final class Y1UsbFocusHelper {

    private static final String TAG = "Y1UsbFocusHelper";
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_USB_CONNECTED = "connected";
    private static final String EXTRA_HOST_CONNECTED = "host_connected";

    /** Poll rate while focus was just lost — fast enough to feel instant. */
    private static final int POLL_INTERVAL_MS = 400;
    /** Poll rate once we've held focus for a tick — saves CPU during normal playback. */
    private static final int POLL_INTERVAL_STABLE_MS = 1000;
    /** After this many back-to-back reclaim failures, stop hammering and fall back to a slow poll. */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final int POLL_INTERVAL_BACKED_OFF_MS = 5000;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver usbReceiver;
    private boolean usbRegistered;
    private boolean hostConnected;
    private boolean polling;
    private int consecutiveFailures;

    public Y1UsbFocusHelper(Activity activity) {
        this.activity = activity;
    }

    public void onResume() {
        registerIfNeeded();
    }

    public void onDestroy() {
        stopPolling();
        if (usbRegistered && usbReceiver != null) {
            try {
                activity.unregisterReceiver(usbReceiver);
            } catch (Exception e) {
                Log.d(TAG, "unregisterReceiver failed (already unregistered?)", e);
            }
            usbRegistered = false;
        }
    }

    private void registerIfNeeded() {
        if (usbRegistered) return;
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleUsbStateIntent(intent);
            }
        };
        activity.registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_STATE));
        usbRegistered = true;
        // Sticky USB_STATE may already have fired before we registered.
        Intent sticky = activity.registerReceiver(null, new IntentFilter(ACTION_USB_STATE));
        if (sticky != null) handleUsbStateIntent(sticky);
    }

    private void handleUsbStateIntent(Intent intent) {
        if (intent == null || !ACTION_USB_STATE.equals(intent.getAction())) return;
        boolean connected = intent.getBooleanExtra(EXTRA_USB_CONNECTED, false);
        boolean host = intent.getBooleanExtra(EXTRA_HOST_CONNECTED, false)
                || intent.getBooleanExtra("mass_storage", false)
                || intent.getBooleanExtra("USB_IS_PC_KNOW_ME", false);

        if (!connected) {
            hostConnected = false;
            stopPolling();
            return;
        }
        if (host && !hostConnected) {
            Log.d(TAG, "USB host connected, starting focus-reclaim polling");
            hostConnected = true;
            consecutiveFailures = 0;
            bringToFrontAsync();
            startPolling();
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            boolean hadFocus = activity.hasWindowFocus();
            int delay;
            if (hadFocus) {
                consecutiveFailures = 0;
                delay = POLL_INTERVAL_STABLE_MS;
            } else {
                bringToFrontAsync();
                consecutiveFailures++;
                // Reclaim keeps failing (e.g. a legitimate system dialog holding focus, or the
                // reclaim call itself being rejected) -- stop hammering moveTaskToFront/startActivity
                // every 400ms and fall back to a slow poll so this can't peg a single-core CPU.
                delay = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
                        ? POLL_INTERVAL_BACKED_OFF_MS : POLL_INTERVAL_MS;
            }
            handler.postDelayed(this, delay);
        }
    };

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

    private void stopPolling() {
        polling = false;
        consecutiveFailures = 0;
        handler.removeCallbacks(pollRunnable);
    }

    /**
     * moveTaskToFront/startActivity are ordinary Binder IPCs that don't require the calling
     * thread to be the main thread -- but they block on system_server, which can occasionally
     * stall on this hardware (observed once during testing: a single startActivity() call on
     * the main thread blocked long enough to trigger an ANR). Run them off the main thread so
     * a slow system_server can never freeze the UI here.
     */
    private void bringToFrontAsync() {
        new Thread(this::bringToFront, "Y1UsbFocusReclaim").start();
    }

    private void bringToFront() {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.moveTaskToFront(activity.getTaskId(), 0);
        } catch (Exception e) {
            Log.d(TAG, "moveTaskToFront failed", e);
        }
        try {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(startMain);
        } catch (Exception e) {
            Log.d(TAG, "startActivity(HOME) failed", e);
        }
    }
}
