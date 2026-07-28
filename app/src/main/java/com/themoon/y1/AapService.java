package com.themoon.y1;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
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

    public static final int EAR_IN_EAR = 0x00;
    public static final int EAR_OUT_OF_EAR = 0x01;
    public static final int EAR_IN_CASE = 0x02;
    public static final int EAR_UNKNOWN = -1;

    public static final int BATTERY_UNKNOWN = -1;

    public interface Listener {
        void onAapStateChanged(AapState state);
        void onAapConnectionChanged(boolean connected);
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
            return s;
        }
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private static volatile AapState lastState = new AapState();
    private static volatile boolean lastConnected = false;

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

    private static final int BLE_RSSI_FLOOR = -70;
    // Strongest advert seen lately is tracked for the log only -- it makes the
    // relative strength of a foreign pair obvious when diagnosing on-device.
    private static final long RSSI_PEAK_TTL_MS = 30000;
    // How long a locked advertiser may go quiet before another may take over.
    // AirPods re-advertise every couple of seconds, so this only expires on
    // address rotation or the pair genuinely leaving.
    private static final long ADDR_LOCK_TTL_MS = 10000;
    // How much stronger a different advertiser must be to steal a live lock.
    private static final int RSSI_STEAL_DB = 12;
    private static final int PROXIMITY_MSG_LEN = 27;
    private static final int STATUS_PRIMARY_IN_EAR = 0x02;
    private static final int STATUS_SECONDARY_IN_EAR = 0x08;
    private static final int STATUS_PRIMARY_IS_LEFT = 0x20;

    private BluetoothAdapter.LeScanCallback leScan;
    private int lastAdvertKey = -1;
    // Strongest proximity advert seen lately: our own AirPods are the nearest
    // transmitter, so anything much weaker is a different pair (see onLeScan).
    private int bestRssi = Integer.MIN_VALUE;
    private long bestRssiAt = 0;
    // The advertiser we're currently following (see acceptAdvertiser()).
    private String lockedAddr;
    private long lockedSeenAt = 0;
    private int lockedRssi = Integer.MIN_VALUE;
    // Advertiser whose adverts produced the current ear state (see
    // handleEarDetectionForAutoPause) -- transitions are only honoured within one.
    private String earStateSource;
    private int skippedAdverts = 0;
    private volatile boolean shouldRun = false;
    private volatile BluetoothSocket activeSocket = null;
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
                // A fixed floor alone isn't enough: another pair of AirPods elsewhere in
                // the home clears -70 easily, and mixing their ear state into ours flaps
                // the auto-pause (seen as random pause/play, from a single bud worn two
                // rooms away at -54 against our -44). Signal strength alone can't
                // separate them reliably either, so lock onto one advertiser: the
                // rotating random address is stable for minutes at a time, which is long
                // enough to follow a single pair.
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - bestRssiAt > RSSI_PEAK_TTL_MS) bestRssi = Integer.MIN_VALUE;
                if (rssi > bestRssi) {
                    bestRssi = rssi;
                    bestRssiAt = now;
                }
                String addr = device != null ? device.getAddress() : null;
                if (!acceptAdvertiser(addr, rssi, now)) {
                    // Throttled, so a pair we're deliberately ignoring can't spam the
                    // log, but a total lock-out is still visible instead of silent.
                    if ((++skippedAdverts % 25) == 1) {
                        Log.d(TAG, "AAP-BLE skipped " + skippedAdverts + " adverts; last addr="
                                + addr + " rssi=" + rssi + " locked=" + lockedAddr
                                + " lockedRssi=" + lockedRssi);
                    }
                    return;
                }
                applyAdvert(p, rssi, addr);
            }
        };
        if (!adapter.startLeScan(leScan)) {
            Log.w(TAG, "startLeScan refused by the stack");
            leScan = null;
        }
    }

    /**
     * Follows a single advertiser so a second pair of AirPods in the house can't
     * feed its ear state into ours. While a lock is alive only that address is
     * accepted; a lock is (re)taken when it goes silent -- address rotation, or
     * the pods going away -- and only by the nearest advertiser then audible. A
     * clearly closer advertiser can also steal an existing lock, so picking the
     * wrong one initially self-corrects instead of sticking until reboot.
     *
     * Called only from the scan callback (single-threaded), hence no locking.
     */
    private boolean acceptAdvertiser(String addr, int rssi, long now) {
        if (addr == null) return false;
        if (lockedAddr == null || now - lockedSeenAt > ADDR_LOCK_TTL_MS) {
            // No live lock: the address rotated, or the pods went away. Accept
            // whatever clears the absolute floor rather than comparing against an
            // older peak -- a bud resting on a table is much weaker than the same
            // bud in an ear, and gating on the peak locked our own pods out.
            lockedAddr = addr;
        } else if (!addr.equals(lockedAddr)) {
            // Someone else's pods while our lock is alive. Only a clearly closer
            // advertiser takes over, so an unlucky initial pick self-corrects.
            if (rssi < lockedRssi + RSSI_STEAL_DB) return false;
            lockedAddr = addr;
        }
        lockedSeenAt = now;
        lockedRssi = rssi;
        return true;
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
        int status = p[5] & 0xFF;
        int battery = p[6] & 0xFF;
        int charge = p[7] & 0xFF;

        int key = (status << 16) | (battery << 8) | charge;
        if (key == lastAdvertKey) return; // adverts repeat every ~2s; only act on changes
        lastAdvertKey = key;
        Log.d(TAG, "AAP-BLE status=" + Integer.toHexString(status)
                + " battery=" + Integer.toHexString(battery)
                + " charge=" + Integer.toHexString(charge)
                + " rssi=" + rssi + " peak=" + bestRssi + " addr=" + addr
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

        out.write(AAP_HANDSHAKE);
        out.flush();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] readBuf = new byte[1024];
        boolean sentFollowUp = false;
        while (shouldRun) {
            int n = in.read(readBuf);
            if (n < 0) {
                Log.i(TAG, "AAP socket EOF");
                return;
            }
            if (n == 0) continue;
            Log.d(TAG, "AAP rx " + n + " bytes: " + hexPrefix(readBuf, n));
            if (!sentFollowUp && startsWith(readBuf, n, AAP_HANDSHAKE_ACK)) {
                // Wait for the ack rather than guessing a delay: the pods ignore
                // anything sent before they have acknowledged the handshake.
                sentFollowUp = true;
                out.write(AAP_SET_SPECIFIC_FEATURES);
                out.flush();
                out.write(AAP_ENABLE_NOTIFICATIONS);
                out.flush();
                Log.i(TAG, "AAP handshake acked; requested notifications");
            }
            buffer.write(readBuf, 0, n);
            drainPackets(buffer);
        }
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
                boolean charging = status == 0x01;
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
        }
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private void handleEarDetectionForAutoPause(AapState s, String source) {
        // The L2CAP session reports ear changes in well under a second, the BLE
        // advert route takes ~5s. While the session is live it is the only source
        // worth listening to -- and letting both through is actively harmful,
        // because they count as different sources and the check below swallows
        // every transition where they alternate. That showed up on-device as the
        // first couple of removals taking seconds to pause before settling down.
        if (activeSocket != null && !AAP_L2CAP_SOURCE.equals(source)) return;
        boolean nowBothInEar = s.earLeft == EAR_IN_EAR && s.earRight == EAR_IN_EAR;
        // Only treat a change as a real ear event when it comes from the same
        // advertiser that set the previous state. Otherwise a different pair
        // taking over the lock would read as "a bud came out" and pause the music.
        if (source != null && !source.equals(earStateSource)) {
            earStateSource = source;
            bothInEar = nowBothInEar;
            return;
        }
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
        earStateSource = source;
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
