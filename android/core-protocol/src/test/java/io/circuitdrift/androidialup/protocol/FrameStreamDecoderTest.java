package io.circuitdrift.androidialup.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameStreamDecoderTest {
    @Test
    void partialFrameWaitsForMoreBytes() {
        byte[] encoded = FrameCodec.encode(new AduFrame(FrameKind.PING, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 7, new byte[]{1,2,3}));
        var decoder = new FrameStreamDecoder();
        assertTrue(decoder.feed(java.util.Arrays.copyOfRange(encoded, 0, 10)).isEmpty());
        var frames = decoder.feed(java.util.Arrays.copyOfRange(encoded, 10, encoded.length));
        assertEquals(1, frames.size());
        assertArrayEquals(new byte[]{1,2,3}, frames.get(0).payload());
    }

    @Test
    void multipleFramesInOneReadAreReturnedInOrder() {
        var a = new AduFrame(FrameKind.PING, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 1, new byte[0]);
        var b = new AduFrame(FrameKind.PONG, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 2, new byte[0]);
        byte[] ea = FrameCodec.encode(a), eb = FrameCodec.encode(b);
        byte[] joined = new byte[ea.length + eb.length];
        System.arraycopy(ea, 0, joined, 0, ea.length);
        System.arraycopy(eb, 0, joined, ea.length, eb.length);
        assertEquals(List.of(a,b), new FrameStreamDecoder().feed(joined));
    }

    @Test
    void byteAtATimeStillDecodesExactlyOnce() {
        var frame = new AduFrame(FrameKind.DATA_BYTES, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 0, new byte[]{9,8,7,6});
        var decoder = new FrameStreamDecoder();
        var out = new ArrayList<AduFrame>();
        for (byte value : FrameCodec.encode(frame)) out.addAll(decoder.feed(new byte[]{value}));
        assertEquals(List.of(frame), out);
    }

    @Test
    void hardBufferLimitRejectsUnboundedInput() {
        var decoder = new FrameStreamDecoder(FrameCodec.MAX_PAYLOAD, 64);
        assertThrows(ProtocolException.class, () -> decoder.feed(new byte[65]));
    }


    @Test
    void aggregateOfValidFramesMayExceedBoundedBuffer() {
        var frames = List.of(
                AduFrame.simple(FrameKind.PING),
                AduFrame.simple(FrameKind.PONG),
                AduFrame.simple(FrameKind.PING),
                AduFrame.simple(FrameKind.PONG));
        var bytes = new ByteArrayOutputStream();
        frames.forEach(frame -> bytes.writeBytes(FrameCodec.encode(frame)));
        var decoder = new FrameStreamDecoder(FrameCodec.MAX_PAYLOAD, 64);
        assertEquals(frames, decoder.feed(bytes.toByteArray()));
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    void partialThenLargeBatchStillDecodesFrames() {
        var frames = List.of(AduFrame.simple(FrameKind.PING),
                AduFrame.simple(FrameKind.PONG), AduFrame.simple(FrameKind.PING));
        var bytes = new ByteArrayOutputStream();
        frames.forEach(frame -> bytes.writeBytes(FrameCodec.encode(frame)));
        byte[] encoded = bytes.toByteArray();
        var decoder = new FrameStreamDecoder(FrameCodec.MAX_PAYLOAD, 64);
        assertTrue(decoder.feed(java.util.Arrays.copyOfRange(encoded, 0, 20)).isEmpty());
        assertEquals(frames, decoder.feed(java.util.Arrays.copyOfRange(encoded, 20, encoded.length)));
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    void oversizedIndividualFrameStillExceedsBoundedBuffer() {
        var decoder = new FrameStreamDecoder(FrameCodec.MAX_PAYLOAD, 64);
        byte[] encoded = FrameCodec.encode(new AduFrame(
                FrameKind.PING, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 0, new byte[11]));
        var failure = assertThrows(ProtocolException.class, () -> decoder.feed(encoded));
        assertTrue(failure.getMessage().contains("frame exceeds stream buffer"));
    }

    @Test
    void declaredOversizePayloadIsRejectedBeforeAllocation() {
        byte[] header = FrameCodec.encode(AduFrame.simple(FrameKind.PING));
        header[10] = 0x00;
        header[11] = 0x10;
        header[12] = 0x00;
        header[13] = 0x01;
        var decoder = new FrameStreamDecoder(1024, 4096);
        assertThrows(ProtocolException.class, () -> decoder.feed(header));
    }
}
