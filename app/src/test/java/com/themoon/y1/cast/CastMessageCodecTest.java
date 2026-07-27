package com.themoon.y1.cast;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers CastMessageCodec's hand-rolled CastMessage protobuf encode/decode and the
 * 4-byte length framing, since a bug here silently breaks every Cast session.
 */
public class CastMessageCodecTest {

    @Test
    public void roundTrip_preservesNamespaceSourceAndPayload() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastMessageCodec.writeFrame(out, "sender-0", "receiver-0",
                "urn:x-cast:com.google.cast.tp.connection", "{\"type\":\"PING\"}");

        CastMessageCodec.Message msg = CastMessageCodec.readFrame(
                new ByteArrayInputStream(out.toByteArray()));

        assertEquals("urn:x-cast:com.google.cast.tp.connection", msg.namespace);
        assertEquals("sender-0", msg.sourceId);
        assertEquals("{\"type\":\"PING\"}", msg.payloadUtf8);
    }

    @Test
    public void roundTrip_handlesUnicodePayloadAndLongStrings() throws IOException {
        String longNamespace = "urn:x-cast:" + "a".repeat(300); // forces multi-byte varint length
        String payload = "{\"title\":\"日本語 — café\"}";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastMessageCodec.writeFrame(out, "src", "dst", longNamespace, payload);

        CastMessageCodec.Message msg = CastMessageCodec.readFrame(
                new ByteArrayInputStream(out.toByteArray()));

        assertEquals(longNamespace, msg.namespace);
        assertEquals(payload, msg.payloadUtf8);
    }

    @Test
    public void nullPayload_encodesAsEmptyString() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastMessageCodec.writeFrame(out, "src", "dst", "ns", null);

        CastMessageCodec.Message msg = CastMessageCodec.readFrame(
                new ByteArrayInputStream(out.toByteArray()));

        assertEquals("", msg.payloadUtf8);
    }

    @Test
    public void cleanEof_returnsNull() throws IOException {
        assertNull(CastMessageCodec.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void bogusOversizedLength_throws() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x7F); out.write(0xFF); out.write(0xFF); out.write(0xFF); // huge length prefix
        try {
            CastMessageCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));
            fail("expected IOException for oversized frame length");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Bogus"));
        }
    }

    @Test
    public void negativeLength_throws() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xFF); out.write(0xFF); out.write(0xFF); // -1 as int32
        try {
            CastMessageCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));
            fail("expected IOException for negative frame length");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Bogus"));
        }
    }
}
