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

public class SongListAdapter extends BaseAdapter {
    private List<SongItem> items;

    public SongListAdapter(List<SongItem> items) {
        this.items = items;
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

        // 🚀 [Indent alignment] Assigns the appropriate prefix icon for music mode vs. audiobook mode
        String prefixIcon = MainActivity.instance.isAudiobookLibraryMode ? "🎧 " : "🎵 ";

        // Only prefix the track number when browsing an album screen.
        String trackNum = "";
        if (("ALBUM".equals(MainActivity.instance.virtualQueryType) || "COVER_FLOW_ALBUM".equals(MainActivity.instance.virtualQueryType))
                && MainActivity.instance.trackNumberMap.containsKey(song.file.getAbsolutePath())) {
            int track = MainActivity.instance.trackNumberMap.get(song.file.getAbsolutePath());
            if (track > 0) {
                trackNum = String.format(java.util.Locale.US, "%02d. ", track);
            }
        }

        btn.setText(prefixIcon + trackNum + song.title);

        // 🚀 [Core fix for bug 1] Prevents the progress bar from being forcibly cleared when focus moves.
        if (MainActivity.instance.isAudiobookLibraryMode) {
            com.themoon.y1.db.LibraryCacheDb.Bookmark bm = MainActivity.instance.libraryCacheDb.getBookmark(song.file.getAbsolutePath());

            if (bm != null && bm.posMs > 0 && bm.durMs > 0) {
                // Audiobook files with a resume record connect to the progress-only focus-retention engine!
                MainActivity.instance.setupAudiobookProgress(btn, bm.posMs, bm.durMs);
            } else {
                // Plain files with no playback history get the default focus listener
                applyDefaultFocusListener(btn, song.title);
            }
        } else {
            // Assign the stock focus listener in regular music mode too (not audiobook mode)
            applyDefaultFocusListener(btn, song.title);
        }

        // Short-click listener (transitions to STATE_PLAYER and starts playback)
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.instance.clickFeedback();
                MainActivity.instance.currentIndex = position;
                MainActivity.instance.currentPlaylist.clear();
                MainActivity.instance.currentPlaylist.addAll(MainActivity.instance.virtualSongList);
                com.themoon.y1.managers.AudioPlayerManager.getInstance().prepareMusicTrack(MainActivity.instance.currentIndex);
                MainActivity.instance.changeScreen(3); // 3: STATE_PLAYER
            }
        });

        // Long-click listener (unified branching for unfavorite / add to M3U playlist / etc.)
        btn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
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
        });

        return btn;
    }

    // 🚀 [Safety net] Default listener factory that helps solid-background buttons handle wheel navigation smoothly
    private void applyDefaultFocusListener(final Button btn, final String title) {
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonFocusedBg()));
                    btn.setTextColor(ThemeManager.getListButtonFocusedTextColor());
                } else {
                    btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg()));
                    btn.setTextColor(ThemeManager.getTextColorPrimary());
                }
            }
        });
        // Cleans up any leftover rainbow-colored progress background residue when a list view item is recycled.
        btn.setBackground(MainActivity.instance.createButtonBackground(ThemeManager.getListButtonNormalBg()));
    }
}