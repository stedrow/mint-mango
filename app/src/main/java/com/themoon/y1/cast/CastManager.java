package com.themoon.y1.cast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.managers.AudioPlayerManager;
import com.themoon.y1.subsonic.SubsonicClient;
import com.themoon.y1.subsonic.SubsonicSong;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Front door for Google Cast support: owns discovery, the single active {@link CastConnection},
 * and the {@link CastMediaServer}, and drives the whole cast session.
 *
 * Phase 2 model — "the Cast device is the sink." When a cast starts, on-device playback is
 * paused and the speaker becomes the source of truth for transport: play/pause, seek, next/prev,
 * auto-advance at end-of-track (honouring the app's repeat mode), and a position/state feed for
 * the Now Playing progress bar. {@link AudioPlayerManager} delegates its query/control methods
 * here while {@link #isCasting()} is true, so the existing UI, wheel, and hardware-key paths
 * "just work" against the speaker without themselves knowing about Cast.
 *
 * Both local files (served over the LAN by {@link CastMediaServer}) and Navidrome streams (the
 * speaker fetches the transcoded-MP3 stream URL directly) are supported.
 *
 * All connection I/O runs on a single-thread network executor — never the main thread (a socket
 * write there would throw NetworkOnMainThreadException). Pure state reads used by the UI tick
 * (isPlaying / position / duration) are lock-free reads of volatile fields.
 */
public final class CastManager {
    private static final String TAG = "CastManager";
    private static final long POLL_INTERVAL_MS = 1500;
    private static CastManager instance;

    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private CastDiscovery discovery;
    // Written on netExecutor (castCurrentTrack/stopCasting), read on the main thread
    // (isCasting / transport passthroughs / quick menu) — must be volatile.
    private volatile CastConnection connection;
    private volatile CastDevice activeDevice;
    private volatile boolean navidromeCast = false;

    // Playback state mirrored from the speaker's MEDIA_STATUS, plus a wall-clock anchor so the
    // progress bar can interpolate between the (throttled) status updates.
    private volatile String playerState = "IDLE";
    private volatile long lastPositionMs = 0;
    private volatile long lastDurationMs = 0;
    private volatile long lastPosWallClock = 0;
    // Guards against double-advance: the receiver commonly emits two IDLE/FINISHED frames at
    // end-of-track (the spontaneous end-of-media plus the reply to an in-flight polled
    // GET_STATUS). One-shot per loaded track; reset when the next track is loaded.
    private volatile boolean finishHandled = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!isCasting()) return;
            if (isPlaying()) {
                final CastConnection c = connection;
                if (c != null) netExecutor.execute(new Runnable() {
                    @Override public void run() { c.requestMediaStatus(); }
                });
            }
            main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private CastManager() {}

    public static synchronized CastManager getInstance() {
        if (instance == null) instance = new CastManager();
        return instance;
    }

    public boolean isCasting() {
        CastConnection c = connection;
        return c != null && c.isConnected();
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

    // ── starting a cast ─────────────────────────────────────────────────────────

    /**
     * Casts the track currently loaded in the player to {@code device}. Pauses local playback,
     * establishes the Cast session, and hands off the current track (local file or Navidrome
     * stream). Returns immediately; progress/errors surface as toasts and the Now Playing UI.
     */
    public void castCurrentTrack(final MainActivity a, final CastDevice device) {
        final AudioPlayerManager am = AudioPlayerManager.getInstance();
        final boolean navi = am.isNavidromeMode;

        if (navi) {
            if (am.navidromePlaylist.isEmpty()) {
                Toast.makeText(a, a.t("Nothing is playing to cast"), Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (a.currentPlaylist.isEmpty() || a.currentIndex < 0 || a.currentIndex >= a.currentPlaylist.size()) {
            Toast.makeText(a, a.t("Nothing is playing to cast"), Toast.LENGTH_SHORT).show();
            return;
        }

        final String host = localIpAddress();
        if (host == null) {
            Toast.makeText(a, a.t("Not connected to Wi-Fi"), Toast.LENGTH_SHORT).show();
            return;
        }

        // Pause on-device playback BEFORE the connection exists (so this doesn't delegate back
        // here) — the speaker is about to become the audio sink.
        if (am.isPlaying()) am.playOrPauseMusic();

        if (!navi) CastMediaServer.getInstance().ensureStarted();
        navidromeCast = navi;
        resetPlaybackState();

        Toast.makeText(a, a.t("Casting to") + " " + device.friendlyName + "…", Toast.LENGTH_SHORT).show();

        netExecutor.execute(new Runnable() {
            @Override public void run() {
                if (connection != null) {
                    try { connection.disconnect(); } catch (Exception ignored) {}
                }
                activeDevice = device;
                connection = new CastConnection(device, new StatusListener(device));
                connection.connect();
                loadCurrentIndexInternal(host); // queued until the session is ready
            }
        });

        main.removeCallbacks(pollRunnable);
        main.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    public void stopCasting() {
        final CastConnection c = connection;
        connection = null;
        activeDevice = null;
        main.removeCallbacks(pollRunnable);
        resetPlaybackState();
        netExecutor.execute(new Runnable() {
            @Override public void run() {
                if (c != null) {
                    try { c.disconnect(); } catch (Exception ignored) {}
                }
            }
        });
        refreshNowPlaying();
    }

    // ── transport (called by AudioPlayerManager delegation while casting) ────────

    public void togglePlayPause() {
        final CastConnection c = connection;
        if (c == null) return;
        final boolean playing = isPlaying();
        // Optimistic local flip so the UI icon responds instantly; the next MEDIA_STATUS confirms.
        anchorPosition(getPositionMs(), playing ? "PAUSED" : "PLAYING");
        netExecutor.execute(new Runnable() {
            @Override public void run() {
                if (playing) c.pause(); else c.play();
            }
        });
        refreshNowPlaying();
    }

    public void next() {
        netExecutor.execute(new Runnable() {
            @Override public void run() {
                if (!advanceIndex(+1)) return;
                loadCurrentIndexInternal(localIpAddress());
            }
        });
    }

    public void prev() {
        netExecutor.execute(new Runnable() {
            @Override public void run() {
                if (!advanceIndex(-1)) return;
                loadCurrentIndexInternal(localIpAddress());
            }
        });
    }

    public void seekRelative(int offsetMs) {
        long target = getPositionMs() + offsetMs;
        long dur = getDurationMs();
        if (target < 0) target = 0;
        if (dur > 0 && target > dur) target = dur;
        seekTo(target);
    }

    public void seekTo(final long positionMs) {
        final CastConnection c = connection;
        if (c == null) return;
        anchorPosition(positionMs, playerState);
        netExecutor.execute(new Runnable() {
            @Override public void run() { c.seek(positionMs); }
        });
    }

    public void setVolume(final float level) {
        final CastConnection c = connection;
        if (c == null) return;
        netExecutor.execute(new Runnable() {
            @Override public void run() { c.setVolume(level); }
        });
    }

    // ── state reads (used by the UI tick; no network, safe on the main thread) ───

    public boolean isPlaying() {
        return "PLAYING".equals(playerState) || "BUFFERING".equals(playerState);
    }

    public long getPositionMs() {
        long base = lastPositionMs;
        if ("PLAYING".equals(playerState) && lastPosWallClock > 0) {
            base += System.currentTimeMillis() - lastPosWallClock;
        }
        if (lastDurationMs > 0 && base > lastDurationMs) base = lastDurationMs;
        return base < 0 ? 0 : base;
    }

    public long getDurationMs() {
        if (lastDurationMs > 0) return lastDurationMs;
        // Fall back to Navidrome API metadata while the receiver is still buffering.
        if (navidromeCast) {
            AudioPlayerManager am = AudioPlayerManager.getInstance();
            if (!am.navidromePlaylist.isEmpty() && am.navidromeIndex < am.navidromePlaylist.size()) {
                return am.navidromePlaylist.get(am.navidromeIndex).durationSecs * 1000L;
            }
        }
        return 0;
    }

    // ── internals ───────────────────────────────────────────────────────────────

    /** Builds the media URL + metadata for the current index and hands it to the connection.
     *  Runs on the network executor (it issues a socket write). */
    private void loadCurrentIndexInternal(String host) {
        MainActivity main = MainActivity.instance;
        CastConnection c = connection;
        if (main == null || c == null) return;
        try {
            String url, contentType, title, artist = null, cover = null;
            long durationMs = 0;
            if (navidromeCast) {
                AudioPlayerManager am = AudioPlayerManager.getInstance();
                if (am.navidromePlaylist.isEmpty()) return;
                int idx = clamp(am.navidromeIndex, am.navidromePlaylist.size());
                am.navidromeIndex = idx;
                SubsonicSong song = am.navidromePlaylist.get(idx);
                SubsonicClient sc = SubsonicClient.getInstance();
                url = sc.getStreamUrl(song.id);
                contentType = "audio/mpeg"; // Navidrome transcodes to MP3 for us
                title = song.title;
                artist = song.artist;
                if (song.coverArtId != null) cover = sc.getCoverArtUrl(song.coverArtId, 500);
                durationMs = song.durationSecs * 1000L;
            } else {
                if (main.currentPlaylist.isEmpty()) return;
                int idx = clamp(main.currentIndex, main.currentPlaylist.size());
                main.currentIndex = idx;
                File file = main.currentPlaylist.get(idx);
                if (!file.exists()) {
                    toast(main.t("Track file not found"));
                    return;
                }
                if (host == null) { toast(main.t("Not connected to Wi-Fi")); return; }
                CastMediaServer server = CastMediaServer.getInstance();
                String token = server.serve(file);
                url = server.urlFor(host, token);
                contentType = CastMediaServer.mimeFor(file.getName());
                title = stripExtension(file.getName());
            }
            anchorPosition(0, "BUFFERING");
            lastDurationMs = durationMs;
            finishHandled = false; // a fresh track can finish again
            c.loadMedia(url, contentType, title, artist, cover);
            refreshNowPlaying();
        } catch (Exception e) {
            Log.e(TAG, "loadCurrentIndexInternal failed", e);
            toast("Cast playback error");
        }
    }

    /** Advance the active queue index by delta (wrapping). Returns false if there's no queue. */
    private boolean advanceIndex(int delta) {
        MainActivity main = MainActivity.instance;
        if (main == null) return false;
        if (navidromeCast) {
            AudioPlayerManager am = AudioPlayerManager.getInstance();
            int size = am.navidromePlaylist.size();
            if (size == 0) return false;
            am.navidromeIndex = ((am.navidromeIndex + delta) % size + size) % size;
        } else {
            int size = main.currentPlaylist.size();
            if (size == 0) return false;
            main.currentIndex = ((main.currentIndex + delta) % size + size) % size;
        }
        return true;
    }

    private boolean isLastTrack() {
        MainActivity main = MainActivity.instance;
        if (main == null) return true;
        if (navidromeCast) {
            AudioPlayerManager am = AudioPlayerManager.getInstance();
            return am.navidromeIndex >= am.navidromePlaylist.size() - 1;
        }
        return main.currentIndex >= main.currentPlaylist.size() - 1;
    }

    /** End-of-track handler mirroring AudioPlayerManager.handleTrackCompletion's repeat logic. */
    private void onTrackFinished() {
        if (finishHandled) return; // ignore the duplicate FINISHED frame(s) for this track
        finishHandled = true;
        MainActivity main = MainActivity.instance;
        if (main == null) return;
        int repeatMode = main.prefs.getInt("repeat_mode", 0);
        if (repeatMode == 1) {              // repeat one
            netExecutor.execute(new Runnable() {
                @Override public void run() { loadCurrentIndexInternal(localIpAddress()); }
            });
            return;
        }
        if (repeatMode == 0 && isLastTrack()) {  // end of queue, repeat off — stop
            anchorPosition(getDurationMs(), "PAUSED");
            refreshNowPlaying();
            return;
        }
        next(); // repeat-all, or repeat-off mid-queue
    }

    private void anchorPosition(long positionMs, String state) {
        lastPositionMs = positionMs;
        lastPosWallClock = System.currentTimeMillis();
        playerState = state;
    }

    private void resetPlaybackState() {
        playerState = "IDLE";
        lastPositionMs = 0;
        lastDurationMs = 0;
        lastPosWallClock = 0;
        finishHandled = false;
    }

    private void refreshNowPlaying() {
        final MainActivity a = MainActivity.instance;
        if (a == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    a.updatePlayerUI();
                    a.progressHandler.removeCallbacks(a.updateProgressTask);
                    a.progressHandler.post(a.updateProgressTask);
                } catch (Exception e) {
                    Log.d(TAG, "refreshNowPlaying failed", e);
                }
            }
        });
    }

    private void toast(final String msg) {
        final MainActivity a = MainActivity.instance;
        if (a == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (!a.isFinishing()) Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static int clamp(int idx, int size) {
        if (idx < 0) return 0;
        if (idx >= size) return size - 1;
        return idx;
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

    /** Bridges CastConnection callbacks (reader thread) into manager state + UI. */
    private final class StatusListener implements CastConnection.Listener {
        private final CastDevice device;
        StatusListener(CastDevice device) { this.device = device; }

        @Override public void onSessionReady() {
            Log.d(TAG, "session ready on " + device.friendlyName);
        }

        @Override public void onMediaLoaded() {
            toast(MainActivity.instance != null
                    ? MainActivity.instance.t("Playing on") + " " + device.friendlyName
                    : "Playing on " + device.friendlyName);
        }

        @Override public void onMediaStatus(String state, long positionMs, long durationMs, String idleReason) {
            if (durationMs > 0) lastDurationMs = durationMs;
            if (positionMs >= 0) {
                lastPositionMs = positionMs;
                lastPosWallClock = System.currentTimeMillis();
            }
            if (state != null && !"UNKNOWN".equals(state)) playerState = state;

            if ("IDLE".equals(state) && "FINISHED".equals(idleReason)) {
                onTrackFinished();
            } else {
                refreshNowPlaying();
            }
        }

        @Override public void onError(final String message) {
            toast(message);
            activeDevice = null;
            main.removeCallbacks(pollRunnable);
            refreshNowPlaying();
        }

        @Override public void onDisconnected() {
            Log.d(TAG, "disconnected from " + device.friendlyName);
            activeDevice = null;
            main.removeCallbacks(pollRunnable);
            resetPlaybackState();
            refreshNowPlaying();
        }
    }
}
