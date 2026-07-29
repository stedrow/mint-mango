package com.themoon.y1;

import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Replaces the framework's long-press-power menu (Power off / Restart) with our own.
 *
 * KEYCODE_POWER never reaches an app -- PhoneWindowManager swallows it in
 * interceptKeyBeforeQueueing -- so we time the key ourselves off the raw evdev node. That needs
 * gid 1004 (input) and the launcher living in /system/priv-app; both come from
 * scripts/patch-device-power-menu.sh, which also strips the stock dialog out of
 * PhoneWindowManager. Without that script this class simply never opens the node and the
 * long-press menu doesn't appear.
 */
public final class PowerMenuInterceptor {

    private static final String TAG = "PowerMenuInterceptor";
    private static final String EVENT_NODE = "/dev/input/event0";
    /** struct input_event on 32-bit ARM: timeval (2 x 4 bytes) + type + code + value. */
    private static final int EVENT_SIZE = 16;
    private static final int EV_KEY = 0x01;
    private static final int KEY_POWER = 116;
    /** Matches ViewConfiguration.getGlobalActionKeyTimeout(), i.e. what the stock menu used. */
    private static final long LONG_PRESS_MS = 500;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile InputStream evdev;
    private Thread reader;

    private final Runnable showMenu = new Runnable() {
        @Override
        public void run() {
            com.themoon.y1.managers.SettingsUiManager.getInstance().showPowerMenu(activity);
        }
    };

    public PowerMenuInterceptor(MainActivity activity) {
        this.activity = activity;
    }

    public void onResume() {
        if (reader != null) return;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readEvents();
            }
        }, "PowerKeyReader");
        reader.start();
    }

    public void onDestroy() {
        handler.removeCallbacks(showMenu);
        reader = null;
        // The reader thread is parked in a blocking read(); closing the node is what unblocks it.
        InputStream open = evdev;
        evdev = null;
        if (open == null) return;
        try {
            open.close();
        } catch (Exception e) {
            Log.d(TAG, "closing " + EVENT_NODE + " failed", e);
        }
    }

    private void readEvents() {
        DataInputStream in = null;
        try {
            in = new DataInputStream(new FileInputStream(EVENT_NODE));
            evdev = in;
            byte[] buf = new byte[EVENT_SIZE];
            while (evdev != null) {
                in.readFully(buf);
                ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                int type = b.getShort(8) & 0xFFFF;
                int code = b.getShort(10) & 0xFFFF;
                int value = b.getInt(12);
                if (type != EV_KEY || code != KEY_POWER) continue;
                if (value == 1) onPowerDown();
                else handler.removeCallbacks(showMenu);
            }
        } catch (Exception e) {
            // Expected on close(); anything else means we lost the node (no gid input?) and the
            // long-press menu simply won't come up.
            if (evdev != null) Log.w(TAG, "power key reader stopped", e);
        } finally {
            evdev = null;
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
                // already closed
            }
        }
    }

    private void onPowerDown() {
        // A press that wakes the device shouldn't also arm the menu -- that press is a wake,
        // not a request for power options.
        PowerManager pm = (PowerManager) activity.getSystemService(android.content.Context.POWER_SERVICE);
        if (activity.isFakeScreenOff || (pm != null && !pm.isScreenOn())) return;
        handler.postDelayed(showMenu, LONG_PRESS_MS);
    }
}
