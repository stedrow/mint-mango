package com.themoon.y1.subsonic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

public class SubsonicClient {

    private static final String TAG = "Subsonic";

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    public interface DownloadCallback {
        /** percent is -1 when the server doesn't send a length (transcodes) — use bytesSoFar. */
        void onProgress(int percent, long bytesSoFar);
        void onComplete(String savedPath);
        void onError(String message);
    }

    private static SubsonicClient instance;
    private String serverUrl;
    private String username;
    private String password;
    private Context appContext;
    private com.themoon.y1.db.LibraryCacheDb libraryCacheDb;
    private final Handler mainHandler = new Handler();
    // Bounded shared pool instead of spawning a raw Thread per request, so a burst of calls
    // (e.g. loading an artist list with many albums) can't pile up unbounded threads on this SoC.
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);

    private SubsonicClient() {}

    public static synchronized SubsonicClient getInstance() {
        if (instance == null) instance = new SubsonicClient();
        return instance;
    }

    public void loadSettings(Context ctx) {
        appContext = ctx.getApplicationContext();
        if (libraryCacheDb == null) libraryCacheDb = new com.themoon.y1.db.LibraryCacheDb(appContext);
        SharedPreferences prefs = ctx.getSharedPreferences("Y1Prefs", Context.MODE_PRIVATE);
        serverUrl = prefs.getString("navidrome_url", "").trim().replaceAll("/+$", "");
        username  = prefs.getString("navidrome_user", "").trim();
        password  = prefs.getString("navidrome_pass", "").trim();
    }

    public void saveSettings(Context ctx, String url, String user, String pass) {
        String newUrl = url.trim().replaceAll("/+$", "");
        String newUser = user.trim();
        String newPass = pass.trim();
        boolean changed = !newUrl.equals(serverUrl) || !newUser.equals(username) || !newPass.equals(password);
        serverUrl = newUrl;
        username = newUser;
        password = newPass;
        ctx.getSharedPreferences("Y1Prefs", Context.MODE_PRIVATE).edit()
                .putString("navidrome_url", serverUrl)
                .putString("navidrome_user", username)
                .putString("navidrome_pass", password)
                .apply();
        // A stale artist cache from the previous server would otherwise keep showing
        // instantly and mask a bad URL/login on the new one (getArtists swallows
        // refresh errors whenever it already served cached data).
        if (changed) {
            configVersion++;
            if (libraryCacheDb == null) libraryCacheDb = new com.themoon.y1.db.LibraryCacheDb(ctx.getApplicationContext());
            libraryCacheDb.clearNavidromeArtists();
        }
    }

    // Bumped whenever saveSettings() actually changes the server/user/pass, so callers
    // holding their own in-memory artist list (e.g. MainActivity.lastNavidromeArtists)
    // know to drop it instead of showing another server's stale data.
    private int configVersion = 0;
    public int getConfigVersion() { return configVersion; }

    public boolean isConfigured() {
        return serverUrl != null && !serverUrl.isEmpty()
                && username != null && !username.isEmpty()
                && password != null;
    }

    public String getServerUrl() { return serverUrl != null ? serverUrl : ""; }

    // ── URL builders ─────────────────────────────────────────────────────────

    private String buildUrl(String endpoint, String extraParams) {
        String salt = generateSalt();
        String token = md5(password + salt);
        String base = serverUrl + "/rest/" + endpoint
                + "?u=" + encode(username)
                + "&t=" + token
                + "&s=" + salt
                + "&v=1.16.1&c=Y1Player&f=json";
        return extraParams != null ? base + "&" + extraParams : base;
    }

    public String getStreamUrl(String songId) {
        // estimateContentLength makes Navidrome send a Content-Length for transcoded
        // streams, which is what lets ExoPlayer compute duration and seek.
        return buildUrl("stream", "id=" + encode(songId) + "&format=mp3&maxBitRate=192&estimateContentLength=true");
    }

    public String getCoverArtUrl(String id, int size) {
        return buildUrl("getCoverArt", "id=" + encode(id) + "&size=" + size);
    }

    public String getDownloadUrl(String songId) {
        return buildUrl("download", "id=" + encode(songId));
    }

    public String getTranscodedDownloadUrl(String songId) {
        // No estimateContentLength here: HttpURLConnection enforces Content-Length,
        // and the estimate rarely matches ffmpeg's actual output — every download
        // would die with "unexpected end of file" at the finish line.
        return buildUrl("stream", "id=" + encode(songId) + "&format=mp3&maxBitRate=192");
    }

    // ── API calls ─────────────────────────────────────────────────────────────

    public void ping(final Callback<Boolean> cb) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject resp = fetchJson(buildUrl("ping", null));
                    final boolean ok = "ok".equals(resp.getJSONObject("subsonic-response").getString("status"));
                    mainHandler.post(new Runnable() { @Override public void run() {
                        if (ok) cb.onSuccess(true);
                        else cb.onError(extractError(resp));
                    }});
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onError(e.getMessage()); }});
                }
            }
        });
    }

    public void getArtists(final Callback<List<SubsonicArtist>> cb) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // Serve the cached list first so the screen is instant (and browsable
                // offline); then refresh from the network and re-deliver only if changed.
                final List<SubsonicArtist> cachedArtists = libraryCacheDb != null ? libraryCacheDb.loadNavidromeArtists() : null;
                boolean deliveredFromCache = cachedArtists != null && !cachedArtists.isEmpty();
                if (deliveredFromCache) {
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onSuccess(cachedArtists); }});
                }
                try {
                    String rawJson = fetchString(buildUrl("getArtists", null));
                    JSONObject root = new JSONObject(rawJson);
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        if (deliveredFromCache) return;
                        final String msg = extractError(root);
                        mainHandler.post(new Runnable() { @Override public void run() { cb.onError(msg); }});
                        return;
                    }
                    final List<SubsonicArtist> artists = parseArtists(root);
                    boolean unchanged = deliveredFromCache && artistListsEqual(cachedArtists, artists);
                    if (libraryCacheDb != null) libraryCacheDb.saveNavidromeArtists(artists);
                    if (unchanged) return; // no change, keep UI as-is
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onSuccess(artists); }});
                } catch (final Exception e) {
                    if (deliveredFromCache) {
                        android.util.Log.w("Subsonic", "getArtists refresh failed, using cache: " + e.getMessage());
                        return;
                    }
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onError(e.getMessage()); }});
                }
            }
        });
    }

    private static List<SubsonicArtist> parseArtists(JSONObject root) throws Exception {
        JSONObject sr = root.getJSONObject("subsonic-response");
        if (!"ok".equals(sr.getString("status"))) throw new Exception("status not ok");
        List<SubsonicArtist> artists = new ArrayList<>();
        JSONArray indexes = sr.getJSONObject("artists").getJSONArray("index");
        for (int i = 0; i < indexes.length(); i++) {
            JSONObject index = indexes.getJSONObject(i);
            String letter = index.optString("name", "#");
            JSONArray arr = index.optJSONArray("artist");
            if (arr == null) continue;
            for (int j = 0; j < arr.length(); j++) {
                JSONObject a = arr.getJSONObject(j);
                SubsonicArtist artist = new SubsonicArtist();
                artist.id = a.getString("id");
                artist.name = a.getString("name");
                artist.albumCount = a.optInt("albumCount", 0);
                artist.coverArtId = a.optString("coverArt", null);
                artist.indexLetter = letter;
                artists.add(artist);
            }
        }
        return artists;
    }

    private boolean artistListsEqual(List<SubsonicArtist> a, List<SubsonicArtist> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SubsonicArtist x = a.get(i), y = b.get(i);
            if (!java.util.Objects.equals(x.id, y.id)) return false;
            if (!java.util.Objects.equals(x.name, y.name)) return false;
            if (x.albumCount != y.albumCount) return false;
            if (!java.util.Objects.equals(x.coverArtId, y.coverArtId)) return false;
            if (!java.util.Objects.equals(x.indexLetter, y.indexLetter)) return false;
        }
        return true;
    }

    public void getArtist(final String artistId, final Callback<List<SubsonicAlbum>> cb) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("getArtist", "id=" + encode(artistId)));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        final String msg = extractError(root);
                        mainHandler.post(new Runnable() { @Override public void run() { cb.onError(msg); }});
                        return;
                    }
                    final List<SubsonicAlbum> albums = new ArrayList<>();
                    JSONObject artistObj = sr.getJSONObject("artist");
                    String artistName = artistObj.optString("name", "");
                    JSONArray arr = artistObj.optJSONArray("album");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject a = arr.getJSONObject(i);
                            SubsonicAlbum album = new SubsonicAlbum();
                            album.id = a.getString("id");
                            album.name = a.getString("name");
                            album.artistId = artistId;
                            album.artistName = artistName;
                            album.year = a.optInt("year", 0);
                            album.songCount = a.optInt("songCount", 0);
                            album.coverArtId = a.optString("coverArt", null);
                            albums.add(album);
                        }
                    }
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onSuccess(albums); }});
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onError(e.getMessage()); }});
                }
            }
        });
    }

    public void getAlbum(final String albumId, final Callback<List<SubsonicSong>> cb) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject root = fetchJson(buildUrl("getAlbum", "id=" + encode(albumId)));
                    JSONObject sr = root.getJSONObject("subsonic-response");
                    if (!"ok".equals(sr.getString("status"))) {
                        final String msg = extractError(root);
                        mainHandler.post(new Runnable() { @Override public void run() { cb.onError(msg); }});
                        return;
                    }
                    final List<SubsonicSong> songs = new ArrayList<>();
                    JSONObject albumObj = sr.getJSONObject("album");
                    String albumName = albumObj.optString("name", "");
                    String albumId2 = albumObj.optString("id", albumId);
                    String albumArtistName = albumObj.optString("artist", "");
                    JSONArray arr = albumObj.optJSONArray("song");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.getJSONObject(i);
                            SubsonicSong song = new SubsonicSong();
                            song.id = s.getString("id");
                            song.title = s.optString("title", "Unknown");
                            song.artist = s.optString("artist", "Unknown");
                            song.album = albumName;
                            song.albumId = albumId2;
                            song.track = s.optInt("track", 0);
                            song.durationSecs = s.optInt("duration", 0);
                            song.sizeBytes = s.optLong("size", 0);
                            song.suffix = s.optString("suffix", "mp3");
                            song.coverArtId = s.optString("coverArt", null);
                            song.year = s.optInt("year", 0);
                            song.genre = s.optString("genre", null);
                            song.albumArtist = albumArtistName;
                            songs.add(song);
                        }
                    }
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onSuccess(songs); }});
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onError(e.getMessage()); }});
                }
            }
        });
    }

    public void downloadSong(final String songId, final String savePath, final boolean transcoded, final DownloadCallback cb) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream is = null;
                // Download to a .part file and rename on success so a failed
                // transfer never leaves a truncated track for the media scanner.
                File partFile = new File(savePath + ".part");
                try {
                    String url = transcoded ? getTranscodedDownloadUrl(songId) : getDownloadUrl(songId);
                    conn = openConnection(url);
                    conn.connect();
                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) throw new Exception("HTTP " + responseCode);

                    long fileSize = conn.getContentLength();
                    is = conn.getInputStream();
                    if (partFile.getParentFile() != null) partFile.getParentFile().mkdirs();

                    FileOutputStream fos = new FileOutputStream(partFile);
                    byte[] buf = new byte[16384];
                    int read;
                    long total = 0;
                    int lastPercent = -1;
                    long lastReportedBytes = 0;
                    while ((read = is.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        total += read;
                        if (fileSize > 0) {
                            final int pct = (int) ((total * 100) / fileSize);
                            if (pct != lastPercent) {
                                lastPercent = pct;
                                final long soFar = total;
                                mainHandler.post(new Runnable() { @Override public void run() { cb.onProgress(pct, soFar); }});
                            }
                        } else if (total - lastReportedBytes >= 262144) { // every 256KB
                            lastReportedBytes = total;
                            final long soFar = total;
                            mainHandler.post(new Runnable() { @Override public void run() { cb.onProgress(-1, soFar); }});
                        }
                    }
                    fos.flush();
                    fos.getFD().sync();
                    fos.close();
                    // Transcoded length is only an estimate — the strict check would
                    // reject perfectly good transfers, so it applies to originals only
                    if (!transcoded && fileSize > 0 && total < fileSize) throw new Exception("Connection lost (" + total + "/" + fileSize + " bytes)");
                    if (!partFile.renameTo(new File(savePath))) throw new Exception("Could not rename .part file");
                    final String path = savePath;
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onComplete(path); }});
                } catch (final Exception e) {
                    android.util.Log.e(TAG, "Download failed: songId=" + songId + " path=" + savePath, e);
                    try {
                        partFile.delete();
                    } catch (Exception delEx) {
                        android.util.Log.d(TAG, "partFile cleanup failed", delEx);
                    }
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onError(e.getMessage()); }});
                } finally {
                    // Drain + close instead of disconnect() so the socket returns to the
                    // keep-alive pool (on the success path the stream is already at EOF).
                    drainAndClose(is);
                }
            }
        });
    }

    /**
     * Fetch cover art to a disk cache file (skips the network if already cached)
     * and return the local path. Callback fires on the main thread.
     */
    public void fetchCoverArt(final String coverArtId, final int size, final File cacheFile, final Callback<String> cb) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onSuccess(cacheFile.getAbsolutePath()); }});
                    return;
                }
                HttpURLConnection conn = null;
                InputStream is = null;
                File partFile = new File(cacheFile.getAbsolutePath() + ".part");
                try {
                    conn = openConnection(getCoverArtUrl(coverArtId, size));
                    conn.connect();
                    if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
                    is = conn.getInputStream();
                    if (partFile.getParentFile() != null) partFile.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(partFile);
                    byte[] buf = new byte[16384];
                    int read;
                    while ((read = is.read(buf)) != -1) fos.write(buf, 0, read);
                    fos.close();
                    if (!partFile.renameTo(cacheFile)) throw new Exception("rename failed");
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onSuccess(cacheFile.getAbsolutePath()); }});
                } catch (final Exception e) {
                    android.util.Log.e(TAG, "Cache download failed: " + cacheFile.getAbsolutePath(), e);
                    try {
                        partFile.delete();
                    } catch (Exception delEx) {
                        android.util.Log.d(TAG, "partFile cleanup failed", delEx);
                    }
                    mainHandler.post(new Runnable() { @Override public void run() { cb.onError(e.getMessage()); }});
                } finally {
                    // Drain + close instead of disconnect() so the socket returns to the
                    // keep-alive pool (on the success path the stream is already at EOF).
                    drainAndClose(is);
                }
            }
        });
    }

    // ── Blocking API (for the Web Server download manager) ──────────────────────
    // These run synchronously on the CALLER's thread and throw on error. They are
    // meant to be called from the Web Server's own connection-pool threads (never the
    // main/UI thread), so a download manager in the browser can search/browse without
    // the main-thread-callback ceremony the on-device UI needs.

    /** Combined search3 result: albums + songs (artists intentionally skipped — the
     *  web manager downloads albums/songs, not artists). */
    public static class SearchResult {
        public final List<SubsonicAlbum> albums = new ArrayList<>();
        public final List<SubsonicSong> songs = new ArrayList<>();
    }

    public SearchResult searchBlocking(String query, int albumCount, int songCount) throws Exception {
        JSONObject root = fetchJson(buildUrl("search3",
                "query=" + encode(query) + "&artistCount=0&albumCount=" + albumCount + "&songCount=" + songCount));
        JSONObject sr = root.getJSONObject("subsonic-response");
        if (!"ok".equals(sr.getString("status"))) throw new Exception(extractError(root));
        SearchResult res = new SearchResult();
        JSONObject s3 = sr.optJSONObject("searchResult3");
        if (s3 != null) {
            JSONArray al = s3.optJSONArray("album");
            if (al != null) for (int i = 0; i < al.length(); i++) res.albums.add(parseAlbumSummary(al.getJSONObject(i)));
            JSONArray so = s3.optJSONArray("song");
            if (so != null) for (int i = 0; i < so.length(); i++) res.songs.add(parseSong(so.getJSONObject(i), null, null));
        }
        return res;
    }

    /** getAlbumList2 — type is one of newest/frequent/recent/random/alphabeticalByName/etc. */
    public List<SubsonicAlbum> getAlbumListBlocking(String type, int size, int offset) throws Exception {
        JSONObject root = fetchJson(buildUrl("getAlbumList2",
                "type=" + encode(type) + "&size=" + size + "&offset=" + offset));
        JSONObject sr = root.getJSONObject("subsonic-response");
        if (!"ok".equals(sr.getString("status"))) throw new Exception(extractError(root));
        List<SubsonicAlbum> out = new ArrayList<>();
        JSONObject listObj = sr.optJSONObject("albumList2");
        if (listObj != null) {
            JSONArray arr = listObj.optJSONArray("album");
            if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(parseAlbumSummary(arr.getJSONObject(i)));
        }
        return out;
    }

    /** Full track list for an album — better metadata (album artist, real album name)
     *  than the per-song search results, so the downloader files tracks correctly. */
    public List<SubsonicSong> getAlbumSongsBlocking(String albumId) throws Exception {
        JSONObject root = fetchJson(buildUrl("getAlbum", "id=" + encode(albumId)));
        JSONObject sr = root.getJSONObject("subsonic-response");
        if (!"ok".equals(sr.getString("status"))) throw new Exception(extractError(root));
        JSONObject albumObj = sr.getJSONObject("album");
        String albumName = albumObj.optString("name", "");
        String albumArtistName = albumObj.optString("artist", "");
        String realAlbumId = albumObj.optString("id", albumId);
        List<SubsonicSong> songs = new ArrayList<>();
        JSONArray arr = albumObj.optJSONArray("song");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                SubsonicSong song = parseSong(arr.getJSONObject(i), albumName, albumArtistName);
                song.album = albumName;
                song.albumId = realAlbumId;
                songs.add(song);
            }
        }
        return songs;
    }

    /** Blocking cover-art fetch to a disk cache file; returns the cached File. Reuses
     *  the cache on repeat calls so the browser's covers cost the Y1 nothing after the
     *  first view. */
    public File cacheCoverArtBlocking(String coverArtId, int size, File cacheFile) throws Exception {
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile;
        HttpURLConnection conn = null;
        InputStream is = null;
        File partFile = new File(cacheFile.getAbsolutePath() + ".part");
        try {
            conn = openConnection(getCoverArtUrl(coverArtId, size));
            conn.connect();
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            is = conn.getInputStream();
            if (partFile.getParentFile() != null) partFile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(partFile);
            byte[] buf = new byte[16384];
            int read;
            while ((read = is.read(buf)) != -1) fos.write(buf, 0, read);
            fos.close();
            if (!partFile.renameTo(cacheFile)) throw new Exception("rename failed");
            return cacheFile;
        } catch (Exception e) {
            try { partFile.delete(); } catch (Exception delEx) { android.util.Log.d(TAG, "cover partFile cleanup failed", delEx); }
            throw e;
        } finally {
            drainAndClose(is);
        }
    }

    private static SubsonicAlbum parseAlbumSummary(JSONObject a) {
        SubsonicAlbum al = new SubsonicAlbum();
        al.id = a.optString("id");
        al.name = a.optString("name", a.optString("album", "Unknown"));
        al.artistId = a.optString("artistId", null);
        al.artistName = a.optString("artist", "");
        al.year = a.optInt("year", 0);
        al.songCount = a.optInt("songCount", 0);
        al.coverArtId = a.optString("coverArt", null);
        return al;
    }

    private static SubsonicSong parseSong(JSONObject s, String albumNameFallback, String albumArtistFallback) {
        SubsonicSong song = new SubsonicSong();
        song.id = s.optString("id");
        song.title = s.optString("title", "Unknown");
        song.artist = s.optString("artist", "Unknown");
        song.album = s.optString("album", albumNameFallback != null ? albumNameFallback : "");
        song.albumId = s.optString("albumId", null);
        song.track = s.optInt("track", 0);
        song.durationSecs = s.optInt("duration", 0);
        song.sizeBytes = s.optLong("size", 0);
        song.suffix = s.optString("suffix", "mp3");
        song.coverArtId = s.optString("coverArt", null);
        song.year = s.optInt("year", 0);
        song.genre = s.optString("genre", null);
        song.albumArtist = s.optString("albumArtist", albumArtistFallback != null ? albumArtistFallback : "");
        return song;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JSONObject fetchJson(String urlStr) throws Exception {
        return new JSONObject(fetchString(urlStr));
    }

    private String fetchString(String urlStr) throws Exception {
        HttpURLConnection conn = openConnection(urlStr);
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200) {
            // Drain the error body so this socket can still return to the keep-alive pool.
            drainAndClose(conn.getErrorStream());
            throw new Exception("HTTP " + code);
        }
        InputStream is = conn.getInputStream();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            // Read to EOF + close (NOT disconnect()) so the underlying socket goes
            // back to HttpURLConnection's keep-alive pool and the next call skips the
            // TLS handshake.
            drainAndClose(is);
        }
    }

    // Drains a response stream to EOF and closes it. Used instead of conn.disconnect()
    // so the socket is returned to the keep-alive pool rather than being torn down.
    private static void drainAndClose(InputStream is) {
        if (is == null) return;
        try {
            byte[] buf = new byte[4096];
            while (is.read(buf) != -1) { /* discard remaining bytes */ }
        } catch (Exception e) {
            android.util.Log.d(TAG, "drainAndClose: drain failed", e);
        } finally {
            try {
                is.close();
            } catch (Exception e) {
                android.util.Log.d(TAG, "drainAndClose: close failed", e);
            }
        }
    }

    private HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        if (conn instanceof HttpsURLConnection) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(getTls12Factory());
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Y1Player/1.0");
        return conn;
    }

    private static SSLSocketFactory tls12Factory;

    // The Y1's factory Android build predates Let's Encrypt's ISRG Root X1 being added to
    // the system trust store, so plain system validation rejects most Navidrome servers'
    // certs. Rather than trusting all certs (previous approach), trust the system store
    // plus this pinned Let's Encrypt root so real cert validation still applies.
    private static final String ISRG_ROOT_X1_PEM =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" +
            "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
            "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n" +
            "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n" +
            "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n" +
            "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n" +
            "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n" +
            "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n" +
            "A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW\n" +
            "T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH\n" +
            "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC\n" +
            "B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv\n" +
            "KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn\n" +
            "OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn\n" +
            "jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw\n" +
            "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI\n" +
            "rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV\n" +
            "HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq\n" +
            "hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL\n" +
            "ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ\n" +
            "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK\n" +
            "NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5\n" +
            "ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur\n" +
            "TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC\n" +
            "jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc\n" +
            "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq\n" +
            "4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA\n" +
            "mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d\n" +
            "emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=\n" +
            "-----END CERTIFICATE-----\n";

    private static X509TrustManager[] systemAndLetsEncryptTrustManagers() throws Exception {
        TrustManagerFactory systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        systemTmf.init((KeyStore) null);
        X509TrustManager systemTm = null;
        for (TrustManager tm : systemTmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) { systemTm = (X509TrustManager) tm; break; }
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate isrgRootX1 = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(ISRG_ROOT_X1_PEM.getBytes("UTF-8")));
        KeyStore letsEncryptStore = KeyStore.getInstance(KeyStore.getDefaultType());
        letsEncryptStore.load(null, null);
        letsEncryptStore.setCertificateEntry("isrg-root-x1", isrgRootX1);
        TrustManagerFactory leTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        leTmf.init(letsEncryptStore);
        X509TrustManager letsEncryptTm = null;
        for (TrustManager tm : leTmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) { letsEncryptTm = (X509TrustManager) tm; break; }
        }

        return new X509TrustManager[]{systemTm, letsEncryptTm};
    }

    private static synchronized SSLSocketFactory getTls12Factory() throws Exception {
        if (tls12Factory == null) {
            final X509TrustManager[] delegates = systemAndLetsEncryptTrustManagers();
            TrustManager[] combined = new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    delegates[0].checkClientTrusted(chain, authType);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    for (X509TrustManager tm : delegates) {
                        if (tm == null) continue;
                        try {
                            tm.checkServerTrusted(chain, authType);
                            return;
                        } catch (CertificateException ignored) {
                            // fall through to next trust manager
                        }
                    }
                    throw new CertificateException("No trust manager accepted the server certificate");
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    List<X509Certificate> all = new ArrayList<>();
                    for (X509TrustManager tm : delegates) {
                        if (tm != null) all.addAll(java.util.Arrays.asList(tm.getAcceptedIssuers()));
                    }
                    return all.toArray(new X509Certificate[0]);
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, combined, null);
            tls12Factory = new Tls12SocketFactory(ctx.getSocketFactory());
        }
        return tls12Factory;
    }

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory base) { this.delegate = base; }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }
        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }
        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }
        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"});
            }
            return s;
        }
    }

    private String extractError(JSONObject root) {
        try {
            JSONObject sr = root.getJSONObject("subsonic-response");
            if (sr.has("error")) return sr.getJSONObject("error").optString("message", "Server error");
        } catch (Exception e) {
            android.util.Log.d(TAG, "extractError: malformed response JSON", e);
        }
        return "Unknown server error";
    }

    private String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private String generateSalt() {
        return String.format(Locale.US, "%06x", (int) (Math.random() * 0xFFFFFF));
    }

    private String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
