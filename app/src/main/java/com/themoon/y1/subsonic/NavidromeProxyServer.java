package com.themoon.y1.subsonic;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal localhost HTTP proxy for Navidrome streaming.
 * Listens on 127.0.0.1:8081, proxies GET /stream?id=SONGID to the Navidrome
 * server using our HttpURLConnection stack (TLS 1.2 + trust-all already
 * installed globally in MainActivity). ExoPlayer connects via plain HTTP so
 * it never touches SSL itself.
 *
 * Singleton: only one instance ever binds port 8081. Call ensureStarted()
 * from MainActivity.onCreate() — safe to call multiple times.
 */
public class NavidromeProxyServer extends Thread {

    private static volatile NavidromeProxyServer sInstance;

    public static synchronized void ensureStarted() {
        if (sInstance != null && sInstance.isAlive()) {
            android.util.Log.d("NaviProxy", "Proxy already running, skipping");
            return;
        }
        sInstance = new NavidromeProxyServer();
        sInstance.startServer();
    }

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    // Bounded pool instead of one raw Thread per accept(): caps how many concurrent
    // upstream streams the tiny Y1 hardware can be dragged into at once.
    private final ExecutorService clientPool = Executors.newFixedThreadPool(4);

    private NavidromeProxyServer() {
        setDaemon(true);
        setName("NavidromeProxy");
    }

    private void startServer() {
        if (running) return;
        running = true;
        start();
    }

    public void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        try { clientPool.shutdownNow(); } catch (Throwable ignored) {}
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8081), 4);
            android.util.Log.d("NaviProxy", "Proxy listening on 127.0.0.1:8081");
            while (running) {
                try {
                    final Socket client = serverSocket.accept();
                    clientPool.execute(new Runnable() {
                        @Override public void run() { handleClient(client); }
                    });
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            android.util.Log.e("NaviProxy", "Server error", e);
        }
    }

    private void handleClient(Socket client) {
        HttpURLConnection conn = null;
        try {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            // Read request line + headers byte-by-byte until \r\n\r\n
            StringBuilder req = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                req.append((char) b);
                int len = req.length();
                if (len >= 4
                        && req.charAt(len - 4) == '\r' && req.charAt(len - 3) == '\n'
                        && req.charAt(len - 2) == '\r' && req.charAt(len - 1) == '\n') break;
            }

            String[] lines = req.toString().split("\r\n");
            String requestLine = lines.length > 0 ? lines[0] : "";

            if (!requestLine.startsWith("GET")) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes("UTF-8"));
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
                return;
            }

            String path = parts[1]; // /stream?id=abc123
            String songId = null;
            String rangeHeader = null;
            if (path.contains("?")) {
                for (String param : path.split("\\?")[1].split("&")) {
                    if (param.startsWith("id=")) {
                        songId = java.net.URLDecoder.decode(param.substring(3), "UTF-8");
                        break;
                    }
                }
            }
            for (String line : lines) {
                if (line.toLowerCase().startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                }
            }

            if (songId == null) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\nMissing id".getBytes("UTF-8"));
                return;
            }

            String streamUrl = SubsonicClient.getInstance().getStreamUrl(songId);
            conn = (HttpURLConnection) new URL(streamUrl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000); // long but finite: a hung upstream must not pin a proxy thread forever
            conn.setRequestMethod("GET");
            // Forward Range header to Navidrome if ExoPlayer sent one
            if (rangeHeader != null) {
                conn.setRequestProperty("Range", rangeHeader);
            }
            conn.connect();

            int status = conn.getResponseCode();
            String contentType = conn.getContentType();
            // getContentLengthLong() requires API 24; use getContentLength() for API 17
            int contentLength = conn.getContentLength();
            String contentRange = conn.getHeaderField("Content-Range");

            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 ").append(status).append(status == 206 ? " Partial Content" : " OK").append("\r\n");
            if (contentType != null)  header.append("Content-Type: ").append(contentType).append("\r\n");
            if (contentLength > 0)    header.append("Content-Length: ").append(contentLength).append("\r\n");
            if (contentRange != null) header.append("Content-Range: ").append(contentRange).append("\r\n");
            header.append("Accept-Ranges: bytes\r\n");
            header.append("Connection: close\r\n");
            header.append("\r\n");
            out.write(header.toString().getBytes("UTF-8"));

            InputStream audioIn = conn.getInputStream();
            byte[] buf = new byte[32768];
            int read;
            while ((read = audioIn.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
            audioIn.close();

        } catch (Throwable e) {
            android.util.Log.w("NaviProxy", "handleClient error: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            try { client.close(); } catch (Throwable ignored) {}
        }
    }
}
