package io.circuitdrift.androidialup.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FrameCodecTest {
    @Test
    void roundTripPreservesFrozenFields() {
        byte[] callId = new byte[16];
        byte[] sessionId = new byte[16];
        for (int i = 0; i < 16; i++) {
            callId[i] = (byte) i;
            sessionId[i] = (byte) (i + 16);
        }
        var frame = new AduFrame(FrameKind.HELLO, 0x1234, callId, sessionId, 0x10203040L, "hello".getBytes());
        byte[] encoded = FrameCodec.encode(frame);
        var decoded = FrameCodec.decode(encoded, FrameCodec.MAX_PAYLOAD);
        assertEquals(frame, decoded.frame());
        assertEquals(encoded.length, decoded.bytesConsumed());
    }

    @Test
    void headerMatchesNetworkByteOrderAndFrozen54ByteSize() {
        var frame = new AduFrame(FrameKind.PING, 0x0102, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 0x01020304L, new byte[0]);
        byte[] encoded = FrameCodec.encode(frame);
        assertEquals(54, FrameCodec.FIXED_HEADER_LEN);
        assertArrayEquals(new byte[]{'A','D','U','P'}, Arrays.copyOfRange(encoded, 0, 4));
        assertEquals(1, Byte.toUnsignedInt(encoded[4]));
        assertArrayEquals(new byte[]{0x01, 0x02}, Arrays.copyOfRange(encoded, 6, 8));
        assertArrayEquals(new byte[]{0x00, 0x36}, Arrays.copyOfRange(encoded, 8, 10));
        assertArrayEquals(new byte[]{0x01,0x02,0x03,0x04}, Arrays.copyOfRange(encoded, 46, 50));
    }

    @Test
    void rejectsBadMagicVersionReservedAndUnknownKind() {
        byte[] base = FrameCodec.encode(AduFrame.simple(FrameKind.PING));

        byte[] badMagic = base.clone();
        badMagic[0] = 'N';
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(badMagic, FrameCodec.MAX_PAYLOAD));

        byte[] badVersion = base.clone();
        badVersion[4] = 2;
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(badVersion, FrameCodec.MAX_PAYLOAD));

        byte[] badKind = base.clone();
        badKind[5] = (byte) 0x7f;
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(badKind, FrameCodec.MAX_PAYLOAD));

        byte[] badReserved = base.clone();
        badReserved[53] = 1;
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(badReserved, FrameCodec.MAX_PAYLOAD));
    }

    @Test
    void rejectsTruncationAndPayloadOverNegotiatedLimit() {
        byte[] encoded = FrameCodec.encode(new AduFrame(
                FrameKind.DATA_BYTES, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 0, new byte[]{1,2,3,4,5}));
        assertThrows(ProtocolException.class,
                () -> FrameCodec.decode(Arrays.copyOf(encoded, encoded.length - 1), FrameCodec.MAX_PAYLOAD));
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(encoded, 4));
    }

    @Test
    void idsAndPayloadAreDefensivelyCopied() {
        byte[] id = new byte[16];
        byte[] payload = new byte[]{1,2,3};
        var frame = new AduFrame(FrameKind.PONG, 0, id, id, 1, payload);
        id[0] = 99;
        payload[0] = 99;
        assertEquals(0, frame.callId()[0]);
        assertEquals(1, frame.payload()[0]);
        byte[] returned = frame.payload();
        returned[1] = 88;
        assertEquals(2, frame.payload()[1]);
    }
}
