package com.themoon.y1.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists tag-extraction results across app restarts so the media scan only has to
 * re-run MediaMetadataRetriever on files that are new or have changed (tracked via
 * mtime + size), instead of every file on every cold start. Also persists the last
 * playback position/playlist so the app can resume where it left off.
 */
public class LibraryCacheDb extends SQLiteOpenHelper {

    private static final String TAG = "LibraryCacheDb";
    private static final String DB_NAME = "library_cache.db";
    private static final int DB_VERSION = 4;
    private static final String TABLE = "songs";
    private static final String PLAYER_STATE_TABLE = "player_state";
    private static final String STATE_TABLE = "song_state";
    private static final String ARTISTS_TABLE = "navidrome_artists";

    public static class CachedSong {
        public final String path;
        public final long mtime;
        public final long size;
        public final String title;
        public final String artist;
        public final String album;
        public final String year;
        public final String genre;
        public final int trackNumber;
        public final boolean isAudiobook;

        public CachedSong(String path, long mtime, long size, String title, String artist,
                           String album, String year, String genre, int trackNumber, boolean isAudiobook) {
            this.path = path;
            this.mtime = mtime;
            this.size = size;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.year = year;
            this.genre = genre;
            this.trackNumber = trackNumber;
            this.isAudiobook = isAudiobook;
        }
    }

    /** Single-row snapshot of what was playing, so playback can resume across a cold start. */
    public static class PlayerState {
        public final List<String> playlist;
        public final int index;
        public final int positionMs;
        public final boolean isAudiobook;

        public PlayerState(List<String> playlist, int index, int positionMs, boolean isAudiobook) {
            this.playlist = playlist;
            this.index = index;
            this.positionMs = positionMs;
            this.isAudiobook = isAudiobook;
        }
    }

    /** Per-track favorite/custom-title/album-art/audiobook-bookmark state, keyed by path. */
    public static class MetaOverride {
        public final String title;
        public final String artist;
        public MetaOverride(String title, String artist) {
            this.title = title;
            this.artist = artist;
        }
    }

    public static class Bookmark {
        public final int posMs;
        public final int durMs;
        public Bookmark(int posMs, int durMs) {
            this.posMs = posMs;
            this.durMs = durMs;
        }
    }

    public LibraryCacheDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "path TEXT PRIMARY KEY," +
                "mtime INTEGER," +
                "size INTEGER," +
                "title TEXT," +
                "artist TEXT," +
                "album TEXT," +
                "year TEXT," +
                "genre TEXT," +
                "track_number INTEGER," +
                "is_audiobook INTEGER)");
        db.execSQL("CREATE TABLE " + PLAYER_STATE_TABLE + " (" +
                "id INTEGER PRIMARY KEY," +
                "playlist TEXT," +
                "idx INTEGER," +
                "position_ms INTEGER," +
                "is_audiobook INTEGER)");
        db.execSQL("CREATE TABLE " + STATE_TABLE + " (" +
                "path TEXT PRIMARY KEY," +
                "is_favorite INTEGER DEFAULT 0," +
                "meta_title TEXT," +
                "meta_artist TEXT," +
                "album_art_path TEXT," +
                "book_pos_ms INTEGER," +
                "book_dur_ms INTEGER)");
        // Speeds up loadFavoritePaths()'s is_favorite=1 lookup on weak hardware.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_state_fav ON " + STATE_TABLE + "(is_favorite)");
        db.execSQL("CREATE TABLE " + ARTISTS_TABLE + " (" +
                "id TEXT PRIMARY KEY," +
                "name TEXT," +
                "album_count INTEGER," +
                "cover_art_id TEXT," +
                "index_letter TEXT," +
                "sort_order INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + PLAYER_STATE_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + STATE_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + ARTISTS_TABLE);
        onCreate(db);
    }

    /** Loads every cached entry keyed by absolute file path for quick per-file lookup during a scan. */
    public Map<String, CachedSong> loadAll() {
        Map<String, CachedSong> result = new HashMap<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.query(TABLE, null, null, null, null, null, null);
            try {
                while (c.moveToNext()) {
                    String path = c.getString(c.getColumnIndexOrThrow("path"));
                    result.put(path, new CachedSong(
                            path,
                            c.getLong(c.getColumnIndexOrThrow("mtime")),
                            c.getLong(c.getColumnIndexOrThrow("size")),
                            c.getString(c.getColumnIndexOrThrow("title")),
                            c.getString(c.getColumnIndexOrThrow("artist")),
                            c.getString(c.getColumnIndexOrThrow("album")),
                            c.getString(c.getColumnIndexOrThrow("year")),
                            c.getString(c.getColumnIndexOrThrow("genre")),
                            c.getInt(c.getColumnIndexOrThrow("track_number")),
                            c.getInt(c.getColumnIndexOrThrow("is_audiobook")) != 0));
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Replaces the whole cache with exactly the entries found in the latest scan. */
    public void replaceAll(List<CachedSong> entries) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(TABLE, null, null);
                for (CachedSong s : entries) {
                    ContentValues cv = new ContentValues();
                    cv.put("path", s.path);
                    cv.put("mtime", s.mtime);
                    cv.put("size", s.size);
                    cv.put("title", s.title);
                    cv.put("artist", s.artist);
                    cv.put("album", s.album);
                    cv.put("year", s.year);
                    cv.put("genre", s.genre);
                    cv.put("track_number", s.trackNumber);
                    cv.put("is_audiobook", s.isAudiobook ? 1 : 0);
                    db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "replaceAll failed", e);
        }
    }

    /** Inserts or updates a single row, e.g. right after a track finishes downloading. */
    public void upsert(CachedSong s) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("path", s.path);
            cv.put("mtime", s.mtime);
            cv.put("size", s.size);
            cv.put("title", s.title);
            cv.put("artist", s.artist);
            cv.put("album", s.album);
            cv.put("year", s.year);
            cv.put("genre", s.genre);
            cv.put("track_number", s.trackNumber);
            cv.put("is_audiobook", s.isAudiobook ? 1 : 0);
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) {}
    }

    /** Wipes the cache so the next scan re-extracts tags for every file. */
    public void clear() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Exception ignored) {}
    }

    /** Saves what's currently loaded/playing so it can be restored after a restart. */
    public void savePlayerState(List<String> playlist, int index, int positionMs, boolean isAudiobook) {
        try {
            JSONArray arr = new JSONArray();
            for (String p : playlist) arr.put(p);
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("id", 0);
            cv.put("playlist", arr.toString());
            cv.put("idx", index);
            cv.put("position_ms", positionMs);
            cv.put("is_audiobook", isAudiobook ? 1 : 0);
            db.insertWithOnConflict(PLAYER_STATE_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) {}
    }

    /** Loads the last-saved playback state, or null if nothing's been saved yet. */
    public PlayerState loadPlayerState() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor c = db.query(PLAYER_STATE_TABLE, null, null, null, null, null, null);
            try {
                if (c.moveToFirst()) {
                    JSONArray arr = new JSONArray(c.getString(c.getColumnIndexOrThrow("playlist")));
                    List<String> playlist = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) playlist.add(arr.getString(i));
                    return new PlayerState(
                            playlist,
                            c.getInt(c.getColumnIndexOrThrow("idx")),
                            c.getInt(c.getColumnIndexOrThrow("position_ms")),
                            c.getInt(c.getColumnIndexOrThrow("is_audiobook")) != 0);
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---- song_state: favorites / custom title-artist / album art / audiobook bookmarks ----

    private void ensureStateRow(SQLiteDatabase db, String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        db.insertWithOnConflict(STATE_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Deletes the row once none of its fields hold anything worth keeping, so the table doesn't accumulate empties. */
    private void pruneStateRowIfEmpty(SQLiteDatabase db, String path) {
        db.delete(STATE_TABLE, "path=? AND is_favorite=0 AND meta_title IS NULL AND meta_artist IS NULL " +
                "AND album_art_path IS NULL AND book_pos_ms IS NULL", new String[]{path});
    }

    public Set<String> loadFavoritePaths() {
        Set<String> result = new HashSet<>();
        try {
            Cursor c = getReadableDatabase().query(STATE_TABLE, new String[]{"path"}, "is_favorite=1", null, null, null, null);
            try {
                while (c.moveToNext()) result.add(c.getString(0));
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return result;
    }

    public void setFavorite(String path, boolean favorite) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ensureStateRow(db, path);
            ContentValues cv = new ContentValues();
            cv.put("is_favorite", favorite ? 1 : 0);
            db.update(STATE_TABLE, cv, "path=?", new String[]{path});
            pruneStateRowIfEmpty(db, path);
        } catch (Exception ignored) {}
    }

    public MetaOverride getMetaOverride(String path) {
        try {
            Cursor c = getReadableDatabase().query(STATE_TABLE, new String[]{"meta_title", "meta_artist"},
                    "path=?", new String[]{path}, null, null, null);
            try {
                if (c.moveToFirst()) return new MetaOverride(c.getString(0), c.getString(1));
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void setMetaOverride(String path, String title, String artist) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ensureStateRow(db, path);
            ContentValues cv = new ContentValues();
            cv.put("meta_title", title);
            cv.put("meta_artist", artist);
            db.update(STATE_TABLE, cv, "path=?", new String[]{path});
        } catch (Exception ignored) {}
    }

    public String getAlbumArtPath(String path) {
        try {
            Cursor c = getReadableDatabase().query(STATE_TABLE, new String[]{"album_art_path"},
                    "path=?", new String[]{path}, null, null, null);
            try {
                if (c.moveToFirst()) return c.getString(0);
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void setAlbumArtPath(String path, String artPath) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ensureStateRow(db, path);
            ContentValues cv = new ContentValues();
            cv.put("album_art_path", artPath);
            db.update(STATE_TABLE, cv, "path=?", new String[]{path});
        } catch (Exception ignored) {}
    }

    /** Returns null if no bookmark (or a zeroed one) is saved for this path. */
    public Bookmark getBookmark(String path) {
        try {
            Cursor c = getReadableDatabase().query(STATE_TABLE, new String[]{"book_pos_ms", "book_dur_ms"},
                    "path=?", new String[]{path}, null, null, null);
            try {
                if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) return new Bookmark(c.getInt(0), c.getInt(1));
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Bulk-loads every saved bookmark in one query so list adapters don't hit the DB per row.
     * Keyed by path -> [book_pos_ms, book_dur_ms]. Only rows with a non-null book_pos_ms are
     * included; a null book_dur_ms is returned as 0 (mirrors getBookmark() treating it as "no
     * bookmark"). Returns an empty map on error.
     */
    public Map<String, long[]> loadAllBookmarks() {
        Map<String, long[]> result = new HashMap<>();
        try {
            Cursor c = getReadableDatabase().query(STATE_TABLE, new String[]{"path", "book_pos_ms", "book_dur_ms"},
                    "book_pos_ms IS NOT NULL", null, null, null, null);
            try {
                while (c.moveToNext()) {
                    result.put(c.getString(0), new long[]{c.getLong(1), c.getLong(2)});
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return result;
    }

    public void setBookmark(String path, int posMs, int durMs) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ensureStateRow(db, path);
            ContentValues cv = new ContentValues();
            cv.put("book_pos_ms", posMs);
            cv.put("book_dur_ms", durMs);
            db.update(STATE_TABLE, cv, "path=?", new String[]{path});
        } catch (Exception ignored) {}
    }

    /** Clears every custom title/artist/album-art override (keeps favorites and audiobook bookmarks). */
    public void clearAllMetaAndArt() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.putNull("meta_title");
            cv.putNull("meta_artist");
            cv.putNull("album_art_path");
            db.update(STATE_TABLE, cv, null, null);
            db.delete(STATE_TABLE, "is_favorite=0 AND meta_title IS NULL AND meta_artist IS NULL " +
                    "AND album_art_path IS NULL AND book_pos_ms IS NULL", null);
        } catch (Exception ignored) {}
    }

    /** Removes all per-track state for a deleted file (favorite, overrides, art, bookmark) in one go. */
    public void deleteSongState(String path) {
        try {
            getWritableDatabase().delete(STATE_TABLE, "path=?", new String[]{path});
        } catch (Exception ignored) {}
    }

    // ---- navidrome_artists: cached getArtists() response ----

    public void saveNavidromeArtists(List<com.themoon.y1.subsonic.SubsonicArtist> artists) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(ARTISTS_TABLE, null, null);
                int order = 0;
                for (com.themoon.y1.subsonic.SubsonicArtist a : artists) {
                    ContentValues cv = new ContentValues();
                    cv.put("id", a.id);
                    cv.put("name", a.name);
                    cv.put("album_count", a.albumCount);
                    cv.put("cover_art_id", a.coverArtId);
                    cv.put("index_letter", a.indexLetter);
                    cv.put("sort_order", order++);
                    db.insertWithOnConflict(ARTISTS_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "saveNavidromeArtists failed", e);
        }
    }

    public List<com.themoon.y1.subsonic.SubsonicArtist> loadNavidromeArtists() {
        List<com.themoon.y1.subsonic.SubsonicArtist> result = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().query(ARTISTS_TABLE, null, null, null, null, null, "sort_order ASC");
            try {
                while (c.moveToNext()) {
                    com.themoon.y1.subsonic.SubsonicArtist a = new com.themoon.y1.subsonic.SubsonicArtist();
                    a.id = c.getString(c.getColumnIndexOrThrow("id"));
                    a.name = c.getString(c.getColumnIndexOrThrow("name"));
                    a.albumCount = c.getInt(c.getColumnIndexOrThrow("album_count"));
                    a.coverArtId = c.getString(c.getColumnIndexOrThrow("cover_art_id"));
                    a.indexLetter = c.getString(c.getColumnIndexOrThrow("index_letter"));
                    result.add(a);
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
        return result;
    }

    public void clearNavidromeArtists() {
        try {
            getWritableDatabase().delete(ARTISTS_TABLE, null, null);
        } catch (Exception ignored) {}
    }
}
