package com.themoon.y1.adapters;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.models.SongItem;

import java.util.List;
import java.util.Map;

public class SongListAdapter extends BaseAdapter {
    private List<SongItem> items;

    // Bulk-loaded audiobook bookmarks (path -> [posMs, durMs]) so getView() does a HashMap
    // lookup instead of a per-row DB query on the main thread. Loaded once, lazily.
    private Map<String, long[]> bookmarkMap;
    private boolean bookmarksLoaded;

    // Cached button backgrounds so we don't rebuild a GradientDrawable on every bind/focus change.
    private android.graphics.drawable.Drawable normalBg;
    private android.graphics.drawable.Drawable focusedBg;

    // Stateless focus listener shared by every row (reads the button from the callback's view arg).
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

    // Shared click/long-click listeners; per-row state (position + song) is carried on the view tag.
    private final View.OnClickListener sharedClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Holder h = (Holder) v.getTag();
            MainActivity.instance.clickFeedback();
            com.themoon.y1.managers.AudioPlayerManager.getInstance()
                    .playTrackList(MainActivity.instance.virtualSongList, h.position);
            MainActivity.instance.changeScreen(3); // 3: STATE_PLAYER
        }
    };

    private final View.OnLongClickListener sharedLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            SongItem song = ((Holder) v.getTag()).song;
            MainActivity.instance.clickFeedback();
            MainActivity.instance.isLongPressConsumed = true;

            if (MainActivity.instance.currentBrowserMode == 5) { // BROWSER_FAVORITES
                MainActivity.instance.showRemoveFromFavoritesDialog(song.file);
            } else if (MainActivity.instance.currentBrowserMode == 7) { // BROWSER_M3U_SONGS
                MainActivity.instance.showRemoveFromPlaylistDialog(song.file);
            } else {
                MainActivity.instance.showSongOptionsDialog(song.file);
            }
            return true;
        }
    };

    // Lightweight per-row state holder stored on the button's tag.
    private static class Holder {
        int position;
        SongItem song;
    }

    public SongListAdapter(List<SongItem> items) {
        this.items = items;
    }

    private android.graphics.drawable.Drawable getNormalBg() {
        if (normalBg == null) {
            normalBg = MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg());
        }
        return normalBg;
    }

    private android.graphics.drawable.Drawable getFocusedBg() {
        if (focusedBg == null) {
            focusedBg = MainActivity.instance.createButtonBackground(ThemeManager.getListButtonFocusedBg());
        }
        return focusedBg;
    }

    private long[] lookupBookmark(String path) {
        if (!bookmarksLoaded) {
            bookmarkMap = MainActivity.instance.libraryCacheDb.loadAllBookmarks();
            bookmarksLoaded = true;
        }
        return bookmarkMap == null ? null : bookmarkMap.get(path);
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

        final SongItem song = items.get(position);

        // Carry per-row state on the tag so the shared click/long-click listeners can read it.
        Holder holder = (Holder) btn.getTag();
        if (holder == null) {
            holder = new Holder();
            btn.setTag(holder);
        }
        holder.position = position;
        holder.song = song;

        // Only prefix the track number when browsing an album screen.
        String trackNum = "";
        if (("ALBUM".equals(MainActivity.instance.virtualQueryType) || "COVER_FLOW_ALBUM".equals(MainActivity.instance.virtualQueryType))
                && MainActivity.instance.trackNumberMap.containsKey(song.file.getAbsolutePath())) {
            int track = MainActivity.instance.trackNumberMap.get(song.file.getAbsolutePath());
            if (track > 0) {
                trackNum = (track < 10 ? "0" + track : Integer.toString(track)) + ". ";
            }
        }

        btn.setText(trackNum + song.title);

        // 🚀 [Core fix for bug 1] Prevents the progress bar from being forcibly cleared when focus moves.
        if (MainActivity.instance.isAudiobookLibraryMode) {
            // In-memory bulk lookup instead of a per-row DB query (path -> [posMs, durMs]).
            long[] bm = lookupBookmark(song.file.getAbsolutePath());

            if (bm != null && bm[0] > 0 && bm[1] > 0) {
                // Audiobook files with a resume record connect to the progress-only focus-retention engine!
                MainActivity.instance.setupAudiobookProgress(btn, (int) bm[0], (int) bm[1]);
            } else {
                // Plain files with no playback history get the default focus listener
                applyDefaultFocusListener(btn);
            }
        } else {
            // Assign the stock focus listener in regular music mode too (not audiobook mode)
            applyDefaultFocusListener(btn);
        }

        // Short-click / long-click use shared listener instances (state read from the view tag).
        btn.setOnClickListener(sharedClickListener);
        btn.setOnLongClickListener(sharedLongClickListener);

        return btn;
    }

    // 🚀 [Safety net] Default listener that helps solid-background buttons handle wheel navigation smoothly
    private void applyDefaultFocusListener(final Button btn) {
        btn.setOnFocusChangeListener(sharedFocusListener);
        // Cleans up any leftover rainbow-colored progress background residue when a list view item is recycled.
        btn.setBackground(getNormalBg());
    }
}