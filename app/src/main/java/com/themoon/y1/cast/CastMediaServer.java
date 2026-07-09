package com.themoon.y1.cast;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP server that exposes exactly one file — the track currently being cast — to
 * the Cast device on the LAN. Bound to all interfaces (0.0.0.0) so the speaker can reach
 * it, unlike {@link com.themoon.y1.subsonic.NavidromeProxyServer} which is localhost-only.
 *
 * Deliberately separate from {@link com.themoon.y1.Y1WebServer}: that server is sandboxed
 * to a single root folder for the file-manager UI, whereas the current track can live
 * anywhere on the SD card (Music, Navidrome downloads, Audiobooks). Serving one explicit,
 * app-chosen file behind an opaque token keeps the browse-anything surface closed while
 * still reaching every playable location.
 *
 * Supports HTTP Range (206 partial content), 416 for unsatisfiable ranges, and HEAD — all
 * of which the Cast Default Media Receiver relies on to seek and to probe length before
 * buffering. The receiver typically opens several connections at once (a HEAD probe plus
 * parallel range GETs), so the worker pool is unbounded-cached and every accepted socket
 * gets a read timeout so a half-open connection can't wedge playback.
 */
public final class CastMediaServer {
    private static final String TAG = "CastMediaServer";
    public static final int PORT = 8083;
    private static final int SOCKET_TIMEOUT_MS = 15000;

    private static volatile CastMediaServer sInstance;

    private volatile ServerSocket serverSocket;
    private volatile Thread worker;
    private volatile boolean running = false;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    // The single file we currently serve, guarded by an opaque token so a stale URL from a
    // previous track can't fetch the new one.
    private volatile File currentFile;
    private volatile String token;

    // Same idea for the current track's embedded/cached album art, served straight from
    // memory — local casts otherwise have no artwork the Cast device could fetch on its own.
    private volatile byte[] currentArt;
    private volatile String artToken;

    private CastMediaServer() {}

    public static synchronized CastMediaServer getInstance() {
        if (sInstance == null) sInstance = new CastMediaServer();
        return sInstance;
    }

    /** Idempotent — safe to call every time we start a cast, including after a stop. */
    public synchronized void ensureStarted() {
        if (running) return;
        running = true;
        // Hold the worker in a field (rather than being a Thread) so the server can be
        // stopped and started again — a Thread instance can only be start()ed once.
        worker = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "CastMediaServer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Point the server at {@code file} and return the path segment (token) the Cast device
     * should request. Rotates the token so previously handed-out URLs stop resolving.
     */
    public synchronized String serve(File file) {
        this.currentFile = file;
        this.token = Long.toHexString(System.nanoTime()) + Integer.toHexString(file.getAbsolutePath().hashCode());
        return token;
    }

    public String urlFor(String host, String token) {
        return "http://" + host + ":" + PORT + "/media/" + token;
    }

    /** Points the server at {@code art} (raw image bytes) and returns its token, mirroring
     *  {@link #serve(File)}. Pass null/empty to stop serving art for the current track. */
    public synchronized String serveArt(byte[] art) {
        if (art == null || art.length == 0) {
            this.currentArt = null;
            this.artToken = null;
            return null;
        }
        this.currentArt = art;
        this.artToken = Long.toHexString(System.nanoTime());
        return artToken;
    }

    public String artUrlFor(String host, String token) {
        return "http://" + host + ":" + PORT + "/art/" + token;
    }

    public synchronized void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        worker = null;
    }

    private void acceptLoop() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress((java.net.InetAddress) null, PORT)); // 0.0.0.0
            serverSocket = ss;
            while (running) {
                final Socket socket = ss.accept();
                try { socket.setSoTimeout(SOCKET_TIMEOUT_MS); } catch (Exception ignored) {}
                pool.execute(new Runnable() {
                    @Override public void run() { handle(socket); }
                });
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "server loop stopped", e);
        }
    }

    private void handle(Socket socket) {
        RandomAccessFile raf = null;
        try {
            InputStream is = new BufferedInputStream(socket.getInputStream());
            OutputStream os = socket.getOutputStream();

            String requestLine = readLine(is);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { writeStatus(os, 400, "Bad Request"); return; }
            String method = parts[0];
            String path = parts[1];

            String rangeHeader = null;
            String line;
            while ((line = readLine(is)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                }
            }

            if (path.startsWith("/art/")) {
                handleArt(os, method, path);
                return;
            }

            File file = currentFile;
            String tok = token;
            String want = "/media/" + (tok == null ? "" : tok);
            if (file == null || tok == null || !path.equals(want) || !file.exists()) {
                writeStatus(os, 404, "Not Found");
                return;
            }

            long len = file.length();
            long start = 0, end = len - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                try {
                    String spec = rangeHeader.substring(6).trim();
                    int dash = spec.indexOf('-');
                    String s = spec.substring(0, dash).trim();
                    String e = spec.substring(dash + 1).trim();
                    long rs = s.isEmpty() ? 0 : Long.parseLong(s);
                    long re = e.isEmpty() ? len - 1 : Long.parseLong(e);
                    if (rs >= len) {
                        // Genuinely unsatisfiable (start past EOF) — spec says 416.
                        os.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len
                                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        os.flush();
                        return;
                    }
                    if (re > len - 1) re = len - 1;
                    if (rs >= 0 && rs <= re) {
                        start = rs; end = re; partial = true;
                    }
                    // Inverted/degenerate range → fall through as a full-file 200 (start/end
                    // stay at their full-file defaults).
                } catch (Exception ex) {
                    start = 0; end = len - 1; partial = false;
                }
            }

            String contentType = mimeFor(file.getName());
            long contentLen = end - start + 1;
            StringBuilder h = new StringBuilder();
            h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(contentType).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Content-Length: ").append(contentLen).append("\r\n");
            if (partial) {
                h.append("Content-Range: bytes ").append(start).append("-").append(end)
                        .append("/").append(len).append("\r\n");
            }
            h.append("Connection: close\r\n\r\n");
            os.write(h.toString().getBytes("UTF-8"));

            if (method.equals("HEAD")) { os.flush(); return; }

            raf = new RandomAccessFile(file, "r");
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = contentLen;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = raf.read(buf, 0, toRead);
                if (read == -1) break;
                os.write(buf, 0, read);
                remaining -= read;
            }
            os.flush();
        } catch (Exception e) {
            // Broken pipe when the receiver stops mid-stream is normal — don't shout.
            Log.d(TAG, "request ended: " + e.getMessage());
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /** Serves the current track's in-memory album art. No Range support — Cast receivers
     *  fetch cover images with a single plain GET, unlike the audio file above. */
    private void handleArt(OutputStream os, String method, String path) throws java.io.IOException {
        byte[] art = currentArt;
        String tok = artToken;
        if (art == null || tok == null || !path.equals("/art/" + tok)) {
            writeStatus(os, 404, "Not Found");
            return;
        }
        String contentType = (art.length > 8 && (art[0] & 0xFF) == 0x89 && art[1] == 'P') ? "image/png" : "image/jpeg";
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + art.length
                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        if (!method.equals("HEAD")) os.write(art);
        os.flush();
    }

    private void writeStatus(OutputStream os, int code, String reason) {
        try {
            os.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes("UTF-8"));
            os.flush();
        } catch (Exception ignored) {}
    }

    private String readLine(InputStream is) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\r') continue;
            if (c == '\n') break;
            sb.append((char) c);
        }
        return (sb.length() == 0 && c == -1) ? null : sb.toString();
    }

    /** MIME type the Cast Default Media Receiver uses to select a decoder. */
    static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac") || n.endsWith(".alac")) return "audio/mp4";
        return "audio/*";
    }
}
