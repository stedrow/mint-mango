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

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver usbReceiver;
    private boolean usbRegistered;
    private boolean hostConnected;
    private boolean polling;

    public Y1UsbFocusHelper(Activity activity) {
        this.activity = activity;
    }

    public void onResume() {
        registerIfNeeded();
    }

    public void onDestroy() {
        stopPolling();
        if (usbRegistered && usbReceiver != null) {
            try { activity.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
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
            bringToFront();
            startPolling();
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            boolean hadFocus = activity.hasWindowFocus();
            if (!hadFocus) {
                bringToFront();
            }
            handler.postDelayed(this, hadFocus ? POLL_INTERVAL_STABLE_MS : POLL_INTERVAL_MS);
        }
    };

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

    private void stopPolling() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
    }

    private void bringToFront() {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.moveTaskToFront(activity.getTaskId(), 0);
        } catch (Exception ignored) {}
        try {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(startMain);
        } catch (Exception ignored) {}
    }
}
