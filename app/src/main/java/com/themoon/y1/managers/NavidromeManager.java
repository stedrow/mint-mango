package com.themoon.y1.managers;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.models.SongItem;
import com.themoon.y1.subsonic.SubsonicAlbum;
import com.themoon.y1.subsonic.SubsonicArtist;
import com.themoon.y1.subsonic.SubsonicClient;
import com.themoon.y1.subsonic.SubsonicSong;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Builds the Navidrome/Subsonic browse screens (artists/albums/songs, letter-jump index) and
 * runs the one-at-a-time download queue (acquire wake/wifi locks, transfer, register into the
 * local library, cache cover art, retry up to MAX_NAVIDROME_DOWNLOAD_RETRIES). Extracted from
 * MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * Genuinely clean field boundary like BluetoothAudioManager: the download-queue state
 * (NavidromeDownloadItem, the queue itself, progress counters, wake/wifi locks) is private to
 * this engine and moves here outright. A handful of fields stay in MainActivity instead --
 * navidromeBrowseDepth/selectedNavidromeArtist/isNavidromeLetterView/lastNavidromeArtists/
 * navidromeBackTarget are already touched directly by KeyEventRouter's back-navigation branch
 * (`a.navidromeBrowseDepth` etc.), and the view bindings (layoutNavidromeMode,
 * containerNavidromeItems, tvNavidromePath, tvNavidromeStatus) are assigned once from
 * findViewById() in MainActivity.onCreate() -- both reached here via the MainActivity
 * instance parameter, the same pattern as FmRadioUiManager/SettingsUiManager/KeyEventRouter.
 */
public class NavidromeManager {
    private static final String TAG = "NavidromeManager";
    private static NavidromeManager instance;

    private SubsonicAlbum selectedNavidromeAlbum;
    private List<SubsonicSong> lastNavidromeSongs = new ArrayList<>();
    private boolean isNavidromeLoading = false;
    private int lastSeenNavidromeConfigVersion = 0; // detects a server/user/pass change made via the Web Server web UI

    // Navidrome download queue -- one transfer at a time (the ~190kbps link can't
    // share), with progress shown in tv_navidrome_status
    private static class NavidromeDownloadItem {
        final SubsonicSong song;
        final boolean transcoded; // true = MP3 192kbps, false = original file
        int retryCount = 0; // bumped on each failed attempt, reset never -- item is discarded once exhausted
        NavidromeDownloadItem(SubsonicSong song, boolean transcoded) {
            this.song = song;
            this.transcoded = transcoded;
        }
    }
    private static final int MAX_NAVIDROME_DOWNLOAD_RETRIES = 2; // up to 3 attempts total per track
    private final ArrayDeque<NavidromeDownloadItem> navidromeDownloadQueue = new ArrayDeque<>();
    private boolean isNavidromeDownloading = false;
    private int navidromeQueueTotal = 0;
    private int navidromeQueueDone = 0;
    // Keep CPU + WiFi awake while the queue runs -- otherwise transfers stall
    // and time out as soon as the screen sleeps
    private android.os.PowerManager.WakeLock navidromeDownloadWakeLock;
    private android.net.wifi.WifiManager.WifiLock navidromeDownloadWifiLock;
    // Keep CPU + WiFi awake while a track is streaming from the server. ExoPlayer's
    // WAKE_MODE_NETWORK is supposed to cover this, but on this device WiFi still dozes
    // once the screen turns off (the stream reaches ExoPlayer through a localhost proxy,
    // so the real network fetch happens off in NavidromeProxyServer's threads). An
    // explicit high-perf WiFi lock held for the streaming session fixes the stalls.
    private android.os.PowerManager.WakeLock navidromeStreamWakeLock;
    private android.net.wifi.WifiManager.WifiLock navidromeStreamWifiLock;
    private String currentNavidromeCoverArtId; // guards against stale async art landing on a newer track
    // Downloads run strictly one at a time -- parallel transfers just divide the
    // ~190kbps link and make every track take the full album's time.
    private String currentNavidromeDownloadId;

    /** Clears the selected-album state; called from MainActivity whenever the Navidrome browse
     * state is reset (opening Navidrome fresh from the main menu, a library shortcut, etc.). */
    public void clearSelectedAlbum() {
        selectedNavidromeAlbum = null;
    }

    private NavidromeManager() {}

    public static synchronized NavidromeManager getInstance() {
        if (instance == null) {
            instance = new NavidromeManager();
        }
        return instance;
    }

    public void updateNavidromeQualityInfo(MainActivity a, com.themoon.y1.managers.AudioPlayerManager am) {
        if (am.navidromePlaylist.isEmpty()) return;
        com.themoon.y1.subsonic.SubsonicSong song = am.navidromePlaylist.get(am.navidromeIndex);
        String localPath = song.getExistingLocalPath();
        if (localPath != null) {
            // Playing the downloaded file — show its real quality info
            a.updateAudioQualityInfo(new File(localPath));
            return;
        }
        if (a.layoutAudioQualityContainer == null) return;
        // Streaming: Navidrome transcodes everything to MP3 at maxBitRate=192
        a.tvQualityExt.setText("MP3");
        a.tvQualityFormat.setText("STREAM");
        a.tvQualityBitrate.setText("192 kbps");
        a.tvQualityBitrate.setVisibility(View.VISIBLE);
        a.layoutAudioQualityContainer.setVisibility(View.VISIBLE);
        a.qualityInfoHandler.removeCallbacks(a.hideQualityInfoTask);
        a.qualityInfoHandler.postDelayed(a.hideQualityInfoTask, 3000);
    }

    public void buildNavidromeUI(MainActivity a) {
        com.themoon.y1.subsonic.SubsonicClient client = com.themoon.y1.subsonic.SubsonicClient.getInstance();

        if (client.getConfigVersion() != lastSeenNavidromeConfigVersion) {
            // Server/user/pass changed via the Web Server web UI since we last browsed —
            // drop the old server's in-memory artist list so we don't show it as if
            // the new settings never took effect.
            lastSeenNavidromeConfigVersion = client.getConfigVersion();
            a.lastNavidromeArtists = new java.util.ArrayList<>();
            lastNavidromeSongs = new java.util.ArrayList<>();
            a.navidromeBrowseDepth = a.NAV_ARTISTS;
            a.selectedNavidromeArtist = null;
            selectedNavidromeAlbum = null;
            a.isNavidromeLetterView = false;
        }

        if (!client.isConfigured()) {
            showNavidromeMessage(a, "NOT CONFIGURED",
                    "Open the Web Server page from your computer browser,\nthen fill in the Navidrome settings section.");
            return;
        }

        android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager)
                a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            showNavidromeWifiOffMessage(a);
            return;
        }

        if (a.navidromeBrowseDepth == a.NAV_ARTISTS) {
            a.tvNavidromePath.setText("NAVIDROME  ▸  Artists");
            // Already have the list from this session? Show it instantly and only
            // refresh silently in the background — no "Loading artists…" flash.
            final boolean showedInstantly = !a.lastNavidromeArtists.isEmpty();
            if (showedInstantly) {
                a.tvNavidromeStatus.setTextColor(0xFF00FF88);
                a.tvNavidromeStatus.setText("●");
                buildNavidromeArtistsUI(a, a.lastNavidromeArtists);
            } else {
                a.tvNavidromeStatus.setTextColor(0xFFFFFF00);
                a.tvNavidromeStatus.setText("●");
                showNavidromeMessage(a, "", "Loading artists…");
                isNavidromeLoading = true;
            }

            client.getArtists(new com.themoon.y1.subsonic.SubsonicClient.Callback<java.util.List<com.themoon.y1.subsonic.SubsonicArtist>>() {
                @Override
                public void onSuccess(java.util.List<com.themoon.y1.subsonic.SubsonicArtist> artists) {
                    isNavidromeLoading = false;
                    a.tvNavidromeStatus.setTextColor(0xFF00FF88);
                    boolean changed = !navidromeArtistListsEqual(a, artists, a.lastNavidromeArtists);
                    boolean stillOnArtists = a.currentScreenState == a.STATE_NAVIDROME
                            && a.navidromeBrowseDepth == a.NAV_ARTISTS && !a.isNavidromeLetterView;
                    a.lastNavidromeArtists = artists;
                    // Rebuild only for the first load or a real library change, and
                    // only while the artist list is still what's on screen.
                    if (stillOnArtists && (changed || !showedInstantly)) {
                        buildNavidromeArtistsUI(a, artists);
                    }
                }
                @Override
                public void onError(String message) {
                    isNavidromeLoading = false;
                    if (showedInstantly) return; // a stale list beats an error screen
                    a.tvNavidromeStatus.setTextColor(0xFFFF5555);
                    showNavidromeMessage(a, "CONNECTION ERROR", message);
                }
            });

        } else if (a.navidromeBrowseDepth == a.NAV_ALBUMS && a.selectedNavidromeArtist != null) {
            a.tvNavidromePath.setText("NAVIDROME  ▸  " + a.selectedNavidromeArtist.name);
            showNavidromeMessage(a, "", "Loading albums…");

            client.getArtist(a.selectedNavidromeArtist.id, new com.themoon.y1.subsonic.SubsonicClient.Callback<java.util.List<com.themoon.y1.subsonic.SubsonicAlbum>>() {
                @Override
                public void onSuccess(java.util.List<com.themoon.y1.subsonic.SubsonicAlbum> albums) {
                    buildNavidromeAlbumsUI(a, albums);
                }
                @Override
                public void onError(String message) {
                    showNavidromeMessage(a, "ERROR", message);
                }
            });

        } else if (a.navidromeBrowseDepth == a.NAV_SONGS && selectedNavidromeAlbum != null) {
            a.tvNavidromePath.setText("NAVIDROME  ▸  " + selectedNavidromeAlbum.artistName + "  ▸  " + selectedNavidromeAlbum.name);
            showNavidromeMessage(a, "", "Loading songs…");

            client.getAlbum(selectedNavidromeAlbum.id, new com.themoon.y1.subsonic.SubsonicClient.Callback<java.util.List<com.themoon.y1.subsonic.SubsonicSong>>() {
                @Override
                public void onSuccess(java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
                    lastNavidromeSongs = songs;
                    buildNavidromeSongsUI(a, songs);
                }
                @Override
                public void onError(String message) {
                    showNavidromeMessage(a, "ERROR", message);
                }
            });
        }
    }

    public boolean navidromeArtistListsEqual(MainActivity a, java.util.List<com.themoon.y1.subsonic.SubsonicArtist> listA,
                                              java.util.List<com.themoon.y1.subsonic.SubsonicArtist> b) {
        if (listA.size() != b.size()) return false;
        for (int i = 0; i < listA.size(); i++) {
            com.themoon.y1.subsonic.SubsonicArtist x = listA.get(i), y = b.get(i);
            if (!x.id.equals(y.id) || !x.name.equals(y.name) || x.albumCount != y.albumCount) return false;
        }
        return true;
    }

    private void showNavidromeWifiOffMessage(MainActivity a) {
        a.containerNavidromeItems.removeAllViews();
        SongContextMenuManager.getInstance().showWifiOffDialog(a, a.t("Navidrome requires a Wi-Fi connection"));
    }

    public void showNavidromeMessage(MainActivity a, String title, String body) {
        a.containerNavidromeItems.removeAllViews();
        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(a);
            tv.setText(title);
            tv.setTextColor(0xFFFF5555);
            tv.setTextSize(16);
            tv.setPadding(20, 20, 20, 6);
            a.containerNavidromeItems.addView(tv);
        }
        TextView tv = new TextView(a);
        tv.setText(body);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(14);
        tv.setPadding(20, 8, 20, 20);
        a.containerNavidromeItems.addView(tv);
    }

    public void buildNavidromeArtistsUI(MainActivity a, java.util.List<com.themoon.y1.subsonic.SubsonicArtist> artists) {
        buildNavidromeArtistsUI(a, artists, null);
    }

    public void buildNavidromeArtistsUI(MainActivity a, java.util.List<com.themoon.y1.subsonic.SubsonicArtist> artists, String focusLetter) {
        a.lastNavidromeArtists = artists;
        a.isNavidromeLetterView = false;
        a.containerNavidromeItems.removeAllViews();
        if (artists.isEmpty()) {
            showNavidromeMessage(a, "", "No artists found on server.");
            return;
        }

        Button btnJump = a.createListButton("A-Z  Jump to Letter");
        btnJump.setTextColor(0xFF88CCFF);
        btnJump.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { buildNavidromeLetterIndexUI(a); }
        });
        a.containerNavidromeItems.addView(btnJump);

        View focusTarget = null;
        for (final com.themoon.y1.subsonic.SubsonicArtist artist : artists) {
            String label = artist.name + "  (" + artist.albumCount + " albums)";
            Button btn = a.createListButton(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.selectedNavidromeArtist = artist;
                    a.navidromeBrowseDepth = a.NAV_ALBUMS;
                    buildNavidromeUI(a);
                }
            });
            a.containerNavidromeItems.addView(btn);
            if (focusTarget == null && focusLetter != null && focusLetter.equals(artist.indexLetter)) {
                focusTarget = btn;
            }
        }
        if (focusTarget != null) {
            final View target = focusTarget;
            // Focus after layout so the ScrollView can scroll to the letter
            a.containerNavidromeItems.post(new Runnable() {
                @Override public void run() { target.requestFocus(); }
            });
        } else {
            focusFirstNavidromeItem(a);
        }
    }

    public void buildNavidromeLetterIndexUI(MainActivity a) {
        a.isNavidromeLetterView = true;
        a.containerNavidromeItems.removeAllViews();
        a.tvNavidromePath.setText("NAVIDROME  ▸  Jump to Letter");

        java.util.List<String> letters = new java.util.ArrayList<>();
        final java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        for (com.themoon.y1.subsonic.SubsonicArtist artist : a.lastNavidromeArtists) {
            String letter = artist.indexLetter != null ? artist.indexLetter : "#";
            if (!letters.contains(letter)) letters.add(letter);
            Integer c = counts.get(letter);
            counts.put(letter, c == null ? 1 : c + 1);
        }
        for (final String letter : letters) {
            Button btn = a.createListButton(letter + "   (" + counts.get(letter) + " artists)");
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.tvNavidromePath.setText("NAVIDROME  ▸  Artists");
                    buildNavidromeArtistsUI(a, a.lastNavidromeArtists, letter);
                }
            });
            a.containerNavidromeItems.addView(btn);
        }
        focusFirstNavidromeItem(a);
    }

    public void buildNavidromeAlbumsUI(MainActivity a, java.util.List<com.themoon.y1.subsonic.SubsonicAlbum> albums) {
        a.containerNavidromeItems.removeAllViews();
        if (albums.isEmpty()) {
            showNavidromeMessage(a, "", "No albums found for this artist.");
            return;
        }
        for (final com.themoon.y1.subsonic.SubsonicAlbum album : albums) {
            String yearStr = album.year > 0 ? " (" + album.year + ")" : "";
            String label = album.name + yearStr + "  —  " + album.songCount + " songs";
            Button btn = a.createListButton(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedNavidromeAlbum = album;
                    a.navidromeBrowseDepth = a.NAV_SONGS;
                    buildNavidromeUI(a);
                }
            });
            a.containerNavidromeItems.addView(btn);
        }
        focusFirstNavidromeItem(a);
    }

    public void buildNavidromeSongsUI(MainActivity a, final java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
        a.containerNavidromeItems.removeAllViews();
        if (songs.isEmpty()) {
            showNavidromeMessage(a, "", "No songs found in this album.");
            return;
        }

        // Play All button
        Button btnPlayAll = a.createListButton("▶  Play Album");
        btnPlayAll.setTextColor(0xFF00FF88);
        btnPlayAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNavidromeAlbum(a, songs, 0);
            }
        });
        a.containerNavidromeItems.addView(btnPlayAll);

        // Download Album button — long-press deletes the album's downloads
        Button btnDlAll = a.createListButton("⬇  Download Album");
        btnDlAll.setTextColor(0xFF88CCFF);
        btnDlAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadNavidromeAlbum(a, songs);
            }
        });
        btnDlAll.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                int downloaded = 0;
                for (com.themoon.y1.subsonic.SubsonicSong s : songs) if (s.isDownloaded()) downloaded++;
                if (downloaded == 0) {
                    Toast.makeText(a, a.t("No downloads to delete"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                final int count = downloaded;
                a.showThemedOptionsDialog(a.t("Delete Downloads"),
                        count + " " + a.t("downloaded tracks"),
                        new String[]{ "🗑  " + a.t("Delete"), a.t("Cancel") },
                        new Runnable[]{
                                new Runnable() {
                                    @Override public void run() {
                                        for (com.themoon.y1.subsonic.SubsonicSong s : songs) deleteNavidromeDownload(a, s);
                                        refreshNavidromeSongLabels(a);
                                        Toast.makeText(a, "🗑 " + a.t("Deleted") + " " + count, Toast.LENGTH_SHORT).show();
                                    }
                                },
                                null
                        });
                return true;
            }
        });
        a.containerNavidromeItems.addView(btnDlAll);

        // Individual song rows — single focusable button per song
        // Click = play from this track, long-press = download this track
        for (int i = 0; i < songs.size(); i++) {
            final com.themoon.y1.subsonic.SubsonicSong song = songs.get(i);
            final int index = i;

            int mins = song.durationSecs / 60, secs = song.durationSecs % 60;
            android.view.View btn = createNavidromeSongRow(a, navidromeSongTitleLabel(a, song),
                    String.format(java.util.Locale.US, "%d:%02d", mins, secs));
            btn.setTag(song); // lets refreshNavidromeSongLabels(a) update the ✓ marker in place
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playNavidromeAlbum(a, songs, index); }
            });
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (song.isDownloaded()) {
                        showNavidromeDeleteDialog(a, song);
                    } else {
                        java.util.List<com.themoon.y1.subsonic.SubsonicSong> single =
                                new java.util.ArrayList<com.themoon.y1.subsonic.SubsonicSong>();
                        single.add(song);
                        showNavidromeDownloadQualityDialog(a, single);
                    }
                    return true;
                }
            });
            a.containerNavidromeItems.addView(btn);
        }
        focusFirstNavidromeItem(a);
    }

    public String navidromeSongTitleLabel(MainActivity a, com.themoon.y1.subsonic.SubsonicSong song) {
        String downloadedMark = song.isDownloaded() ? "✓ " : "";
        String trackNum = song.track > 0 ? String.format(java.util.Locale.US, "%02d. ", song.track) : "";
        return downloadedMark + trackNum + song.title;
    }

    public android.view.View createNavidromeSongRow(MainActivity a, String titleText, String durationText) {
        float d = a.getResources().getDisplayMetrics().density;
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(false);
        row.setBackground(a.createButtonBackground(ThemeManager.getListButtonNormalBg()));
        row.setPadding((int) (25 * d), (int) (12 * d), (int) (10 * d), (int) (12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 2, 0, 2);
        row.setLayoutParams(lp);

        final TextView tvTitle = new TextView(a);
        tvTitle.setText(titleText);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvTitle);

        final TextView tvDuration = new TextView(a);
        tvDuration.setText(durationText);
        tvDuration.setTextSize(15f);
        tvDuration.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
        tvDuration.setTextColor(ThemeManager.getTextColorSecondary());
        LinearLayout.LayoutParams durLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        durLp.leftMargin = (int) (8 * d);
        tvDuration.setLayoutParams(durLp);
        row.addView(tvDuration);

        row.setOnLongClickListener(a.globalScreenOffLongClickListener);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    row.setBackground(a.createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    tvTitle.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                    tvDuration.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                } else {
                    row.setBackground(a.createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
                    tvDuration.setTextColor(ThemeManager.getTextColorSecondary());
                }
            }
        });
        return row;
    }

    public void refreshNavidromeSongLabels(MainActivity a) {
        if (a.currentScreenState != a.STATE_NAVIDROME || a.navidromeBrowseDepth != a.NAV_SONGS) return;
        for (int i = 0; i < a.containerNavidromeItems.getChildCount(); i++) {
            View child = a.containerNavidromeItems.getChildAt(i);
            if (child instanceof LinearLayout && child.getTag() instanceof com.themoon.y1.subsonic.SubsonicSong) {
                TextView tvTitle = (TextView) ((LinearLayout) child).getChildAt(0);
                tvTitle.setText(navidromeSongTitleLabel(a, (com.themoon.y1.subsonic.SubsonicSong) child.getTag()));
            }
        }
    }

    public void playNavidromeAlbum(MainActivity a, java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) return;
        com.themoon.y1.managers.AudioPlayerManager am = com.themoon.y1.managers.AudioPlayerManager.getInstance();
        am.navidromePlaylist.clear();
        am.navidromePlaylist.addAll(songs);
        am.navidromeIndex = startIndex;

        com.themoon.y1.subsonic.SubsonicSong song = songs.get(startIndex);
        String url = com.themoon.y1.subsonic.SubsonicClient.getInstance().getStreamUrl(song.id);
        am.playNavidromeSong(a, song, url);
        a.changeScreen(a.STATE_PLAYER);
        a.progressHandler.removeCallbacks(a.updateProgressTask);
        a.progressHandler.post(a.updateProgressTask);
    }

    public void downloadNavidromeAlbum(MainActivity a, java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
        showNavidromeDownloadQualityDialog(a, songs);
    }

    public void showNavidromeDownloadQualityDialog(MainActivity a, final java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs) {
        if (songs == null || songs.isEmpty()) return;
        String what = songs.size() == 1 ? songs.get(0).title : songs.size() + " " + a.t("tracks");
        a.showThemedOptionsDialog(a.t("Download Quality"), what,
                new String[]{ "⬇  " + a.t("Original Quality"), "⬇  " + a.t("MP3 192kbps"), a.t("Cancel") },
                new Runnable[]{
                        new Runnable() { @Override public void run() { enqueueNavidromeDownloads(a, songs, false); } },
                        new Runnable() { @Override public void run() { enqueueNavidromeDownloads(a, songs, true); } },
                        null
                });
    }

    public void showNavidromeDeleteDialog(MainActivity a, final com.themoon.y1.subsonic.SubsonicSong song) {
        a.showThemedOptionsDialog(a.t("Delete Download"), song.title,
                new String[]{ "🗑  " + a.t("Delete"), a.t("Cancel") },
                new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                if (deleteNavidromeDownload(a, song)) {
                                    refreshNavidromeSongLabels(a);
                                    Toast.makeText(a, "🗑 " + a.t("Deleted") + ": " + song.title, Toast.LENGTH_SHORT).show();
                                }
                            }
                        },
                        null
                });
    }

    public boolean deleteNavidromeDownload(MainActivity a, com.themoon.y1.subsonic.SubsonicSong song) {
        boolean deleted = false;
        String[] paths = { song.getLocalPath(), song.getLocalPathMp3() };
        for (String p : paths) {
            java.io.File f = new java.io.File(p);
            if (!f.exists() || !f.delete()) continue;
            deleted = true;
            java.util.Iterator<SongItem> it = a.customLibrary.iterator();
            while (it.hasNext()) {
                if (it.next().file.getAbsolutePath().equals(p)) it.remove();
            }
            a.trackNumberMap.remove(p);
            if (a.favoritePaths.remove(p)) {
                try { a.libraryCacheDb.setFavorite(p, false); } catch (Exception ignored) { Log.d(TAG, "deleteNavidromeDownload failed", ignored); }
            }
            // delete() only succeeds on empty dirs, so this safely prunes
            // the album folder and then the artist folder when they empty out
            java.io.File albumDir = f.getParentFile();
            if (albumDir != null && albumDir.delete()) {
                java.io.File artistDir = albumDir.getParentFile();
                if (artistDir != null) artistDir.delete();
            }
        }
        return deleted;
    }

    public void enqueueNavidromeDownloads(MainActivity a, java.util.List<com.themoon.y1.subsonic.SubsonicSong> songs, boolean transcoded) {
        if (songs == null || songs.isEmpty()) return;
        java.util.List<NavidromeDownloadItem> toAdd = new java.util.ArrayList<NavidromeDownloadItem>();
        long neededBytes = 0;
        for (com.themoon.y1.subsonic.SubsonicSong song : songs) {
            String target = transcoded ? song.getLocalPathMp3() : song.getLocalPath();
            if (new java.io.File(target).exists() || isNavidromeDownloadQueued(a, song.id)) continue;
            toAdd.add(new NavidromeDownloadItem(song, transcoded));
            if (transcoded) {
                neededBytes += (long) song.durationSecs * 24000L; // 192kbps ≈ 24KB/s
            } else {
                neededBytes += song.sizeBytes > 0 ? song.sizeBytes
                        : (long) song.durationSecs * 130000L; // ~1Mbps FLAC fallback
            }
        }
        if (toAdd.isEmpty()) {
            Toast.makeText(a, "✅ Already downloaded", Toast.LENGTH_SHORT).show();
            return;
        }

        // Free-space check with a 50MB safety margin — a FLAC album on a full
        // card would otherwise fail confusingly mid-queue
        try {
            android.os.StatFs sf = new android.os.StatFs("/storage/sdcard0");
            long available = (long) sf.getAvailableBlocks() * sf.getBlockSize();
            if (neededBytes + 50L * 1024 * 1024 > available) {
                Toast.makeText(a, "❌ " + a.t("Not enough space") + ": ~" + (neededBytes / (1024 * 1024))
                        + " MB " + a.t("needed") + ", " + (available / (1024 * 1024)) + " MB " + a.t("free"),
                        Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception ignored) {
            Log.d(TAG, "enqueueNavidromeDownloads failed", ignored);
        }

        navidromeDownloadQueue.addAll(toAdd);
        navidromeQueueTotal += toAdd.size();
        Toast.makeText(a, "⬇ Queued " + toAdd.size() + (toAdd.size() == 1 ? " track" : " tracks"), Toast.LENGTH_SHORT).show();
        if (!isNavidromeDownloading) processNextNavidromeDownload(a);
    }

    public boolean isNavidromeDownloadQueued(MainActivity a, String songId) {
        if (songId.equals(currentNavidromeDownloadId)) return true;
        for (NavidromeDownloadItem item : navidromeDownloadQueue) {
            if (songId.equals(item.song.id)) return true;
        }
        return false;
    }

    public void acquireNavidromeDownloadLocks(MainActivity a) {
        try {
            if (navidromeDownloadWakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) a.getSystemService(Context.POWER_SERVICE);
                navidromeDownloadWakeLock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "y1:NavidromeDownload");
                navidromeDownloadWakeLock.setReferenceCounted(false);
            }
            if (!navidromeDownloadWakeLock.isHeld()) navidromeDownloadWakeLock.acquire();

            if (navidromeDownloadWifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                navidromeDownloadWifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "y1:NavidromeDownload");
                navidromeDownloadWifiLock.setReferenceCounted(false);
            }
            if (!navidromeDownloadWifiLock.isHeld()) navidromeDownloadWifiLock.acquire();
        } catch (Exception ignored) {
            Log.d(TAG, "acquireNavidromeDownloadLocks failed", ignored);
        }
    }

    public void releaseNavidromeDownloadLocks(MainActivity a) {
        try { if (navidromeDownloadWakeLock != null && navidromeDownloadWakeLock.isHeld()) navidromeDownloadWakeLock.release(); } catch (Exception ignored) { Log.d(TAG, "releaseNavidromeDownloadLocks failed", ignored); }
        try { if (navidromeDownloadWifiLock != null && navidromeDownloadWifiLock.isHeld()) navidromeDownloadWifiLock.release(); } catch (Exception ignored) { Log.d(TAG, "releaseNavidromeDownloadLocks failed", ignored); }
    }

    /** Hold CPU + WiFi awake for the duration of a streaming Navidrome track so the stream
     * doesn't stall when the screen turns off. Reference counting is off, so repeat calls
     * while already streaming are cheap no-ops. Pair with {@link #releaseNavidromeStreamLocks}. */
    public void acquireNavidromeStreamLocks(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (navidromeStreamWakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
                navidromeStreamWakeLock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "y1:NavidromeStream");
                navidromeStreamWakeLock.setReferenceCounted(false);
            }
            if (!navidromeStreamWakeLock.isHeld()) navidromeStreamWakeLock.acquire();

            if (navidromeStreamWifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        app.getSystemService(Context.WIFI_SERVICE);
                navidromeStreamWifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "y1:NavidromeStream");
                navidromeStreamWifiLock.setReferenceCounted(false);
            }
            if (!navidromeStreamWifiLock.isHeld()) navidromeStreamWifiLock.acquire();
        } catch (Exception ignored) {
            Log.d(TAG, "acquireNavidromeStreamLocks failed", ignored);
        }
    }

    public void releaseNavidromeStreamLocks() {
        try { if (navidromeStreamWakeLock != null && navidromeStreamWakeLock.isHeld()) navidromeStreamWakeLock.release(); } catch (Exception ignored) { Log.d(TAG, "releaseNavidromeStreamLocks failed", ignored); }
        try { if (navidromeStreamWifiLock != null && navidromeStreamWifiLock.isHeld()) navidromeStreamWifiLock.release(); } catch (Exception ignored) { Log.d(TAG, "releaseNavidromeStreamLocks failed", ignored); }
    }

    public void processNextNavidromeDownload(MainActivity a) {
        final NavidromeDownloadItem item = navidromeDownloadQueue.poll();
        final com.themoon.y1.subsonic.SubsonicSong song = item != null ? item.song : null;
        if (song == null) {
            isNavidromeDownloading = false;
            currentNavidromeDownloadId = null;
            releaseNavidromeDownloadLocks(a);
            if (navidromeQueueTotal > 0) {
                Toast.makeText(a, "✅ Downloads finished (" + navidromeQueueDone + "/" + navidromeQueueTotal + ")",
                        Toast.LENGTH_SHORT).show();
            }
            navidromeQueueTotal = 0;
            navidromeQueueDone = 0;
            updateNavidromeDownloadStatus(a, null);
            refreshNavidromeSongLabels(a);
            return;
        }
        isNavidromeDownloading = true;
        currentNavidromeDownloadId = song.id;
        acquireNavidromeDownloadLocks(a);
        try {
            startNavidromeDownload(a, item, song);
        } catch (Exception e) {
            // downloadSong() threw before registering any async callback — release the
            // locks now instead of leaving them held with nothing left to release them.
            releaseNavidromeDownloadLocks(a);
            navidromeQueueDone++;
            logNavidromeDownloadError(a, song, "Failed to start download: " + e.getMessage());
            processNextNavidromeDownload(a);
        }
    }

    public void startNavidromeDownload(MainActivity a, final NavidromeDownloadItem item, final com.themoon.y1.subsonic.SubsonicSong song) {
        updateNavidromeDownloadStatus(a, "⬇ " + (navidromeQueueDone + 1) + "/" + navidromeQueueTotal + "  0%");

        String savePath = item.transcoded ? song.getLocalPathMp3() : song.getLocalPath();
        com.themoon.y1.subsonic.SubsonicClient.getInstance().downloadSong(song.id, savePath, item.transcoded,
                new com.themoon.y1.subsonic.SubsonicClient.DownloadCallback() {
                    @Override
                    public void onProgress(int percent, long bytesSoFar) {
                        String p = percent >= 0 ? percent + "%"
                                : String.format(java.util.Locale.US, "%.1f MB", bytesSoFar / 1048576f);
                        updateNavidromeDownloadStatus(a, "⬇ " + (navidromeQueueDone + 1) + "/" + navidromeQueueTotal
                                + "  " + p);
                    }
                    @Override
                    public void onComplete(String path) {
                        navidromeQueueDone++;
                        // Register in the launcher's own library right away (its scan is
                        // manual/boot-time only) and in the system MediaStore
                        registerDownloadedSongInLibrary(a, song, path);
                        // Transcoded MP3s lose their embedded art (ffmpeg keeps audio
                        // only), so stash the server's cover for Cover Flow / player
                        cacheNavidromeCoverForDownloadedTrack(a, song, path);
                        android.media.MediaScannerConnection.scanFile(
                                a.getApplicationContext(), new String[]{path}, null, null);
                        refreshNavidromeSongLabels(a);
                        processNextNavidromeDownload(a);
                    }
                    @Override
                    public void onError(String message) {
                        if (item.retryCount < MAX_NAVIDROME_DOWNLOAD_RETRIES) {
                            item.retryCount++;
                            updateNavidromeDownloadStatus(a, "⚠ Retry " + item.retryCount + "/" + MAX_NAVIDROME_DOWNLOAD_RETRIES
                                    + ": " + song.title);
                            navidromeDownloadQueue.addFirst(item); // retry this track next, ahead of the rest of the queue
                            new Handler().postDelayed(new Runnable() {
                                @Override public void run() { processNextNavidromeDownload(a); }
                            }, 1500);
                            return;
                        }
                        navidromeQueueDone++;
                        logNavidromeDownloadError(a, song, message + " (gave up after " + (item.retryCount + 1) + " attempts)");
                        Toast.makeText(a, "❌ " + song.title + ": " + message, Toast.LENGTH_SHORT).show();
                        processNextNavidromeDownload(a);
                    }
                });
    }

    public void logNavidromeDownloadError(MainActivity a, com.themoon.y1.subsonic.SubsonicSong song, String message) {
        try {
            File logDir = new File("/storage/sdcard0/Y1_Logs");
            if (!logDir.exists()) logDir.mkdirs();
            File logFile = new File(logDir, "navidrome_download_errors.log");
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new Date())
                    + "  " + song.title + " (id=" + song.id + ")  ->  " + message + "\n";
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            fw.write(line);
            fw.close();
        } catch (Exception ignored) {
            Log.d(TAG, "logNavidromeDownloadError failed", ignored);
        }
    }

    public void registerDownloadedSongInLibrary(MainActivity a, com.themoon.y1.subsonic.SubsonicSong song, String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return;
            for (SongItem existing : a.customLibrary) {
                if (existing.file.getAbsolutePath().equals(path)) return;
            }
            String title = song.title != null && !song.title.isEmpty() ? song.title : f.getName();
            // Album artist first — same grouping rule as the tag scan
            String artist = song.albumArtist != null && !song.albumArtist.isEmpty() ? song.albumArtist
                    : (song.artist != null && !song.artist.isEmpty() ? song.artist : a.t("Unknown Artist"));
            String album = song.album != null && !song.album.isEmpty() ? song.album : a.t("Unknown Album");
            String year = song.year > 0 ? String.valueOf(song.year) : a.t("Unknown Year");
            String genre = song.genre != null && !song.genre.isEmpty() ? song.genre : a.t("Unknown Genre");
            a.customLibrary.add(new SongItem(f, title, artist, album, year, genre));
            a.trackNumberMap.put(path, song.track);
            if (a.libraryCacheDb != null) {
                a.libraryCacheDb.upsert(new com.themoon.y1.db.LibraryCacheDb.CachedSong(
                        path, f.lastModified(), f.length(), title, artist, album, year, genre, song.track, false));
            }
        } catch (Exception ignored) {
            Log.d(TAG, "registerDownloadedSongInLibrary failed", ignored);
        }
    }

    public void cacheNavidromeCoverForDownloadedTrack(MainActivity a, final com.themoon.y1.subsonic.SubsonicSong song,
                                                       final String trackPath) {
        if (song.coverArtId == null || song.coverArtId.isEmpty()) return;
        java.io.File cacheFile = new java.io.File("/storage/sdcard0/Y1_Covers/Navidrome",
                song.coverArtId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
        com.themoon.y1.subsonic.SubsonicClient.getInstance().fetchCoverArt(song.coverArtId, 320, cacheFile,
                new com.themoon.y1.subsonic.SubsonicClient.Callback<String>() {
                    @Override
                    public void onSuccess(String coverPath) {
                        try {
                            String base = new java.io.File(trackPath).getName();
                            int dot = base.lastIndexOf('.');
                            if (dot > 0) base = base.substring(0, dot);
                            java.io.File dest = new java.io.File("/storage/sdcard0/Y1_Covers", base + ".jpg");
                            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
                            if (!dest.exists()) {
                                java.io.FileInputStream in = new java.io.FileInputStream(coverPath);
                                java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
                                byte[] buf = new byte[8192];
                                int r;
                                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                                out.close();
                                in.close();
                            }
                            a.libraryCacheDb.setAlbumArtPath(trackPath, dest.getAbsolutePath());
                        } catch (Exception ignored) {
                            Log.d(TAG, "cacheNavidromeCoverForDownloadedTrack failed", ignored);
                        }
                    }
                    @Override
                    public void onError(String message) {}
                });
    }

    public void updateNavidromeDownloadStatus(MainActivity a, String status) {
        if (status == null) {
            a.tvNavidromeStatus.setText("●");
        } else {
            a.tvNavidromeStatus.setTextColor(0xFF88CCFF);
            a.tvNavidromeStatus.setText(status);
        }
    }

    public void loadNavidromeCoverArt(MainActivity a, final com.themoon.y1.subsonic.SubsonicSong song) {
        if (song == null || song.coverArtId == null || song.coverArtId.isEmpty()) return;
        currentNavidromeCoverArtId = song.coverArtId;
        java.io.File cacheFile = new java.io.File("/storage/sdcard0/Y1_Covers/Navidrome",
                song.coverArtId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
        com.themoon.y1.subsonic.SubsonicClient.getInstance().fetchCoverArt(song.coverArtId, 320, cacheFile,
                new com.themoon.y1.subsonic.SubsonicClient.Callback<String>() {
                    @Override
                    public void onSuccess(String path) {
                        // Skip if the user already moved on to a track with different art
                        if (song.coverArtId.equals(currentNavidromeCoverArtId)) a.applyCachedCoverArt(path);
                    }
                    @Override
                    public void onError(String message) {}
                });
    }

    public void focusFirstNavidromeItem(MainActivity a) {
        if (a.containerNavidromeItems.getChildCount() > 0) {
            a.containerNavidromeItems.getChildAt(0).requestFocus();
        }
    }

}
