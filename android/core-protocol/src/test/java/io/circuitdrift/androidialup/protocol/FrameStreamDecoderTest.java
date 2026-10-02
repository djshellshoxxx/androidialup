package io.circuitdrift.androidialup.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors prototype/python/tests/test_stream.py plus the error paths of stream.py. */
class FrameStreamDecoderTest {
    private static AduFrame frame(FrameKind kind, long requestId, byte[] payload) {
        return new AduFrame(kind, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, requestId, payload);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void partialFrameWaitsForMoreBytes() {
        byte[] encoded = FrameCodec.encode(frame(FrameKind.PING, 0, "abc".getBytes()));
        var decoder = new FrameStreamDecoder();
        assertEquals(List.of(), decoder.feed(Arrays.copyOfRange(encoded, 0, 10)));
        assertEquals(10, decoder.bufferedBytes());
        List<AduFrame> frames = decoder.feed(Arrays.copyOfRange(encoded, 10, encoded.length));
        assertEquals(1, frames.size());
        assertArrayEquals("abc".getBytes(), frames.get(0).payload());
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    void splitHeaderThenSplitPayload() {
        byte[] encoded = FrameCodec.encode(frame(FrameKind.DATA_BYTES, 0, "payload".getBytes()));
        var decoder = new FrameStreamDecoder();
        assertEquals(List.of(), decoder.feed(Arrays.copyOfRange(encoded, 0, 53)));
        assertEquals(List.of(), decoder.feed(Arrays.copyOfRange(encoded, 53, 56)));
        assertEquals(56, decoder.bufferedBytes());
        var frames = decoder.feed(Arrays.copyOfRange(encoded, 56, encoded.length));
        assertEquals(List.of(frame(FrameKind.DATA_BYTES, 0, "payload".getBytes())), frames);
    }

    @Test
    void multipleFramesInOneRead() {
        AduFrame a = frame(FrameKind.PING, 1, new byte[0]);
        AduFrame b = frame(FrameKind.PONG, 2, new byte[0]);
        var decoder = new FrameStreamDecoder();
        assertEquals(List.of(a, b), decoder.feed(concat(FrameCodec.encode(a), FrameCodec.encode(b))));
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    void twoFramesPlusPartialThirdKeepsRemainder() {
        AduFrame a = frame(FrameKind.PING, 1, new byte[]{1});
        AduFrame b = frame(FrameKind.PONG, 2, new byte[]{2, 3});
        AduFrame c = frame(FrameKind.DATA_BYTES, 3, new byte[]{4, 5, 6});
        byte[] all = concat(concat(FrameCodec.encode(a), FrameCodec.encode(b)), FrameCodec.encode(c));
        var decoder = new FrameStreamDecoder();
        assertEquals(List.of(a, b), decoder.feed(Arrays.copyOf(all, all.length - 2)));
        assertEquals(FrameCodec.FIXED_HEADER_LEN + 1, decoder.bufferedBytes());
        assertEquals(List.of(c), decoder.feed(Arrays.copyOfRange(all, all.length - 2, all.length)));
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    void byteAtATime() {
        AduFrame f = frame(FrameKind.DATA_BYTES, 0, "abcdef".getBytes());
        var decoder = new FrameStreamDecoder();
        List<AduFrame> out = new ArrayList<>();
        for (byte b : FrameCodec.encode(f)) out.addAll(decoder.feed(new byte[]{b}));
        assertEquals(List.of(f), out);
    }

    @Test
    void emptyFeedIsNoOp() {
        var decoder = new FrameStreamDecoder();
        assertEquals(List.of(), decoder.feed(new byte[0]));
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    void defaultBufferLimitIsTwiceMaxFrame() {
        assertEquals((FrameCodec.FIXED_HEADER_LEN + FrameCodec.MAX_PAYLOAD) * 2, new FrameStreamDecoder().maxBuffer());
        assertEquals(FrameCodec.MAX_PAYLOAD, new FrameStreamDecoder().maxPayload());
        assertEquals((FrameCodec.FIXED_HEADER_LEN + 100) * 2, new FrameStreamDecoder(100).maxBuffer());
        // max payload is clamped to the protocol maximum
        assertEquals(FrameCodec.MAX_PAYLOAD, new FrameStreamDecoder(FrameCodec.MAX_PAYLOAD * 4).maxPayload());
        assertEquals(64, new FrameStreamDecoder(100, 64).maxBuffer());
    }

    @Test
    void bufferLimitIsBounded() {
        var decoder = new FrameStreamDecoder(FrameCodec.MAX_PAYLOAD, 64);
        byte[] big = new byte[65];
        Arrays.fill(big, (byte) 'x');
        var ex = assertThrows(ProtocolException.class, () -> decoder.feed(big));
        assertTrue(ex.getMessage().contains("buffer"));
        // rejected data is not buffered
        assertEquals(0, decoder.bufferedBytes());
        // the limit also applies cumulatively across feeds
        decoder.feed(new byte[40]);
        assertThrows(ProtocolException.class, () -> decoder.feed(new byte[25]));
        assertEquals(40, decoder.bufferedBytes());
    }

    @Test
    void rejectsHeaderShorterThanFixed() {
        byte[] encoded = FrameCodec.encode(frame(FrameKind.PING, 0, new byte[0]));
        encoded[8] = 0;
        encoded[9] = 10;
        var ex = assertThrows(ProtocolException.class, () -> new FrameStreamDecoder().feed(encoded));
        assertTrue(ex.getMessage().contains("shorter"));
    }

    @Test
    void rejectsHeaderExtensions() {
        byte[] encoded = FrameCodec.encode(frame(FrameKind.PING, 0, new byte[0]));
        encoded[9] = 55;
        var ex = assertThrows(ProtocolException.class, () -> new FrameStreamDecoder().feed(encoded));
        assertTrue(ex.getMessage().contains("extensions"));
    }

    @Test
    void rejectsPayloadOverNegotiatedMaxWithoutWaitingForPayload() {
        byte[] encoded = FrameCodec.encode(frame(FrameKind.DATA_BYTES, 0, new byte[16]));
        var decoder = new FrameStreamDecoder(8);
        var ex = assertThrows(ProtocolException.class,
                () -> decoder.feed(Arrays.copyOf(encoded, FrameCodec.FIXED_HEADER_LEN)));
        assertTrue(ex.getMessage().contains("negotiated"));
    }

    @Test
    void rejectsFrameLargerThanBufferLimit() {
        // payload within negotiated max but the whole frame cannot ever fit in the buffer
        byte[] encoded = FrameCodec.encode(frame(FrameKind.DATA_BYTES, 0, new byte[32]));
        var decoder = new FrameStreamDecoder(1000, 80);
        var ex = assertThrows(ProtocolException.class,
                () -> decoder.feed(Arrays.copyOf(encoded, FrameCodec.FIXED_HEADER_LEN)));
        assertTrue(ex.getMessage().contains("frame exceeds"));
    }

    @Test
    void rejectsBadMagicAndUnknownKindViaFrameCodec() {
        byte[] encoded = FrameCodec.encode(frame(FrameKind.PING, 0, new byte[0]));
        byte[] badMagic = encoded.clone();
        badMagic[0] = 'X';
        assertThrows(ProtocolException.class, () -> new FrameStreamDecoder().feed(badMagic));
        byte[] badKind = encoded.clone();
        badKind[5] = (byte) 0x7f;
        assertThrows(ProtocolException.class, () -> new FrameStreamDecoder().feed(badKind));
    }

    @Test
    void framesCarryTypedPayloadsThroughPayloadCodec() {
        var ping = new Ping(1, 2);
        AduFrame f = frame(PayloadCodec.kindFor(ping), 7, PayloadCodec.encode(ping));
        var decoded = new FrameStreamDecoder().feed(FrameCodec.encode(f));
        assertEquals(1, decoded.size());
        assertEquals(ping, PayloadCodec.decode(decoded.get(0).kind(), decoded.get(0).payload()));
    }
}
