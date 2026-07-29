package com.themoon.y1;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent AAP (Apple Accessory Protocol) client for AirPods, speaking L2CAP
 * PSM 0x1001 via the reflected hidden API-17 BluetoothSocket ctor. Requires the
 * ps_type patch in scripts/airpods-aap/ to be flashed (see PHASE2_PLAN.md) --
 * without it the connect fails immediately and this service gives up quietly.
 *
 * Lifecycle: started/stopped by MainActivity alongside the A2DP audio
 * connection for the target device. Not gated to AirPods specifically -- any
 * device that fails the AAP connect a few times in a row is assumed non-Apple
 * and the service stops itself instead of retrying forever.
 */
public class AapService extends Service {

    private static final String TAG = "AapService";
    private static final int AAP_PSM = 0x1001;
    /** Stands in for an advertiser address when ear state came over L2CAP. */
    private static final String AAP_L2CAP_SOURCE = "l2cap";
    private static final int MAX_BOOTSTRAP_ATTEMPTS = 3;
    private static final int MAX_BUFFER_BYTES = 8192;

    private static final byte[] AAP_HANDSHAKE = {
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    // Byte-for-byte from LibrePods (linux/airpods_packets.h, Connection namespace),
    // which is the reference implementation for this protocol. The previous value
    // here was both a byte short and had 0xFE where the fifth mask byte should be
    // 0xFF, so the AirPods simply ignored it and never sent notifications.
    private static final byte[] AAP_ENABLE_NOTIFICATIONS = {
            0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };
    /** Sent between the handshake and the notification request, as LibrePods does. */
    private static final byte[] AAP_SET_SPECIFIC_FEATURES = {
            0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, (byte) 0xD7,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    /** The AirPods' reply to the handshake; the rest of the sequence follows it. */
    private static final byte[] AAP_HANDSHAKE_ACK = {0x01, 0x00, 0x04, 0x00};
    private static final byte[] MAGIC = {0x04, 0x00, 0x04, 0x00};

    private static final int OPCODE_BATTERY = 0x0004;
    private static final int OPCODE_EAR_DETECTION = 0x0006;
    /**
     * Settings channel. Both directions carry the same shape -- identifier byte
     * then value byte -- so the pods report a setting the same way we set it.
     * Confirmed on-device: 04 00 04 00 09 00 0D 03 00 00 00 arriving unprompted
     * is "listening mode is currently transparency".
     */
    private static final int OPCODE_CONTROL = 0x0009;
    /** Stem press, once the matching gesture bit is set via CTRL_STEM_GESTURES. */
    private static final int OPCODE_STEM_PRESS = 0x0019;
    /** Conversational-awareness speech events (distinct from the on/off setting). */
    private static final int OPCODE_CONV_AWARENESS = 0x004B;
    /**
     * List of devices the pods are connected to: {@code 01 00 <count>} then
     * {@code <6-byte MAC little-endian> <2 flag bytes>} per device. Decoded from
     * captures on this device -- one of the MACs is the Y2's own address.
     * Useful because multipoint is a common explanation for audio going
     * somewhere unexpected.
     */
    private static final int OPCODE_CONNECTED_DEVICES = 0x002E;
    /**
     * Audio-source change: 6-byte MAC (little-endian) then 2 flag bytes.
     * Undocumented upstream; decoded from captures here. This is the best
     * candidate for a signal that the pods stopped rendering *our* audio --
     * the failure that previously had no signal at all and could only be
     * recovered by removing and reinserting a bud.
     */
    private static final int OPCODE_AUDIO_SOURCE = 0x000C;
    /**
     * Audio-source *response*: 6-byte MAC (little-endian) then a flag byte, 13
     * bytes total. All zeros means the pods are rendering for nobody.
     *
     * Captured on-device across a bud-out/bud-in cycle: zeros the moment audio
     * stopped, this device's address the moment it resumed. That makes it the
     * signal the silent-mute failure never had -- an audio drop is not a
     * Bluetooth drop, so until now there was nothing to trigger recovery on
     * except a human pulling a bud out.
     */
    private static final int OPCODE_AUDIO_SOURCE_RESP = 0x000E;

    public static final int EAR_IN_EAR = 0x00;
    public static final int EAR_OUT_OF_EAR = 0x01;
    public static final int EAR_IN_CASE = 0x02;
    public static final int EAR_UNKNOWN = -1;

    public static final int BATTERY_UNKNOWN = -1;
    private static final int BATTERY_CHARGING = 0x01;
    private static final int BATTERY_DISCONNECTED = 0x04;
    /** Apple's "optimized" charging -- still charging as far as the UI cares. */
    private static final int BATTERY_CHARGING_OPTIMIZED = 0x05;

    // Settings carried on OPCODE_CONTROL. Identifiers are from LibrePods'
    // ControlCommandIdentifiers, which were extracted from iOS 19.1's Bluetooth
    // stack; CTRL_LISTENING_MODE is additionally confirmed on hardware here (an
    // AirPods Pro 3 reports 0x0D/3 for transparency).
    public static final int CTRL_LISTENING_MODE = 0x0D;
    public static final int CTRL_EAR_DETECTION = 0x0A;
    public static final int CTRL_CONVERSATIONAL_AWARENESS = 0x28;
    public static final int CTRL_DOUBLE_CLICK_INTERVAL = 0x17;
    public static final int CTRL_CLICK_HOLD_INTERVAL = 0x18;
    public static final int CTRL_CLICK_HOLD_MODE = 0x16;
    public static final int CTRL_STEM_GESTURES = 0x39;

    // Nearly every setting uses this pair. Note the direction: enabled is 1.
    public static final int CTRL_ON = 0x01;
    public static final int CTRL_OFF = 0x02;

    // Stem gestures we can ask the pods to hand to us instead of acting on
    // themselves (identifier 0x39, a bitmask).
    public static final int STEM_SINGLE = 0x01;
    public static final int STEM_DOUBLE = 0x02;
    public static final int STEM_TRIPLE = 0x04;
    public static final int STEM_LONG = 0x08;

    // ...and how a press is reported back (opcode 0x19). Different numbering
    // from the bitmask above, which is easy to trip over.
    public static final int PRESS_SINGLE = 0x05;
    public static final int PRESS_DOUBLE = 0x06;
    public static final int PRESS_TRIPLE = 0x07;
    public static final int PRESS_LONG = 0x08;
    public static final int BUD_LEFT = 0x01;
    public static final int BUD_RIGHT = 0x02;

    public static final int NOISE_OFF = 0x01;
    public static final int NOISE_ANC = 0x02;
    public static final int NOISE_TRANSPARENCY = 0x03;
    public static final int NOISE_ADAPTIVE = 0x04;
    public static final int NOISE_UNKNOWN = -1;

    public interface Listener {
        void onAapStateChanged(AapState state);
        void onAapConnectionChanged(boolean connected);
    }

    /**
     * Stem presses, delivered only for gestures enabled via
     * {@link #setStemGestures(int)}. Separate from {@link Listener} so existing
     * implementors don't have to care.
     */
    public interface StemListener {
        /** @param type a PRESS_* constant, @param bud BUD_LEFT or BUD_RIGHT. */
        void onStemPress(int type, int bud);
    }

    public static void addStemListener(StemListener l) {
        stemListeners.add(l);
    }

    public static void removeStemListener(StemListener l) {
        stemListeners.remove(l);
    }

    public static final class AapState {
        public int earLeft = EAR_UNKNOWN;
        public int earRight = EAR_UNKNOWN;
        public int batteryCase = BATTERY_UNKNOWN;
        public int batteryLeft = BATTERY_UNKNOWN;
        public int batteryRight = BATTERY_UNKNOWN;
        public boolean chargingCase = false;
        public boolean chargingLeft = false;
        public boolean chargingRight = false;
        /** One of the NOISE_* constants, or NOISE_UNKNOWN before the pods report it. */
        public int noiseMode = NOISE_UNKNOWN;
        public boolean earDetectionEnabled = true;
        public boolean conversationalAwareness = false;
        /** How many hosts the pods are attached to, or -1 before they say. */
        public int connectedDevices = -1;
        /** Last audio source the pods announced, or null. */
        public String audioSource = null;

        AapState copy() {
            AapState s = new AapState();
            s.earLeft = earLeft;
            s.earRight = earRight;
            s.batteryCase = batteryCase;
            s.batteryLeft = batteryLeft;
            s.batteryRight = batteryRight;
            s.chargingCase = chargingCase;
            s.chargingLeft = chargingLeft;
            s.chargingRight = chargingRight;
            s.noiseMode = noiseMode;
            s.earDetectionEnabled = earDetectionEnabled;
            s.conversationalAwareness = conversationalAwareness;
            s.connectedDevices = connectedDevices;
            s.audioSource = audioSource;
            return s;
        }
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private static volatile AapState lastState = new AapState();
    private static volatile boolean lastConnected = false;
    /** The running service, so the static setters below can reach its socket. */
    private static volatile AapService instance;
    /**
     * Gestures to intercept; re-applied on every session, 0 = leave the pods
     * alone. Double-press is on by default so it can be split per bud (right =
     * next, left = previous), which the buds cannot do themselves. Single press
     * is deliberately left alone so play/pause keeps working through the normal
     * media-button path even when this service isn't running.
     */
    private static volatile int stemGestureMask = STEM_DOUBLE;
    private static final CopyOnWriteArrayList<StemListener> stemListeners =
            new CopyOnWriteArrayList<StemListener>();

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static AapState getLastState() {
        return lastState.copy();
    }

    public static boolean isConnected() {
        return lastConnected;
    }

    /**
     * True when the last known ear-detection state has both AirPods in the
     * case. Used by MainActivity to tell "deliberately stowed" apart from a
     * genuine A2DP dropout so the zombie audio-reconnect logic doesn't spam
     * retries at an unreachable device.
     */
    public static boolean isLikelyStowed() {
        AapState s = lastState;
        return s.earLeft == EAR_IN_CASE && s.earRight == EAR_IN_CASE;
    }

    public static void deviceConnected(android.content.Context ctx, BluetoothDevice device) {
        if (device == null) return;
        Intent intent = new Intent(ctx, AapService.class);
        intent.putExtra("mac", device.getAddress());
        ctx.startService(intent);
    }

    public static void deviceDisconnected(android.content.Context ctx) {
        ctx.stopService(new Intent(ctx, AapService.class));
    }

    // --- Settings, all requiring a live AAP session (they have no BLE fallback).
    // Each returns false if nothing could be sent. The pods echo the change back
    // as a notification, so callers should render from AapState rather than
    // assuming the write took effect.

    /** @param mode one of NOISE_OFF / NOISE_ANC / NOISE_TRANSPARENCY / NOISE_ADAPTIVE. */
    public static boolean setNoiseMode(int mode) {
        AapService s = instance;
        return s != null && s.sendControl(CTRL_LISTENING_MODE, mode);
    }

    public static boolean setEarDetectionEnabled(boolean enabled) {
        AapService s = instance;
        return s != null && s.sendControl(CTRL_EAR_DETECTION, enabled ? CTRL_ON : CTRL_OFF);
    }

    public static boolean setConversationalAwareness(boolean enabled) {
        AapService s = instance;
        return s != null && s.sendControl(CTRL_CONVERSATIONAL_AWARENESS,
                enabled ? CTRL_ON : CTRL_OFF);
    }

    /** Press-and-hold duration: 0 default, 1 slower, 2 slowest. */
    public static boolean setClickHoldInterval(int value) {
        AapService s = instance;
        return s != null && s.sendControl(CTRL_CLICK_HOLD_INTERVAL, value);
    }

    /** Double-press speed: 0 default, 1 slower, 2 slowest. */
    public static boolean setDoubleClickInterval(int value) {
        AapService s = instance;
        return s != null && s.sendControl(CTRL_DOUBLE_CLICK_INTERVAL, value);
    }

    /**
     * What press-and-hold does on each bud: 0x01 noise control, 0x05 Siri.
     * This one really is per-bud, and data2 is the left bud.
     */
    public static boolean setClickHoldMode(int right, int left) {
        AapService s = instance;
        return s != null && s.sendControl(CTRL_CLICK_HOLD_MODE, right, left);
    }

    /**
     * Asks the pods to report the given gestures to us instead of acting on them
     * (bitwise-or of the STEM_* constants; 0 restores their built-in behaviour).
     *
     * Off by default and deliberately so: setting a bit *removes* the bud's own
     * function for that gesture, so nothing is intercepted unless a caller asks.
     * The pods forget this whenever they connect to another non-iCloud device,
     * so it is re-sent on every session -- see {@link #stemGestureMask}.
     */
    public static boolean setStemGestures(int mask) {
        AapService s = instance;
        stemGestureMask = mask;
        return s != null && s.sendControl(CTRL_STEM_GESTURES, mask);
    }

    private static final int BLE_RSSI_FLOOR = -70;

    private static final int PROXIMITY_MSG_LEN = 27;
    private static final int STATUS_PRIMARY_IN_EAR = 0x02;
    private static final int STATUS_SECONDARY_IN_EAR = 0x08;
    private static final int STATUS_PRIMARY_IS_LEFT = 0x20;

    private BluetoothAdapter.LeScanCallback leScan;
    private int lastAdvertKey = -1;
    private volatile boolean shouldRun = false;
    private volatile BluetoothSocket activeSocket = null;
    /** Write end of the live session, and the lock serialising writes to it. */
    private volatile OutputStream activeOut = null;
    private final Object writeLock = new Object();
    private volatile boolean sentFollowUp = false;
    /** When the pods last said anything, for the diagnostic snapshot below. */
    private volatile long lastRxAtMs = 0;
    private Thread worker;
    private String targetMac;

    // Ear-detection auto-pause bookkeeping (M2).
    private boolean bothInEar = true;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        String mac = intent != null ? intent.getStringExtra("mac") : null;
        if (mac == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (shouldRun && mac.equalsIgnoreCase(targetMac)) {
            // Already running against this device.
            return START_STICKY;
        }
        targetMac = mac;
        shouldRun = true;
        startBleScan();
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoop();
                }
            }, "aap-service");
            worker.start();
        }
        return START_STICKY;
    }

    /**
     * Y2's Bluetooth stack never completes the raw L2CAP AAP connect (see
     * scripts/airpods-aap/Y2_INVESTIGATION.md), so ear/battery state is read
     * from Apple's proximity-pairing BLE advertisement instead -- the same
     * broadcast the Apple Continuity protocol uses, no connection needed.
     */
    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (leScan != null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        leScan = new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] record) {
                byte[] p = appleProximityPayload(record);
                // ponytail: RSSI gate instead of identity checks -- AirPods use rotating
                // random addresses, so there is no stable id to match on.
                if (p == null || rssi < BLE_RSSI_FLOOR) return;
                String addr = device != null ? device.getAddress() : null;
                applyAdvert(p, rssi, addr);
            }
        };
        if (!adapter.startLeScan(leScan)) {
            Log.w(TAG, "startLeScan refused by the stack");
            leScan = null;
        }
    }


    @SuppressLint("MissingPermission")
    private void stopBleScan() {
        if (leScan == null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) adapter.stopLeScan(leScan);
        leScan = null;
    }

    /**
     * Decodes the plaintext head of a proximity-pairing message into the same
     * state the L2CAP path publishes. Bit meanings were derived on-device by
     * logging raw adverts through a known sequence of pod positions -- see
     * scripts/airpods-aap/Y2_INVESTIGATION.md for the capture.
     */
    private void applyAdvert(byte[] p, int rssi, String addr) {
        // The advert is the fallback, not a second opinion. Its ear state lags by
        // seconds and its battery levels are 10% nibbles, so while the AAP session
        // is up it would only overwrite better data with worse.
        if (activeSocket != null) return;
        int status = p[5] & 0xFF;
        int battery = p[6] & 0xFF;
        int charge = p[7] & 0xFF;

        int key = (status << 16) | (battery << 8) | charge;
        if (key == lastAdvertKey) return; // adverts repeat every ~2s; only act on changes
        lastAdvertKey = key;
        Log.d(TAG, "AAP-BLE status=" + Integer.toHexString(status)
                + " battery=" + Integer.toHexString(battery)
                + " charge=" + Integer.toHexString(charge)
                + " rssi=" + rssi + " addr=" + addr
                + " model=" + Integer.toHexString(((p[3] & 0xFF) << 8) | (p[4] & 0xFF)));

        boolean primaryLeft = (status & STATUS_PRIMARY_IS_LEFT) != 0;
        int primaryEar = (status & STATUS_PRIMARY_IN_EAR) != 0 ? EAR_IN_EAR : EAR_OUT_OF_EAR;
        int secondaryEar = (status & STATUS_SECONDARY_IN_EAR) != 0 ? EAR_IN_EAR : EAR_OUT_OF_EAR;

        int podCharge = (charge >> 4) & 0x0F;
        // Both buds charging means both are seated in the case. Read from the charge
        // flags rather than a status bit because it holds regardless of which bud the
        // adverts currently call "primary" (that flips when one is stowed).
        if ((podCharge & 0x03) == 0x03) {
            primaryEar = EAR_IN_CASE;
            secondaryEar = EAR_IN_CASE;
        }

        AapState s = lastState.copy();
        s.earLeft = primaryLeft ? primaryEar : secondaryEar;
        s.earRight = primaryLeft ? secondaryEar : primaryEar;
        s.batteryLeft = batteryPercent(primaryLeft ? battery >> 4 : battery);
        s.batteryRight = batteryPercent(primaryLeft ? battery : battery >> 4);
        s.batteryCase = batteryPercent(charge);
        s.chargingLeft = (podCharge & (primaryLeft ? 0x01 : 0x02)) != 0;
        s.chargingRight = (podCharge & (primaryLeft ? 0x02 : 0x01)) != 0;
        s.chargingCase = (podCharge & 0x04) != 0;
        publishState(s);
        handleEarDetectionForAutoPause(s, addr);
    }

    /** Battery levels ride in a nibble as tens of percent, with 0xF meaning "not reported". */
    static int batteryPercent(int nibble) {
        nibble &= 0x0F;
        return nibble == 0x0F ? BATTERY_UNKNOWN : Math.min(100, nibble * 10);
    }

    /**
     * Returns the 27-byte Apple proximity-pairing message (starting at its
     * 0x07 type byte) from a raw advertisement, or null if this advert isn't
     * one. Adverts are a sequence of (length, type, data...) structures.
     */
    static byte[] appleProximityPayload(byte[] record) {
        if (record == null) return null;
        int i = 0;
        while (i < record.length) {
            int len = record[i] & 0xFF;
            if (len == 0 || i + len + 1 > record.length) return null;
            int type = record[i + 1] & 0xFF;
            int dataAt = i + 2;
            int dataLen = len - 1;
            if (type == 0xFF && dataLen >= 4
                    && (record[dataAt] & 0xFF) == 0x4C && (record[dataAt + 1] & 0xFF) == 0x00
                    && (record[dataAt + 2] & 0xFF) == 0x07) {
                if (dataLen - 2 != PROXIMITY_MSG_LEN) return null; // short form, no state in it
                byte[] out = new byte[PROXIMITY_MSG_LEN];
                System.arraycopy(record, dataAt + 2, out, 0, out.length);
                return out;
            }
            i += len + 1;
        }
        return null;
    }

    @Override
    public void onDestroy() {
        // Hand the intercepted gestures back before going away. Otherwise the
        // pods keep withholding double-press for a listener that no longer
        // exists, and a double squeeze would do nothing at all.
        if (stemGestureMask != 0 && activeOut != null) {
            sendControl(CTRL_STEM_GESTURES, 0);
            try {
                Thread.sleep(50); // let the queued write reach the socket
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (instance == this) instance = null;
        shouldRun = false;
        stopBleScan();
        BluetoothSocket s = activeSocket;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable t) {
                Log.d(TAG, "socket close failed on destroy", t);
            }
        }
        setConnected(false);
        super.onDestroy();
    }

    private void runLoop() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            stopSelf();
            return;
        }

        int bootstrapFailures = 0;
        boolean everConnected = false;

        while (shouldRun) {
            BluetoothDevice device = adapter.getRemoteDevice(targetMac);
            BluetoothSocket socket = tryConnect(device);
            if (socket == null) {
                if (!everConnected) {
                    bootstrapFailures++;
                    if (bootstrapFailures >= MAX_BOOTSTRAP_ATTEMPTS) {
                        Log.i(TAG, "giving up on " + targetMac + " after " + bootstrapFailures
                                + " failed AAP connect attempts (probably not Apple hardware)");
                        break;
                    }
                }
                sleepQuiet(3000);
                continue;
            }

            everConnected = true;
            bootstrapFailures = 0;
            activeSocket = socket;
            setConnected(true);
            try {
                sessionLoop(socket);
            } catch (Throwable t) {
                Log.i(TAG, "AAP session ended: " + t);
            } finally {
                try {
                    socket.close();
                } catch (Throwable t) {
                    Log.d(TAG, "socket close failed after session end", t);
                }
                activeSocket = null;
                // Drop the write end too, so any handshake retry still pending on
                // the main thread sees a stale session and does nothing.
                activeOut = null;
                setConnected(false);
            }

            if (shouldRun) sleepQuiet(2000);
        }

        // On Y2 the L2CAP path never connects and the BLE advert scan is the only
        // source of ear/battery state -- it needs the service to stay alive, so only
        // shut down when there's no scan running to keep it useful.
        if (leScan == null) {
            shouldRun = false;
            stopSelf();
        }
    }

    @SuppressLint("MissingPermission") // connect() failure (incl. SecurityException) is caught below
    private BluetoothSocket tryConnect(BluetoothDevice device) {
        boolean[][] variants = {{false, false}, {true, true}};
        for (boolean[] v : variants) {
            try {
                Constructor<BluetoothSocket> ctor = BluetoothSocket.class.getDeclaredConstructor(
                        int.class, int.class, boolean.class, boolean.class,
                        BluetoothDevice.class, int.class, ParcelUuid.class);
                ctor.setAccessible(true);
                BluetoothSocket socket = ctor.newInstance(3 /* TYPE_L2CAP */, -1, v[0], v[1], device, AAP_PSM, null);
                Log.i(TAG, "AAP connect: calling connect() auth=" + v[0] + " encrypt=" + v[1]
                        + " bondState=" + device.getBondState());
                socket.connect();
                Log.i(TAG, "AAP L2CAP connected to " + device.getAddress());
                return socket;
            } catch (Throwable t) {
                // Full stack trace + cause chain -- the previous toString()-only log hid whether
                // this fails locally (stack rejects the connect synchronously) or after a round
                // trip to the remote device.
                Log.w(TAG, "AAP connect attempt failed (auth=" + v[0] + ")", t);
                Throwable cause = t.getCause();
                while (cause != null) {
                    Log.w(TAG, "  caused by: " + cause);
                    cause = cause.getCause();
                }
            }
            if (!shouldRun) return null;
        }
        return null;
    }

    private void sessionLoop(BluetoothSocket socket) throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        activeOut = out;
        lastRxAtMs = android.os.SystemClock.elapsedRealtime();
        startDiagnostics(socket);
        startHandshake(out);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] readBuf = new byte[1024];
        while (shouldRun) {
            int n = in.read(readBuf);
            if (n < 0) {
                Log.i(TAG, "AAP socket EOF");
                return;
            }
            if (n == 0) continue;
            lastRxAtMs = android.os.SystemClock.elapsedRealtime();
            Log.d(TAG, "AAP rx " + n + " bytes: " + hexPrefix(readBuf, n));
            if (!sentFollowUp && startsWith(readBuf, n, AAP_HANDSHAKE_ACK)) {
                sentFollowUp = true;
                sendOpeningSequence(out, "handshake acked");
            }
            buffer.write(readBuf, 0, n);
            drainPackets(buffer);
        }
    }

    /**
     * Sends the handshake and then repeats the whole opening sequence at +200ms
     * and +5s, as LibrePods' Android client does.
     *
     * Waiting only for the handshake ACK is not enough in practice: the pods
     * sometimes ignore the first handshake entirely, and the features ACK that
     * would gate the rest is documented upstream as only tested on AirPods Pro 2,
     * so an ACK-driven state machine can stall on other models. Re-sending is
     * harmless -- these are idempotent setup packets -- and it turns a silent
     * dead session into a slightly late one.
     */
    private void startHandshake(final OutputStream out) throws Exception {
        sentFollowUp = false;
        out.write(AAP_HANDSHAKE);
        out.flush();
        for (final int delayMs : new int[]{200, 5000}) {
            ioHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (activeOut != out) return; // session already replaced
                    try {
                        out.write(AAP_HANDSHAKE);
                        out.flush();
                        sendOpeningSequence(out, "retry +" + delayMs + "ms");
                    } catch (Throwable t) {
                        Log.d(TAG, "AAP handshake retry failed: " + t);
                    }
                }
            }, delayMs);
        }
    }

    /**
     * Writes a settings packet: {@code 04 00 04 00 09 00 <id> <value> 00 00 00 00}.
     * Same shape the pods use to report a setting back, so a successful write is
     * echoed as a notification and {@link #applyControlValue} updates the state.
     *
     * Returns false when there is no live session -- these settings only exist
     * over AAP, there is no BLE fallback for them.
     */
    private boolean sendControl(int id, int value) {
        return sendControl(id, value, 0x00);
    }

    /** Two-value form; only a few settings use data2 (e.g. per-bud click-hold mode). */
    private boolean sendControl(int id, final int value, final int value2) {
        final OutputStream out = activeOut;
        if (out == null) {
            Log.w(TAG, "AAP control id=0x" + Integer.toHexString(id) + " dropped: no session");
            return false;
        }
        // 11 bytes exactly: header, LE opcode, identifier, then four data bytes.
        final byte[] pkt = {0x04, 0x00, 0x04, 0x00, 0x09, 0x00,
                (byte) id, (byte) value, (byte) value2, 0x00, 0x00};
        final int idForLog = id;
        // Always off the caller's thread: these come from UI taps, and a blocking
        // Bluetooth write on the main thread ANRs the launcher. The return value
        // therefore only means "there was a session to write to" -- the pods echo
        // the setting back, so callers should render from AapState regardless.
        ioHandler.post(new Runnable() {
            @Override
            public void run() {
                if (activeOut != out) {
                    Log.w(TAG, "AAP control id=0x" + Integer.toHexString(idForLog)
                            + " dropped: session replaced before the write ran");
                    return;
                }
                try {
                    synchronized (writeLock) {
                        out.write(pkt);
                        out.flush();
                    }
                    Log.i(TAG, "AAP control sent id=0x" + Integer.toHexString(idForLog)
                            + " value=" + value);
                } catch (Throwable t) {
                    Log.w(TAG, "AAP control write failed: " + t);
                }
            }
        });
        return true;
    }

    /**
     * Periodic one-line snapshot, tagged {@code AapDiag} so it can be filtered on
     * its own: {@code adb logcat -s AapDiag}.
     *
     * Exists for the silent-mute failure -- the AirPods stop producing sound while
     * everything else looks healthy: still connected, still "playing", position
     * still advancing. There is nothing to detect in software at the moment it
     * happens, so the point is to have a timeline afterwards. Given a rough time
     * of day, these lines say whether the player kept advancing (pods muted
     * themselves), whether the pods went quiet on the AAP channel (link trouble),
     * and what the ear state claimed at the time.
     *
     * Only ticks while a session is up, and only logs when something is playing or
     * the ear state isn't plain both-in, so an idle device stays quiet.
     */
    private void startDiagnostics(final BluetoothSocket socket) {
        // Tied to the socket it was started for, rather than a "running" flag: a
        // flag left set by a previous session made startDiagnostics() a silent
        // no-op for the rest of the process, which is exactly what happened the
        // first time this shipped.
        diagHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!shouldRun || activeSocket != socket) return; // session gone

                try {
                    AapState st = lastState;
                    boolean playing = com.themoon.y1.managers.AudioPlayerManager
                            .getInstance().isPlaying();
                    long pos = com.themoon.y1.managers.AudioPlayerManager
                            .getInstance().getCurrentPosition();
                    // Unconditional on purpose. Gating this on "playing" made an
                    // idle device indistinguishable from broken tracing, which is
                    // the wrong failure mode for a trace left running unattended.
                    long quietMs = android.os.SystemClock.elapsedRealtime() - lastRxAtMs;
                    Log.i("AapDiag", "playing=" + playing
                            + " pos=" + pos
                            + " ear=" + st.earLeft + "/" + st.earRight
                            + " batt=" + st.batteryLeft + "/" + st.batteryRight
                            + " noise=" + st.noiseMode
                            + " links=" + st.connectedDevices
                            + " src=" + st.audioSource
                            + " quietMs=" + quietMs);
                } catch (Throwable t) {
                    Log.d("AapDiag", "snapshot failed", t);
                }
                diagHandler.postDelayed(this, 10000);
            }
        }, 10000);
    }

    /** Feature flags then the notification request; safe to repeat. */
    private void sendOpeningSequence(OutputStream out, String why) throws Exception {
        synchronized (writeLock) {
            out.write(AAP_SET_SPECIFIC_FEATURES);
            out.flush();
            out.write(AAP_ENABLE_NOTIFICATIONS);
            out.flush();
        }
        // The pods forget an intercept mask whenever they attach to a new
        // non-iCloud host, so it has to be re-asserted per session rather than
        // set once.
        if (stemGestureMask != 0) {
            sendControl(CTRL_STEM_GESTURES, stemGestureMask);
        }
        Log.i(TAG, "AAP opening sequence sent (" + why + ")");
    }

    /**
     * Peels complete packets off {@code buffer} and dispatches them. Packet
     * boundaries for opcodes we don't explicitly know are found by scanning
     * for the next 04 00 04 00 marker -- the same trick community AAP clients
     * (LibrePods etc.) use, since there's no universal length prefix.
     */
    private void drainPackets(ByteArrayOutputStream buffer) {
        byte[] data = buffer.toByteArray();
        int consumed = 0;

        while (true) {
            int magicAt = indexOfMagic(data, consumed);
            if (magicAt < 0) {
                consumed = Math.max(consumed, Math.max(0, data.length - 3));
                break;
            }
            if (magicAt + 6 > data.length) break; // need more bytes for the opcode

            int opcode = (data[magicAt + 4] & 0xFF) | ((data[magicAt + 5] & 0xFF) << 8);
            int packetLen;

            if (opcode == OPCODE_EAR_DETECTION) {
                packetLen = 8;
            } else if (opcode == OPCODE_STEM_PRESS) {
                packetLen = 8;
            } else if (opcode == OPCODE_CONTROL) {
                packetLen = 11;
            } else if (opcode == OPCODE_AUDIO_SOURCE) {
                packetLen = 14;
            } else if (opcode == OPCODE_AUDIO_SOURCE_RESP) {
                packetLen = 13;
            } else if (opcode == OPCODE_CONNECTED_DEVICES) {
                if (magicAt + 9 > data.length) break; // need the count byte
                packetLen = 9 + (data[magicAt + 8] & 0xFF) * 8;
            } else if (opcode == OPCODE_CONV_AWARENESS) {
                packetLen = 10;
            } else if (opcode == OPCODE_BATTERY) {
                if (magicAt + 7 > data.length) break; // need the count byte
                int count = data[magicAt + 6] & 0xFF;
                packetLen = 7 + count * 5;
            } else {
                int next = indexOfMagic(data, magicAt + 6);
                if (next < 0) break; // don't know where this one ends yet
                packetLen = next - magicAt;
            }

            if (magicAt + packetLen > data.length) break; // incomplete, wait for more

            handlePacket(opcode, data, magicAt, packetLen);
            consumed = magicAt + packetLen;
        }

        byte[] remainder = new byte[data.length - consumed];
        System.arraycopy(data, consumed, remainder, 0, remainder.length);
        buffer.reset();
        if (remainder.length > 0) {
            if (remainder.length > MAX_BUFFER_BYTES) {
                Log.w(TAG, "AAP buffer overflow (" + remainder.length + " bytes unresolved), dropping");
                return;
            }
            buffer.write(remainder, 0, remainder.length);
        }
    }

    private static boolean startsWith(byte[] data, int len, byte[] prefix) {
        if (len < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    /** First bytes of a packet as hex, for working out what the pods actually send. */
    private static String hexPrefix(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(len, 24);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        if (len > limit) sb.append("...");
        return sb.toString();
    }

    private static int indexOfMagic(byte[] data, int from) {
        return AapPacketFraming.indexOfMagic(MAGIC, data, from);
    }

    private void handlePacket(int opcode, byte[] data, int offset, int len) {
        if (opcode == OPCODE_EAR_DETECTION) {
            int primary = data[offset + 6] & 0xFF;
            int secondary = data[offset + 7] & 0xFF;
            // Tagged distinctly from the "AAP-BLE" advert path so it's obvious which
            // source drove a pause -- the L2CAP notifications are the sub-second one.
            Log.d(TAG, "AAP-L2CAP ear primary=" + primary + " secondary=" + secondary);
            AapState s = lastState.copy();
            s.earLeft = primary;
            s.earRight = secondary;
            publishState(s);
            handleEarDetectionForAutoPause(s, AAP_L2CAP_SOURCE);
        } else if (opcode == OPCODE_BATTERY) {
            int count = data[offset + 6] & 0xFF;
            AapState s = lastState.copy();
            int p = offset + 7;
            for (int i = 0; i < count && p + 5 <= offset + len; i++, p += 5) {
                int component = data[p] & 0xFF;
                int level = data[p + 2] & 0xFF;
                int status = data[p + 3] & 0xFF;
                // 0x04 is "disconnected": the entry carries no usable level, so
                // keep whatever we last knew rather than showing a stale zero.
                if (status == BATTERY_DISCONNECTED) continue;
                boolean charging = status == BATTERY_CHARGING
                        || status == BATTERY_CHARGING_OPTIMIZED;
                if (component == 0x04) { // left
                    s.batteryLeft = level;
                    s.chargingLeft = charging;
                } else if (component == 0x02) { // right
                    s.batteryRight = level;
                    s.chargingRight = charging;
                } else if (component == 0x08) { // case
                    s.batteryCase = level;
                    s.chargingCase = charging;
                }
            }
            publishState(s);
        } else if (opcode == OPCODE_STEM_PRESS) {
            if (len < 8) return;
            int type = data[offset + 6] & 0xFF;
            int bud = data[offset + 7] & 0xFF;
            Log.i(TAG, "AAP stem press type=" + type + " bud=" + bud);
            for (StemListener l : stemListeners) {
                l.onStemPress(type, bud);
            }
            handleStemPress(type, bud);
        } else if (opcode == OPCODE_AUDIO_SOURCE) {
            if (len < 12) return;
            StringBuilder mac = new StringBuilder();
            for (int i = 5; i >= 0; i--) { // little-endian on the wire
                if (mac.length() > 0) mac.append(':');
                mac.append(String.format("%02X", data[offset + 6 + i] & 0xFF));
            }
            AapState as = lastState.copy();
            as.audioSource = mac.toString();
            Log.i("AapDiag", "audio source -> " + as.audioSource);
            publishState(as);
        } else if (opcode == OPCODE_AUDIO_SOURCE_RESP) {
            if (len < 12) return;
            boolean none = true;
            StringBuilder mac = new StringBuilder();
            for (int i = 5; i >= 0; i--) { // little-endian on the wire
                int b = data[offset + 6 + i] & 0xFF;
                if (b != 0) none = false;
                if (mac.length() > 0) mac.append(':');
                mac.append(String.format("%02X", b));
            }
            AapState rs = lastState.copy();
            rs.audioSource = none ? null : mac.toString();
            Log.i("AapDiag", "audio sink -> " + (none ? "NONE" : rs.audioSource));
            publishState(rs);
        } else if (opcode == OPCODE_CONNECTED_DEVICES) {
            if (len < 9) return;
            int count = data[offset + 8] & 0xFF;
            Log.d(TAG, "AAP connected devices=" + count);
            AapState s = lastState.copy();
            s.connectedDevices = count;
            publishState(s);
        } else if (opcode == OPCODE_CONV_AWARENESS) {
            if (len < 10) return;
            // Level ramps rather than toggling; LibrePods' shipping code treats
            // 1-2 as "started speaking" and 6/8/9 as "stopped", which disagrees
            // with their own docs -- the code is the tested one.
            int level = data[offset + 9] & 0xFF;
            Log.d(TAG, "AAP conversational awareness level=" + level);
        } else if (opcode == OPCODE_CONTROL) {
            if (len < 8) return;
            int id = data[offset + 6] & 0xFF;
            int value = data[offset + 7] & 0xFF;
            Log.d(TAG, "AAP control id=0x" + Integer.toHexString(id) + " value=" + value);
            applyControlValue(id, value);
        }
    }

    /**
     * Default gesture mapping: a double squeeze skips forward on the right bud
     * and back on the left. The pods have no notion of "which bud" for their own
     * built-in actions, which is the whole reason for intercepting.
     */
    private void handleStemPress(int type, int bud) {
        if (type != PRESS_DOUBLE) return;
        final boolean forward = bud != BUD_LEFT;
        // Track changes go through MainActivity and ExoPlayer, so main thread only.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (forward) {
                        com.themoon.y1.managers.AudioPlayerManager.getInstance().nextTrack();
                    } else {
                        com.themoon.y1.managers.AudioPlayerManager.getInstance().prevTrack();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "stem track change failed", t);
                }
            }
        });
    }

    /** Records a setting the pods reported, so the UI reflects changes made elsewhere. */
    private void applyControlValue(int id, int value) {
        AapState s = lastState.copy();
        switch (id) {
            case CTRL_LISTENING_MODE:
                s.noiseMode = value;
                break;
            case CTRL_EAR_DETECTION:
                s.earDetectionEnabled = value == CTRL_ON;
                break;
            case CTRL_CONVERSATIONAL_AWARENESS:
                s.conversationalAwareness = value == CTRL_ON;
                break;
            default:
                return; // a setting we don't model; the log line above is enough
        }
        publishState(s);
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * Handshake retries run here, never on the main thread: these are blocking
     * writes to a Bluetooth socket, and doing them on the UI thread froze the
     * launcher hard enough to ANR.
     */
    private static final Handler ioHandler;
    /**
     * Diagnostics run on their own thread. They used to share ioHandler, which
     * is a FIFO queue that also carries blocking socket writes -- one write that
     * never returned starved every snapshot behind it, so the tracer went silent
     * exactly when something was wrong, which is the worst possible time. A
     * monitor must not queue behind the thing it monitors.
     */
    private static final Handler diagHandler;
    static {
        HandlerThread t = new HandlerThread("aap-io");
        t.start();
        ioHandler = new Handler(t.getLooper());
        HandlerThread d = new HandlerThread("aap-diag");
        d.start();
        diagHandler = new Handler(d.getLooper());
    }

    private void handleEarDetectionForAutoPause(AapState s, String source) {
        // The L2CAP session reports ear changes in well under a second; the BLE
        // advert route takes ~5s and is only a fallback for when there is no
        // session. applyAdvert() already bows out while one is live -- this is
        // the same rule enforced at the point it actually matters.
        if (activeSocket != null && !AAP_L2CAP_SOURCE.equals(source)) return;
        boolean nowBothInEar = s.earLeft == EAR_IN_EAR && s.earRight == EAR_IN_EAR;
        if (bothInEar && !nowBothInEar) {
            // ExoPlayer must only be touched from the main thread -- this callback
            // runs on the AapService read-loop thread, so hop over first.
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().pauseForAirpods();
                }
            });
        } else if (!bothInEar && nowBothInEar) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().resumeForAirpods();
                }
            });
        }
        bothInEar = nowBothInEar;
    }

    private void publishState(AapState s) {
        lastState = s;
        for (Listener l : listeners) {
            l.onAapStateChanged(s);
        }
    }

    private void setConnected(boolean connected) {
        if (lastConnected == connected) return;
        lastConnected = connected;
        for (Listener l : listeners) {
            l.onAapConnectionChanged(connected);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
