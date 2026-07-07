package com.themoon.y1;

/**
 * Pure packet-framing helper for AapService's AAP (Apple Accessory Protocol) L2CAP reader.
 * Kept in its own class (no Android dependencies) so it's plain-JVM unit-testable --
 * AapService itself can't be class-loaded outside a real device/Robolectric because of an
 * unrelated static Handler field.
 */
final class AapPacketFraming {
    private AapPacketFraming() {}

    static int indexOfMagic(byte[] magic, byte[] data, int from) {
        for (int i = Math.max(from, 0); i + magic.length <= data.length; i++) {
            boolean match = true;
            for (int j = 0; j < magic.length; j++) {
                if (data[i + j] != magic[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}
