package com.themoon.y1;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers AapPacketFraming's packet-boundary search (finds the {0x04,0x00,0x04,0x00} AAP magic
 * marker in a byte stream) -- the framing logic AapService/AirPods ear-detection L2CAP reader
 * depends on to resync after a partial read.
 */
public class AapServiceMagicTest {

    private static final byte[] MAGIC = {0x04, 0x00, 0x04, 0x00};

    private static int indexOfMagic(byte[] data, int from) {
        return AapPacketFraming.indexOfMagic(MAGIC, data, from);
    }

    @Test
    public void findsMagicAtStart() {
        byte[] data = {0x04, 0x00, 0x04, 0x00, 0x01, 0x02};
        assertEquals(0, indexOfMagic(data, 0));
    }

    @Test
    public void findsMagicMidBuffer() {
        byte[] data = {0x11, 0x22, 0x33, 0x04, 0x00, 0x04, 0x00, 0x55};
        assertEquals(3, indexOfMagic(data, 0));
    }

    @Test
    public void respectsFromOffset_skipsEarlierMatch() {
        byte[] data = {0x04, 0x00, 0x04, 0x00, 0x11, 0x04, 0x00, 0x04, 0x00};
        assertEquals(5, indexOfMagic(data, 1));
    }

    @Test
    public void noMagicPresent_returnsNegativeOne() {
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05};
        assertEquals(-1, indexOfMagic(data, 0));
    }

    @Test
    public void partialMagicAtEnd_returnsNegativeOne() {
        // Only the first 2 of 4 magic bytes present at the tail -- not a full match.
        byte[] data = {0x11, 0x22, 0x04, 0x00};
        assertEquals(-1, indexOfMagic(data, 0));
    }

    @Test
    public void emptyBuffer_returnsNegativeOne() {
        assertEquals(-1, indexOfMagic(new byte[0], 0));
    }

    @Test
    public void negativeFromOffset_clampedToZero() {
        byte[] data = {0x04, 0x00, 0x04, 0x00};
        assertEquals(0, indexOfMagic(data, -5));
    }

    @Test
    public void doesNotFalsePositiveOnPartialByteSequence() {
        // 0x04,0x00,0x04 followed by a non-matching 4th byte should not match.
        byte[] data = {0x04, 0x00, 0x04, 0x01, 0x04, 0x00, 0x04, 0x00};
        assertEquals(4, indexOfMagic(data, 0));
    }
}
