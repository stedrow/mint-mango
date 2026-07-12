package com.themoon.y1;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Y1WebServer extends Thread {
    private static final String TAG = "Y1WebServer";

    /** Package-visible (not private) so it's unit-testable without a running server/socket. */
    static final class RangeResult {
        final long start;
        final long end;
        final boolean partial;

        RangeResult(long start, long end, boolean partial) {
            this.start = start;
            this.end = end;
            this.partial = partial;
        }
    }

    /**
     * Parses an HTTP "Range: bytes=start-end" header (including a suffix range "bytes=-N" for
     * the last N bytes) against a known file length. Returns a full-file, non-partial result for
     * a null/absent header or anything malformed -- callers should always fall back to serving
     * the whole file rather than erroring out on a Range header they can't parse.
     */
    static RangeResult parseRange(String rangeHeader, long fileLen) {
        long start = 0;
        long end = fileLen - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String startStr = spec.substring(0, dash).trim();
                String endStr = spec.substring(dash + 1).trim();
                try {
                    if (startStr.isEmpty() && !endStr.isEmpty()) {
                        // Suffix range "bytes=-N" => the LAST N bytes of the file.
                        long n = Long.parseLong(endStr);
                        if (n > fileLen) n = fileLen;
                        start = fileLen - n;
                        end = fileLen - 1;
                    } else {
                        if (!startStr.isEmpty()) start = Long.parseLong(startStr);
                        if (!endStr.isEmpty()) end = Long.parseLong(endStr);
                        if (end > fileLen - 1) end = fileLen - 1;
                    }
                    if (start >= 0 && start <= end) partial = true;
                } catch (NumberFormatException nfe) {
                    partial = false;
                    start = 0;
                    end = fileLen - 1;
                }
            }
        }
        return new RangeResult(start, end, partial);
    }
    private ServerSocket serverSocket;
    private boolean running = true;
    private final File rootFolder = new File("/storage/sdcard0"); // file manager serves the whole device, not just the app's music folder
    private Context context;
    // Wider than before because keep-alive connections now hold their pool thread across an
    // idle gap (browsers open several); the threads are almost always blocked on a socket read,
    // so they're cheap. Paired with a short idle SO_TIMEOUT so they free quickly.
    private final ExecutorService connectionPool = Executors.newFixedThreadPool(16);
    public Y1WebServer(Context context) {
        this.context = context;
    }

    public void run() {
        try {
            serverSocket = new ServerSocket(8080);
            while (running) {
                Socket socket = serverSocket.accept();
                connectionPool.execute(new RequestHandler(socket));
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Server loop stopped", e);
        }
    }

    public void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch(Exception e){ Log.w(TAG, "Error closing server socket", e); }
        connectionPool.shutdownNow();
    }

    // WifiManager.getConnectionInfo().getIpAddress() is a cached snapshot that can go stale
    // after a DHCP lease renewal or reconnect, so read the live interface address instead.
    public String getLocalIpAddress() {
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
            return "Unknown IP";
        } catch (Exception ex) { Log.w(TAG, "Could not resolve local IP", ex); return "Unknown IP"; }
    }

    // Resolves a client-supplied relative path against rootFolder and rejects
    // anything that escapes it via ".." traversal (verified: Java's File(parent, child)
    // does NOT discard an absolute child, but canonicalization still collapses "..").
    private File resolveSafePath(String relativePath) throws java.io.IOException {
        File target = (relativePath == null || relativePath.isEmpty()) ? rootFolder : new File(rootFolder, relativePath);
        String rootCanonical = rootFolder.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.equals(rootCanonical) && !targetCanonical.startsWith(rootCanonical + File.separator)) {
            throw new java.io.IOException("Path escapes root folder: " + relativePath);
        }
        return target;
    }

    private void deleteFileOrFolder(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteFileOrFolder(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    // ── Helpers for the Navidrome download-manager endpoints ────────────────────

    /** Parse the query string of a request path ("/api/x?a=1&b=2") into decoded pairs. */
    private static java.util.Map<String, String> parseQuery(String path) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        int q = path.indexOf('?');
        if (q < 0 || q == path.length() - 1) return out;
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) out.put(URLDecoder.decode(pair, "UTF-8"), "");
                else out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception e) { Log.d(TAG, "parseQuery decode failed for: " + pair, e); }
        }
        return out;
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void appendAlbumJson(StringBuilder sb, com.themoon.y1.subsonic.SubsonicAlbum al) {
        sb.append("{\"id\":\"").append(jsonEsc(al.id)).append("\"")
                .append(",\"name\":\"").append(jsonEsc(al.name)).append("\"")
                .append(",\"artist\":\"").append(jsonEsc(al.artistName)).append("\"")
                .append(",\"year\":").append(al.year)
                .append(",\"songCount\":").append(al.songCount)
                .append(",\"coverArt\":\"").append(jsonEsc(al.coverArtId)).append("\"}");
    }

    /** Full song JSON. include the downloaded flag only in album detail (a cheap stat()
     *  per song) — never in big list responses where it would add up. */
    private static void appendSongJson(StringBuilder sb, com.themoon.y1.subsonic.SubsonicSong s, boolean withDownloaded) {
        sb.append("{\"id\":\"").append(jsonEsc(s.id)).append("\"")
                .append(",\"title\":\"").append(jsonEsc(s.title)).append("\"")
                .append(",\"artist\":\"").append(jsonEsc(s.artist)).append("\"")
                .append(",\"album\":\"").append(jsonEsc(s.album)).append("\"")
                .append(",\"albumId\":\"").append(jsonEsc(s.albumId)).append("\"")
                .append(",\"albumArtist\":\"").append(jsonEsc(s.albumArtist)).append("\"")
                .append(",\"track\":").append(s.track)
                .append(",\"duration\":").append(s.durationSecs)
                .append(",\"size\":").append(s.sizeBytes)
                .append(",\"suffix\":\"").append(jsonEsc(s.suffix)).append("\"")
                .append(",\"year\":").append(s.year)
                .append(",\"genre\":\"").append(jsonEsc(s.genre)).append("\"")
                .append(",\"coverArt\":\"").append(jsonEsc(s.coverArtId)).append("\"");
        if (withDownloaded) sb.append(",\"downloaded\":").append(s.isDownloaded());
        sb.append("}");
    }

    // ── Response writers (all emit Content-Length + Connection so keep-alive works) ──
    private void sendResponse(OutputStream os, int status, String statusText, String contentType,
                              byte[] body, boolean keepAlive, String extraHeaders) throws java.io.IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
        if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        if (extraHeaders != null) h.append(extraHeaders);
        h.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
        if (body.length > 0) os.write(body);
    }

    private void sendJson(OutputStream os, String json, boolean keepAlive) throws java.io.IOException {
        sendResponse(os, 200, "OK", "application/json; charset=UTF-8", json.getBytes("UTF-8"), keepAlive, null);
    }
    private void sendJsonError(OutputStream os, String message, boolean keepAlive) throws java.io.IOException {
        sendJson(os, "{\"ok\":false,\"error\":\"" + jsonEsc(message) + "\"}", keepAlive);
    }
    private void sendText(OutputStream os, int status, String statusText, String text, boolean keepAlive) throws java.io.IOException {
        sendResponse(os, status, statusText, "text/plain; charset=UTF-8", text.getBytes("UTF-8"), keepAlive, null);
    }

    /** Read exactly contentLength bytes of a request body as a UTF-8 string (small API bodies). */
    private static String readRequestBody(InputStream is, int contentLength) throws java.io.IOException {
        if (contentLength <= 0) return "";
        byte[] body = new byte[contentLength];
        int total = 0;
        while (total < body.length) {
            int r = is.read(body, total, body.length - total);
            if (r == -1) break;
            total += r;
        }
        return new String(body, 0, total, "UTF-8");
    }

    /** Discard exactly n body bytes so the stream stays aligned for the next keep-alive request. */
    private static void drainBody(InputStream is, long n) throws java.io.IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = is.read(buf, 0, (int) Math.min(buf.length, n));
            if (r == -1) break;
            n -= r;
        }
    }

    /** Serve a file bundled under assets/webui. */
    private void serveAsset(OutputStream os, String assetPath, String contentType, boolean keepAlive) throws java.io.IOException {
        InputStream in = null;
        try {
            in = context.getAssets().open(assetPath);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int r;
            while ((r = in.read(tmp)) != -1) buf.write(tmp, 0, r);
            sendResponse(os, 200, "OK", contentType, buf.toByteArray(), keepAlive, null);
        } catch (java.io.FileNotFoundException fnf) {
            sendText(os, 404, "Not Found", "Not Found", keepAlive);
        } finally {
            if (in != null) try { in.close(); } catch (Exception e) { Log.d(TAG, "asset close failed", e); }
        }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /**
     * Resolve a request body into concrete songs. Body is one of:
     *   {"albumId":".."}                 — whole album
     *   {"albumId":"..","songIds":[".."]} — selected album tracks
     *   {"songs":[{...}]}                — songs the browser already has
     * The album fetch (network) happens on the web thread; callers hop to the main thread
     * only for the short enqueue/delete step.
     */
    private java.util.List<com.themoon.y1.subsonic.SubsonicSong> resolveSongsFromBody(JSONObject obj) throws Exception {
        java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs = new java.util.ArrayList<>();
        String albumId = obj.optString("albumId", "");
        if (!albumId.isEmpty()) {
            java.util.List<com.themoon.y1.subsonic.SubsonicSong> albumSongs =
                    com.themoon.y1.subsonic.SubsonicClient.getInstance().getAlbumSongsBlocking(albumId);
            org.json.JSONArray idFilter = obj.optJSONArray("songIds");
            if (idFilter != null && idFilter.length() > 0) {
                java.util.Set<String> keep = new java.util.HashSet<>();
                for (int i = 0; i < idFilter.length(); i++) keep.add(idFilter.optString(i));
                for (com.themoon.y1.subsonic.SubsonicSong s : albumSongs) if (keep.contains(s.id)) songs.add(s);
            } else {
                songs.addAll(albumSongs);
            }
        } else {
            org.json.JSONArray arr = obj.optJSONArray("songs");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                songs.add(com.themoon.y1.subsonic.SubsonicSong.fromWebJson(arr.getJSONObject(i)));
            }
        }
        return songs;
    }

    private void handleNavidromeDownload(OutputStream os, String body, boolean keepAlive) throws java.io.IOException {
        final java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs;
        final boolean transcoded;
        try {
            JSONObject obj = new JSONObject(body);
            transcoded = obj.optBoolean("transcoded", false);
            songs = resolveSongsFromBody(obj);
        } catch (Exception e) {
            sendJsonError(os, e.getMessage() != null ? e.getMessage() : "Bad request", keepAlive);
            return;
        }
        if (songs.isEmpty()) { sendJsonError(os, "No tracks to download", keepAlive); return; }
        final com.themoon.y1.MainActivity a = com.themoon.y1.MainActivity.instance;
        if (a == null) { sendJsonError(os, "Player app not running", keepAlive); return; }

        final boolean tr = transcoded;
        final com.themoon.y1.managers.NavidromeManager.EnqueueResult[] holder = new com.themoon.y1.managers.NavidromeManager.EnqueueResult[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        a.runOnUiThread(new Runnable() { @Override public void run() {
            try { holder[0] = com.themoon.y1.managers.NavidromeManager.getInstance().enqueueNavidromeDownloadsCore(a, songs, tr); }
            finally { latch.countDown(); }
        }});
        try { latch.await(15, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        com.themoon.y1.managers.NavidromeManager.EnqueueResult r = holder[0];
        if (r == null) { sendJsonError(os, "Timed out queueing download", keepAlive); return; }
        if (r.error != null) { sendJsonError(os, r.error, keepAlive); return; }
        sendJson(os, "{\"ok\":true,\"queued\":" + r.queued + ",\"alreadyHave\":" + r.alreadyHave + "}", keepAlive);
    }

    private void handleNavidromeDelete(OutputStream os, String body, boolean keepAlive) throws java.io.IOException {
        final java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs;
        try {
            songs = resolveSongsFromBody(new JSONObject(body));
        } catch (Exception e) {
            sendJsonError(os, e.getMessage() != null ? e.getMessage() : "Bad request", keepAlive);
            return;
        }
        if (songs.isEmpty()) { sendJsonError(os, "Nothing to delete", keepAlive); return; }
        final com.themoon.y1.MainActivity a = com.themoon.y1.MainActivity.instance;
        if (a == null) { sendJsonError(os, "Player app not running", keepAlive); return; }

        final int[] deleted = {0};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        a.runOnUiThread(new Runnable() { @Override public void run() {
            try {
                com.themoon.y1.managers.NavidromeManager nm = com.themoon.y1.managers.NavidromeManager.getInstance();
                for (com.themoon.y1.subsonic.SubsonicSong s : songs) if (nm.deleteNavidromeDownload(a, s)) deleted[0]++;
                nm.refreshNavidromeSongLabels(a);
            } finally { latch.countDown(); }
        }});
        try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        sendJson(os, "{\"ok\":true,\"deleted\":" + deleted[0] + "}", keepAlive);
    }

    private static final int MAX_HEADER_LINE = 8192;       // one header line
    private static final int MAX_HEADERS = 100;            // header lines per request
    private static final int MAX_REQUESTS_PER_CONN = 200;  // keep-alive request cap per socket
    private static final int NONSTREAM_BODY_CAP = 1 << 20; // 1MB cap for JSON bodies read into memory

    private class RequestHandler implements Runnable {
        private final Socket socket;
        RequestHandler(Socket socket) { this.socket = socket; }

        // Reads one CRLF/LF-terminated header line, capped so a client that never sends a
        // newline can't grow the buffer without bound (memory-exhaustion guard).
        private String readHeaderLine(InputStream is) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\r') continue;
                if (c == '\n') break;
                sb.append((char) c);
                if (sb.length() > MAX_HEADER_LINE) throw new java.io.IOException("Header line too long");
            }
            return sb.toString();
        }

        public void run() {
            try {
                // Short idle timeout: keeps the connection warm across a burst (cover grid,
                // 2s queue polls) but frees the pool thread quickly when the browser goes quiet.
                socket.setSoTimeout(10000);
                InputStream is = new BufferedInputStream(socket.getInputStream());
                OutputStream os = socket.getOutputStream();
                int served = 0;
                boolean keepGoing = true;
                while (running && keepGoing && served < MAX_REQUESTS_PER_CONN) {
                    String requestLine;
                    try {
                        requestLine = readHeaderLine(is);
                    } catch (java.net.SocketTimeoutException ste) {
                        break; // idle keep-alive connection -> close
                    }
                    if (requestLine.isEmpty()) break; // client closed / no further pipelined request
                    served++;
                    keepGoing = handleRequest(is, os, requestLine);
                    os.flush();
                }
            } catch (Exception e) {
                Log.w(TAG, "Request failed", e);
            } finally {
                try { socket.close(); } catch (Exception e) { /* socket already gone */ }
            }
        }

        /** Handle one request; returns whether the connection may be reused for another. */
        private boolean handleRequest(InputStream is, OutputStream os, String requestLine) throws java.io.IOException {
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { sendText(os, 400, "Bad Request", "Bad Request", false); return false; }
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            String rangeHeader = null;
            boolean clientClose = false;
            String line;
            int headerCount = 0;
            while (!(line = readHeaderLine(is)).isEmpty()) {
                if (++headerCount > MAX_HEADERS) { sendText(os, 431, "Request Header Fields Too Large", "Too many headers", false); return false; }
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); }
                    catch (NumberFormatException nfe) { contentLength = 0; }
                } else if (lower.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                } else if (lower.startsWith("connection:")) {
                    if (lower.contains("close")) clientClose = true;
                }
            }
            if (contentLength < 0) contentLength = 0;
            boolean keepAlive = !clientClose && running;

            // Pre-read small bodies so the stream stays aligned for the next keep-alive request.
            // Large binary uploads (upload/save) are streamed straight to disk by their handlers.
            boolean streamsBody = method.equals("POST") && (path.startsWith("/api/upload") || path.startsWith("/api/save"));
            String body = "";
            if (!streamsBody && contentLength > 0) {
                if (contentLength > NONSTREAM_BODY_CAP) { drainBody(is, contentLength); sendJsonError(os, "Request too large", false); return false; }
                body = readRequestBody(is, contentLength);
            }

            // ── Shared stylesheet + favicon ──
            if (method.equals("GET") && path.equals("/app.css")) { serveAsset(os, "webui/app.css", "text/css; charset=UTF-8", keepAlive); return keepAlive; }
            if (method.equals("GET") && path.equals("/favicon.ico")) { sendText(os, 404, "Not Found", "", keepAlive); return keepAlive; }

            // ── Pages ──
            if (method.equals("GET") && path.equals("/")) { serveAsset(os, "webui/files.html", "text/html; charset=UTF-8", keepAlive); return keepAlive; }
            if (method.equals("GET") && (path.equals("/music") || path.startsWith("/music?"))) { serveAsset(os, "webui/music.html", "text/html; charset=UTF-8", keepAlive); return keepAlive; }

            // ── Disk info ──
            if (method.equals("GET") && path.startsWith("/api/diskinfo")) {
                long free = 0, total = 0;
                try {
                    android.os.StatFs sf = new android.os.StatFs("/storage/sdcard0");
                    free = (long) sf.getAvailableBlocks() * sf.getBlockSize();
                    total = (long) sf.getBlockCount() * sf.getBlockSize();
                } catch (Exception e) { Log.d(TAG, "statfs failed", e); }
                sendJson(os, "{\"freeBytes\":" + free + ",\"totalBytes\":" + total + "}", keepAlive);
                return keepAlive;
            }

            // ── File manager: list ──
            if (method.equals("GET") && path.startsWith("/api/list")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String dirStr = qp.containsKey("dir") ? qp.get("dir") : "";
                File targetDir = resolveSafePath(dirStr);
                StringBuilder json = new StringBuilder("[");
                if (targetDir.exists() && targetDir.isDirectory()) {
                    File[] files = targetDir.listFiles();
                    if (files != null) {
                        for (int i = 0; i < 2; i++) {
                            for (File f : files) {
                                boolean isDir = f.isDirectory();
                                if ((i == 0 && isDir) || (i == 1 && !isDir)) {
                                    if (json.length() > 1) json.append(",");
                                    json.append("{\"name\":\"").append(jsonEsc(f.getName())).append("\",\"isDir\":").append(isDir).append("}");
                                }
                            }
                        }
                    }
                }
                json.append("]");
                sendJson(os, json.toString(), keepAlive);
                return keepAlive;
            }
            // ── File manager: create folder ──
            if (method.equals("POST") && path.startsWith("/api/create")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String dirStr = qp.containsKey("dir") ? qp.get("dir") : "";
                String name = qp.containsKey("name") ? qp.get("name") : "";
                if (!name.isEmpty()) { File nd = resolveSafePath(dirStr.isEmpty() ? name : dirStr + "/" + name); nd.mkdirs(); }
                sendText(os, 200, "OK", "OK", keepAlive);
                return keepAlive;
            }
            // ── File manager: delete ──
            if (method.equals("POST") && path.startsWith("/api/delete")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String targetPath = qp.containsKey("path") ? qp.get("path") : "";
                if (!targetPath.isEmpty()) { File tf = resolveSafePath(targetPath); if (tf.exists()) deleteFileOrFolder(tf); }
                sendText(os, 200, "OK", "OK", keepAlive);
                return keepAlive;
            }
            // ── File manager: rename ──
            if (method.equals("POST") && path.startsWith("/api/rename")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String dirStr = qp.containsKey("dir") ? qp.get("dir") : "";
                String oldName = qp.containsKey("old") ? qp.get("old") : "";
                String newName = qp.containsKey("new") ? qp.get("new") : "";
                if (!oldName.isEmpty() && !newName.isEmpty()) {
                    File oldFile = resolveSafePath(dirStr.isEmpty() ? oldName : dirStr + "/" + oldName);
                    File newFile = resolveSafePath(dirStr.isEmpty() ? newName : dirStr + "/" + newName);
                    if (oldFile.exists() && !newFile.exists()) oldFile.renameTo(newFile);
                }
                sendText(os, 200, "OK", "OK", keepAlive);
                return keepAlive;
            }
            // ── Navidrome settings: GET (never returns the stored password) ──
            if (method.equals("GET") && path.equals("/api/navidrome-settings")) {
                android.content.SharedPreferences prefs = context.getSharedPreferences("Y1Prefs", android.content.Context.MODE_PRIVATE);
                String navUrl = prefs.getString("navidrome_url", "");
                String navUser = prefs.getString("navidrome_user", "");
                boolean hasPass = !prefs.getString("navidrome_pass", "").isEmpty();
                sendJson(os, "{\"url\":\"" + jsonEsc(navUrl) + "\",\"user\":\"" + jsonEsc(navUser) + "\",\"hasPassword\":" + hasPass + "}", keepAlive);
                return keepAlive;
            }
            // ── Navidrome settings: POST (pass optional — omitted keeps the stored one) ──
            if (method.equals("POST") && path.equals("/api/navidrome-settings")) {
                boolean pingOk = false;
                String pingError = "Invalid request";
                try {
                    JSONObject obj = new JSONObject(body);
                    String navUrl = obj.optString("url", "").trim().replaceAll("/+$", "");
                    String navUser = obj.optString("user", "").trim();
                    android.content.SharedPreferences prefs = context.getSharedPreferences("Y1Prefs", android.content.Context.MODE_PRIVATE);
                    String navPass = obj.has("pass") ? obj.optString("pass", "") : prefs.getString("navidrome_pass", "");
                    prefs.edit()
                            .putString("navidrome_url", navUrl)
                            .putString("navidrome_user", navUser)
                            .putString("navidrome_pass", navPass)
                            .apply();
                    com.themoon.y1.subsonic.SubsonicClient.getInstance().saveSettings(context, navUrl, navUser, navPass);
                    if (com.themoon.y1.subsonic.SubsonicClient.getInstance().isConfigured()) {
                        com.themoon.y1.subsonic.NavidromeProxyServer.ensureStarted();
                    }
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final boolean[] okHolder = {false};
                    final String[] errHolder = {"Timed out waiting for server"};
                    com.themoon.y1.subsonic.SubsonicClient.getInstance().ping(
                            new com.themoon.y1.subsonic.SubsonicClient.Callback<Boolean>() {
                                @Override public void onSuccess(Boolean result) { okHolder[0] = true; latch.countDown(); }
                                @Override public void onError(String message) { errHolder[0] = message; latch.countDown(); }
                            });
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                    pingOk = okHolder[0];
                    pingError = errHolder[0];
                } catch (Exception e) {
                    pingError = e.getMessage() != null ? e.getMessage() : "Save failed";
                }
                sendJson(os, pingOk ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"" + jsonEsc(pingError == null ? "Unknown error" : pingError) + "\"}", keepAlive);
                return keepAlive;
            }

            // ── Navidrome browse: albums (getAlbumList2) ──
            if (method.equals("GET") && path.startsWith("/api/navidrome/albums")) {
                if (!com.themoon.y1.subsonic.SubsonicClient.getInstance().isConfigured()) { sendJsonError(os, "Navidrome is not configured — set the server URL and login on the Files page first.", keepAlive); return keepAlive; }
                java.util.Map<String, String> qp = parseQuery(path);
                String type = qp.containsKey("type") ? qp.get("type") : "newest";
                int size = parseIntSafe(qp.get("size"), 24);
                int offset = parseIntSafe(qp.get("offset"), 0);
                try {
                    java.util.List<com.themoon.y1.subsonic.SubsonicAlbum> albums =
                            com.themoon.y1.subsonic.SubsonicClient.getInstance().getAlbumListBlocking(type, size, offset);
                    StringBuilder json = new StringBuilder("{\"albums\":[");
                    for (int i = 0; i < albums.size(); i++) { if (i > 0) json.append(","); appendAlbumJson(json, albums.get(i)); }
                    json.append("]}");
                    sendJson(os, json.toString(), keepAlive);
                } catch (Exception e) { sendJsonError(os, e.getMessage() != null ? e.getMessage() : "Browse failed", keepAlive); }
                return keepAlive;
            }
            // ── Navidrome search (search3) ──
            if (method.equals("GET") && path.startsWith("/api/navidrome/search")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String query = qp.containsKey("q") ? qp.get("q") : "";
                if (query.trim().isEmpty()) { sendJson(os, "{\"albums\":[],\"songs\":[]}", keepAlive); return keepAlive; }
                try {
                    com.themoon.y1.subsonic.SubsonicClient.SearchResult r =
                            com.themoon.y1.subsonic.SubsonicClient.getInstance().searchBlocking(query, 30, 30);
                    StringBuilder json = new StringBuilder("{\"albums\":[");
                    for (int i = 0; i < r.albums.size(); i++) { if (i > 0) json.append(","); appendAlbumJson(json, r.albums.get(i)); }
                    json.append("],\"songs\":[");
                    for (int i = 0; i < r.songs.size(); i++) { if (i > 0) json.append(","); appendSongJson(json, r.songs.get(i), false); }
                    json.append("]}");
                    sendJson(os, json.toString(), keepAlive);
                } catch (Exception e) { sendJsonError(os, e.getMessage() != null ? e.getMessage() : "Search failed", keepAlive); }
                return keepAlive;
            }
            // ── Navidrome album detail (getAlbum) — must come AFTER /albums ──
            if (method.equals("GET") && path.startsWith("/api/navidrome/album")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String id = qp.get("id");
                if (id == null || id.isEmpty()) { sendJsonError(os, "Missing album id", keepAlive); return keepAlive; }
                try {
                    java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs =
                            com.themoon.y1.subsonic.SubsonicClient.getInstance().getAlbumSongsBlocking(id);
                    StringBuilder json = new StringBuilder("{\"songs\":[");
                    for (int i = 0; i < songs.size(); i++) { if (i > 0) json.append(","); appendSongJson(json, songs.get(i), true); }
                    json.append("]}");
                    sendJson(os, json.toString(), keepAlive);
                } catch (Exception e) { sendJsonError(os, e.getMessage() != null ? e.getMessage() : "Load failed", keepAlive); }
                return keepAlive;
            }
            // ── Navidrome cover proxy (disk-cached) ──
            if (method.equals("GET") && path.startsWith("/api/navidrome/cover")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String id = qp.get("id");
                int size = parseIntSafe(qp.get("size"), 200);
                if (size < 32) size = 32;
                if (size > 640) size = 640;
                if (id == null || id.isEmpty()) { sendText(os, 404, "Not Found", "No cover", keepAlive); return keepAlive; }
                File art;
                try {
                    File cacheDir = new File("/storage/sdcard0/Y1_Covers/NavidromeWeb");
                    File cacheFile = new File(cacheDir, id.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + size + ".jpg");
                    art = com.themoon.y1.subsonic.SubsonicClient.getInstance().cacheCoverArtBlocking(id, size, cacheFile);
                } catch (Exception e) {
                    sendText(os, 404, "Not Found", "No cover", keepAlive);
                    return keepAlive;
                }
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + art.length()
                        + "\r\nCache-Control: max-age=604800\r\nConnection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n").getBytes("UTF-8"));
                FileInputStream fis = new FileInputStream(art);
                try {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = fis.read(buffer)) != -1) os.write(buffer, 0, n);
                } finally { fis.close(); }
                return keepAlive;
            }
            // ── Navidrome download queue state ──
            if (method.equals("GET") && path.startsWith("/api/navidrome/queue")) {
                sendJson(os, com.themoon.y1.managers.NavidromeManager.getInstance().getWebQueueJson(), keepAlive);
                return keepAlive;
            }
            // ── Navidrome enqueue download ──
            if (method.equals("POST") && path.startsWith("/api/navidrome/download")) { handleNavidromeDownload(os, body, keepAlive); return keepAlive; }
            // ── Navidrome delete downloaded track(s) ──
            if (method.equals("POST") && path.startsWith("/api/navidrome/delete")) { handleNavidromeDelete(os, body, keepAlive); return keepAlive; }
            // ── Navidrome clear pending queue ──
            if (method.equals("POST") && path.startsWith("/api/navidrome/cancel")) {
                final com.themoon.y1.MainActivity a = com.themoon.y1.MainActivity.instance;
                if (a == null) { sendJsonError(os, "Player app not running", keepAlive); return keepAlive; }
                final int[] removed = {0};
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                a.runOnUiThread(new Runnable() { @Override public void run() {
                    try { removed[0] = com.themoon.y1.managers.NavidromeManager.getInstance().clearPendingNavidromeDownloads(); }
                    finally { latch.countDown(); }
                }});
                try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                sendJson(os, "{\"ok\":true,\"removed\":" + removed[0] + "}", keepAlive);
                return keepAlive;
            }

            // ── File read/stream (audio seek, image view, editor load). Always closes the
            //    connection afterward: a client aborting a media stream mid-transfer would
            //    otherwise leave the socket in an unknown state for the next request. ──
            if (method.equals("GET") && path.startsWith("/api/file")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String targetPath = qp.containsKey("path") ? qp.get("path") : "";
                File targetFile = resolveSafePath(targetPath);
                if (!targetFile.exists() || targetFile.isDirectory()) { sendText(os, 404, "Not Found", "Not Found", false); return false; }
                String mimeType = "application/octet-stream";
                String lowerName = targetFile.getName().toLowerCase();
                if (lowerName.endsWith(".mp3")) mimeType = "audio/mpeg";
                else if (lowerName.endsWith(".flac")) mimeType = "audio/flac";
                else if (lowerName.endsWith(".wav")) mimeType = "audio/wav";
                else if (lowerName.endsWith(".ogg") || lowerName.endsWith(".opus")) mimeType = "audio/ogg";
                else if (lowerName.endsWith(".m4a") || lowerName.endsWith(".aac")) mimeType = "audio/mp4";
                else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) mimeType = "image/jpeg";
                else if (lowerName.endsWith(".png")) mimeType = "image/png";
                else if (lowerName.endsWith(".webp")) mimeType = "image/webp";
                else if (lowerName.endsWith(".gif")) mimeType = "image/gif";
                else if (lowerName.endsWith(".json")) mimeType = "application/json";
                else if (lowerName.endsWith(".txt") || lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8") || lowerName.endsWith(".eq")
                        || lowerName.endsWith(".md") || lowerName.endsWith(".log") || lowerName.endsWith(".xml") || lowerName.endsWith(".ini")
                        || lowerName.endsWith(".cfg") || lowerName.endsWith(".csv")) mimeType = "text/plain; charset=UTF-8";

                long fileLen = targetFile.length();
                RangeResult range = parseRange(rangeHeader, fileLen);
                long start = range.start, end = range.end;
                boolean partial = range.partial;
                String header;
                if (partial) {
                    header = "HTTP/1.1 206 Partial Content\r\nContent-Type: " + mimeType + "\r\nContent-Length: " + (end - start + 1)
                            + "\r\nContent-Range: bytes " + start + "-" + end + "/" + fileLen + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n";
                } else {
                    header = "HTTP/1.1 200 OK\r\nContent-Type: " + mimeType + "\r\nContent-Length: " + fileLen
                            + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n";
                }
                os.write(header.getBytes("UTF-8"));
                FileInputStream fis = new FileInputStream(targetFile);
                try {
                    if (partial && start > 0) {
                        long toSkip = start;
                        while (toSkip > 0) { long skipped = fis.skip(toSkip); if (skipped <= 0) break; toSkip -= skipped; }
                    }
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    if (partial) {
                        long remaining = end - start + 1;
                        while (remaining > 0 && (bytesRead = fis.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                            os.write(buffer, 0, bytesRead);
                            remaining -= bytesRead;
                        }
                    } else {
                        while ((bytesRead = fis.read(buffer)) != -1) os.write(buffer, 0, bytesRead);
                    }
                } finally { fis.close(); }
                return false; // always close after a file stream
            }
            // ── File upload (streams the body straight to disk) ──
            if (method.equals("POST") && path.startsWith("/api/upload")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String dirStr = qp.containsKey("dir") ? qp.get("dir") : "";
                String name = qp.containsKey("name") ? qp.get("name") : "unnamed.file";
                File targetDir = resolveSafePath(dirStr);
                if (!targetDir.exists()) targetDir.mkdirs();
                File outFile = resolveSafePath(dirStr.isEmpty() ? name : dirStr + "/" + name);
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    byte[] buffer = new byte[8192];
                    int bytesRead, totalRead = 0;
                    while (totalRead < contentLength && (bytesRead = is.read(buffer, 0, Math.min(buffer.length, contentLength - totalRead))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                    }
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception e) { Log.d(TAG, "fsync failed after upload write", e); }
                } finally { fos.close(); }
                sendText(os, 200, "OK", "OK", keepAlive);
                return keepAlive;
            }
            // ── Save text file (streams the body straight to disk) ──
            if (method.equals("POST") && path.startsWith("/api/save")) {
                java.util.Map<String, String> qp = parseQuery(path);
                String targetPath = qp.containsKey("path") ? qp.get("path") : "";
                File targetFile = resolveSafePath(targetPath);
                FileOutputStream fos = new FileOutputStream(targetFile);
                try {
                    byte[] buffer = new byte[8192];
                    int bytesRead, totalRead = 0;
                    while (totalRead < contentLength && (bytesRead = is.read(buffer, 0, Math.min(buffer.length, contentLength - totalRead))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                    }
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception e) { Log.d(TAG, "fsync failed after save write", e); }
                } finally { fos.close(); }
                sendText(os, 200, "OK", "OK", keepAlive);
                return keepAlive;
            }

            sendText(os, 404, "Not Found", "Not Found", keepAlive);
            return keepAlive;
        }
    }
}
