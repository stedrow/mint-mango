package com.themoon.y1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the HTTP Range-header parsing added to Y1WebServer for the embedded file manager's
 * audio seeking / partial-download support. Verified against the real byte math on-device
 * (curl with Range headers against a live track) during the KitKat performance review; this
 * pins that behavior down as a regression test.
 */
public class Y1WebServerRangeTest {

    private static final long FILE_LEN = 4441992; // real track size used for the on-device check

    @Test
    public void noRangeHeader_servesWholeFileNonPartial() {
        Y1WebServer.RangeResult r = Y1WebServer.parseRange(null, FILE_LEN);
        assertFalse(r.partial);
        assertEquals(0, r.start);
        assertEquals(FILE_LEN - 1, r.end);
    }

    @Test
    public void headerNotStartingWithBytes_ignored() {
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("frobnicate=0-99", FILE_LEN);
        assertFalse(r.partial);
        assertEquals(0, r.start);
        assertEquals(FILE_LEN - 1, r.end);
    }

    @Test
    public void explicitStartEnd_returnsExactSlice() {
        // Matches the on-device curl check: "Range: bytes=0-999" -> 206, Content-Length 1000
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=0-999", FILE_LEN);
        assertTrue(r.partial);
        assertEquals(0, r.start);
        assertEquals(999, r.end);
        assertEquals(1000, r.end - r.start + 1);
    }

    @Test
    public void suffixRange_returnsLastNBytes() {
        // Matches the on-device curl check: "Range: bytes=-500" -> 206,
        // Content-Range bytes 4441492-4441991/4441992
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=-500", FILE_LEN);
        assertTrue(r.partial);
        assertEquals(FILE_LEN - 500, r.start);
        assertEquals(FILE_LEN - 1, r.end);
        assertEquals(500, r.end - r.start + 1);
    }

    @Test
    public void suffixRangeLargerThanFile_clampsToWholeFile() {
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=-999999999", FILE_LEN);
        assertTrue(r.partial);
        assertEquals(0, r.start);
        assertEquals(FILE_LEN - 1, r.end);
    }

    @Test
    public void openEndedRange_extendsToEndOfFile() {
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=100-", FILE_LEN);
        assertTrue(r.partial);
        assertEquals(100, r.start);
        assertEquals(FILE_LEN - 1, r.end);
    }

    @Test
    public void endBeyondFileLength_clampsToLastByte() {
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=0-99999999999", FILE_LEN);
        assertTrue(r.partial);
        assertEquals(0, r.start);
        assertEquals(FILE_LEN - 1, r.end);
    }

    @Test
    public void startGreaterThanEnd_notPartial() {
        // partial=false means the caller serves the whole file and never reads start/end
        // (see the GET /api/file handler), so their exact values here are unspecified.
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=500-100", FILE_LEN);
        assertFalse(r.partial);
    }

    @Test
    public void malformedNumber_fallsBackToWholeFile() {
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=abc-def", FILE_LEN);
        assertFalse(r.partial);
        assertEquals(0, r.start);
        assertEquals(FILE_LEN - 1, r.end);
    }

    @Test
    public void noDash_ignored() {
        Y1WebServer.RangeResult r = Y1WebServer.parseRange("bytes=12345", FILE_LEN);
        assertFalse(r.partial);
        assertEquals(0, r.start);
        assertEquals(FILE_LEN - 1, r.end);
    }
}
