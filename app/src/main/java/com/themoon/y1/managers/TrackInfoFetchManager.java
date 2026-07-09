package com.themoon.y1.managers;

import android.content.Context;
import android.widget.Toast;

import com.themoon.y1.MainActivity;

import java.io.File;

/**
 * Looks up a track's title/artist/cover art from the Deezer search API when local tags are
 * missing or unreliable, caching the result into LibraryCacheDb and (if it's the currently
 * playing track) refreshing the Now Playing UI. Extracted verbatim from MainActivity per
 * GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- reads/writes MainActivity's library cache and player UI fields
 * directly -- so it takes the MainActivity instance as a parameter. MainActivity keeps a thin
 * pass-through method for fetchTrackInfoFromInternet() since AudioPlayerManager calls it by
 * name.
 */
public class TrackInfoFetchManager {
    private static TrackInfoFetchManager instance;

    private TrackInfoFetchManager() {}

    public static synchronized TrackInfoFetchManager getInstance() {
        if (instance == null) {
            instance = new TrackInfoFetchManager();
        }
        return instance;
    }

    public void fetchTrackInfoFromInternet(final MainActivity a, final File track, final String originalQuery, final boolean hasValidTags,
                                           final String origTitle, final String origArtist) {

        // 🚀 [Added] Offline guard: silently turn back if there's no Wi-Fi or data connection!
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo == null || !netInfo.isConnected()) {
            return;
        }

        final String cleanQuery = originalQuery
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("^[0-9\\s\\-]+", "")
                .replaceAll("\\s[0-9]{2}\\s", " ")
                .trim();

        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, "🔍 Searching: " + cleanQuery, Toast.LENGTH_SHORT).show();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String query = java.net.URLEncoder.encode(cleanQuery, "UTF-8");
                    String urlString = "http://api.deezer.com/search?q=" + query;
                    java.net.URL url = new java.net.URL(urlString);

                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200)
                        throw new Exception("HTTP Response Code: " + responseCode);

                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null)
                        response.append(line);
                    reader.close();

                    org.json.JSONObject jsonResponse = new org.json.JSONObject(response.toString());
                    org.json.JSONArray dataArray = jsonResponse.optJSONArray("data");

                    if (dataArray != null && dataArray.length() > 0) {
                        org.json.JSONObject trackInfo = dataArray.getJSONObject(0);

                        final String fetchedTitle = trackInfo.getString("title");
                        final String fetchedArtist = trackInfo.getJSONObject("artist").getString("name");

                        final String finalTitle = fetchedTitle;
                        final String finalArtist = fetchedArtist;

                        // cover_big (500px) is plenty for this device's small screen; cover_xl (1000px) just wastes decode time/memory.
                        String coverUrl = trackInfo.getJSONObject("album").getString("cover_big").replace("https://", "http://");
                        java.net.URL imgUrl = new java.net.URL(coverUrl);
                        java.net.HttpURLConnection imgConn = (java.net.HttpURLConnection) imgUrl.openConnection();
                        java.io.InputStream in = imgConn.getInputStream();
                        final android.graphics.Bitmap coverBitmap = android.graphics.BitmapFactory.decodeStream(in);
                        in.close();

                        File coverFolder = new File("/storage/sdcard0/Y1_Covers");
                        if (!coverFolder.exists()) coverFolder.mkdirs();
                        String safeFileName = track.getName().replace(".mp3", "").replace(".flac", "");
                        final File coverFile = new File(coverFolder, safeFileName + ".jpg");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(coverFile);
                        coverBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos);
                        fos.close();

                        // 🚀 [Added] Don't forget to also save the album cover path (album_art_) to storage!
                        a.libraryCacheDb.setMetaOverride(track.getAbsolutePath(), finalTitle, finalArtist);
                        a.libraryCacheDb.setAlbumArtPath(track.getAbsolutePath(), coverFile.getAbsolutePath()); // 💡 The key line that had been missing

                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(a, "✅ Album Art & Info Updated!", Toast.LENGTH_SHORT).show();
                                if (a.currentPlaylist.get(a.currentIndex).getAbsolutePath().equals(track.getAbsolutePath())) {
                                    a.tvPlayerTitle.setText(finalTitle);
                                    a.tvPlayerArtist.setText(finalArtist);
                                    a.applyCachedCoverArt(coverFile.getAbsolutePath());
                                }
                            }
                        });
                    } else {
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() { Toast.makeText(a, "❌ No results found.", Toast.LENGTH_SHORT).show(); }
                        });
                    }
                } catch (final Exception e) {
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() { Toast.makeText(a, "⚠️ Connection Error", Toast.LENGTH_LONG).show(); }
                    });
                }
            }
        }).start();
    }
}
