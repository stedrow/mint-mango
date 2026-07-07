package com.themoon.y1.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;

import com.themoon.y1.MainActivity;
import com.themoon.y1.R;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CategoryListAdapter extends BaseAdapter {
    private List<String> items;
    private String type;

    // 🚀 An in-memory cache "vault" that remembers images so scrolling doesn't stutter!
    private static LruCache<String, Drawable> coverCache;

    // album -> its tracks, built once so cover lookup is O(tracks-in-album) instead of
    // O(whole-library) per album. Rebuilt only when the backing library instance changes.
    private static Map<String, List<SongItem>> albumTrackIndex;
    private static List<SongItem> albumIndexSource;

    // Cached button backgrounds so focus changes don't rebuild a GradientDrawable each time.
    private Drawable normalBg;
    private Drawable focusedBg;

    // Density-derived pixel sizes, computed once instead of per bind.
    private int artSizePx;
    private int artPaddingPx;

    // Stateless focus listener shared by every row.
    private final View.OnFocusChangeListener sharedFocusListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                v.setBackground(getFocusedBg());
                ((Button) v).setTextColor(ThemeManager.getListButtonFocusedTextColor());
            } else {
                v.setBackground(getNormalBg());
                ((Button) v).setTextColor(ThemeManager.getTextColorPrimary());
            }
        }
    };

    // Shared click/long-click listeners; the album/artist name is read from the view tag.
    private final View.OnClickListener sharedClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            String name = (String) v.getTag();
            MainActivity.instance.clickFeedback();
            MainActivity.instance.virtualQueryType = type;
            MainActivity.instance.virtualQueryValue = name;
            MainActivity.instance.currentBrowserMode = MainActivity.BROWSER_VIRTUAL_SONGS;
            MainActivity.instance.buildVirtualSongs();
        }
    };

    private final View.OnLongClickListener sharedLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            String name = (String) v.getTag();
            MainActivity.instance.clickFeedback();
            MainActivity.instance.isLongPressConsumed = true;
            MainActivity.instance.showDeleteAlbumDialog(name);
            return true;
        }
    };

    // Album art lookup does file scans + MediaMetadataRetriever + bitmap decode, all real I/O.
    // A single background thread keeps decode order roughly matching scroll order and avoids
    // spawning one raw Thread per row on a CPU this weak.
    private static final java.util.concurrent.ExecutorService albumArtExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static Drawable defaultAlbumDrawable;

    public CategoryListAdapter(List<String> items, String type) {
        this.items = items;
        this.type = type;

        if (coverCache == null) {
            coverCache = new LruCache<>(50); // safely keeps up to 50 album art images in memory
        }

        float density = MainActivity.instance.getResources().getDisplayMetrics().density;
        artSizePx = (int) (40 * density);
        artPaddingPx = (int) (15 * density);
    }

    private Drawable getNormalBg() {
        if (normalBg == null) {
            normalBg = MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg());
        }
        return normalBg;
    }

    private Drawable getFocusedBg() {
        if (focusedBg == null) {
            focusedBg = MainActivity.instance.createButtonBackground(ThemeManager.getListButtonFocusedBg());
        }
        return focusedBg;
    }

    // Returns the tracks belonging to an album, using a lazily-built index. Called only from the
    // single-threaded albumArtExecutor, so the index build needs no extra locking.
    private static List<SongItem> tracksForAlbum(String album) {
        List<SongItem> lib = MainActivity.customLibrary;
        if (albumTrackIndex == null || albumIndexSource != lib) {
            Map<String, List<SongItem>> idx = new HashMap<>();
            for (SongItem s : lib) {
                List<SongItem> bucket = idx.get(s.album);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    idx.put(s.album, bucket);
                }
                bucket.add(s);
            }
            albumTrackIndex = idx;
            albumIndexSource = lib;
        }
        List<SongItem> res = albumTrackIndex.get(album);
        return res == null ? Collections.<SongItem>emptyList() : res;
    }

    @Override
    public int getCount() { return items.size(); }

    @Override
    public Object getItem(int position) { return items.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        final Button btn;

        if (convertView == null) {
            btn = MainActivity.instance.createListButton("");
            btn.setLayoutParams(new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT));
        } else {
            btn = (Button) convertView;
        }

        final String name = items.get(position);

        // The tag carries the album/artist name: it drives both the async art-recycle guard and
        // the shared click/long-click listeners.
        btn.setTag(name);

        // 🚀 [core change] Only set the left icon (album art thumbnail) in album mode!
        if (type.equals("ALBUM")) {
            btn.setText(name); // 💿 Drop the emoji and use the plain album name only.

            // 1. Check whether the image is already loaded in the memory cache!
            Drawable cached = coverCache.get(name);

            if (cached != null) {
                btn.setCompoundDrawables(cached, null, null, null);
            } else {
                // 2. Cache miss: show the default icon immediately and hand off the actual lookup/decoding to a background thread.
                // (Doing the full library scan + MediaMetadataRetriever + bitmap decoding on the main thread
                // caused heavy stutter while scrolling, so it was moved to a separate thread.)
                final int size = artSizePx;
                btn.setCompoundDrawables(getDefaultAlbumDrawable(size), null, null, null);

                albumArtExecutor.execute(() -> {
                    final Drawable loaded = loadAlbumArtDrawable(name, size);
                    coverCache.put(name, loaded);
                    MainActivity.instance.runOnUiThread(() -> {
                        if (name.equals(btn.getTag())) {
                            btn.setCompoundDrawables(loaded, null, null, null);
                        }
                    });
                });
            }

            // Sets the image on the left of the button, with 15dp of spacing before the text.
            btn.setCompoundDrawablePadding(artPaddingPx);

        } else {
            // 👤 In artist (ARTIST) mode, keep using only the text emoji as before!
            btn.setText("👤 " + name);
            btn.setCompoundDrawables(null, null, null, null);
        }

        if (type.equals("ALBUM")) {
            // Long click = delete the whole album (confirmed via themed modal)
            btn.setOnLongClickListener(sharedLongClickListener);
        } else {
            // Artist rows had no long-click handler; clear any leftover from a recycled album row.
            btn.setOnLongClickListener(null);
        }

        btn.setOnFocusChangeListener(sharedFocusListener);
        btn.setOnClickListener(sharedClickListener);

        return btn;
    }

    private Drawable getDefaultAlbumDrawable(int size) {
        if (defaultAlbumDrawable == null) {
            Bitmap bmp = BitmapFactory.decodeResource(MainActivity.instance.getResources(), R.drawable.default_album);
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, size, size, true);
            defaultAlbumDrawable = new BitmapDrawable(MainActivity.instance.getResources(), scaled);
            defaultAlbumDrawable.setBounds(0, 0, size, size);
        }
        return defaultAlbumDrawable;
    }

    // Runs off the main thread: scans the library for this album's cover (saved download path,
    // manually-dropped cover file, or embedded tag art) and decodes/scales a thumbnail.
    private Drawable loadAlbumArtDrawable(String name, int size) {
        String artPath = "";
        byte[] embeddedPic = null;

        // 🚀 [major bug fix] Search through every song belonging to this album!
        // Even if a downloaded image was only saved while playing one specific track (e.g. track 3),
        // widen the lookup scope so it's still found reliably from the full album category list.
        // Uses the album->tracks index so this is O(tracks-in-album) instead of O(whole-library).
        for (SongItem song : tracksForAlbum(name)) {
            String trackPath = song.file.getAbsolutePath();

            // (1) Check whether a download path is registered in the DB
            if (MainActivity.instance.libraryCacheDb != null) {
                String savedPath = MainActivity.instance.libraryCacheDb.getAlbumArtPath(trackPath);
                if (savedPath != null && !savedPath.isEmpty() && new File(savedPath).exists()) {
                    artPath = savedPath;
                    break; // found the image, exit immediately!
                }
            }

            // (2) In case the cache registration is missing, double-check by scanning the folder directly via filename matching!
            String safeFileName = song.file.getName().replace(".mp3", "").replace(".flac", "").replace(".wav", "").replace(".m4a", "").replace(".aac", "").replace(".ogg", "");
            File manualCoverFile = new File("/storage/sdcard0/Y1_Covers", safeFileName + ".jpg");
            if (manualCoverFile.exists()) {
                artPath = manualCoverFile.getAbsolutePath();
                break; // exit immediately once the actual file is found!
            }

            // (3) If there's no downloaded image, register the file's embedded art as a candidate (excluding FLAC)
            if (embeddedPic == null && !trackPath.toLowerCase().endsWith(".flac")) {
                android.media.MediaMetadataRetriever mmr = null;
                java.io.FileInputStream fis = null;
                try {
                    mmr = new android.media.MediaMetadataRetriever();
                    fis = new java.io.FileInputStream(trackPath);
                    mmr.setDataSource(fis.getFD());
                    byte[] pic = mmr.getEmbeddedPicture();
                    if (pic != null && pic.length > 0) {
                        embeddedPic = pic;
                    }
                } catch (Exception e) {
                } finally {
                    try { if (fis != null) fis.close(); } catch (Exception e) {}
                    try { if (mmr != null) mmr.release(); } catch (Exception e) {}
                }
            }
        }

        Bitmap bmp = null;

        // [option 1] If a downloaded cover exists, load it first
        if (!artPath.isEmpty()) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                bmp = BitmapFactory.decodeFile(artPath, opts);
            } catch (Exception e) {}
        }
        // [option 2] If there's no downloaded cover, load the file's embedded art
        else if (embeddedPic != null) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                bmp = BitmapFactory.decodeByteArray(embeddedPic, 0, embeddedPic.length, opts);
            } catch (Exception e) {}
        }

        // [option 3] If neither exists, use the default image
        if (bmp == null) {
            return getDefaultAlbumDrawable(size);
        }

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, size, size, true);
        Drawable drawable = new BitmapDrawable(MainActivity.instance.getResources(), scaled);
        drawable.setBounds(0, 0, size, size);
        return drawable;
    }
}