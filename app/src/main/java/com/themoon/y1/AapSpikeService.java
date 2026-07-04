package com.themoon.y1;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Set;

/**
 * Milestone 0 spike: can this MTK 4.2.2 stack open an app-level L2CAP socket
 * to the AirPods AAP service (PSM 0x1001)?
 *
 * Throwaway code — start with:
 *   adb shell am startservice -n com.themoon.y1/.AapSpikeService
 * optionally with -e mac AA:BB:CC:DD:EE:FF to pick a specific device.
 * Watch:  adb logcat -s AAPSPIKE BluetoothSocket_MTK
 */
public class AapSpikeService extends Service {

    private static final String TAG = "AAPSPIKE";
    private static final int AAP_PSM = 0x1001;

    private static final byte[] AAP_HANDSHAKE = {
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    private static final byte[] AAP_ENABLE_NOTIFICATIONS = {
            0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFE, (byte) 0xFF
    };

    private volatile boolean running = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String macExtra = intent != null ? intent.getStringExtra("mac") : null;
        if (running) {
            Log.i(TAG, "spike already running, ignoring start");
            return START_NOT_STICKY;
        }
        running = true;
        Log.i(TAG, "=== M0 L2CAP spike starting (mac extra: " + macExtra + ") ===");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runSpike(macExtra);
                } catch (Throwable t) {
                    Log.e(TAG, "spike died", t);
                } finally {
                    running = false;
                    Log.i(TAG, "=== M0 spike finished ===");
                    stopSelf();
                }
            }
        }, "aap-spike").start();
        return START_NOT_STICKY;
    }

    private void runSpike(String macExtra) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.e(TAG, "no BluetoothAdapter");
            return;
        }

        if (!adapter.isEnabled()) {
            Log.i(TAG, "BT off -> enable()");
            adapter.enable();
            for (int i = 0; i < 30 && !adapter.isEnabled(); i++) Thread.sleep(1000);
            if (!adapter.isEnabled()) {
                Log.e(TAG, "BT did not turn on within 30s, aborting");
                return;
            }
            Log.i(TAG, "BT is on; settling 5s");
            Thread.sleep(5000);
        }

        BluetoothDevice target = null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        for (BluetoothDevice d : bonded) {
            Log.i(TAG, "bonded: " + d.getAddress() + " '" + d.getName() + "'");
            String name = d.getName();
            if (macExtra != null) {
                if (d.getAddress().equalsIgnoreCase(macExtra)) target = d;
            } else if (name != null && name.toLowerCase().contains("airpods")) {
                target = d;
            }
        }
        if (target == null && macExtra != null) {
            target = adapter.getRemoteDevice(macExtra);
        }
        if (target == null) {
            Log.e(TAG, "no AirPods among bonded devices and no mac extra, aborting");
            return;
        }
        Log.i(TAG, "target: " + target.getAddress() + " '" + target.getName() + "'");

        logSocketClassRecon();

        int typeL2cap = 3;
        try {
            Field f = BluetoothSocket.class.getDeclaredField("TYPE_L2CAP");
            f.setAccessible(true);
            typeL2cap = f.getInt(null);
        } catch (Throwable t) {
            Log.w(TAG, "TYPE_L2CAP field not readable, assuming 3: " + t);
        }
        Log.i(TAG, "TYPE_L2CAP=" + typeL2cap + " PSM=0x" + Integer.toHexString(AAP_PSM));

        boolean[][] variants = {{false, false}, {true, true}};
        for (boolean[] v : variants) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                Log.i(TAG, ">>> attempt auth=" + v[0] + " encrypt=" + v[1] + " try#" + attempt);
                if (tryL2cap(target, typeL2cap, v[0], v[1])) {
                    return; // success path already did the AAP exchange
                }
                Thread.sleep(3000);
            }
        }
        Log.e(TAG, "RESULT: all L2CAP connect variants FAILED");
    }

    private void logSocketClassRecon() {
        try {
            for (Constructor<?> c : BluetoothSocket.class.getDeclaredConstructors()) {
                Log.i(TAG, "ctor: " + c);
            }
            for (String fn : new String[]{"TYPE_RFCOMM", "TYPE_SCO", "TYPE_L2CAP"}) {
                try {
                    Field f = BluetoothSocket.class.getDeclaredField(fn);
                    f.setAccessible(true);
                    Log.i(TAG, "field " + fn + "=" + f.getInt(null));
                } catch (Throwable t) {
                    Log.i(TAG, "field " + fn + ": " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "recon failed", t);
        }
    }

    private boolean tryL2cap(BluetoothDevice device, int type, boolean auth, boolean encrypt) {
        BluetoothSocket socket = null;
        try {
            Constructor<BluetoothSocket> ctor = BluetoothSocket.class.getDeclaredConstructor(
                    int.class, int.class, boolean.class, boolean.class,
                    BluetoothDevice.class, int.class, ParcelUuid.class);
            ctor.setAccessible(true);
            socket = ctor.newInstance(type, -1, auth, encrypt, device, AAP_PSM, null);
            Log.i(TAG, "socket constructed OK");
        } catch (Throwable t) {
            Log.e(TAG, "socket construction FAILED", t);
            return false;
        }

        final BluetoothSocket watchedSocket = socket;
        final boolean[] connectDone = {false};
        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(20000);
                    synchronized (connectDone) {
                        if (!connectDone[0]) {
                            Log.w(TAG, "watchdog: connect() >20s, forcing close");
                            try { watchedSocket.close(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (InterruptedException ignored) {}
            }
        }, "aap-spike-watchdog");
        watchdog.start();

        try {
            long t0 = System.currentTimeMillis();
            socket.connect();
            synchronized (connectDone) { connectDone[0] = true; }
            watchdog.interrupt();
            Log.i(TAG, "RESULT: L2CAP CONNECTED in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            synchronized (connectDone) { connectDone[0] = true; }
            watchdog.interrupt();
            Log.e(TAG, "connect() FAILED", t);
            try { socket.close(); } catch (Throwable ignored) {}
            return false;
        }

        try {
            aapExchange(socket);
        } catch (Throwable t) {
            Log.e(TAG, "AAP exchange error", t);
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
            Log.i(TAG, "socket closed");
        }
        return true;
    }

    private void aapExchange(BluetoothSocket socket) throws Exception {
        final InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        final long deadline = System.currentTimeMillis() + 90000;
        final int[] totalReads = {0};
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[1024];
                try {
                    while (System.currentTimeMillis() < deadline) {
                        int n = in.read(buf);
                        if (n < 0) {
                            Log.i(TAG, "RX: EOF");
                            return;
                        }
                        if (n > 0) {
                            totalReads[0]++;
                            Log.i(TAG, "RX[" + n + "]: " + hex(buf, n));
                        }
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "reader ended: " + t);
                }
            }
        }, "aap-spike-reader");
        reader.start();

        Log.i(TAG, "TX handshake: " + hex(AAP_HANDSHAKE, AAP_HANDSHAKE.length));
        out.write(AAP_HANDSHAKE);
        out.flush();
        Thread.sleep(1000);

        Log.i(TAG, "TX enable-notifications: "
                + hex(AAP_ENABLE_NOTIFICATIONS, AAP_ENABLE_NOTIFICATIONS.length));
        out.write(AAP_ENABLE_NOTIFICATIONS);
        out.flush();

        Log.i(TAG, "listening for AAP packets for 90s — exercise the AirPods now"
                + " (remove/insert an AirPod, open/close case lid)");
        reader.join(95000);
        Log.i(TAG, "RESULT: AAP exchange done, packets received: " + totalReads[0]);
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
            if (i < len - 1) sb.append(' ');
        }
        return sb.toString();
    }
}
