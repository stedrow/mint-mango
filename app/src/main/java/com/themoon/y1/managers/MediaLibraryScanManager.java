package com.themoon.y1.managers;

import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans the Music/Audiobooks folders into customLibrary/audiobookLibrary, backed by
 * LibraryCacheDb so unchanged files skip MediaMetadataRetriever on repeat scans, plus the
 * blocking "Scanning..." loading popup shown while that runs. Extracted verbatim from
 * MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * Like the other UI/engine managers, this subsystem has no clean field boundary -- it reads and
 * writes MainActivity's library lists, scan-progress fields, and loading-overlay views directly
 * -- so it takes the MainActivity instance as a parameter rather than owning any of this state
 * itself. MainActivity keeps thin pass-through methods for startMediaLibraryScan(),
 * showLoadingPopup(), and loadLibraryFromCacheInstant() since other already-extracted managers
 * (MusicBrowserManager, MainMenuManager, FmRadioUiManager, SettingsUiManager) call them by name.
 */
public class MediaLibraryScanManager {
    private static final String TAG = "MediaLibraryScanManager";
    private static MediaLibraryScanManager instance;

    private MediaLibraryScanManager() {}

    public static synchronized MediaLibraryScanManager getInstance() {
        if (instance == null) {
            instance = new MediaLibraryScanManager();
        }
        return instance;
    }

    private void countAudioFiles(MainActivity a, File folder) {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) countAudioFiles(a, f);
                else if (a.isAudioFile(f)) a.totalAudioFiles++;
            }
        }
    }

    /** One audio file discovered during the (cheap, single-threaded) directory walk, waiting on
     *  its tag extraction — either already resolved from cache, or running on scanExecutor. */
    private static final class ScanTask {
        final File file;
        final boolean isAudiobook;
        final com.themoon.y1.db.LibraryCacheDb.CachedSong cachedResult; // non-null = cache hit, no future
        final java.util.concurrent.Future<com.themoon.y1.db.LibraryCacheDb.CachedSong> future; // non-null = cache miss

        ScanTask(File file, boolean isAudiobook, com.themoon.y1.db.LibraryCacheDb.CachedSong cachedResult,
                 java.util.concurrent.Future<com.themoon.y1.db.LibraryCacheDb.CachedSong> future) {
            this.file = file;
            this.isAudiobook = isAudiobook;
            this.cachedResult = cachedResult;
            this.future = future;
        }
    }

    /** Reads tags for one file with MediaMetadataRetriever. Runs on scanExecutor for
     *  cache misses so multiple files' (slow, I/O-bound) tag reads overlap instead of running
     *  one after another. */
    private com.themoon.y1.db.LibraryCacheDb.CachedSong extractTags(MainActivity a, File f, long mtime, long size, boolean isAudiobook) {
        String path = f.getAbsolutePath();
        String title = f.getName();
        String artist = a.t("Unknown Artist");
        String album = a.t("Unknown Album");
        String year = a.t("Unknown Year");
        String genre = a.t("Unknown Genre");
        int trackNum = 0;

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            mmr.setDataSource(fis.getFD());

            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String ar = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            // Prefer ALBUMARTIST for grouping — track-artist tags like
            // "Coldplay • Avicii" scatter one album across the Artists list
            String aa = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String al = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String trackStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
            String y = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE); // 💡 KEY_DATE holds the year.
            String g = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);

            if (t != null && !t.trim().isEmpty()) title = t;
            if (aa != null && !aa.trim().isEmpty()) artist = aa;
            else if (ar != null && !ar.trim().isEmpty()) artist = ar;
            if (al != null && !al.trim().isEmpty()) album = al;
            if (y != null && !y.trim().isEmpty()) year = y;
            if (g != null && !g.trim().isEmpty()) genre = g;

            if (trackStr != null && !trackStr.isEmpty()) {
                try {
                    if (trackStr.contains("/")) trackNum = Integer.parseInt(trackStr.split("/")[0].trim());
                    else trackNum = Integer.parseInt(trackStr.trim());
                } catch (Exception e) {
                    Log.d(TAG, "extractTags failed", e);
                }
            }
        } catch (Exception e) {
            // 💡 Even if there's no tag or the scanner fails, the app doesn't crash and safely exits into this block.
        } finally {
            if (fis != null) try { fis.close(); } catch (Exception e) { Log.d(TAG, "extractTags failed", e); }
            try { mmr.release(); } catch (Exception e) { Log.d(TAG, "extractTags failed", e); }
        }
        return new com.themoon.y1.db.LibraryCacheDb.CachedSong(
                path, mtime, size, title, artist, album, year, genre, trackNum, isAudiobook);
    }

    // 2. Walk the folder tree and queue each audio file's tag lookup. cachedSongs holds tag
    // results from the last scan (keyed by absolute path) so files whose mtime/size haven't
    // changed skip MediaMetadataRetriever entirely — those resolve immediately with no thread
    // hand-off. Cache misses run on scanExecutor so several (slow, I/O-bound) tag reads overlap.
    private void buildCustomLibrary(MainActivity a, File folder, List<ScanTask> pendingTasks,
                                     Map<String, com.themoon.y1.db.LibraryCacheDb.CachedSong> cachedSongs,
                                     boolean isAudiobook,
                                     java.util.concurrent.ExecutorService scanExecutor) {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) {
            for (final File f : files) {
                if (f.isDirectory()) {
                    buildCustomLibrary(a, f, pendingTasks, cachedSongs, isAudiobook, scanExecutor);
                } else if (a.isAudioFile(f)) {
                    if (a.blacklist.contains(f.getAbsolutePath())) continue;

                    final long mtime = f.lastModified();
                    final long size = f.length();
                    com.themoon.y1.db.LibraryCacheDb.CachedSong cached = cachedSongs.get(f.getAbsolutePath());

                    if (cached != null && cached.mtime == mtime && cached.size == size) {
                        // Quick scan: file is unchanged, reuse the cached tags — no thread needed
                        pendingTasks.add(new ScanTask(f, isAudiobook, cached, null));
                    } else {
                        java.util.concurrent.Future<com.themoon.y1.db.LibraryCacheDb.CachedSong> future =
                                scanExecutor.submit(new java.util.concurrent.Callable<com.themoon.y1.db.LibraryCacheDb.CachedSong>() {
                                    @Override
                                    public com.themoon.y1.db.LibraryCacheDb.CachedSong call() {
                                        return extractTags(a, f, mtime, size, isAudiobook);
                                    }
                                });
                        pendingTasks.add(new ScanTask(f, isAudiobook, null, future));
                    }
                }
            }
        }
    }

    /** Resolves every queued ScanTask (blocking on any still-running extraction, though
     *  most finish while later folders are still being walked) and files the result into the
     *  right library list, updating scan progress as it goes. */
    private void finalizeScanTasks(final MainActivity a, List<ScanTask> pendingTasks, List<SongItem> newCustomLibrary,
                                    List<SongItem> newAudiobookLibrary,
                                    java.util.HashMap<String, Integer> newTrackNumberMap,
                                    List<com.themoon.y1.db.LibraryCacheDb.CachedSong> freshEntries) {
        for (ScanTask task : pendingTasks) {
            com.themoon.y1.db.LibraryCacheDb.CachedSong result = task.cachedResult;
            if (result == null) {
                try {
                    result = task.future.get();
                } catch (Exception e) {
                    continue; // extraction thread crashed — skip rather than corrupt the library
                }
            }

            SongItem item = new SongItem(task.file, result.title, result.artist, result.album, result.year, result.genre);
            if (task.isAudiobook) newAudiobookLibrary.add(item);
            else newCustomLibrary.add(item);
            newTrackNumberMap.put(result.path, result.trackNumber);
            freshEntries.add(result);

            a.scannedAudioFiles++;
            if (a.totalAudioFiles > 0) {
                final int progress = (int) (((float) a.scannedAudioFiles / a.totalAudioFiles) * 100);
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.pbLoadingProgress != null) a.pbLoadingProgress.setProgress(progress);
                        if (a.tvLoadingProgress != null) {
                            a.tvLoadingProgress.setText("Scanning Media: " + progress + "%\n(" + a.scannedAudioFiles + " / " + a.totalAudioFiles + ")\nDo not turn off the screen.");
                        }
                    }
                });
            }
        }
    }
    // 3. Central scan engine (scans the two folders in order)
    /**
     * Populates customLibrary/audiobookLibrary/trackNumberMap directly from the SQLite
     * cache with no disk I/O — lets the UI (Cover Flow, Music browser) show last-known
     * data instantly on cold start instead of sitting empty until a full scan finishes.
     * A background scan should still run afterward to reconcile with the real filesystem.
     */
    public void loadLibraryFromCacheInstant(MainActivity a) {
        Map<String, com.themoon.y1.db.LibraryCacheDb.CachedSong> cachedSongs = a.libraryCacheDb.loadAll();
        if (cachedSongs.isEmpty()) return;

        MainActivity.customLibrary.clear();
        MainActivity.audiobookLibrary.clear();
        a.trackNumberMap.clear();
        for (com.themoon.y1.db.LibraryCacheDb.CachedSong cached : cachedSongs.values()) {
            SongItem item = new SongItem(new File(cached.path), cached.title, cached.artist,
                    cached.album, cached.year, cached.genre);
            if (cached.isAudiobook) MainActivity.audiobookLibrary.add(item);
            else MainActivity.customLibrary.add(item);
            a.trackNumberMap.put(cached.path, cached.trackNumber);
        }
    }

    public void startMediaLibraryScan(MainActivity a) { startMediaLibraryScan(a, false); }

    /** @param silent Skip the blocking "Scanning..." popup — used when the cache already
     *  populated the library instantly and this run is just reconciling with disk in the background. */
    public void startMediaLibraryScan(final MainActivity a, final boolean silent) {
        if (a.isCustomScanning) return;
        a.isCustomScanning = true;

        if (!silent) {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (a.pbLoadingProgress != null) a.pbLoadingProgress.setProgress(0);
                    if (a.tvLoadingProgress != null) a.tvLoadingProgress.setText(a.t("Counting files...\nPlease wait."));
                    showLoadingPopup(a);
                }
            });
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SongItem> newCustomLibrary = new ArrayList<>();
                List<SongItem> newAudiobookLibrary = new ArrayList<>();
                java.util.HashMap<String, Integer> newTrackNumberMap = new java.util.HashMap<>();
                a.totalAudioFiles = 0;
                a.scannedAudioFiles = 0;

                // 🚀 Count files in both folders
                countAudioFiles(a, a.rootFolder);
                countAudioFiles(a, a.audiobookRootFolder);

                // Quick scan: load the previous scan's results so unchanged files skip tag re-reading
                Map<String, com.themoon.y1.db.LibraryCacheDb.CachedSong> cachedSongs = a.libraryCacheDb.loadAll();
                List<com.themoon.y1.db.LibraryCacheDb.CachedSong> freshEntries = new ArrayList<>();

                // 🚀 Walk both folders (fast) queuing tag extraction for cache misses onto a small
                // thread pool so slow MediaMetadataRetriever reads overlap instead of serializing.
                List<ScanTask> pendingTasks = new ArrayList<>();
                int scanThreads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
                java.util.concurrent.ExecutorService scanExecutor = java.util.concurrent.Executors.newFixedThreadPool(scanThreads);
                try {
                    buildCustomLibrary(a, a.rootFolder, pendingTasks, cachedSongs, false, scanExecutor);
                    buildCustomLibrary(a, a.audiobookRootFolder, pendingTasks, cachedSongs, true, scanExecutor);
                } finally {
                    scanExecutor.shutdown();
                }
                finalizeScanTasks(a, pendingTasks, newCustomLibrary, newAudiobookLibrary, newTrackNumberMap, freshEntries);

                a.libraryCacheDb.replaceAll(freshEntries);

                // Run the favorites auto-cleaner (based on Music)
                java.util.HashSet<String> aliveSongs = new java.util.HashSet<>();
                for (SongItem song : newCustomLibrary) aliveSongs.add(song.file.getAbsolutePath());
                for (SongItem book : newAudiobookLibrary) aliveSongs.add(book.file.getAbsolutePath()); // 💡 Include audiobooks too!

                java.util.Iterator<String> favIterator = a.favoritePaths.iterator();
                while (favIterator.hasNext()) {
                    String favPath = favIterator.next();
                    if (!aliveSongs.contains(favPath)) {
                        favIterator.remove();
                        a.libraryCacheDb.setFavorite(favPath, false);
                    }
                }

                final List<SongItem> finalNewCustomLibrary = newCustomLibrary;
                final List<SongItem> finalNewAudiobookLibrary = newAudiobookLibrary;
                final java.util.HashMap<String, Integer> finalNewTrackNumberMap = newTrackNumberMap;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        a.isCustomScanning = false;
                        // Swap the freshly reconciled results in now that scanning is done — avoids
                        // showing a half-populated library to anything reading these lists mid-scan.
                        MainActivity.customLibrary.clear();
                        MainActivity.customLibrary.addAll(finalNewCustomLibrary);
                        MainActivity.audiobookLibrary.clear();
                        MainActivity.audiobookLibrary.addAll(finalNewAudiobookLibrary);
                        a.trackNumberMap.clear();
                        a.trackNumberMap.putAll(finalNewTrackNumberMap);

                        if (!silent) {
                            Toast.makeText(a, a.t("Scan Complete! Music")+": " + MainActivity.customLibrary.size()+" " + a.t("Books: ") + MainActivity.audiobookLibrary.size(), Toast.LENGTH_SHORT).show();
                        }

                        if (a.currentScreenState == MainActivity.STATE_BROWSER) {
                            if (a.currentBrowserMode == MainActivity.BROWSER_ROOT) a.buildFileBrowserUI();
                            else if (a.currentBrowserMode == MainActivity.BROWSER_ARTISTS) a.buildVirtualCategories("ARTIST");
                            else if (a.currentBrowserMode == MainActivity.BROWSER_ALBUMS) a.buildVirtualCategories("ALBUM");
                            else if (a.currentBrowserMode == MainActivity.BROWSER_VIRTUAL_SONGS) a.buildVirtualSongs();
                            else if (a.currentBrowserMode == MainActivity.BROWSER_COVER_FLOW) a.buildCoverFlowUI();
                        }
                    }
                });
            }
        }).start();
    }

    // 💡 [Overhaul complete] Reliable full-screen loading popup & screen-off prevention engine
    public void showLoadingPopup(final MainActivity a) {
        if (a.layoutLoadingOverlay != null) {
            // 🚀 [Fix 3] Also ensures the popup's opacity is fully set to 100% when showing the auto-scan screen!
            a.layoutLoadingOverlay.setAlpha(1.0f);
            a.layoutLoadingOverlay.setVisibility(View.VISIBLE);

            // 🚀 [Fix complete] Force-reset the shared canvas (tvLoadingProgress) text size back to its default (18f)!
            // This way, text that grew to 30f during frequency adjustment is properly restored back to a modest 18f during other scan operations.
            if (a.tvLoadingProgress != null) {
                a.tvLoadingProgress.setTextSize(18f);
            }

            // 🚀 [Core technique] Forces the system to never turn off the screen while scanning!
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            final Handler checker = new Handler();
            checker.post(new Runnable() {
                @Override
                public void run() {
                    // Guard against this self-rescheduling loop outliving the Activity (e.g. a
                    // scan stuck in isCustomScanning/isRadioScanning while the Activity is destroyed).
                    if (a.isFinishing() || a.isDestroyed()) return;
                    // 🚀 [Bug fix complete!] Don't close the window if either the music scan or radio scan is still running!
                    if (!a.isCustomScanning && !a.isRadioScanning) {
                        a.layoutLoadingOverlay.setVisibility(View.GONE);
                        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        checker.postDelayed(this, 200); // Check every 0.2 seconds
                    }
                }
            });
        }
    }
}
