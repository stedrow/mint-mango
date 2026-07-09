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
 * Supports HTTP Range (206 partial content) and HEAD, both of which the Cast Default Media
 * Receiver relies on to seek and to probe length before buffering.
 */
public final class CastMediaServer extends Thread {
    private static final String TAG = "CastMediaServer";
    public static final int PORT = 8083;

    private static volatile CastMediaServer sInstance;

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    // The single file we currently serve, guarded by an opaque token so a stale URL from a
    // previous track can't fetch the new one.
    private volatile File currentFile;
    private volatile String token;

    private CastMediaServer() {
        setDaemon(true);
        setName("CastMediaServer");
    }

    public static synchronized CastMediaServer getInstance() {
        if (sInstance == null) sInstance = new CastMediaServer();
        return sInstance;
    }

    /** Idempotent — safe to call every time we start a cast. */
    public synchronized void ensureStarted() {
        if (running) return;
        running = true;
        start();
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

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress((java.net.InetAddress) null, PORT)); // 0.0.0.0
            while (running) {
                final Socket socket = serverSocket.accept();
                pool.execute(new Runnable() {
                    @Override public void run() { handle(socket); }
                });
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "server loop stopped", e);
        }
    }

    public void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
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
                    if (!s.isEmpty()) start = Long.parseLong(s);
                    if (!e.isEmpty()) end = Long.parseLong(e);
                    if (start < 0) start = 0;
                    if (end > len - 1) end = len - 1;
                    if (start <= end) partial = true;
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
