package com.themoon.y1.cast;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discovers Cast devices on the LAN via a hand-rolled mDNS query/response reader (service type
 * _googlecast._tcp) over a plain {@link MulticastSocket} -- deliberately NOT {@link
 * android.net.nsd.NsdManager}.
 *
 * This device's NsdService leaks one internal client-registration slot on every
 * discoverServices() call and never frees it on stopServiceDiscovery() (confirmed via logcat:
 * "Exceeded max outstanding requests" after repeated start/stop cycles, with old client ids still
 * listed as outstanding). NsdService is a fixed part of the platform we can't patch from this
 * app, so the only way to let discovery be started and stopped every time the cast picker
 * opens/closes -- without eventually bricking it until reboot -- is to own the socket ourselves.
 *
 * Two practical hazards this still works around:
 *  - mDNS multicast is dropped by Wi-Fi power saving unless a {@link WifiManager.MulticastLock}
 *    is held, so we take one for the duration of a scan.
 *  - DNS name compression (RFC 1035 message-compression pointers) shows up throughout real Cast
 *    responses, so the name reader follows 0xC0 pointers rather than assuming flat labels.
 *
 * Results are delivered on the main thread. Discovered devices accumulate for the life of a scan;
 * call {@link #stop()} when the picker closes to close the socket and release the multicast lock.
 */
public final class CastDiscovery {
    private static final String TAG = "CastDiscovery";
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;
    private static final String QUERY_NAME = "_googlecast._tcp.local";
    private static final long REQUERY_INTERVAL_MS = 3000;

    public interface Callback {
        /** Called on the main thread whenever the known-device set changes. */
        void onDevicesChanged(List<CastDevice> devices);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();

    private volatile MulticastSocket socket;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean active = false;
    private Callback callback;

    private final Map<String, CastDevice> devices = new LinkedHashMap<>();
    // Instance names whose real friendly name (fetchEurekaName) has come back -- devices not yet
    // in here are still sitting on friendlyFallback()'s raw-hostname guess and are hidden from
    // emit() until they resolve, rather than shown as a garbled truncated hostname.
    private final java.util.Set<String> namesResolved = new java.util.HashSet<>();
    // Partial records waiting to be paired up into a CastDevice, keyed by mDNS instance name.
    private final Map<String, String> pendingTarget = new LinkedHashMap<>(); // instance -> SRV target host
    private final Map<String, Integer> pendingPort = new LinkedHashMap<>();  // instance -> SRV port
    private final Map<String, String> resolvedIp = new LinkedHashMap<>();   // target host -> A record IP

    private final Runnable requeryRunnable = new Runnable() {
        @Override public void run() {
            if (!active) return;
            sendQuery();
            main.postDelayed(this, REQUERY_INTERVAL_MS);
        }
    };

    public CastDiscovery(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Begins (or restarts) a scan. Idempotent while already running. */
    public void start(Callback cb) {
        this.callback = cb;
        if (active) {
            emit();
            sendQuery(); // give it another beat to catch anything powered on since the last scan
            return;
        }
        devices.clear();
        namesResolved.clear();
        pendingTarget.clear();
        pendingPort.clear();
        resolvedIp.clear();
        active = true;
        acquireMulticastLock();

        ioExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    MulticastSocket s = new MulticastSocket(MDNS_PORT);
                    s.setReuseAddress(true);
                    s.joinGroup(InetAddress.getByName(MDNS_GROUP));
                    socket = s;
                } catch (Exception e) {
                    Log.e(TAG, "failed to open mDNS socket", e);
                    active = false;
                    releaseMulticastLock();
                    return;
                }
                // Send the opening query from this same thread, right after the socket is ready --
                // dispatching back through sendQuery()'s own executor.execute() would race the
                // cached thread pool against this task with no ordering guarantee between them.
                sendQueryOnCurrentThread(socket);
                receiveLoop();
            }
        });

        main.postDelayed(requeryRunnable, REQUERY_INTERVAL_MS);
    }

    public void stop() {
        active = false;
        main.removeCallbacks(requeryRunnable);
        final MulticastSocket s = socket;
        socket = null;
        if (s != null) {
            ioExecutor.execute(new Runnable() {
                @Override public void run() {
                    try { s.leaveGroup(InetAddress.getByName(MDNS_GROUP)); } catch (Exception ignored) {}
                    try { s.close(); } catch (Exception ignored) {}
                }
            });
        }
        releaseMulticastLock();
    }

    // ── query / receive ──────────────────────────────────────────────────────────

    private void sendQuery() {
        final MulticastSocket s = socket;
        if (s == null) return;
        ioExecutor.execute(new Runnable() {
            @Override public void run() { sendQueryOnCurrentThread(s); }
        });
    }

    private void sendQueryOnCurrentThread(MulticastSocket s) {
        try {
            byte[] q = buildPtrQuery(QUERY_NAME);
            DatagramPacket p = new DatagramPacket(q, q.length, InetAddress.getByName(MDNS_GROUP), MDNS_PORT);
            s.send(p);
        } catch (Exception e) {
            Log.d(TAG, "sendQuery failed: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[8192];
        while (active) {
            MulticastSocket s = socket;
            if (s == null) break;
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                final byte[] data = java.util.Arrays.copyOf(p.getData(), p.getLength());
                parsePacket(data);
            } catch (Exception e) {
                if (active) Log.d(TAG, "receive loop ended: " + e.getMessage());
                break;
            }
        }
    }

    // ── DNS message building ─────────────────────────────────────────────────────

    private static byte[] buildPtrQuery(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0}); // ID=0, flags=0, QDCOUNT=1
        for (String label : name.split("\\.")) {
            out.write(label.length());
            out.write(label.getBytes("UTF-8"));
        }
        out.write(0); // root label
        out.write(new byte[]{0, 12}); // QTYPE = PTR
        out.write(new byte[]{0, 1});  // QCLASS = IN
        return out.toByteArray();
    }

    // ── DNS message parsing (with name-compression support) ─────────────────────

    private void parsePacket(byte[] msg) {
        try {
            int ancount = u16(msg, 6);
            int nscount = u16(msg, 8);
            int arcount = u16(msg, 10);
            int qdcount = u16(msg, 4);

            int pos = 12;
            for (int i = 0; i < qdcount; i++) {
                pos = skipName(msg, pos);
                pos += 4; // QTYPE + QCLASS
            }

            boolean changed = false;
            int total = ancount + nscount + arcount;
            for (int i = 0; i < total; i++) {
                int[] nameEnd = new int[1];
                String name = readName(msg, pos, nameEnd);
                pos = nameEnd[0];
                int type = u16(msg, pos);
                pos += 2;
                pos += 2; // CLASS (top "cache flush" bit ignored)
                pos += 4; // TTL
                int rdlen = u16(msg, pos);
                pos += 2;
                int rdataStart = pos;

                if (type == 12) { // PTR
                    int[] end = new int[1];
                    String instance = readName(msg, rdataStart, end);
                    if (!devices.containsKey(instance) && !pendingTarget.containsKey(instance)) {
                        pendingTarget.put(instance, null);
                    }
                } else if (type == 33) { // SRV
                    int port = u16(msg, rdataStart + 4);
                    int[] end = new int[1];
                    String target = readName(msg, rdataStart + 6, end);
                    pendingTarget.put(name, target);
                    pendingPort.put(name, port);
                } else if (type == 1 && rdlen == 4) { // A
                    String ip = (msg[rdataStart] & 0xFF) + "." + (msg[rdataStart + 1] & 0xFF) + "."
                            + (msg[rdataStart + 2] & 0xFF) + "." + (msg[rdataStart + 3] & 0xFF);
                    resolvedIp.put(name, ip);
                }
                // TXT (type 16) intentionally skipped: this firmware's mdnsd predates reliable
                // TXT delivery anyway, so friendly names come from fetchEurekaName() below.

                pos = rdataStart + rdlen;
            }

            changed = reconcilePending();
            if (changed) emit();
        } catch (Exception e) {
            Log.d(TAG, "parsePacket failed: " + e.getMessage());
        }
    }

    /** Pairs up instance name + SRV target/port + A-record IP once all three are known. */
    private boolean reconcilePending() {
        boolean changed = false;
        List<String> resolvedInstances = new ArrayList<>();
        for (Map.Entry<String, String> e : pendingTarget.entrySet()) {
            String instance = e.getKey();
            String target = e.getValue();
            Integer port = pendingPort.get(instance);
            if (target == null || port == null) continue;
            String ip = resolvedIp.get(target);
            if (ip == null) continue;

            // A device that already resolved its real name keeps it here — the requery timer
            // re-announces (and thus re-reconciles) every ~3s, and rebuilding with
            // friendlyFallback() unconditionally would clobber the resolved name right back to
            // the raw "device.local"-style guess on every one of those cycles, even though
            // namesResolved still (correctly) says this instance is resolved and emit() would
            // then show that clobbered fallback name as if it were final.
            CastDevice existing = devices.get(instance);
            boolean alreadyResolved = existing != null && namesResolved.contains(instance);
            String name = alreadyResolved ? existing.friendlyName : friendlyFallback(instance);
            CastDevice dev = new CastDevice(instance, name, ip, port);
            CastDevice prev = devices.put(instance, dev);
            resolvedInstances.add(instance);
            if (prev == null || !prev.equals(dev)) {
                changed = true;
                if (!alreadyResolved) fetchEurekaName(instance, ip, port);
            }
        }
        for (String instance : resolvedInstances) {
            pendingTarget.remove(instance);
            pendingPort.remove(instance);
        }
        return changed;
    }

    /** Cleaned-up fallback label from the mDNS instance name, used until fetchEurekaName() (or
     *  never, if that fails) supplies the receiver's real friendly name. */
    private String friendlyFallback(String instance) {
        // Instance names share a model prefix (e.g. "Google-Home-Mini-<uniquehex>"), so same-model
        // speakers collide if we slice off the front -- the unique part is the trailing hex, not
        // the model name. Keep a readable prefix plus that unique tail instead.
        String cleaned = instance == null ? "Cast device" : instance.replace("-", "").trim();
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 6) + "…" + cleaned.substring(cleaned.length() - 4);
        }
        return cleaned.isEmpty() ? "Cast device" : cleaned;
    }

    /** Queries the Cast receiver's own /setup/eureka_info for its real friendly name ("name"
     *  field) and updates the device list if it differs from the mDNS-derived fallback. */
    private void fetchEurekaName(final String id, final String host, final int port) {
        ioExecutor.execute(new Runnable() {
            @Override public void run() {
                String name = null;
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL url = new java.net.URL("http://" + host + ":8008/setup/eureka_info?params=name");
                    conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    java.io.InputStream in = conn.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] tmp = new byte[512];
                    int n;
                    while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                    org.json.JSONObject json = new org.json.JSONObject(buf.toString("UTF-8"));
                    String n2 = json.optString("name", null);
                    if (n2 != null && !n2.isEmpty()) name = n2;
                } catch (Exception e) {
                    Log.d(TAG, "eureka_info fetch failed for " + host + ": " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
                if (name == null) return;
                final String finalName = name;
                main.post(new Runnable() {
                    @Override public void run() {
                        CastDevice existing = devices.get(id);
                        if (existing == null) return;
                        boolean newlyResolved = namesResolved.add(id); // true only the first time this id resolves
                        if (finalName.equals(existing.friendlyName) && !newlyResolved) return;
                        devices.put(id, new CastDevice(id, finalName, existing.host, existing.port));
                        emit();
                    }
                });
            }
        });
    }

    private void emit() {
        final List<CastDevice> snapshot = new ArrayList<>();
        // Some Cast receivers announce the same physical device under more than one mDNS
        // instance name (e.g. one per network interface) -- those land as separate `devices`
        // entries but resolve to the same eureka_info friendly name, so dedupe on that once
        // resolved rather than showing the same speaker twice in the picker.
        final java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (Map.Entry<String, CastDevice> e : devices.entrySet()) {
            // Devices whose /setup/eureka_info friendly-name lookup (fetchEurekaName) hasn't come
            // back yet are still sitting on friendlyFallback()'s raw-hostname guess (a garbled,
            // truncated mDNS domain) -- hide those rather than show that, they reappear once a
            // real name resolves and a later emit() picks them back up.
            if (!namesResolved.contains(e.getKey())) continue;
            CastDevice dev = e.getValue();
            if (!seenNames.add(dev.friendlyName)) continue;
            snapshot.add(dev);
        }
        final Callback cb = callback;
        if (cb == null) return;
        main.post(new Runnable() {
            @Override public void run() { cb.onDevicesChanged(snapshot); }
        });
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("y1-cast-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "could not acquire multicast lock", e);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    // ── raw DNS message helpers ──────────────────────────────────────────────────

    private static int u16(byte[] msg, int off) {
        return ((msg[off] & 0xFF) << 8) | (msg[off + 1] & 0xFF);
    }

    /** Advances past a (possibly compressed) name without building it, for the question section. */
    private static int skipName(byte[] msg, int pos) {
        while (true) {
            int len = msg[pos] & 0xFF;
            if (len == 0) return pos + 1;
            if ((len & 0xC0) == 0xC0) return pos + 2; // pointer: always exactly 2 bytes here
            pos += 1 + len;
        }
    }

    /** Reads a (possibly compressed) domain name starting at {@code pos}. {@code outEnd[0]} is set
     *  to the position immediately after this name in the original stream (after the pointer, if
     *  one was used, NOT after whatever it points to). */
    private static String readName(byte[] msg, int pos, int[] outEnd) {
        StringBuilder sb = new StringBuilder();
        int cursor = pos;
        int end = -1;
        int guard = 0;
        while (guard++ < 128) {
            int len = msg[cursor] & 0xFF;
            if (len == 0) {
                cursor += 1;
                if (end < 0) end = cursor;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                int pointer = ((len & 0x3F) << 8) | (msg[cursor + 1] & 0xFF);
                if (end < 0) end = cursor + 2;
                cursor = pointer;
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            try {
                sb.append(new String(msg, cursor + 1, len, "UTF-8"));
            } catch (Exception e) {
                sb.append("?");
            }
            cursor += 1 + len;
        }
        outEnd[0] = end < 0 ? cursor : end;
        return sb.toString();
    }
}
