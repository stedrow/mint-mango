package com.themoon.y1.cast;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Minimal encoder/decoder for the Cast V2 {@code CastMessage} protobuf, plus the
 * 4-byte big-endian length framing used on the wire. Hand-rolled rather than pulled
 * in via the protobuf-gradle-plugin: the whole protocol is this one flat message, so
 * a code-gen toolchain would be far more weight than the ~7 fields justify.
 *
 * CastMessage (cast_channel.proto), all string payloads for our use:
 *   1 protocol_version  varint   (always CASTV2_1_0 = 0)
 *   2 source_id         string
 *   3 destination_id    string
 *   4 namespace         string
 *   5 payload_type      varint   (STRING = 0)
 *   6 payload_utf8      string   (the JSON message)
 *   7 payload_binary    bytes    (unused here)
 *
 * Frame = uint32be length prefix + serialized CastMessage.
 */
final class CastMessageCodec {

    /** Parsed view of an inbound CastMessage — only the fields we act on. */
    static final class Message {
        final String namespace;
        final String sourceId;
        final String payloadUtf8;

        Message(String namespace, String sourceId, String payloadUtf8) {
            this.namespace = namespace;
            this.sourceId = sourceId;
            this.payloadUtf8 = payloadUtf8;
        }
    }

    private CastMessageCodec() {}

    /** Serialize + length-frame a STRING CastMessage and write it to the socket stream. */
    static void writeFrame(OutputStream rawOut, String sourceId, String destinationId,
                           String namespace, String payloadJson) throws IOException {
        byte[] body = encode(sourceId, destinationId, namespace, payloadJson);
        DataOutputStream out = new DataOutputStream(rawOut);
        out.writeInt(body.length); // uint32 big-endian
        out.write(body);
        out.flush();
    }

    /** Blocking read of exactly one length-framed CastMessage. Returns null on clean EOF. */
    static Message readFrame(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        int len;
        try {
            len = in.readInt();
        } catch (java.io.EOFException eof) {
            return null;
        }
        if (len < 0 || len > 8 * 1024 * 1024) {
            throw new IOException("Bogus Cast frame length: " + len);
        }
        byte[] body = new byte[len];
        in.readFully(body);
        return decode(body);
    }

    // ── CastMessage encode ──────────────────────────────────────────────────────

    private static byte[] encode(String sourceId, String destinationId,
                                 String namespace, String payloadJson) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarintField(b, 1, 0);                 // protocol_version = CASTV2_1_0
        writeStringField(b, 2, sourceId);
        writeStringField(b, 3, destinationId);
        writeStringField(b, 4, namespace);
        writeVarintField(b, 5, 0);                 // payload_type = STRING
        writeStringField(b, 6, payloadJson == null ? "" : payloadJson);
        return b.toByteArray();
    }

    private static void writeVarintField(ByteArrayOutputStream b, int fieldNum, long value) {
        b.write((fieldNum << 3) | 0); // wire type 0 = varint
        writeVarint(b, value);
    }

    private static void writeStringField(ByteArrayOutputStream b, int fieldNum, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF-8");
        b.write((fieldNum << 3) | 2); // wire type 2 = length-delimited
        writeVarint(b, bytes.length);
        b.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(ByteArrayOutputStream b, long value) {
        long v = value;
        while (true) {
            int bits = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                b.write(bits | 0x80);
            } else {
                b.write(bits);
                break;
            }
        }
    }

    // ── CastMessage decode ──────────────────────────────────────────────────────

    private static Message decode(byte[] data) throws IOException {
        String namespace = null, sourceId = null, payloadUtf8 = null;
        int[] pos = {0};
        while (pos[0] < data.length) {
            long tag = readVarint(data, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 0x7);
            if (wire == 0) { // varint
                readVarint(data, pos); // protocol_version / payload_type — ignored
            } else if (wire == 2) { // length-delimited
                int len = (int) readVarint(data, pos);
                if (len < 0 || pos[0] + len > data.length) throw new IOException("Truncated Cast field");
                String s = new String(data, pos[0], len, "UTF-8");
                pos[0] += len;
                switch (field) {
                    case 2: sourceId = s; break;
                    case 4: namespace = s; break;
                    case 6: payloadUtf8 = s; break;
                    default: break; // destination_id / payload_binary — not needed
                }
            } else if (wire == 5) {
                pos[0] += 4; // fixed32 — not used by CastMessage, skip defensively
            } else if (wire == 1) {
                pos[0] += 8; // fixed64 — skip defensively
            } else {
                throw new IOException("Unsupported protobuf wire type: " + wire);
            }
        }
        return new Message(namespace, sourceId, payloadUtf8);
    }

    private static long readVarint(byte[] data, int[] pos) throws IOException {
        long result = 0;
        int shift = 0;
        while (shift < 64) {
            if (pos[0] >= data.length) throw new IOException("Truncated varint");
            int b = data[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("Varint too long");
    }
}
