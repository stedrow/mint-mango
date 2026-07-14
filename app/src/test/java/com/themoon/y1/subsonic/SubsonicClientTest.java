package com.themoon.y1.subsonic;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers SubsonicClient's JSON parsing and comparison helpers — widened from private
 * to package-private so this test can hit them directly rather than driving them
 * through real network calls.
 */
public class SubsonicClientTest {

    @Test
    public void parseArtists_flattensIndexesAndFillsDefaults() throws Exception {
        JSONObject root = new JSONObject(
                "{\"subsonic-response\":{\"status\":\"ok\",\"artists\":{\"index\":["
                        + "{\"name\":\"A\",\"artist\":[{\"id\":\"1\",\"name\":\"Air\",\"albumCount\":3,\"coverArt\":\"c1\"}]},"
                        + "{\"name\":\"B\",\"artist\":[{\"id\":\"2\",\"name\":\"Beck\"}]}"
                        + "]}}}");

        List<SubsonicArtist> artists = SubsonicClient.parseArtists(root);

        assertEquals(2, artists.size());
        assertEquals("1", artists.get(0).id);
        assertEquals("Air", artists.get(0).name);
        assertEquals(3, artists.get(0).albumCount);
        assertEquals("c1", artists.get(0).coverArtId);
        assertEquals("A", artists.get(0).indexLetter);
        // no albumCount/coverArt in source -> defaults
        assertEquals(0, artists.get(1).albumCount);
        assertNull(artists.get(1).coverArtId);
    }

    @Test
    public void parseArtists_skipsIndexWithNoArtistArray() throws Exception {
        JSONObject root = new JSONObject(
                "{\"subsonic-response\":{\"status\":\"ok\",\"artists\":{\"index\":["
                        + "{\"name\":\"#\"}]}}}");

        assertTrue(SubsonicClient.parseArtists(root).isEmpty());
    }

    @Test(expected = Exception.class)
    public void parseArtists_throwsWhenStatusNotOk() throws Exception {
        JSONObject root = new JSONObject("{\"subsonic-response\":{\"status\":\"failed\"}}");
        SubsonicClient.parseArtists(root);
    }

    @Test
    public void artistListsEqual_detectsSameAndDifferentLists() throws Exception {
        JSONObject root = new JSONObject(
                "{\"subsonic-response\":{\"status\":\"ok\",\"artists\":{\"index\":["
                        + "{\"name\":\"A\",\"artist\":[{\"id\":\"1\",\"name\":\"Air\",\"albumCount\":3}]}"
                        + "]}}}");
        List<SubsonicArtist> a = SubsonicClient.parseArtists(root);
        List<SubsonicArtist> b = SubsonicClient.parseArtists(root);
        assertTrue(SubsonicClient.artistListsEqual(a, b));

        b.get(0).albumCount = 4;
        assertTrue(!SubsonicClient.artistListsEqual(a, b));

        assertTrue(!SubsonicClient.artistListsEqual(a, new ArrayList<>()));
    }

    @Test
    public void parseAlbumSummary_prefersAlbumFieldFallbackAndFirstReleaseType() throws Exception {
        // "name" absent -> falls back to "album"
        JSONObject a = new JSONObject(
                "{\"id\":\"al1\",\"album\":\"Fallback Name\",\"artist\":\"Some Artist\","
                        + "\"releaseTypes\":[\"single\",\"ep\"]}");

        SubsonicAlbum album = SubsonicClient.parseAlbumSummary(a);

        assertEquals("al1", album.id);
        assertEquals("Fallback Name", album.name);
        assertEquals("Some Artist", album.artistName);
        assertEquals("single", album.releaseType);
    }

    @Test
    public void parseSong_usesAlbumFallbacksWhenFieldsMissing() throws Exception {
        JSONObject s = new JSONObject("{\"id\":\"s1\",\"title\":\"Track One\"}");

        SubsonicSong song = SubsonicClient.parseSong(s, "Fallback Album", "Fallback Artist");

        assertEquals("s1", song.id);
        assertEquals("Track One", song.title);
        assertEquals("Unknown", song.artist);
        assertEquals("Fallback Album", song.album);
        assertEquals("Fallback Artist", song.albumArtist);
        assertEquals("mp3", song.suffix);
    }

    @Test
    public void extractError_returnsServerMessageOrFallback() throws Exception {
        JSONObject withError = new JSONObject(
                "{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"Wrong password\"}}}");
        assertEquals("Wrong password", SubsonicClient.extractError(withError));

        JSONObject malformed = new JSONObject("{\"nope\":true}");
        assertEquals("Unknown server error", SubsonicClient.extractError(malformed));
    }

    @Test
    public void getLocalPath_sanitizesIllegalFilesystemCharsAndPrefixesTrack() {
        SubsonicSong song = new SubsonicSong();
        song.title = "Track: Name?";
        song.artist = "Artist/Name";
        song.album = "Album*Name";
        song.track = 3;
        song.suffix = "flac";

        String path = song.getLocalPath();

        assertEquals(SubsonicSong.DOWNLOAD_ROOT + "/Artist_Name/Album_Name/03 - Track_ Name_.flac", path);
    }
}
