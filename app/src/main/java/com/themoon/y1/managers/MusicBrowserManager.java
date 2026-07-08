package com.themoon.y1.managers;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.themoon.y1.MainActivity;
import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.adapters.CategoryListAdapter;
import com.themoon.y1.adapters.SongListAdapter;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Music library browser (folder tree, virtual artist/album/year/genre categories,
 * audiobook mode) and the 3D Cover Flow screen (transform math, reflection bitmaps, z-index
 * layering). Extracted from MainActivity per GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- reaches deep into MainActivity's browser-navigation state
 * (currentBrowserMode/currentFolder/virtualQueryValue/isAudiobookLibraryMode/etc.) the same way
 * every other screen builder does, so it takes the MainActivity instance as a parameter. The
 * cover-flow icon-scale cache (scaledIconCache) was private to getScaledThemedIcon() alone and
 * moved here outright; albumArtCache stays in MainActivity since it's initialized in
 * MainActivity.onCreate() and read by other subsystems too.
 */
public class MusicBrowserManager {
    private static final String TAG = "MusicBrowserManager";
    private static MusicBrowserManager instance;

    // Menu icons get rescaled with createScaledBitmap on every focus change (the wheel fires this
    // per scroll step), which allocates a fresh bitmap each time even though ThemeManager already
    // caches the decoded source. Cache the scaled result too, keyed by theme+icon+target size.
    private final android.util.LruCache<String, android.graphics.Bitmap> scaledIconCache = new android.util.LruCache<>(80);

    private MusicBrowserManager() {}

    public static synchronized MusicBrowserManager getInstance() {
        if (instance == null) {
            instance = new MusicBrowserManager();
        }
        return instance;
    }

    public void buildFileBrowserUI(MainActivity a) {
        if (a.scrollViewBrowser != null)
            a.scrollViewBrowser.setVisibility(View.VISIBLE);
        if (a.listVirtualSongs != null)
            a.listVirtualSongs.setVisibility(View.GONE);
        a.containerBrowserItems.removeAllViews();

        // 🚀 [Fix] Group the condition so the folder-browser frame also applies in audiobook mode.
        if (a.isPickingBackground || a.currentBrowserMode == a.BROWSER_FOLDER || a.currentBrowserMode == a.BROWSER_AUDIOBOOKS) {
            buildFolderBrowserUI(a);
            return;
        }

        if (a.currentBrowserMode == a.BROWSER_ROOT) {

            // 🎵 [Music library mode]
            if (!a.isAudiobookLibraryMode) {
                a.tvBrowserPath.setText(a.t("Library") + ": " + a.t("Music"));

                android.view.View btnCoverFlow = a.createListButtonWithIcon("\uE3B6", a.t("Cover Flow"));

                // setOnClickListener works exactly the same even if the returned view is a LinearLayout!
                btnCoverFlow.setOnClickListener(v -> { a.clickFeedback(); buildCoverFlowUI(a); });
                a.containerBrowserItems.addView(btnCoverFlow);

                // \u2601\uFE0F Navidrome \uC2A4\uD2B8\uB9AC\uBC0D \u2014 back returns here, not the main menu
                android.view.View btnNavidromeLib = a.createListButtonWithIcon("\uE2BD", a.t("Navidrome"));
                btnNavidromeLib.setOnClickListener(v -> {
                    a.clickFeedback();
                    a.navidromeBrowseDepth = a.NAV_ARTISTS;
                    a.selectedNavidromeArtist = null;
                    com.themoon.y1.managers.NavidromeManager.getInstance().clearSelectedAlbum();
                    a.isNavidromeLetterView = false;
                    a.navidromeBackTarget = a.STATE_BROWSER;
                    a.changeScreen(a.STATE_NAVIDROME);
                });
                a.containerBrowserItems.addView(btnNavidromeLib);

                android.view.View btnM3uPlaylist = a.createListButtonWithIcon("\uE05F", a.t("Playlists"));
                btnM3uPlaylist.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_PLAYLISTS; a.buildM3uPlaylistUI(); });
                a.containerBrowserItems.addView(btnM3uPlaylist);

                //Button btnFolder = createListButton("📁 " + t("Folders"));
                android.view.View btnFolder = a.createListButtonWithIcon("\uE2C7", a.t("Folders"));
                btnFolder.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_FOLDER; a.currentFolder = a.rootFolder; buildFileBrowserUI(a); });
                a.containerBrowserItems.addView(btnFolder);

               /// Button btnArtist = createListButton("👤 " + t("Artists"));
                android.view.View btnArtist = a.createListButtonWithIcon("\uE7FD", a.t("Artists"));
                btnArtist.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_ARTISTS; a.virtualQueryValue = ""; buildVirtualCategories(a, "ARTIST"); });
                a.containerBrowserItems.addView(btnArtist);

              //  Button btnAlbum = createListButton("💿 " + t("Albums"));
                android.view.View btnAlbum = a.createListButtonWithIcon("\uE019", a.t("Albums"));
                btnAlbum.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_ALBUMS; a.virtualQueryValue = ""; buildVirtualCategories(a, "ALBUM"); });
                a.containerBrowserItems.addView(btnAlbum);

                android.view.View btnYear = a.createListButtonWithIcon("\uE916", a.t("Years"));
                btnYear.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_YEARS; a.virtualQueryValue = ""; buildVirtualCategories(a, "YEAR"); });
                a.containerBrowserItems.addView(btnYear);

                android.view.View btnGenre = a.createListButtonWithIcon("\uE030", a.t("Genres"));
                btnGenre.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_GENRES; a.virtualQueryValue = ""; buildVirtualCategories(a, "GENRE"); });
                a.containerBrowserItems.addView(btnGenre);
               // Button btnAll = createListButton("🎵 " + t("All Songs"));
                android.view.View btnAll = a.createListButtonWithIcon("\uE03D", a.t("All Songs"));
                btnAll.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_VIRTUAL_SONGS; a.virtualQueryType = "ALL"; buildVirtualSongs(a); });
                a.containerBrowserItems.addView(btnAll);


                android.view.View btnFav = a.createListButtonWithIcon("\uE87D", a.t("My Favorites"));

//                btnFav.setTextColor(0xFFFF8888);
                btnFav.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_FAVORITES; a.buildVirtualSongsForFavorites(); });
                a.containerBrowserItems.addView(btnFav);
                // 🎧 Switch-to-audiobook-mode button
               // Button btnAudiobook = createListButton("🎧 " + t("Switch to Audiobooks"));
                android.view.View btnAudiobook = a.createListButtonWithIcon("\uE86D", a.t("Switch to Audiobooks"));
          //      btnAudiobook.setTextColor(0xFF00FFFF);
                btnAudiobook.setOnClickListener(v -> { a.clickFeedback(); a.isAudiobookLibraryMode = true; buildFileBrowserUI(a); });
                a.containerBrowserItems.addView(btnAudiobook);
            }
            // 📚 [Audiobook library mode]
            else {
                a.tvBrowserPath.setText(a.t("Library") + ": " + a.t("Audiobooks"));

//                Button btnFolder = createListButton("📁 " + t("Folders"));
                android.view.View btnFolder = a.createListButtonWithIcon("\uE2C7", a.t("Folders"));
                btnFolder.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_FOLDER; a.currentFolder = a.audiobookRootFolder; buildFileBrowserUI(a); });
                a.containerBrowserItems.addView(btnFolder);

                android.view.View btnAuthor = a.createListButtonWithIcon("\uE7FD", a.t("Authors"));
                btnAuthor.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_ARTISTS; a.virtualQueryValue = ""; buildVirtualCategories(a, "ARTIST"); });
                a.containerBrowserItems.addView(btnAuthor);

                android.view.View btnBook = a.createListButtonWithIcon("\uE86D", a.t("Books"));
                btnBook.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_ALBUMS; a.virtualQueryValue = ""; buildVirtualCategories(a, "ALBUM"); });
                a.containerBrowserItems.addView(btnBook);

                android.view.View btnAll = a.createListButtonWithIcon("\uE8FE", a.t("All Audiobooks"));

                btnAll.setOnClickListener(v -> { a.clickFeedback(); a.currentBrowserMode = a.BROWSER_VIRTUAL_SONGS; a.virtualQueryType = "ALL"; buildVirtualSongs(a); });
                a.containerBrowserItems.addView(btnAll);

                // 🎵 Switch-back-to-music-mode button
                android.view.View btnMusic = a.createListButtonWithIcon("\uE03D", a.t("Switch to Music"));

                btnMusic.setOnClickListener(v -> { a.clickFeedback(); a.isAudiobookLibraryMode = false; buildFileBrowserUI(a); });
                a.containerBrowserItems.addView(btnMusic);
            }
            // Uses the hourglass unicode (\uE88B) while scanning, and the sync-arrow unicode (\uE863) otherwise.
            String scanIcon = a.isCustomScanning ? "\uE88B" : "\uE863";
            String scanText = a.isCustomScanning ? a.t("Scanning Media...") : a.t("Scan Media Library");

            android.view.View btnScan = a.createListButtonWithIcon(scanIcon, scanText);

            btnScan.setOnClickListener(v -> {
                a.clickFeedback();
                a.startMediaLibraryScan();
            });
            a.containerBrowserItems.addView(btnScan);

            if (a.containerBrowserItems.getChildCount() > 0) a.containerBrowserItems.getChildAt(0).requestFocus();
        }
        // 🚀 [Added] Once the screen finishes drawing (50ms later), find the folder/menu that just appeared and auto-focus it!
        a.containerBrowserItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean found = false;
                if (!a.lastBrowserFocusText.isEmpty()) {
                    for (int i = 0; i < a.containerBrowserItems.getChildCount(); i++) {
                        View v = a.containerBrowserItems.getChildAt(i);
                        boolean isMatch = (v instanceof Button && ((Button) v).getText().toString().equals(a.lastBrowserFocusText))
                                || a.lastBrowserFocusText.equals(v.getTag());
                        if (isMatch) {
                            v.requestFocus();
                            if (a.containerBrowserItems.getParent() instanceof android.widget.ScrollView) {
                                ((android.widget.ScrollView) a.containerBrowserItems.getParent())
                                        .requestChildFocus(a.containerBrowserItems, v);
                            }
                            found = true;
                            break;
                        }
                    }
                }
                if (!found && a.containerBrowserItems.getChildCount() > 0) {
                    a.containerBrowserItems.getChildAt(0).requestFocus(); // Fall back to the top if not found
                }
                a.lastBrowserFocusText = ""; // 🚀 One-shot, so reset the memory right after use
            }
        }, 50);
    }

    public void buildVirtualCategories(MainActivity a, final String type) {
        // Only block on a scan that's still in progress if there's no cached library to show
        // yet at all — a background reconciliation scan shouldn't hide already-available data.
        if (a.isCustomScanning && a.customLibrary.isEmpty() && a.audiobookLibrary.isEmpty()) {
            a.showLoadingPopup(); // 🚀 Show the nice loading popup if a scan is in progress!
            a.currentBrowserMode = a.BROWSER_ROOT;
            buildFileBrowserUI(a);
            return;
        }

        // 🚀 Turn off the slow ScrollView for the category tabs too, and turn on the ultra-fast ListView!
        a.scrollViewBrowser.setVisibility(View.GONE);
        a.listVirtualSongs.setVisibility(View.VISIBLE);

        // 🚀 [Fix] Corrected so the top title syncs correctly for both the music library (Artists/Albums) and the audiobook library (Authors/Books)!
        if (a.isAudiobookLibraryMode) {
            a.tvBrowserPath.setText(a.t("Library") + ": " + (type.equals("ARTIST") ? a.t("Authors") : a.t("Books")));
        } else {
            a.tvBrowserPath.setText(a.t("Library") + ": " + (type.equals("ARTIST") ? a.t("Artists") : a.t("Albums")));
        }

        // 🚀 Swap the bucket to search through depending on the switch!
        List<SongItem> activeLibrary = a.isAudiobookLibraryMode ? a.audiobookLibrary : a.customLibrary;

        java.util.HashSet<String> uniqueCategories = new java.util.HashSet<>();
        for (SongItem song : activeLibrary) {
            // ❌ existing code: String val = type.equals("ARTIST") ? song.artist : song.album;

            // 🟢 [Fully fixed] Added YEAR and GENRE branches to gather a duplicate-free list of values.
            String val = "Unknown";
            if (type.equals("ARTIST")) val = song.artist;
            else if (type.equals("ALBUM")) val = song.album;
            else if (type.equals("YEAR")) val = song.year;
            else if (type.equals("GENRE")) val = song.genre;

            uniqueCategories.add(val);
        }

        List<String> categories = new ArrayList<>(uniqueCategories);
        // 🚀 [Fix] Sort perfectly in alphabetical order, case-insensitively!
        java.util.Collections.sort(categories, String.CASE_INSENSITIVE_ORDER);
        // 🚀 [Added] Remember the artist/album name for jumping
        // 🚀 [Added] Remember the artist/album name for jumping
        a.currentScrollIndexList.clear();
        a.currentScrollIndexList.addAll(categories);
        // 🚀 Push hundreds of artist/album entries into the recycler engine (adapter) too.
        CategoryListAdapter adapter = new CategoryListAdapter(categories, type);
        a.listVirtualSongs.setAdapter(adapter);

        // 🚀 [Overwrite from here!] Find the name of the previously entered artist/album and compute its index.
        // 🚀 [Fix] Find the name of the previously entered artist/album and compute its index.
        final int targetIndex = categories.indexOf(a.virtualQueryValue);

        a.listVirtualSongs.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (targetIndex >= 0) {
                    // 1. Instantly pull that position to the very top of the screen! (perfectly pinned)
                    a.listVirtualSongs.setSelectionFromTop(targetIndex, 0);

                    // 2. After a short delay for the layout to settle, snap the wheel focus precisely onto that cell.
                    a.listVirtualSongs.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            int visiblePos = targetIndex - a.listVirtualSongs.getFirstVisiblePosition();
                            if (visiblePos >= 0 && visiblePos < a.listVirtualSongs.getChildCount()) {
                                a.listVirtualSongs.getChildAt(visiblePos).requestFocus();
                            }
                        }
                    }, 50);
                } else if (a.listVirtualSongs.getChildCount() > 0) {
                    a.listVirtualSongs.getChildAt(0).requestFocus();
                }
            }
        }, 50);
    }

    public char getInitialChar(MainActivity a, String text) {
        if (text == null || text.isEmpty())
            return '#';
        String clean = text.replace("📁 ", "").replace("👤 ", "")
                .replace("💿 ", "").replace("🎵 ", "").trim().toUpperCase();
        if (clean.isEmpty())
            return '#';
        return clean.charAt(0);
    }

    public android.graphics.Bitmap getScaledThemedIcon(MainActivity a, String iconFileName, int size) {
        String key = ThemeManager.getCurrentThemeIndex() + "|" + iconFileName + "|" + size;
        android.graphics.Bitmap cached = scaledIconCache.get(key);
        if (cached != null) return cached;

        android.graphics.Bitmap raw = ThemeManager.getCustomIcon(iconFileName, a, 0);
        if (raw == null) return null;

        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(raw, size, size, true);
        scaledIconCache.put(key, scaled);
        return scaled;
    }

    public void buildCoverFlowUI(MainActivity a) {
        a.currentBrowserMode = a.BROWSER_COVER_FLOW;
        if (a.scrollViewBrowser != null) a.scrollViewBrowser.setVisibility(View.VISIBLE);
        if (a.listVirtualSongs != null) a.listVirtualSongs.setVisibility(View.GONE);
        a.containerBrowserItems.removeAllViews();

        a.uniqueAlbumList.clear();
        java.util.HashSet<String> seenAlbums = new java.util.HashSet<>();
        List<SongItem> activeLibrary = a.isAudiobookLibraryMode ? a.audiobookLibrary : a.customLibrary;
        for (SongItem song : activeLibrary) {
            if (!seenAlbums.contains(song.album)) {
                seenAlbums.add(song.album);
                a.uniqueAlbumList.add(song);
            }
        }
        java.util.Collections.sort(a.uniqueAlbumList, (s1, s2) -> s1.album.compareToIgnoreCase(s2.album));

        if (a.uniqueAlbumList.isEmpty()) {
            TextView tvEmpty = new TextView(a);
            tvEmpty.setText(a.t("No albums found."));
            tvEmpty.setTextColor(0xFF888888);
            tvEmpty.setGravity(android.view.Gravity.CENTER);
            tvEmpty.setPadding(20, 50, 20, 50);
            a.containerBrowserItems.addView(tvEmpty);
            return;
        }

        if(a.currentCoverFlowIndex >= a.uniqueAlbumList.size()) a.currentCoverFlowIndex = 0;

        a.coverFlowContainer = new android.widget.FrameLayout(a);
        a.coverFlowContainer.setClipChildren(false);
        a.coverFlowContainer.setClipToPadding(false);

        // 🚀 [Bug fully resolved] Completely suppresses the parent layouts' instinct to 'crop margins'!
        // Now the album image is no longer clipped like a knife even when it extends into the margin/padding zones at the screen edges.
        a.containerBrowserItems.setClipChildren(false);
        a.containerBrowserItems.setClipToPadding(false);
        if (a.scrollViewBrowser instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) a.scrollViewBrowser).setClipChildren(false);
            ((android.view.ViewGroup) a.scrollViewBrowser).setClipToPadding(false);
        }

        int height = (int)(320 * a.getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams containerLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height);
        containerLp.topMargin = (int)(15 * a.getResources().getDisplayMetrics().density);
        a.coverFlowContainer.setLayoutParams(containerLp);

        // 🚀 [Key] Dynamically spawns as many slots as the configured variable (visibleCoversCount)!
        a.cfViews = new android.view.View[a.visibleCoversCount];
        for(int i = 0; i < a.visibleCoversCount; i++) {
            a.cfViews[i] = createSingleCoverView(a);
            a.coverFlowContainer.addView(a.cfViews[i]);
        }

        a.containerBrowserItems.addView(a.coverFlowContainer);
        initCoverFlowPositions(a);
    }

    public void initCoverFlowPositions(MainActivity a) {
        int total = a.uniqueAlbumList.size();
        if(total == 0) return;

        int centerIdx = a.visibleCoversCount / 2;

        // Bind data by calculating the forward/backward indices relative to dead center
        for(int i = 0; i < a.visibleCoversCount; i++) {
            int offsetFromCenter = i - centerIdx;
            int targetIdx = (a.currentCoverFlowIndex + offsetFromCenter + total * 3) % total;
            bindCoverData(a, a.cfViews[i], targetIdx);
        }

        float d = a.getResources().getDisplayMetrics().density;

        // 🚀 Mathematical-algorithm loop: scale/coordinate formulas map automatically regardless of view count
        for(int i = 0; i < a.visibleCoversCount; i++) {
            int dist = Math.abs(i - centerIdx);
            float sign = (i < centerIdx) ? -1f : 1f; // negative (-) to the left, positive (+) to the right

            float transX = sign * getTransXForDist(a, dist, d);
            float rotY = -sign * getRotYForDist(a, dist);
            float scale = getScaleForDist(a, dist);
            float alpha = getAlphaForDist(a, dist);

            applyTransform(a, a.cfViews[i], transX, rotY, scale, alpha);
        }

        arrangeZIndex(a);

        for(int i = 0; i < a.visibleCoversCount; i++) {
            setCardTitleAlpha(a, a.cfViews[i], i == centerIdx, 0);
        }

        a.tvBrowserPath.setText(a.t("Cover Flow") + " (" + (a.currentCoverFlowIndex + 1) + "/" + total + ")");
    }

    public float getTransXForDist(MainActivity a, int dist, float d) {
        if (dist == 0) return 0f;
        if (dist == 1) return 130 * d;
        if (dist == 2) return 170 * d;
        return 220 * d; // when distance is 3 or more
    }

    public float getRotYForDist(MainActivity a, int dist) {
        if (dist == 0) return 0f;
        if (dist == 1) return 65f;  // 💡 45 degrees -> a deeper 60 degrees!
//        if (dist == 2) return 75f;  // 💡 60 degrees -> a deeper 75 degrees!
        return 65f;
    }

    public float getScaleForDist(MainActivity a, int dist) {
        if (dist == 0) return 1.0f;
        if (dist == 1) return 0.8f;
        if (dist == 2) return 0.8f;
        return 0.8f;
    }

    public float getAlphaForDist(MainActivity a, int dist) {
//        if (dist == 0) return 1.0f;
//        if (dist == 1) return 0.8f;
//        if (dist == 2) return 0.5f;
        return 1f;
    }

    public void bindCoverData(MainActivity a, View card, int dataIndex) {
        if(a.uniqueAlbumList.isEmpty() || dataIndex < 0 || dataIndex >= a.uniqueAlbumList.size()) return;
        SongItem item = a.uniqueAlbumList.get(dataIndex);

        final ImageView ivCover = card.findViewById(1001);
        final ImageView ivReflection = card.findViewById(1004); // 🚀 Obtain the reflection layer
        TextView tvTitle = card.findViewById(1002);
        TextView tvArtist = card.findViewById(1003);

        tvTitle.setText(item.album);
        tvArtist.setText(item.artist);

        final String path = item.file.getAbsolutePath();
        ivCover.setTag(path); // Fully blocks async race conditions

        // 1. Search the ultra-fast RAM cache vault (searches for both the original and reflection image sets at once)
        android.graphics.Bitmap cachedBmp = null;
        android.graphics.Bitmap cachedRef = null;
        if (a.albumArtCache != null) {
            cachedBmp = a.albumArtCache.get(path);
            cachedRef = a.albumArtCache.get("ref_" + path);
        }

        if (cachedBmp != null) {
            // 💡 If both are in the RAM vault, double-bind them instantly in 0.0001 seconds!
            ivCover.setImageBitmap(cachedBmp);
            if (ivReflection != null) {
                ivReflection.setImageBitmap(cachedRef);
                ivReflection.setVisibility(cachedRef != null ? View.VISIBLE : View.INVISIBLE);
            }
            return;
        }

        // If not cached, bind a blank placeholder canvas and dispatch a worker thread
        ivCover.setImageBitmap(ThemeManager.getCustomIcon("icon_default_album.png", a, R.drawable.default_album));
        if (ivReflection != null) ivReflection.setImageBitmap(null);

        new Thread(new Runnable() {
            @Override
            public void run() {
                android.graphics.Bitmap bmp = null;
                String cachedArtPath = a.libraryCacheDb.getAlbumArtPath(path);

                if (cachedArtPath != null && new File(cachedArtPath).exists()) {
                    bmp = android.graphics.BitmapFactory.decodeFile(cachedArtPath);
                } else {
                    try {
                        String songName = item.file.getName();
                        int dot = songName.lastIndexOf(".");
                        if (dot > 0) songName = songName.substring(0, dot);

                        File fallbackFile = new File("/storage/sdcard0/Y1_Covers", songName + ".jpg");
                        if (fallbackFile.exists()) {
                            bmp = android.graphics.BitmapFactory.decodeFile(fallbackFile.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "bindCoverData failed", e);
                    }
                }

                if (bmp == null) {
                    try {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        mmr.setDataSource(path);
                        byte[] embeddedArt = mmr.getEmbeddedPicture();
                        mmr.release();
                        if (embeddedArt != null) {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inSampleSize = 2;
                            bmp = android.graphics.BitmapFactory.decodeByteArray(embeddedArt, 0, embeddedArt.length, opts);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "bindCoverData failed", e);
                    }
                }

                final android.graphics.Bitmap finalBmp = bmp;

                // 🚀 [Important] The reflection is generated on this background thread, not the main thread, so there is 0% performance overhead!
                final android.graphics.Bitmap finalRef = getReflectionBitmap(a, finalBmp);

                // Store the original and reflection images side by side in the RAM vault for the next lookup
                if (finalBmp != null && a.albumArtCache != null) {
                    a.albumArtCache.put(path, finalBmp);
                    if (finalRef != null) {
                        a.albumArtCache.put("ref_" + path, finalRef);
                    }
                }

                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Only render to the screen if the target hasn't changed to a different track while the wheel was spinning
                        if (path.equals(ivCover.getTag())) {
                            if (finalBmp != null) ivCover.setImageBitmap(finalBmp);
                            if (ivReflection != null) {
                                ivReflection.setImageBitmap(finalRef);
                                ivReflection.setVisibility(finalRef != null ? View.VISIBLE : View.INVISIBLE);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    public View createSingleCoverView(MainActivity a) {
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout card = new android.widget.LinearLayout(a);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                (int)(350 * d), (int)(320 * d));
        lp.gravity = android.view.Gravity.CENTER;

        // 🚀 [Core fix] Changed the existing -25 value to '0'! This stops the cover image from being forced to bounce upward.
        lp.topMargin = 0;

        card.setLayoutParams(lp);

        ImageView ivCover = new ImageView(a);
        ivCover.setId(1001);
        android.widget.LinearLayout.LayoutParams imgLp = new android.widget.LinearLayout.LayoutParams((int)(200 * d), (int)(200 * d));
        imgLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        ivCover.setLayoutParams(imgLp);
        ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivCover.setBackground(a.createButtonBackground(0x00000000));

        ImageView ivReflection = new ImageView(a);
        ivReflection.setId(1004);
        android.widget.LinearLayout.LayoutParams refLp = new android.widget.LinearLayout.LayoutParams((int)(200 * d), (int)(50 * d));
        refLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        refLp.topMargin = (int)(2 * d);
        ivReflection.setLayoutParams(refLp);
        ivReflection.setScaleType(ImageView.ScaleType.CENTER_CROP);

        TextView tvTitle = new TextView(a);
        tvTitle.setId(1002);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setAlpha(0f);

        android.widget.LinearLayout.LayoutParams titleLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

        // 🚀 Positions the album title with a nice gap directly under the reflection image only.
        titleLp.topMargin = (int)(-40 * d);
        titleLp.bottomMargin = (int)(0 * d);
        tvTitle.setLayoutParams(titleLp);

        TextView tvArtist = new TextView(a);
        tvArtist.setId(1003);
        tvArtist.setTextColor(ThemeManager.getTextColorSecondary());
        tvArtist.setTextSize(14f);
        tvArtist.setGravity(android.view.Gravity.CENTER);
        tvArtist.setSingleLine(true);
        tvArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvArtist.setAlpha(0f);

        android.widget.LinearLayout.LayoutParams artistLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = (int)(2 * d);
        tvArtist.setLayoutParams(artistLp);

        card.addView(ivCover);
        card.addView(ivReflection);
        card.addView(tvTitle);
        card.addView(tvArtist);

        card.setOnClickListener(v -> {
            int centerIdx = a.visibleCoversCount / 2;
            if (v == a.cfViews[centerIdx] && a.currentCoverFlowIndex >= 0 && a.currentCoverFlowIndex < a.uniqueAlbumList.size()) {
                a.clickFeedback();
                SongItem chosen = a.uniqueAlbumList.get(a.currentCoverFlowIndex);
                a.currentBrowserMode = a.BROWSER_VIRTUAL_SONGS;
                a.virtualQueryType = "COVER_FLOW_ALBUM";
                a.virtualQueryValue = chosen.album;
                buildVirtualSongs(a);
            }
        });

        return card;
    }

    public android.graphics.Bitmap getReflectionBitmap(MainActivity a, android.graphics.Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int reqH = h / 4; // Use only the bottom 25% of the original as the reflection area
            if (reqH <= 0) return null;

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.preScale(1, -1); // Apply a vertical-flip matrix

            // Crop just the bottom part and generate a flipped bitmap
            android.graphics.Bitmap flipped = android.graphics.Bitmap.createBitmap(src, 0, h - reqH, w, reqH, matrix, false);
            android.graphics.Bitmap reflection = android.graphics.Bitmap.createBitmap(w, reqH, android.graphics.Bitmap.Config.ARGB_8888);

            android.graphics.Canvas canvas = new android.graphics.Canvas(reflection);
            canvas.drawBitmap(flipped, 0, 0, null);
            flipped.recycle();

            // Paint a gradient mask that fades away toward the bottom
            android.graphics.Paint paint = new android.graphics.Paint();
            android.graphics.LinearGradient shader = new android.graphics.LinearGradient(
                    0, 0, 0, reqH,
                    0x44FFFFFF, 0x00FFFFFF, // Starts at ~25% subtle reflection opacity -> fully transparent at 0%
                    android.graphics.Shader.TileMode.CLAMP);
            paint.setShader(shader);
            paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            canvas.drawRect(0, 0, w, reqH, paint);

            return reflection;
        } catch (Exception e) {
            return null;
        }
    }

    public void setCardTitleAlpha(MainActivity a, View card, boolean isCenter, int duration) {
        View tvTitle = card.findViewById(1002);
        View tvArtist = card.findViewById(1003);
        if (tvTitle != null && tvArtist != null) {
            float targetAlpha = isCenter ? 1.0f : 0.0f; // 100% if centered, 0% otherwise
            if (duration > 0) {
                tvTitle.animate().alpha(targetAlpha).setDuration(duration).start();
                tvArtist.animate().alpha(targetAlpha).setDuration(duration).start();
            } else {
                tvTitle.setAlpha(targetAlpha);
                tvArtist.setAlpha(targetAlpha);
            }
        }
    }

    public void applyTransform(MainActivity a, View v, float transX, float rotY, float scale, float alpha) {
        v.setTranslationX(transX);
        v.setRotationY(rotY);
        v.setScaleX(scale); v.setScaleY(scale);
        v.setAlpha(alpha);
    }

    public void animateTransform(MainActivity a, View v, float transX, float rotY, float scale, float alpha, int duration) {
        v.animate().translationX(transX).rotationY(rotY).scaleX(scale).scaleY(scale).alpha(alpha).setDuration(duration).start();
    }

    public void arrangeZIndex(MainActivity a) {
        int centerIdx = a.visibleCoversCount / 2;

        // Bring-to-front the cards in reverse order, from the farthest distance down to dead center (0)
        for (int d = centerIdx; d >= 0; d--) {
            int leftViewIdx = centerIdx - d;
            int rightViewIdx = centerIdx + d;

            if (leftViewIdx >= 0) a.cfViews[leftViewIdx].bringToFront();
            if (rightViewIdx < a.visibleCoversCount) a.cfViews[rightViewIdx].bringToFront();
        }

        for(int i = 0; i < a.visibleCoversCount; i++) a.cfViews[i].invalidate();
        a.coverFlowContainer.invalidate();
    }

    public void scrollCoverFlow(MainActivity a, boolean isNext) {
        int total = a.uniqueAlbumList.size();
        if(total == 0) return;

        float d = a.getResources().getDisplayMetrics().density;
        int centerIdx = a.visibleCoversCount / 2;

        long now = System.currentTimeMillis();
        long diff = now - a.lastCoverFlowTime;
        a.lastCoverFlowTime = now;
        int duration = (diff < 80) ? 30 : 180;

        if (isNext) {
            a.currentCoverFlowIndex = (a.currentCoverFlowIndex + 1) % total;
            View oldLeft = a.cfViews[0];

            // 🚀 Dynamic index shuffling
            for(int i = 0; i < a.visibleCoversCount - 1; i++) a.cfViews[i] = a.cfViews[i+1];
            a.cfViews[a.visibleCoversCount - 1] = oldLeft;

            bindCoverData(a, a.cfViews[a.visibleCoversCount - 1], (a.currentCoverFlowIndex + centerIdx + total * 3) % total);

            float maxOff = getTransXForDist(a, centerIdx, d);
            float maxRot = getRotYForDist(a, centerIdx);
            float maxScale = getScaleForDist(a, centerIdx);
            applyTransform(a, a.cfViews[a.visibleCoversCount - 1], maxOff * 1.5f, -maxRot, maxScale, 0f);
        } else {
            a.currentCoverFlowIndex = (a.currentCoverFlowIndex - 1 + total) % total;
            View oldRight = a.cfViews[a.visibleCoversCount - 1];

            for(int i = a.visibleCoversCount - 1; i > 0; i--) a.cfViews[i] = a.cfViews[i-1];
            a.cfViews[0] = oldRight;

            bindCoverData(a, a.cfViews[0], (a.currentCoverFlowIndex - centerIdx + total * 3) % total);

            float maxOff = getTransXForDist(a, centerIdx, d);
            float maxRot = getRotYForDist(a, centerIdx);
            float maxScale = getScaleForDist(a, centerIdx);
            applyTransform(a, a.cfViews[0], -maxOff * 1.5f, maxRot, maxScale, 0f);
        }

        arrangeZIndex(a);

        // 🚀 Fire off the full dynamic-slot animation barrage!
        for(int i = 0; i < a.visibleCoversCount; i++) {
            setCardTitleAlpha(a, a.cfViews[i], i == centerIdx, duration);

            int dist = Math.abs(i - centerIdx);
            float sign = (i < centerIdx) ? -1f : 1f;

            float transX = sign * getTransXForDist(a, dist, d);
            float rotY = -sign * getRotYForDist(a, dist);
            float scale = getScaleForDist(a, dist);
            float alpha = getAlphaForDist(a, dist);

            animateTransform(a, a.cfViews[i], transX, rotY, scale, alpha, duration);
        }

        a.tvBrowserPath.setText(a.t("Cover Flow") + " (" + (a.currentCoverFlowIndex + 1) + "/" + total + ")");
    }

    public void buildVirtualSongs(MainActivity a) {
        // Only block on a scan that's still in progress if there's no cached library to show
        // yet at all — a background reconciliation scan shouldn't hide already-available data.
        if (a.isCustomScanning && a.customLibrary.isEmpty() && a.audiobookLibrary.isEmpty()) {
            a.showLoadingPopup(); // 🚀 Show a large spinner popup instead of hard-to-see text!
            a.currentBrowserMode = a.BROWSER_ROOT;
            buildFileBrowserUI(a);
            return;
        }
        // 🚀 Turn off the existing bloated, slow ScrollView and turn on the ultra-fast ListView!
        a.scrollViewBrowser.setVisibility(View.GONE);
        a.listVirtualSongs.setVisibility(View.VISIBLE);

        // 🚀 [Fix] Changed so the top header title displays correctly for both all-music and all-audiobooks mode!
        if (a.virtualQueryType.equals("ALL")) {
            a.tvBrowserPath.setText(a.t("Library") + ": " + (a.isAudiobookLibraryMode ? a.t("All Audiobooks") : a.t("All Songs")));
        } else {
            a.tvBrowserPath.setText(a.t("Library") + ": " + a.virtualQueryValue); // Output the artist/album name as-is
        }
        a.virtualSongList.clear();
        a.currentScrollIndexList.clear(); // 🚀 [Added] Reset the existing index
        final List<SongItem> targetSongs = new ArrayList<>();

        // 🚀 Swap the bucket to search through depending on the switch!
        List<SongItem> activeLibrary = a.isAudiobookLibraryMode ? a.audiobookLibrary : a.customLibrary;

        for (SongItem song : activeLibrary) {
            if (a.virtualQueryType.equals("ALL") ||
                    (a.virtualQueryType.equals("ARTIST") && song.artist.equals(a.virtualQueryValue)) ||
                    (a.virtualQueryType.equals("ALBUM") && song.album.equals(a.virtualQueryValue)) ||
                    (a.virtualQueryType.equals("COVER_FLOW_ALBUM") && song.album.equals(a.virtualQueryValue)) || // 🚀 [2 lines added below!]
                    (a.virtualQueryType.equals("YEAR") && song.year.equals(a.virtualQueryValue)) ||
                    (a.virtualQueryType.equals("GENRE") && song.genre.equals(a.virtualQueryValue))) {
                targetSongs.add(song);
            }
        }

        java.util.Collections.sort(targetSongs, new java.util.Comparator<SongItem>() {
            @Override
            public int compare(SongItem s1, SongItem s2) {
                // 🚀 [Path-recovery 2] Also add sorting by track number for the cover-flow-dedicated tag.
                if ("ALBUM".equals(a.virtualQueryType) || "COVER_FLOW_ALBUM".equals(a.virtualQueryType)) {
                    int t1 = a.trackNumberMap.containsKey(s1.file.getAbsolutePath()) ? a.trackNumberMap.get(s1.file.getAbsolutePath()) : 0;
                    int t2 = a.trackNumberMap.containsKey(s2.file.getAbsolutePath()) ? a.trackNumberMap.get(s2.file.getAbsolutePath()) : 0;

                    if (t1 != t2) {
                        return Integer.valueOf(t1).compareTo(t2);
                    }
                }
                return s1.title.compareToIgnoreCase(s2.title);
            }
        });
        // ... (rest below unchanged)

        // 🚀 Fill the actual playback list and the fast-scroll index in the sorted order.
        for (SongItem song : targetSongs) {
            a.virtualSongList.add(song.file);
            a.currentScrollIndexList.add(song.title);
        }

        // 🚀 Load thousands of tracks' worth of data into the recycler engine (adapter).

        // 🚀 Load thousands of tracks' worth of data into the recycler engine (adapter).
        SongListAdapter adapter = new SongListAdapter(targetSongs);
        a.listVirtualSongs.setAdapter(adapter);
        a.listVirtualSongs.post(new Runnable() {
            @Override
            public void run() {
                if (a.listVirtualSongs.getChildCount() > 0) {
                    a.listVirtualSongs.getChildAt(0).requestFocus();
                }
            }
        });
    }

    public void buildFolderBrowserUI(MainActivity a) {
        a.containerBrowserItems.removeAllViews();
        a.tvBrowserPath.setText(a.t("Path") + ": " + a.currentFolder.getAbsolutePath().replace("/storage/sdcard0", ""));
        File[] files = a.currentFolder.listFiles();

        if (files == null || files.length == 0) {
            Button btnEmpty = a.createListButton(files == null ? "⚠️ " + a.t("USB Disconnect Required (Tap to go back)") : "📂 " + a.t("Empty Folder (Tap to go back)"));
            btnEmpty.setTextColor(0xFFFF5555);
            btnEmpty.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    // 🚀 [Fix] Handle the whole-folder case here too
                    if (a.currentFolder.getAbsolutePath().equals(a.rootFolder.getAbsolutePath()) || a.currentFolder.getAbsolutePath().equals("/storage/sdcard0")) {
                        if (a.isPickingBackground) {
                            a.isPickingBackground = false;
                            a.changeScreen(a.STATE_SETTINGS);
                            a.buildBackgroundSettingsUI();
                        } else {
                            a.currentBrowserMode = a.BROWSER_ROOT;
                            buildFileBrowserUI(a);
                        }
                    } else {
                        a.currentFolder = a.currentFolder.getParentFile();
                        buildFileBrowserUI(a);
                    }
                }
            });
            a.containerBrowserItems.addView(btnEmpty);
            return;
        }

        List<File> folders = new ArrayList<File>();
        List<File> audioFiles = new ArrayList<File>();
        List<File> apkFiles = new ArrayList<File>();
        List<File> imageFiles = new ArrayList<File>();

        for (File f : files) {
            if (f.isDirectory())
                folders.add(f);
            else if (a.isPickingBackground && a.isImageFile(f))
                imageFiles.add(f);
            else if (!a.isPickingBackground && a.isAudioFile(f))
                audioFiles.add(f);
            else if (!a.isPickingBackground && a.isApkFile(f))
                apkFiles.add(f);
        }
        // 🚀 [10 new lines added here!!] Sort the collected files and folders by name (alphabetical A-Z, case-insensitive)!
        java.util.Comparator<File> fileSorter = new java.util.Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        };
        java.util.Collections.sort(folders, fileSorter);
        java.util.Collections.sort(audioFiles, fileSorter);
        java.util.Collections.sort(apkFiles, fileSorter);
        java.util.Collections.sort(imageFiles, fileSorter);
        // 🚀🚀🚀 [Add here!] If the current folder has even one audio file, create a 'Play All' button at the top
        if (!a.isPickingBackground && (audioFiles.size() > 0 || folders.size() > 0)) {
            Button btnPlayAll = a.createListButton("▶ " + a.t("Play All"));
            btnPlayAll.setTextColor(0xFFFFFFFF); // green!
            btnPlayAll.setTypeface(null, android.graphics.Typeface.BOLD);

            btnPlayAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();

                    // 1. Prepare a large empty bucket to sweep up subfolders too
                    final List<File> allAudioInFolder = new ArrayList<>();

                    // 2. In case there are a lot of files, lock the wheel and show a popup!
                    a.showLoadingPopup();

                    // 3. Run the background engine (to avoid freezing the system)
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            // Call the vacuum-cleaner function we just built!
                            a.collectAudioFilesAsFile(a.currentFolder, allAudioInFolder);

                            // Sort the collected files neatly by name (reusing the existing fileSorter)
                            java.util.Collections.sort(allAudioInFolder, fileSorter);

                            // 4. Once the collection is done, come back to the screen and issue the play command.
                            a.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    // Close the loading popup
                                    if (a.layoutLoadingOverlay != null) a.layoutLoadingOverlay.setVisibility(View.GONE);

                                    if (allAudioInFolder.isEmpty()) {
                                        Toast.makeText(a, a.t("No audio files found in subfolders."), Toast.LENGTH_SHORT).show();
                                    } else {
//                                        Toast.makeText(a, "Loaded " + allAudioInFolder.size() + " songs!", Toast.LENGTH_SHORT).show();
                                        com.themoon.y1.managers.AudioPlayerManager.getInstance().playTrackList(allAudioInFolder, 0); // Start playing right from track 0!

                                        // 🚀 [Fix] Automatically switch to the player screen after Play All!
                                        a.changeScreen(a.STATE_PLAYER);
                                    }
                                }
                            });
                        }
                    }).start();
                }
            });
            a.containerBrowserItems.addView(btnPlayAll);
        }
        // 🚀🚀🚀 [End of addition]
        for (final File folder : folders) {
            android.view.View b = a.createListButtonWithIcon("\uE2C7",folder.getName());
         //   Button b = createListButton("📁 " + folder.getName());
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    a.currentFolder = folder;
                    buildFileBrowserUI(a);
                }
            });
            a.containerBrowserItems.addView(b);
        }

        if (a.isPickingBackground) {
            for (final File img : imageFiles) {
//                Button b = createListButton("🖼 " + img.getName());
                android.view.View b = a.createListButtonWithIcon("\uE410",img.getName());
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.clickFeedback();
                        try {
                            a.prefs.edit().putString("bg_path", img.getAbsolutePath()).apply();
                        } catch (Exception e) {
                            Log.d(TAG, "buildFolderBrowserUI failed", e);
                        }

                        a.updateMainMenuBackground(); // 💡 Apply the blur to the main screen immediately on selection

                        Toast.makeText(a, a.t("Background Applied!"), Toast.LENGTH_SHORT).show();
                        a.isPickingBackground = false;
                        a.changeScreen(a.STATE_SETTINGS);
                        a.buildBackgroundSettingsUI();
                    }
                });
                a.containerBrowserItems.addView(b);
            }
        } else {
            for (final File apk : apkFiles) {
                Button b = a.createListButton("📦 [" + a.t("INSTALL") + "] " + apk.getName());
                b.setTextColor(0xFF00FFFF);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.clickFeedback();
                        a.installApk(apk);
                    }
                });
                a.containerBrowserItems.addView(b);
            }
            for (final File audio : audioFiles) {
                Button b = a.createListButton("🎵 " + audio.getName());

                // 🚀 [Added] Draw the progress bar if we're in audiobook mode or inside an audiobook folder!
                if (a.isAudiobookLibraryMode || a.currentBrowserMode == a.BROWSER_AUDIOBOOKS) {
                    com.themoon.y1.db.LibraryCacheDb.Bookmark bm = a.libraryCacheDb.getBookmark(audio.getAbsolutePath());
                    if (bm != null && bm.posMs > 0 && bm.durMs > 0) {
                        a.setupAudiobookProgress(b, bm.posMs, bm.durMs); // 💡 Replaced with a call to the new engine!
                    }
                }

                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.clickFeedback();
                        if (a.currentBrowserMode == a.BROWSER_AUDIOBOOKS) {
                            com.themoon.y1.managers.AudiobookManager.getInstance(a).setupBookPlaylist(a, audio, a.currentFolder);
                        } else {
                            com.themoon.y1.managers.AudioPlayerManager.getInstance().setupFolderPlaylist(audio, a.currentFolder);
                        }
                        // 🚀 [Fix] Automatically switch to the player screen when playing an individual song from a folder!
                        a.changeScreen(a.STATE_PLAYER);
                    }
                });

                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        a.clickFeedback();
                        a.isLongPressConsumed = true; // 🚀 [Bug fix] Force-enable the long-press guard the instant the popup appears!
                        a.showAddToPlaylistDialog(audio);
                        return true;
                    }
                });
                a.containerBrowserItems.addView(b);
            }
        }
        if (a.containerBrowserItems.getChildCount() > 0)
            a.containerBrowserItems.getChildAt(0).requestFocus();

        // 🚀 [Added] Once the screen finishes drawing (50ms later), find the folder/menu that just appeared and auto-focus it!
        a.containerBrowserItems.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean found = false;
                if (!a.lastBrowserFocusText.isEmpty()) {
                    for (int i = 0; i < a.containerBrowserItems.getChildCount(); i++) {
                        View v = a.containerBrowserItems.getChildAt(i);
                        boolean isMatch = (v instanceof Button && ((Button) v).getText().toString().equals(a.lastBrowserFocusText))
                                || a.lastBrowserFocusText.equals(v.getTag());
                        if (isMatch) {
                            v.requestFocus();
                            if (a.containerBrowserItems.getParent() instanceof android.widget.ScrollView) {
                                ((android.widget.ScrollView) a.containerBrowserItems.getParent())
                                        .requestChildFocus(a.containerBrowserItems, v);
                            }
                            found = true;
                            break;
                        }
                    }
                }
                if (!found && a.containerBrowserItems.getChildCount() > 0) {
                    a.containerBrowserItems.getChildAt(0).requestFocus(); // Fall back to the top if not found
                }
                a.lastBrowserFocusText = ""; // 🚀 One-shot, so reset the memory right after use
            }
        }, 50);
    }

}
