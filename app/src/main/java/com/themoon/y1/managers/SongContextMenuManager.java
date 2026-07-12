package com.themoon.y1.managers;

import android.content.DialogInterface;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.ThemeManager;
import com.themoon.y1.models.SongItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's quick-menu / queue viewer plus library song and album delete flows, all built on
 * the shared themed options-dialog helper. Extracted verbatim from MainActivity per
 * GOD_ACTIVITY_EXTRACTION.md.
 *
 * No clean field boundary -- reads/writes MainActivity's playback state, library lists, and
 * browser-screen fields directly -- so it takes the MainActivity instance as a parameter.
 * MainActivity keeps thin pass-through methods for showQuickMenu(), showSongOptionsDialog(),
 * showDeleteAlbumDialog(), and both showThemedOptionsDialog() overloads since KeyEventRouter,
 * NavidromeManager, and the adapters call them by name.
 */
public class SongContextMenuManager {
    private static final String TAG = "SongContextMenuManager";
    private static SongContextMenuManager instance;

    private SongContextMenuManager() {}

    public static synchronized SongContextMenuManager getInstance() {
        if (instance == null) {
            instance = new SongContextMenuManager();
        }
        return instance;
    }

    /** Double-click center on Now Playing: playback/queue/Wi-Fi/Bluetooth shortcuts without
     *  leaving the player screen (center long-press is already claimed by screen-off there). */
    public void showQuickMenu(final MainActivity a) {
        String favPath = a.getCurrentTrackPathForFavorites();
        final boolean isFav = favPath != null && a.favoritePaths.contains(favPath);
        final boolean isCasting = com.themoon.y1.cast.CastManager.getInstance().isCasting();

        showThemedOptionsDialog(a, a.t("Quick Menu"), null,
                new String[]{
                        null,
                        "", // format_list_bulleted
                        "", // cast
                        "", // wifi
                        ""  // bluetooth
                },
                new String[]{
                        isFav ? "♥  " + a.t("Remove Favorite") : "♡  " + a.t("Add Favorite"),
                        a.t("Playlist"),
                        isCasting ? a.t("Stop Casting") : a.t("Cast"),
                        a.t("Wi-Fi"),
                        a.t("Bluetooth")
                },
                new Runnable[]{
                        new Runnable() { @Override public void run() { a.toggleFavorite(); } },
                        new Runnable() { @Override public void run() { showQueueDialog(a); } },
                        new Runnable() { @Override public void run() {
                            if (isCasting) {
                                com.themoon.y1.cast.CastManager.getInstance().stopCasting();
                                Toast.makeText(a, a.t("Stopped casting"), Toast.LENGTH_SHORT).show();
                            } else if (!isWifiEnabled(a)) {
                                showWifiOffDialog(a);
                            } else {
                                showCastMenu(a);
                            }
                        } },
                        new Runnable() { @Override public void run() { a.changeScreen(MainActivity.STATE_WIFI); } },
                        new Runnable() { @Override public void run() { a.changeScreen(MainActivity.STATE_BLUETOOTH); } }
                });
    }

    public boolean isWifiEnabled(MainActivity a) {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                a.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    private void showWifiOffDialog(final MainActivity a) {
        showWifiOffDialog(a, a.t("Casting requires a Wi-Fi connection"));
    }

    /** Shared "turn it on for me" prompt for any screen that needs Wi-Fi -- also used by
     *  NavidromeManager when Wi-Fi is off. */
    public void showWifiOffDialog(final MainActivity a, String message) {
        showThemedOptionsDialog(a, a.t("Wi-Fi Off"), message,
                new String[]{ a.t("Turn On Wi-Fi"), a.t("Go to Wi-Fi Settings") },
                new Runnable[]{
                        new Runnable() { @Override public void run() {
                            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                                    a.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
                            if (wm != null) {
                                Toast.makeText(a, a.t("Turning Wi-Fi ON..."), Toast.LENGTH_SHORT).show();
                                wm.setWifiEnabled(true);
                            }
                        } },
                        new Runnable() { @Override public void run() { a.changeScreen(MainActivity.STATE_WIFI); } }
                });
    }

    /**
     * Cast device picker. Kicks off mDNS discovery, gives the network a moment to answer, then
     * shows the found Google speakers as a themed options list. Discovery is stopped as soon as
     * the user picks a device or dismisses the picker, so it's never running in the background
     * outside this screen.
     */
    private void showCastMenu(final MainActivity a) {
        final com.themoon.y1.cast.CastManager cast = com.themoon.y1.cast.CastManager.getInstance();
        final java.util.List<com.themoon.y1.cast.CastDevice> found = new ArrayList<>();

        Toast.makeText(a, a.t("Searching for Google speakers…"), Toast.LENGTH_SHORT).show();
        cast.startDiscovery(a, new com.themoon.y1.cast.CastDiscovery.Callback() {
            @Override public void onDevicesChanged(java.util.List<com.themoon.y1.cast.CastDevice> devices) {
                found.clear();
                found.addAll(devices);
            }
        });

        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (a.isFinishing()) { cast.stopDiscovery(); return; }
                if (found.isEmpty()) {
                    showThemedOptionsDialog(a, a.t("Cast to Speaker"), a.t("No speakers found on this Wi-Fi"),
                            new String[]{ a.t("Search again"), a.t("Cancel") },
                            new Runnable[]{
                                    new Runnable() { @Override public void run() { cast.stopDiscovery(); showCastMenu(a); } },
                                    new Runnable() { @Override public void run() { cast.stopDiscovery(); } }
                            });
                    return;
                }
                final java.util.List<com.themoon.y1.cast.CastDevice> devices = new ArrayList<>(found);
                java.util.Collections.sort(devices, new java.util.Comparator<com.themoon.y1.cast.CastDevice>() {
                    @Override public int compare(com.themoon.y1.cast.CastDevice d1, com.themoon.y1.cast.CastDevice d2) {
                        return d1.friendlyName.compareToIgnoreCase(d2.friendlyName);
                    }
                });
                String[] labels = new String[devices.size() + 1];
                Runnable[] actions = new Runnable[devices.size() + 1];
                for (int i = 0; i < devices.size(); i++) {
                    final com.themoon.y1.cast.CastDevice dev = devices.get(i);
                    labels[i] = dev.friendlyName;
                    actions[i] = new Runnable() {
                        @Override public void run() {
                            cast.stopDiscovery();
                            cast.castCurrentTrack(a, dev);
                        }
                    };
                }
                labels[devices.size()] = a.t("Search again");
                actions[devices.size()] = new Runnable() { @Override public void run() { cast.stopDiscovery(); showCastMenu(a); } };
                showThemedOptionsDialog(a, a.t("Cast to Speaker"), a.t("Select a speaker"), labels, actions);
            }
        }, 2500);
    }

    /** Queue viewer opened from the quick menu: click a row to jump to that track,
     *  long-press to remove it from the queue (the currently-playing row can't be removed --
     *  skip to it moving on is what nextTrack()/prevTrack() are for). */
    private void showQueueDialog(final MainActivity a) {
        if (a.currentPlaylist.isEmpty()) {
            Toast.makeText(a, a.t("Playlist is empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        final android.app.Dialog dialog = new android.app.Dialog(a);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(a.createButtonBackground(0xF2151515));
        root.setPadding((int) (18 * d), (int) (14 * d), (int) (18 * d), (int) (14 * d));

        TextView tvTitle = new TextView(a);
        tvTitle.setText(a.t("Playlist") + " (" + a.currentPlaylist.size() + ")");
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        root.addView(tvTitle);

        final android.widget.ListView listView = new android.widget.ListView(a);
        listView.setDivider(null);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d));
        listParams.topMargin = (int) (10 * d);
        listView.setLayoutParams(listParams);

        final int[] cursor = { a.currentIndex };

        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(a,
                android.R.layout.simple_list_item_1) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                boolean isPlaying = position == a.currentIndex;
                boolean isCursor = position == cursor[0];
                String label = a.currentPlaylist.get(position).getName();
                if (isPlaying) label = "▶  " + label;
                tv.setText(label);
                tv.setBackground(isCursor ? a.createButtonBackground(ThemeManager.getListButtonFocusedBg()) : null);
                tv.setTextColor(isCursor ? ThemeManager.getListButtonFocusedTextColor()
                        : isPlaying ? (ThemeManager.getListButtonFocusedBg() | 0xFF000000)
                        : ThemeManager.getTextColorPrimary());
                tv.setTypeface(ThemeManager.getCustomFont());
                tv.setTextSize(15f);
                tv.setPadding((int) (8 * d), (int) (10 * d), (int) (8 * d), (int) (10 * d));
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                return tv;
            }
        };
        for (File f : a.currentPlaylist) adapter.add(f.getName());
        listView.setAdapter(adapter);
        listView.setSelection(a.currentIndex);

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                a.clickFeedback();
                dialog.dismiss();
                com.themoon.y1.managers.AudioPlayerManager.getInstance().playTrackList(a.currentPlaylist, position);
            }
        });
        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == a.currentIndex) {
                    Toast.makeText(a, a.t("Can't remove the track that's playing"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                a.clickFeedback();
                a.currentPlaylist.remove(position);
                if (position < a.currentIndex) a.currentIndex--;
                if (position < cursor[0] || cursor[0] >= a.currentPlaylist.size()) {
                    cursor[0] = Math.max(0, Math.min(cursor[0], a.currentPlaylist.size() - 1));
                }
                adapter.clear();
                for (File f : a.currentPlaylist) adapter.add(f.getName());
                adapter.notifyDataSetChanged();
                return true;
            }
        });

        root.addView(listView);

        TextView hint = new TextView(a);
        hint.setText(a.t("Click: jump to track") + "   •   " + a.t("Long-press: remove"));
        hint.setTextSize(11f);
        hint.setTypeface(ThemeManager.getCustomFont());
        hint.setTextColor(ThemeManager.getTextColorSecondary());
        hint.setPadding(0, (int) (8 * d), 0, 0);
        root.addView(hint);

        // Wheel rotation → moves our own cursor (not ListView's built-in selection, which
        // doesn't draw/track reliably once the window has seen a touch event) and scrolls
        // it into view. 21/22 are the wheel's synthetic key codes.
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != 21 && keyCode != 22) return false;
                cursor[0] += keyCode == 22 ? 1 : -1;
                cursor[0] = Math.max(0, Math.min(cursor[0], a.currentPlaylist.size() - 1));
                listView.setSelection(cursor[0]);
                adapter.notifyDataSetChanged();
                a.clickFeedback();
                return true;
            }
        });

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        listView.requestFocus();
    }

    /** Long-press menu for library songs: playlist add or delete from device. */
    public void showSongOptionsDialog(final MainActivity a, final java.io.File file) {
        showThemedOptionsDialog(a, file.getName(), null,
                new String[]{ "➕  " + a.t("Add to Playlist"), "🗑  " + a.t("Delete from Device"), a.t("Cancel") },
                new Runnable[]{
                        new Runnable() { @Override public void run() { a.showAddToPlaylistDialog(file); } },
                        new Runnable() { @Override public void run() { showDeleteSongDialog(a, file); } },
                        null
                });
    }

    private void showDeleteSongDialog(final MainActivity a, final java.io.File file) {
        showThemedOptionsDialog(a, a.t("Delete from Device"), file.getName(),
                new String[]{ "🗑  " + a.t("Delete"), a.t("Cancel") },
                new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                if (deleteLibrarySong(a, file)) {
                                    Toast.makeText(a, "🗑 " + a.t("Deleted"), Toast.LENGTH_SHORT).show();
                                    if (a.currentBrowserMode == MainActivity.BROWSER_VIRTUAL_SONGS) a.buildVirtualSongs();
                                }
                            }
                        },
                        null
                });
    }

    /** Long-press on an album row — wipe the whole album from the device. */
    public void showDeleteAlbumDialog(final MainActivity a, final String albumName) {
        List<SongItem> active = a.isAudiobookLibraryMode ? MainActivity.audiobookLibrary : MainActivity.customLibrary;
        final List<SongItem> targets = new ArrayList<>();
        for (SongItem s : active) {
            if (albumName.equals(s.album)) targets.add(s);
        }
        if (targets.isEmpty()) return;
        showThemedOptionsDialog(a, a.t("Delete Album"), albumName + "  (" + targets.size() + " " + a.t("tracks") + ")",
                new String[]{ "🗑  " + a.t("Delete"), a.t("Cancel") },
                new Runnable[]{
                        new Runnable() {
                            @Override public void run() {
                                int deleted = 0;
                                for (SongItem s : targets) {
                                    if (deleteLibrarySong(a, s.file)) deleted++;
                                }
                                Toast.makeText(a, "🗑 " + a.t("Deleted") + " " + deleted, Toast.LENGTH_SHORT).show();
                                if (a.currentBrowserMode == MainActivity.BROWSER_ALBUMS) a.buildVirtualCategories("ALBUM");
                            }
                        },
                        null
                });
    }

    /** Delete a track from the SD card and scrub every launcher record of it:
     *  libraries, favorites, per-track prefs, cover cache, empty folders. */
    private boolean deleteLibrarySong(MainActivity a, java.io.File f) {
        String path = f.getAbsolutePath();
        if (f.exists() && !f.delete()) return false;

        java.util.Iterator<SongItem> it = MainActivity.customLibrary.iterator();
        while (it.hasNext()) if (it.next().file.getAbsolutePath().equals(path)) it.remove();
        it = MainActivity.audiobookLibrary.iterator();
        while (it.hasNext()) if (it.next().file.getAbsolutePath().equals(path)) it.remove();
        a.virtualSongList.remove(f);
        a.trackNumberMap.remove(path);
        a.favoritePaths.remove(path);
        try {
            a.libraryCacheDb.deleteSongState(path);
        } catch (Exception ignored) {
            Log.d(TAG, "deleteLibrarySong failed", ignored);
        }
        try {
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            new java.io.File("/storage/sdcard0/Y1_Covers", base + ".jpg").delete();
        } catch (Exception ignored) {
            Log.d(TAG, "deleteLibrarySong failed", ignored);
        }

        // Prune up to two levels of newly-empty folders, but never the roots
        java.io.File dir = f.getParentFile();
        for (int i = 0; i < 2 && dir != null; i++) {
            String dp = dir.getAbsolutePath();
            if (dp.equals("/storage/sdcard0/Music") || dp.equals("/storage/sdcard0/Audiobooks")
                    || dp.equals("/storage/sdcard0")) break;
            if (!dir.delete()) break; // fails while non-empty — that's the stop signal
            dir = dir.getParentFile();
        }
        return true;
    }

    /**
     * List-style modal matching the launcher UI: themed rounded panel, custom
     * font, and createListButton rows. Handles wheel rotation itself — dialogs
     * swallow keys before MainActivity.onKeyDown, and the wheel's 21/22 codes
     * only move focus between HORIZONTAL neighbours natively.
     */
    public void showThemedOptionsDialog(MainActivity a, String title, String subtitle, String[] options, final Runnable[] actions) {
        showThemedOptionsDialog(a, title, subtitle, null, options, actions);
    }

    /** Same as above but each row can carry a Material Icons codepoint (index-matched to options);
     *  pass null in the icons slot to fall back to the plain text row. */
    public void showThemedOptionsDialog(final MainActivity a, String title, String subtitle, String[] icons, String[] options, final Runnable[] actions) {
        float d = a.getResources().getDisplayMetrics().density;
        final android.app.Dialog dialog = new android.app.Dialog(a);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        final int maxScrollHeightPx = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.8f);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(a) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // Caps how tall this can grow so long option lists (e.g. the quick menu) scroll
                // instead of running off-screen, while short dialogs still just wrap to content.
                int capped = android.view.View.MeasureSpec.makeMeasureSpec(maxScrollHeightPx, android.view.View.MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, capped);
            }
        };
        scroll.setBackground(a.createButtonBackground(0xF2151515));
        scroll.setVerticalScrollBarEnabled(false);

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (18 * d), (int) (14 * d), (int) (18 * d), (int) (14 * d));
        scroll.addView(root, new android.widget.ScrollView.LayoutParams(
                android.widget.ScrollView.LayoutParams.MATCH_PARENT, android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(a);
        tvTitle.setText(title);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(ThemeManager.getTextColorPrimary());
        root.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(a);
            tvSub.setText(subtitle);
            tvSub.setTextSize(14f);
            tvSub.setTypeface(ThemeManager.getCustomFont(), android.graphics.Typeface.NORMAL);
            tvSub.setTextColor(ThemeManager.getTextColorSecondary());
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvSub.setPadding(0, (int) (2 * d), 0, 0);
            root.addView(tvSub);
        }

        android.view.View spacer = new android.view.View(a);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, (int) (10 * d)));
        root.addView(spacer);

        for (int i = 0; i < options.length; i++) {
            final Runnable action = actions[i];
            String icon = icons != null ? icons[i] : null;
            View btn = icon != null ? a.createListButtonWithIcon(icon, options[i]) : a.createListButton(options[i]);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.clickFeedback();
                    dialog.dismiss();
                    if (action != null) action.run();
                }
            });
            root.addView(btn);
        }

        // Wheel rotation → focus walk over the option rows (same logic onKeyDown
        // applies to LinearLayout lists, but scoped to this dialog)
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != 21 && keyCode != 22) return false;
                View cur = root.findFocus();
                int index = cur != null ? root.indexOfChild(cur) : -1;
                int dir = keyCode == 22 ? 1 : -1;
                int count = root.getChildCount();
                int i = index == -1 ? (dir == 1 ? 0 : count - 1) : index + dir;
                for (int steps = 0; steps < count; steps++, i += dir) {
                    if (i < 0) i += count;
                    if (i >= count) i -= count;
                    View n = root.getChildAt(i);
                    if (n != null && n.getVisibility() == View.VISIBLE && n.isFocusable()) {
                        n.requestFocus();
                        a.clickFeedback();
                        break;
                    }
                }
                return true;
            }
        });

        dialog.setContentView(scroll);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.88f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        // Land focus on the first option so the wheel works immediately
        root.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < root.getChildCount(); i++) {
                    View n = root.getChildAt(i);
                    if (n.isFocusable()) { n.requestFocus(); break; }
                }
            }
        });
    }
}
