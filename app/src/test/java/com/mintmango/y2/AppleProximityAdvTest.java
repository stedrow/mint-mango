package com.mintmango.y2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Covers the AD-structure walk that pulls Apple's proximity-pairing message out of a raw
 * advertisement. Payloads are real captures from AirPods Pro 3 in known pod positions.
 */
public class AppleProximityAdvTest {

    /** Wraps a proximity payload in the manufacturer-data AD structure, zero-padded like Android's. */
    private static byte[] advert(String payloadHex) {
        byte[] out = new byte[62];
        int i = 0;
        out[i++] = 0x02; out[i++] = 0x01; out[i++] = 0x1A;       // flags, an AD we must skip past
        out[i++] = (byte) (payloadHex.length() / 2 + 3);          // len: type + company id + payload
        out[i++] = (byte) 0xFF; out[i++] = 0x4C; out[i++] = 0x00; // manufacturer data, Apple
        for (int c = 0; c < payloadHex.length(); c += 2) {
            out[i++] = (byte) Integer.parseInt(payloadHex.substring(c, c + 2), 16);
        }
        return out;
    }

    private static final String BOTH_IN_EARS =
            "07190127202b888f110004f2e0ac89ec06c61af0f78f514c5b2500";

    @Test
    public void extractsProximityPayloadPastAnotherAdStructure() {
        byte[] p = AapService.appleProximityPayload(advert(BOTH_IN_EARS));
        assertEquals(27, p.length);
        assertEquals(0x07, p[0] & 0xFF);
        assertEquals(0x2b, p[5] & 0xFF); // status byte the ear bits live in
    }

    @Test
    public void ignoresNonAppleShortFormAndTruncatedAdverts() {
        assertNull(AapService.appleProximityPayload(
                advert("071106b3002e66b5e3314a55f94a3706330c00"))); // short form, no state
        assertNull(AapService.appleProximityPayload(new byte[]{0x1E, (byte) 0xFF, 0x4C}));
        byte[] notApple = advert(BOTH_IN_EARS);
        notApple[5] = 0x75; // some other company id
        assertNull(AapService.appleProximityPayload(notApple));
    }

    @Test
    public void batteryNibblesDecodeToPercentAndUnknown() {
        assertEquals(80, AapService.batteryPercent(0x08));
        assertEquals(100, AapService.batteryPercent(0x0A));
        assertEquals(AapService.BATTERY_UNKNOWN, AapService.batteryPercent(0x0F));
    }
}
