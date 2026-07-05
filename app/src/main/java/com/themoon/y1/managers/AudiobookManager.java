package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AudiobookManager {
    private static AudiobookManager instance;
    private SharedPreferences bookPrefs;
    private File audiobookRoot = new File("/storage/sdcard0/Audiobooks");

    private List<File> currentBookChapters = new ArrayList<>();
    private int currentChapterIndex = -1;

    private AudiobookManager(Context context) {
        // Dedicated storage for audiobooks only (completely separate from the music settings file)
        this.bookPrefs = context.getSharedPreferences("Y1_AUDIOBOOK_BOOKMARKS", Context.MODE_PRIVATE);
        if (!audiobookRoot.exists()) {
            audiobookRoot.mkdirs();
        }
    }

    public static synchronized AudiobookManager getInstance(Context context) {
        if (instance == null) {
            instance = new AudiobookManager(context.getApplicationContext());
        }
        return instance;
    }

    // 🚀 [Save bookmark] Records the last listened position (ms) per book file path
    public void saveBookmark(String filePath, int positionMs, int chapterIdx) {
        if (filePath == null || filePath.isEmpty()) return;
        bookPrefs.edit()
                .putInt("POS_" + filePath, positionMs)
                .putInt("CHAP_" + filePath, chapterIdx)
                .apply(); // Saved asynchronously in the background to avoid blocking the UI
    }

    // 🚀 [Load bookmark] Returns the saved resume position
    public int getSavedPosition(String filePath) {
        return bookPrefs.getInt("POS_" + filePath, 0);
    }

    // Loads the audiobook-specific playlist and hooks up resume playback
    public void setupBookPlaylist(Context context, File clickedFile, File parentFolder) {
        currentBookChapters.clear();

        File[] files = parentFolder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (isAudioFile(f)) currentBookChapters.add(f);
            }
            java.util.Collections.sort(currentBookChapters, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
        }

        this.currentChapterIndex = currentBookChapters.indexOf(clickedFile);
        if (this.currentChapterIndex == -1) this.currentChapterIndex = 0;

        // Fetches the saved resume position (ms).
        int savedOffset = getSavedPosition(clickedFile.getAbsolutePath());

        // 🚀 Reuses the existing music engine, but calls the audiobook-specific offset playback method.
        AudioPlayerManager.getInstance().playTrackListWithOffset(currentBookChapters, currentChapterIndex, savedOffset);
    }

    private boolean isAudioFile(File f) {
        if (f == null || !f.isFile()) return false;
        String name = f.getName().toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".flac") || name.endsWith(".wav");
    }


}