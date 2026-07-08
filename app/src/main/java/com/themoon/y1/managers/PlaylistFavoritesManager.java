package com.themoon.y1.managers;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.adapters.SongListAdapter;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * M3U playlist file management (list/browse/create/delete playlists, add/remove tracks) plus the
 * Favorites-list removal dialog. Extracted verbatim from MainActivity per
 * GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- shares MainActivity's browser-screen views and library state like
 * every other UI-construction manager -- so it takes the MainActivity instance as a parameter.
 * MainActivity keeps thin pass-through methods for buildM3uPlaylistUI(), buildM3uSongsUI(),
 * showAddToPlaylistDialog(), showRemoveFromPlaylistDialog(), and showRemoveFromFavoritesDialog()
 * since MusicBrowserManager, KeyEventRouter, changeScreen(), and SongListAdapter all call them by
 * name.
 */
public class PlaylistFavoritesManager {
    private static final String TAG = "PlaylistFavoritesManager";
    private static PlaylistFavoritesManager instance;

    private PlaylistFavoritesManager() {}

    public static synchronized PlaylistFavoritesManager getInstance() {
        if (instance == null) {
            instance = new PlaylistFavoritesManager();
        }
        return instance;
    }

    public void buildM3uPlaylistUI(final MainActivity a) {
        if (a.scrollViewBrowser != null) a.scrollViewBrowser.setVisibility(View.VISIBLE);
        if (a.listVirtualSongs != null) a.listVirtualSongs.setVisibility(View.GONE);
        a.containerBrowserItems.removeAllViews();
        a.tvBrowserPath.setText(a.t("Library") + ": " + a.t("Playlists"));

        // Set up a dedicated playlist storage bin
        File playlistDir = new File("/storage/sdcard0/Y1_Playlists");
        if (!playlistDir.exists()) playlistDir.mkdirs();

        File[] files = playlistDir.listFiles();
        List<File> m3uFiles = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                // 🚀 [Fix] Collect all files whose names end in either (OR) .m3u or .m3u8!
                String name = f.getName().toLowerCase();
                if (f.isFile() && (name.endsWith(".m3u") || name.endsWith(".m3u8"))) {
                    m3uFiles.add(f);
                }
            }
        }

        // Sort cleanly, case-insensitive alphabetical order
        java.util.Collections.sort(m3uFiles, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));

        if (m3uFiles.isEmpty()) {
            android.view.View btnEmpty = a.createListButtonWithIcon("", a.t("No .m3u files found in Y1_Playlists"), ThemeManager.getTextColorSecondary());
            a.containerBrowserItems.addView(btnEmpty);
        } else {
            for (final File m3u : m3uFiles) {
                // Strip the extension and list only the pure playlist name
                String cleanName = m3u.getName().substring(0, m3u.getName().lastIndexOf("."));
                android.view.View b = a.createListButtonWithIcon("", cleanName);

                // 1. Existing behavior: short press enters the playlist
                b.setOnClickListener(v -> {
                    a.clickFeedback();
                    a.currentBrowserMode = MainActivity.BROWSER_M3U_SONGS;
                    a.currentM3uFile = m3u;
                    buildM3uSongsUI(a, m3u);
                });

                // 🚀 2. New behavior: long press physically deletes the playlist file itself!
                b.setLongClickable(true); // allow long press
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        a.clickFeedback();
                        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(a.t("Delete Playlist"))
                                .setMessage(a.t("Are you sure you want to completely delete this playlist file?") + "\n\n[ " + m3u.getName() + " ]")
                                .setPositiveButton(a.t("Delete"), new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        // 🚀 Actually delete the .m3u / .m3u8 file
                                        if (m3u.exists() && m3u.delete()) {
                                            Toast.makeText(a, a.t("Playlist deleted."), Toast.LENGTH_SHORT).show();
                                            buildM3uPlaylistUI(a); // 🚀 Refresh the screen right away so it disappears from the list!
                                        } else {
                                            Toast.makeText(a, a.t("Failed to delete."), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                })
                                .setNegativeButton(a.t("Cancel"), null)
                                .show();

                        return true; // 🚀 Must return true so the short-click (enter) event doesn't also fire redundantly.
                    }
                });

                a.containerBrowserItems.addView(b);
            }
        }
        if (a.containerBrowserItems.getChildCount() > 0) a.containerBrowserItems.getChildAt(0).requestFocus();
    }

    // 🚀 [Native engine 2] M3U live text-path parser (the core detail work)
    private List<SongItem> parseM3uFile(MainActivity a, File m3uFile) {
        List<SongItem> songs = new ArrayList<>();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(m3uFile), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // Smoothly skip blank lines or comment lines (like #EXTINF)
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Auto-fix Windows-style backslashes (\) by swapping them to Linux/Android-style forward slashes (/)!
                line = line.replace("\\", "/");

                File audioFile = new File(line);
                // If it looks like a PC-relative-path file, force-map it based on the default Music folder!
                if (!audioFile.isAbsolute()) {
                    audioFile = new File(a.rootFolder, line);
                }

                // Physically verify the final check that a real track actually lives at that path
                if (audioFile.exists() && a.isAudioFile(audioFile)) {
                    String title = audioFile.getName();
                    // Strip the extension
                    int dotIdx = title.lastIndexOf(".");
                    if (dotIdx > 0) title = title.substring(0, dotIdx);

                    // For native playback speed, skip the heavy tag lookup and just assemble the title at lightning speed!
                    songs.add(new SongItem(audioFile, title, "M3U Playlist", ""));
                }
            }
            br.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return songs;
    }

    // 🚀 [Native engine 3] Feed the extracted tracks directly into the ultra-fast recycler engine (ListView)
    public void buildM3uSongsUI(final MainActivity a, File m3uFile) {
        a.scrollViewBrowser.setVisibility(View.GONE);
        a.listVirtualSongs.setVisibility(View.VISIBLE);
        a.tvBrowserPath.setText(a.t("Playlist") + ": " + m3uFile.getName().substring(0, m3uFile.getName().lastIndexOf(".")));

        a.virtualSongList.clear();
        a.currentScrollIndexList.clear();

        List<SongItem> songs = parseM3uFile(a, m3uFile);

        // Preserved exactly "as-is" in the order the user manually arranged them in the .m3u file, without sorting!
        for (SongItem song : songs) {
            a.virtualSongList.add(song.file);
            a.currentScrollIndexList.add(song.title);
        }

        if (songs.isEmpty()) {
            Toast.makeText(a, "No valid tracks found in this playlist.", Toast.LENGTH_SHORT).show();
        }

        SongListAdapter adapter = new SongListAdapter(songs);
        a.listVirtualSongs.setAdapter(adapter);
        a.listVirtualSongs.post(() -> {
            if (a.listVirtualSongs.getChildCount() > 0) a.listVirtualSongs.getChildAt(0).requestFocus();
        });
    }

    // 🚀 [Design overhaul and wheel bug fully fixed]
    public void showAddToPlaylistDialog(final MainActivity a, final File songFile) {
        final File playlistDir = new File("/storage/sdcard0/Y1_Playlists");
        if (!playlistDir.exists()) playlistDir.mkdirs();

        File[] files = playlistDir.listFiles();
        final List<File> playlistFiles = new ArrayList<>();

        if (files != null) {
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (f.isFile() && (name.endsWith(".m3u") || name.endsWith(".m3u8"))) {
                    playlistFiles.add(f);
                }
            }
        }

        android.widget.ScrollView scrollView = new android.widget.ScrollView(a);
        scrollView.setBackgroundColor(0xFF222222);

        final android.widget.LinearLayout layout = new android.widget.LinearLayout(a);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);
        scrollView.addView(layout);

        // 🚀 1. Instead of the dated system title, we draw our own 'custom title' identical to the main screen!
        TextView tvTitle = new TextView(a);
        tvTitle.setText("━ ADD TO PLAYLIST ━");
        tvTitle.setTextColor(0xFFFFFFFF); // Pretty sky-blue!
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setPadding(0, 10, 0, 30);
        tvTitle.setTextSize(16);
        layout.addView(tvTitle);

        // 🚀 2. A 'popup-dedicated steering wheel (Listener)' that also recognizes the wheel (21, 22) inside the popup window
        android.view.View.OnKeyListener dialogWheelListener = new android.view.View.OnKeyListener() {
            @Override
            public boolean onKey(android.view.View v, int keyCode, android.view.KeyEvent event) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (keyCode == 21) { // wheel up (UP)
                        int idx = layout.indexOfChild(v);
                        for (int i = idx - 1; i >= 0; i--) {
                            if (layout.getChildAt(i).isFocusable()) {
                                layout.getChildAt(i).requestFocus();
                                a.clickFeedback();
                                return true;
                            }
                        }
                        return true; // Stop if the top is blocked
                    }
                    if (keyCode == 22) { // wheel down (DOWN)
                        int idx = layout.indexOfChild(v);
                        for (int i = idx + 1; i < layout.getChildCount(); i++) {
                            if (layout.getChildAt(i).isFocusable()) {
                                layout.getChildAt(i).requestFocus();
                                a.clickFeedback();
                                return true;
                            }
                        }
                        return true; // Stop if the bottom is blocked
                    }
                }
                return false;
            }
        };

        // 🚀 3. When building the system popup, remove the stock title (.setTitle) to keep it hidden!
        final AlertDialog dialog = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(scrollView)
                .create();
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 4. [First button] Create a new playlist
        Button btnNew = a.createListButton("➕ Create New Playlist");
        btnNew.setTextColor(0xFF00FFFF);
        btnNew.setOnKeyListener(dialogWheelListener); // 🚀 Wire the popup-dedicated steering wheel to the button!
        btnNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.clickFeedback();
                int count = 1;
                File newPlaylistFile;
                do {
                    newPlaylistFile = new File(playlistDir, "Playlist " + count + ".m3u8");
                    count++;
                } while (newPlaylistFile.exists());

                writeSongToM3uFile(newPlaylistFile, songFile, false);
                Toast.makeText(MainActivity.instance, a.t("Created Playlist ") + (count-1) +" "+ a.t("successfully!"), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
        layout.addView(btnNew);

        // 5. [Remaining buttons] List of existing playlist files
        for (final File targetM3u : playlistFiles) {
            String cleanName = targetM3u.getName().substring(0, targetM3u.getName().lastIndexOf("."));
            Button btnExisting = a.createListButton("📝 " + cleanName);
            btnExisting.setOnKeyListener(dialogWheelListener); // 🚀 Wire the popup-dedicated steering wheel to the button!
            btnExisting.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    writeSongToM3uFile(targetM3u, songFile, true);
                    Toast.makeText(MainActivity.instance, a.t("Added to playlist successfully!"), Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            });
            layout.addView(btnExisting);
        }

        dialog.show();

        // 6. Automatically focus the 'first button' when the popup opens
        layout.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Index 0 of the layout is the 'custom title text', so give focus to index 1 (btnNew) instead!
                if (layout.getChildCount() > 1) layout.getChildAt(1).requestFocus();
            }
        }, 50);
    }

    // 🚀 [Custom playlist engine, step 4] Live physical hard-disk recording stream
    private void writeSongToM3uFile(File m3uFile, File songFile, boolean append) {
        try {
            java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(m3uFile, append), "UTF-8"));

            // For a new file, write out the standard playlist header spec.
            if (!append) {
                bw.write("#EXTM3U\n");
            }

            // Safely mark the song's absolute path, then add a line break.
            bw.write(songFile.getAbsolutePath() + "\n");
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 🚀 [Custom playlist engine, step 5] Popup for deleting a track inside a playlist
    public void showRemoveFromPlaylistDialog(final MainActivity a, final File songFile) {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.t("Remove Song"))
                .setMessage(a.t("Do you want to remove") + "\n'" + songFile.getName() + "'\n" + a.t("from this playlist?"))
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        a.clickFeedback();
                        removeSongFromM3uFile(a, a.currentM3uFile, songFile);
                        buildM3uSongsUI(a, a.currentM3uFile); // 🚀 Redraw the list screen immediately to remove it!
                    }
                })
                .setNegativeButton(a.t("Cancel"), null)
                .show();
    }

    // 🚀 [Custom playlist engine, step 6] Feature that finds and removes only the matching song's text line in the M3U file
    private void removeSongFromM3uFile(MainActivity a, File m3uFile, File songFile) {
        if (m3uFile == null || !m3uFile.exists()) return;
        try {
            List<String> lines = new ArrayList<>();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(m3uFile), "UTF-8"));
            String line;
            boolean isRemoved = false; // 💡 Guard so that if the same song appears multiple times, only one is removed at a time

            while ((line = br.readLine()) != null) {
                String cleanLine = line.replace("\\", "/").trim();

                // Pass comments and blank lines straight through, unremoved, to preserve the M3U format's integrity.
                if (cleanLine.isEmpty() || cleanLine.startsWith("#")) {
                    lines.add(line);
                    continue;
                }

                // If the filename matches the song to delete and hasn't already been removed this pass (skipped = not added to the list = deleted)
                if (!isRemoved && cleanLine.endsWith(songFile.getName())) {
                    isRemoved = true;
                    continue;
                }

                lines.add(line); // Keep any song that isn't the deletion target
            }
            br.close();

            // Overwrite (Append = false) the original M3U file with the updated (one-song-shorter) list.
            java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(m3uFile, false), "UTF-8"));
            for (String l : lines) {
                bw.write(l + "\n");
            }
            bw.close();

            Toast.makeText(a, a.t("Removed successfully."), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 🚀 [Bonus] A dedicated popup that also lets you long-press inside Favorites to remove it right away!
    public void showRemoveFromFavoritesDialog(final MainActivity a, final File songFile) {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.t("Remove from Favorites"))
                .setMessage(a.t("Remove this song from your favorites list?"))
                .setPositiveButton(a.t("Remove"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        a.clickFeedback();
                        if (a.favoritePaths.contains(songFile.getAbsolutePath())) {
                            a.favoritePaths.remove(songFile.getAbsolutePath());
                            try { a.libraryCacheDb.setFavorite(songFile.getAbsolutePath(), false); } catch (Exception e) { Log.d(TAG, "showRemoveFromFavoritesDialog failed", e); }
                            a.buildVirtualSongsForFavorites(); // Instantly refresh the screen
                            Toast.makeText(MainActivity.instance, a.t("Removed from Favorites."), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(a.t("Cancel"), null)
                .show();
    }
}
