package com.themoon.y1.cast;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A single control connection to one Cast device, speaking the Cast V2 protocol over a
 * TLS socket (port 8009). Handles the connect → launch-receiver → load-media handshake,
 * the tp.heartbeat PING/PONG keep-alive, and the media transport commands.
 *
 * Cast is a pull model: we never stream audio here. We hand the device an HTTP URL
 * (served by {@link CastMediaServer}) and it fetches the bytes itself; this class is
 * purely the remote control.
 *
 * All socket I/O happens off the main thread — {@link #connect()} blocks on the TLS
 * handshake and must be called from a worker (CastManager's executor). A background
 * reader thread dispatches inbound frames; a heartbeat thread keeps the link alive.
 *
 * Cast devices present a self-signed certificate, so this uses a trust-all TLS context.
 * That's acceptable here: the connection is to a LAN endpoint we discovered via mDNS and
 * carries no secrets (just playback commands and a LAN media URL).
 */
class CastConnection {
    private static final String TAG = "CastConnection";

    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT  = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER   = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA      = "urn:x-cast:com.google.cast.media";

    private static final String SOURCE_ID   = "sender-y1-0";
    private static final String PLATFORM_ID = "receiver-0";
    private static final String DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845";

    interface Listener {
        /** The default media receiver launched and a virtual connection to it is open. */
        void onSessionReady();
        /** A LOAD completed and the device reports a media session (id supplied). */
        void onMediaLoaded();
        /**
         * A MEDIA_STATUS update from the device. playerState is one of
         * PLAYING/PAUSED/BUFFERING/IDLE; idleReason (may be null) is FINISHED/CANCELLED/
         * INTERRUPTED/ERROR when playerState is IDLE. positionMs/durationMs are -1 when unknown.
         */
        void onMediaStatus(String playerState, long positionMs, long durationMs, String idleReason);
        /** Fatal or terminal problem; the connection is (being) torn down. */
        void onError(String message);
        /** Socket closed for any reason. */
        void onDisconnected();
    }

    private final CastDevice device;
    private final Listener listener;
    private final AtomicInteger requestId = new AtomicInteger(1);

    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile boolean running = false;

    // Set once the receiver app is up; the app's transportId is the destination for all
    // media commands, sessionId scopes LOAD, mediaSessionId scopes PLAY/PAUSE/STOP/SEEK.
    private volatile String appTransportId;
    private volatile String sessionId;
    private volatile int mediaSessionId = -1;
    private volatile boolean appConnected = false;

    // A media LOAD requested before the receiver finished launching is stashed here and
    // fired the moment the session becomes ready. Guarded by loadLock so the queue-vs-fire
    // decision can't interleave between loadMedia() (net thread) and the reader thread.
    private JSONObject pendingLoad;
    private final Object loadLock = new Object();

    CastConnection(CastDevice device, Listener listener) {
        this.device = device;
        this.listener = listener;
    }

    /** Opens the socket and kicks off the launch handshake. Blocking; call off the UI thread. */
    void connect() {
        try {
            SSLSocketFactory factory = trustAllFactory();
            SSLSocket s = (SSLSocket) factory.createSocket();
            s.connect(new java.net.InetSocketAddress(device.host, device.port), 8000);
            s.setSoTimeout(0);
            s.startHandshake();
            socket = s;
            out = s.getOutputStream();
            running = true;

            Thread reader = new Thread(new ReaderLoop(s), "CastReader");
            reader.setDaemon(true);
            reader.start();

            Thread heartbeat = new Thread(new HeartbeatLoop(), "CastHeartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            // Virtual connection to the platform, then launch the default media receiver.
            send(NS_CONNECTION, PLATFORM_ID, connectPayload());
            JSONObject launch = new JSONObject();
            launch.put("type", "LAUNCH");
            launch.put("appId", DEFAULT_MEDIA_RECEIVER_APP_ID);
            launch.put("requestId", requestId.getAndIncrement());
            send(NS_RECEIVER, PLATFORM_ID, launch);
        } catch (Exception e) {
            Log.e(TAG, "connect failed", e);
            fail("Could not connect to " + device.friendlyName);
        }
    }

    /**
     * Requests playback of {@code url} on the device. Safe to call before the session is
     * ready — it's queued and sent on launch. contentType is the MIME the receiver uses to
     * pick a decoder (e.g. audio/mpeg, audio/flac).
     */
    void loadMedia(String url, String contentType, String title, String artist, String coverUrl) {
        try {
            JSONObject metadata = new JSONObject();
            metadata.put("metadataType", 3); // MusicTrackMediaMetadata
            if (title != null) metadata.put("title", title);
            if (artist != null) metadata.put("artist", artist);
            if (coverUrl != null) {
                JSONArray images = new JSONArray();
                images.put(new JSONObject().put("url", coverUrl));
                metadata.put("images", images);
            }

            JSONObject media = new JSONObject();
            media.put("contentId", url);
            media.put("streamType", "BUFFERED");
            media.put("contentType", contentType);
            media.put("metadata", metadata);

            JSONObject load = new JSONObject();
            load.put("type", "LOAD");
            load.put("media", media);
            load.put("autoplay", true);
            load.put("currentTime", 0);

            synchronized (loadLock) {
                if (appConnected && sessionId != null) {
                    sendLoad(load);
                } else {
                    pendingLoad = load;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loadMedia failed", e);
            fail("Could not start playback on the speaker");
        }
    }

    void play()  { mediaCommand("PLAY"); }
    void pause() { mediaCommand("PAUSE"); }

    /** Stops the media session but leaves the receiver app running. */
    void stopMedia() { mediaCommand("STOP"); }

    /** Seek within the current media session. positionMs is clamped by the receiver. */
    void seek(long positionMs) {
        if (appTransportId == null || mediaSessionId < 0) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "SEEK");
            msg.put("mediaSessionId", mediaSessionId);
            msg.put("currentTime", positionMs / 1000.0);
            msg.put("requestId", requestId.getAndIncrement());
            send(NS_MEDIA, appTransportId, msg);
        } catch (Exception e) {
            Log.w(TAG, "seek failed", e);
        }
    }

    /** Ask the receiver to push a fresh MEDIA_STATUS (used for position polling). */
    void requestMediaStatus() {
        if (appTransportId == null) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "GET_STATUS");
            if (mediaSessionId >= 0) msg.put("mediaSessionId", mediaSessionId);
            msg.put("requestId", requestId.getAndIncrement());
            send(NS_MEDIA, appTransportId, msg);
        } catch (Exception e) {
            Log.w(TAG, "requestMediaStatus failed", e);
        }
    }

    /** level is 0.0–1.0. */
    void setVolume(float level) {
        try {
            JSONObject vol = new JSONObject();
            vol.put("level", Math.max(0f, Math.min(1f, level)));
            JSONObject msg = new JSONObject();
            msg.put("type", "SET_VOLUME");
            msg.put("volume", vol);
            msg.put("requestId", requestId.getAndIncrement());
            send(NS_RECEIVER, PLATFORM_ID, msg);
        } catch (Exception e) {
            Log.w(TAG, "setVolume failed", e);
        }
    }

    /** Tears everything down: stops the receiver app, closes the socket, ends the threads. */
    void disconnect() {
        running = false;
        try {
            if (out != null) {
                JSONObject stop = new JSONObject();
                stop.put("type", "STOP");
                if (sessionId != null) stop.put("sessionId", sessionId);
                stop.put("requestId", requestId.getAndIncrement());
                send(NS_RECEIVER, PLATFORM_ID, stop);
            }
        } catch (Exception ignored) {
            // best effort — we're closing anyway
        }
        closeSocket();
    }

    boolean isConnected() { return running && socket != null && !socket.isClosed(); }

    // ── protocol internals ──────────────────────────────────────────────────────

    private void mediaCommand(String type) {
        if (appTransportId == null || mediaSessionId < 0) {
            Log.w(TAG, "Ignoring " + type + " — no active media session yet");
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", type);
            msg.put("mediaSessionId", mediaSessionId);
            msg.put("requestId", requestId.getAndIncrement());
            send(NS_MEDIA, appTransportId, msg);
        } catch (Exception e) {
            Log.w(TAG, "mediaCommand " + type + " failed", e);
        }
    }

    private void sendLoad(JSONObject load) throws Exception {
        load.put("sessionId", sessionId);
        load.put("requestId", requestId.getAndIncrement());
        send(NS_MEDIA, appTransportId, load);
    }

    private JSONObject connectPayload() throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "CONNECT");
        return o;
    }

    private synchronized void send(String namespace, String destinationId, JSONObject payload) {
        OutputStream o = out;
        if (o == null) return;
        try {
            CastMessageCodec.writeFrame(o, SOURCE_ID, destinationId, namespace, payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "send failed on " + namespace, e);
            fail("Lost connection to the speaker");
        }
    }

    private void handleReceiverStatus(JSONObject payload) {
        try {
            JSONObject status = payload.optJSONObject("status");
            if (status == null) return;
            JSONArray apps = status.optJSONArray("applications");
            if (apps == null || apps.length() == 0) return;
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                if (DEFAULT_MEDIA_RECEIVER_APP_ID.equals(app.optString("appId"))) {
                    String transport = app.optString("transportId", null);
                    String session = app.optString("sessionId", null);
                    if (transport == null || session == null) return;
                    boolean firstTime = !appConnected;
                    appTransportId = transport;
                    sessionId = session;
                    if (firstTime) {
                        // Open the virtual connection to the receiver app itself.
                        send(NS_CONNECTION, appTransportId, safeConnect());
                        synchronized (loadLock) {
                            appConnected = true;
                            JSONObject queued = pendingLoad;
                            if (queued != null) {
                                pendingLoad = null;
                                sendLoad(queued);
                            }
                        }
                        listener.onSessionReady();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "handleReceiverStatus failed", e);
        }
    }

    private JSONObject safeConnect() {
        try { return connectPayload(); } catch (Exception e) { return new JSONObject(); }
    }

    private void handleMediaStatus(JSONObject payload) {
        try {
            JSONArray arr = payload.optJSONArray("status");
            if (arr == null || arr.length() == 0) {
                // Empty status = the session was torn down; report an idle tick so the
                // manager can react, but without a reason we can't call it a clean finish.
                listener.onMediaStatus("IDLE", -1, -1, null);
                return;
            }
            JSONObject st = arr.getJSONObject(0);
            int wasSession = mediaSessionId;
            mediaSessionId = st.optInt("mediaSessionId", mediaSessionId);
            if (wasSession < 0 && mediaSessionId >= 0) {
                listener.onMediaLoaded();
            }

            String playerState = st.optString("playerState", "UNKNOWN");
            String idleReason = st.has("idleReason") && !st.isNull("idleReason")
                    ? st.optString("idleReason", null) : null;
            long positionMs = st.has("currentTime") ? (long) (st.optDouble("currentTime", 0) * 1000) : -1;
            long durationMs = -1;
            JSONObject media = st.optJSONObject("media");
            if (media != null && media.has("duration")) {
                durationMs = (long) (media.optDouble("duration", 0) * 1000);
            }
            listener.onMediaStatus(playerState, positionMs, durationMs, idleReason);
        } catch (Exception e) {
            Log.w(TAG, "handleMediaStatus failed", e);
        }
    }

    private void fail(String message) {
        if (!running && socket == null) {
            // Never got off the ground — still surface the error once.
        }
        boolean wasRunning = running;
        running = false;
        closeSocket();
        if (wasRunning || socket == null) {
            try { listener.onError(message); } catch (Exception ignored) {}
        }
    }

    private void closeSocket() {
        SSLSocket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static SSLSocketFactory trustAllFactory() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{ new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return ctx.getSocketFactory();
    }

    // ── background loops ────────────────────────────────────────────────────────

    private final class ReaderLoop implements Runnable {
        private final SSLSocket s;
        ReaderLoop(SSLSocket s) { this.s = s; }

        @Override
        public void run() {
            try {
                InputStream in = s.getInputStream();
                while (running) {
                    CastMessageCodec.Message msg = CastMessageCodec.readFrame(in);
                    if (msg == null) break; // clean EOF
                    dispatch(msg);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "reader loop ended", e);
            } finally {
                boolean wasRunning = running;
                running = false;
                closeSocket();
                if (wasRunning) {
                    try { listener.onDisconnected(); } catch (Exception ignored) {}
                }
            }
        }

        private void dispatch(CastMessageCodec.Message msg) {
            if (msg.payloadUtf8 == null || msg.namespace == null) return;
            try {
                JSONObject payload = new JSONObject(msg.payloadUtf8);
                String type = payload.optString("type", "");
                if (NS_HEARTBEAT.equals(msg.namespace)) {
                    if ("PING".equals(type)) {
                        JSONObject pong = new JSONObject();
                        pong.put("type", "PONG");
                        send(NS_HEARTBEAT, PLATFORM_ID, pong);
                    }
                } else if (NS_RECEIVER.equals(msg.namespace)) {
                    if ("RECEIVER_STATUS".equals(type)) handleReceiverStatus(payload);
                } else if (NS_MEDIA.equals(msg.namespace)) {
                    if ("MEDIA_STATUS".equals(type)) handleMediaStatus(payload);
                    else if ("LOAD_FAILED".equals(type) || "INVALID_PLAYER_STATE".equals(type)) {
                        fail("The speaker could not play this track");
                    }
                } else if (NS_CONNECTION.equals(msg.namespace)) {
                    if ("CLOSE".equals(type)) {
                        Log.d(TAG, "Peer closed virtual connection");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "dispatch failed for " + msg.namespace, e);
            }
        }
    }

    private final class HeartbeatLoop implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) return;
                try {
                    JSONObject ping = new JSONObject();
                    ping.put("type", "PING");
                    send(NS_HEARTBEAT, PLATFORM_ID, ping);
                } catch (Exception e) {
                    Log.w(TAG, "heartbeat failed", e);
                    return;
                }
            }
        }
    }
}
