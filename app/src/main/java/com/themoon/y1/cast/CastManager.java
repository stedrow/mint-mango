package com.themoon.y1.cast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.managers.AudioPlayerManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Front door for Google Cast support: owns discovery, the single active {@link CastConnection},
 * and the {@link CastMediaServer}, and exposes the handful of calls the UI needs
 * (start/stop discovery, cast the current track, stop casting).
 *
 * Phase 1 scope: discover devices and hand the currently-playing LOCAL file to the Default
 * Media Receiver, pausing on-device playback so audio isn't duplicated. Full transport sync
 * (queue hand-off, auto-advance, live position polling) and Navidrome-stream casting are
 * deliberately left for a later phase.
 *
 * All network work runs on a dedicated single-thread executor; UI feedback is marshalled back
 * to the main thread. The class is a singleton to match the app's other managers.
 */
public final class CastManager {
    private static final String TAG = "CastManager";
    private static CastManager instance;

    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private CastDiscovery discovery;
    private CastConnection connection;
    private volatile CastDevice activeDevice;

    private CastManager() {}

    public static synchronized CastManager getInstance() {
        if (instance == null) instance = new CastManager();
        return instance;
    }

    public boolean isCasting() {
        return connection != null && connection.isConnected();
    }

    public CastDevice getActiveDevice() { return activeDevice; }

    // ── discovery ───────────────────────────────────────────────────────────────

    public void startDiscovery(Context context, CastDiscovery.Callback cb) {
        if (discovery == null) discovery = new CastDiscovery(context);
        discovery.start(cb);
    }

    public void stopDiscovery() {
        if (discovery != null) discovery.stop();
    }

    // ── casting ─────────────────────────────────────────────────────────────────

    /**
     * Casts the track currently loaded in the player to {@code device}. Resolves the local
     * file, starts the media server, pauses local playback, and drives the Cast handshake on
     * the network executor. Returns immediately; progress/errors surface as toasts.
     */
    public void castCurrentTrack(final MainActivity a, final CastDevice device) {
        AudioPlayerManager am = AudioPlayerManager.getInstance();
        if (am.isNavidromeMode) {
            Toast.makeText(a, a.t("Casting Navidrome streams isn't supported yet"), Toast.LENGTH_LONG).show();
            return;
        }
        if (a.currentPlaylist.isEmpty() || a.currentIndex < 0 || a.currentIndex >= a.currentPlaylist.size()) {
            Toast.makeText(a, a.t("Nothing is playing to cast"), Toast.LENGTH_SHORT).show();
            return;
        }
        final File file = a.currentPlaylist.get(a.currentIndex);
        if (file == null || !file.exists()) {
            Toast.makeText(a, a.t("Track file not found"), Toast.LENGTH_SHORT).show();
            return;
        }

        final String host = localIpAddress();
        if (host == null) {
            Toast.makeText(a, a.t("Not connected to Wi-Fi"), Toast.LENGTH_SHORT).show();
            return;
        }

        // Don't play on the speaker and the Y1 speaker at once.
        if (am.isPlaying()) am.playOrPauseMusic();

        CastMediaServer server = CastMediaServer.getInstance();
        server.ensureStarted();
        final String token = server.serve(file);
        final String url = server.urlFor(host, token);
        final String contentType = CastMediaServer.mimeFor(file.getName());
        final String title = stripExtension(file.getName());

        Toast.makeText(a, a.t("Casting to") + " " + device.friendlyName + "…", Toast.LENGTH_SHORT).show();

        netExecutor.execute(new Runnable() {
            @Override public void run() {
                // Replace any prior connection.
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                activeDevice = device;
                connection = new CastConnection(device, new CastConnection.Listener() {
                    @Override public void onSessionReady() {
                        Log.d(TAG, "session ready on " + device.friendlyName);
                    }
                    @Override public void onMediaLoaded() {
                        toast(a, a.t("Playing on") + " " + device.friendlyName);
                    }
                    @Override public void onError(final String message) {
                        toast(a, message);
                        activeDevice = null;
                    }
                    @Override public void onDisconnected() {
                        Log.d(TAG, "disconnected from " + device.friendlyName);
                        activeDevice = null;
                    }
                });
                connection.connect();
                connection.loadMedia(url, contentType, title, null);
            }
        });
    }

    public void stopCasting() {
        final CastConnection c = connection;
        connection = null;
        activeDevice = null;
        netExecutor.execute(new Runnable() {
            @Override public void run() {
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) {}
                }
            }
        });
    }

    // transport passthroughs (used by later UI wiring; harmless no-ops when not casting)
    public void play()  { CastConnection c = connection; if (c != null) c.play(); }
    public void pause() { CastConnection c = connection; if (c != null) c.pause(); }
    public void setVolume(float level) { CastConnection c = connection; if (c != null) c.setVolume(level); }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void toast(final MainActivity a, final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!a.isFinishing()) Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** First non-loopback IPv4 address — the LAN address the speaker will fetch media from. */
    private static String localIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localIpAddress failed", e);
        }
        return null;
    }
}
