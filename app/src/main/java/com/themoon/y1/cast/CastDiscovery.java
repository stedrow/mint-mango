package com.themoon.y1.cast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers Cast devices on the LAN via mDNS/DNS-SD (service type _googlecast._tcp) using
 * the platform {@link NsdManager} — no third-party discovery library needed.
 *
 * Two practical hazards this works around:
 *  - mDNS multicast is dropped by Wi-Fi power saving unless a {@link WifiManager.MulticastLock}
 *    is held, so we take one for the duration of a scan.
 *  - Older NsdManager rejects a resolve while another is in flight (FAILURE_ALREADY_ACTIVE),
 *    so resolves are serialized through a single-in-flight queue.
 *
 * Results are delivered on the main thread. Discovered devices accumulate; call {@link #stop()}
 * when the picker closes to release the platform listener and the multicast lock.
 */
public final class CastDiscovery {
    private static final String TAG = "CastDiscovery";
    private static final String SERVICE_TYPE = "_googlecast._tcp.";

    public interface Callback {
        /** Called on the main thread whenever the known-device set changes. */
        void onDevicesChanged(List<CastDevice> devices);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private boolean active = false;

    private final Map<String, CastDevice> devices = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private Callback callback;
    private final java.util.concurrent.ExecutorService nameExecutor = java.util.concurrent.Executors.newCachedThreadPool();

    public CastDiscovery(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Begins (or restarts) discovery. Idempotent while already running. */
    public void start(Callback cb) {
        this.callback = cb;
        if (active) {
            emit();
            return;
        }
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.w(TAG, "NSD service unavailable on this device");
            return;
        }
        acquireMulticastLock();
        devices.clear();
        resolveQueue.clear();
        resolving = false;
        active = true;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int errorCode) {
                Log.w(TAG, "start discovery failed: " + errorCode);
            }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) {
                Log.w(TAG, "stop discovery failed: " + errorCode);
            }
            @Override public void onDiscoveryStarted(String type) {
                Log.d(TAG, "discovery started");
            }
            @Override public void onDiscoveryStopped(String type) {
                Log.d(TAG, "discovery stopped");
            }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (!SERVICE_TYPE.contains(strip(info.getServiceType()))
                        && !strip(info.getServiceType()).contains("_googlecast")) return;
                enqueueResolve(info);
            }
            @Override public void onServiceLost(NsdServiceInfo info) {
                final String key = info.getServiceName();
                main.post(new Runnable() {
                    @Override public void run() {
                        if (devices.remove(key) != null) emit();
                    }
                });
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "discoverServices threw", e);
            active = false;
            releaseMulticastLock();
        }
    }

    public void stop() {
        active = false;
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); }
            catch (Exception e) { Log.d(TAG, "stopServiceDiscovery: " + e.getMessage()); }
        }
        discoveryListener = null;
        releaseMulticastLock();
    }

    // ── resolve queue (single in flight) ────────────────────────────────────────

    private void enqueueResolve(final NsdServiceInfo info) {
        main.post(new Runnable() {
            @Override public void run() {
                resolveQueue.offer(info);
                pumpResolveQueue();
            }
        });
    }

    private void pumpResolveQueue() {
        if (resolving || resolveQueue.isEmpty() || !active) return;
        resolving = true;
        final NsdServiceInfo next = resolveQueue.poll();
        try {
            nsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            // Busy → try this one again later; otherwise drop it.
                            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                                resolveQueue.offer(info);
                            }
                            resolving = false;
                            pumpResolveQueue();
                        }
                    });
                }
                @Override public void onServiceResolved(final NsdServiceInfo info) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            addResolved(info);
                            resolving = false;
                            pumpResolveQueue();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "resolveService threw", e);
            resolving = false;
            main.post(new Runnable() { @Override public void run() { pumpResolveQueue(); } });
        }
    }

    private void addResolved(NsdServiceInfo info) {
        if (info.getHost() == null) return;
        final String host = info.getHost().getHostAddress();
        final int port = info.getPort();
        if (host == null || port <= 0) return;
        final String id = info.getServiceName();
        String friendly = friendlyName(info, id);
        CastDevice dev = new CastDevice(id, friendly, host, port);
        CastDevice prev = devices.put(id, dev);
        if (prev == null || !prev.equals(dev) || !prev.friendlyName.equals(dev.friendlyName)) {
            emit();
        }
        // This firmware's NsdManager never delivers TXT record bytes (mdnsd here predates
        // TXT-in-resolve support), so the fn= friendly name above is unreachable. Every Cast
        // receiver also serves its own name over plain HTTP on port 8008, so ask it directly.
        fetchEurekaName(id, host, port);
    }

    /** Queries the Cast receiver's own /setup/eureka_info for its real friendly name ("name"
     *  field) and updates the device list if it differs from the mDNS-derived fallback. */
    private void fetchEurekaName(final String id, final String host, final int port) {
        nameExecutor.execute(new Runnable() {
            @Override public void run() {
                String name = null;
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL url = new java.net.URL("http://" + host + ":8008/setup/eureka_info?params=name");
                    conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    java.io.InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
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
                        if (existing == null || finalName.equals(existing.friendlyName)) return;
                        devices.put(id, new CastDevice(id, finalName, existing.host, existing.port));
                        emit();
                    }
                });
            }
        });
    }

    /** Prefer the TXT record's fn= (Cast friendly name); fall back to a cleaned service name. */
    private String friendlyName(NsdServiceInfo info, String id) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Map<String, byte[]> attrs = info.getAttributes();
                if (attrs != null && attrs.get("fn") != null) {
                    return new String(attrs.get("fn"), "UTF-8");
                }
            } catch (Exception ignored) {}
        }
        // Service names share a model prefix (e.g. "Google-Home-Mini-<uniquehex>"), so same-model
        // speakers collide if we slice off the front — the unique part is the trailing hex, not
        // the model name. Keep a readable prefix plus that unique tail instead.
        String cleaned = id == null ? "Cast device" : id.replace("-", "").trim();
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 6) + "…" + cleaned.substring(cleaned.length() - 4);
        }
        return cleaned.isEmpty() ? "Cast device" : cleaned;
    }

    private void emit() {
        final List<CastDevice> snapshot = new ArrayList<>(devices.values());
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

    private static String strip(String s) {
        if (s == null) return "";
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
