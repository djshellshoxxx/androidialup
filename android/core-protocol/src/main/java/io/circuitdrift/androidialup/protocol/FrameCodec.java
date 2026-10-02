package io.circuitdrift.androidialup.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class FrameCodec {
    public static final int PROTOCOL_VERSION = 1;
    public static final int FIXED_HEADER_LEN = 54;
    public static final int MAX_PAYLOAD = 1024 * 1024;
    private static final byte[] MAGIC = "ADUP".getBytes(StandardCharsets.US_ASCII);

    private FrameCodec() {}

    public record Decoded(AduFrame frame, int bytesConsumed) {}

    public static byte[] encode(AduFrame frame) {
        byte[] payload = frame.payload();
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload exceeds protocol maximum");
        ByteBuffer out = ByteBuffer.allocate(FIXED_HEADER_LEN + payload.length).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC);
        out.put((byte) PROTOCOL_VERSION);
        out.put((byte) frame.kind().value());
        out.putShort((short) frame.flags());
        out.putShort((short) FIXED_HEADER_LEN);
        out.putInt(payload.length);
        out.put(frame.callId());
        out.put(frame.sessionId());
        out.putInt((int) frame.requestId());
        out.putInt(0);
        out.put(payload);
        return out.array();
    }

    public static Decoded decode(byte[] data, int maxPayload) {
        if (data.length < FIXED_HEADER_LEN) throw new ProtocolException("incomplete fixed header");
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new ProtocolException("invalid magic");

        int version = Byte.toUnsignedInt(in.get());
        if (version != PROTOCOL_VERSION) throw new ProtocolException("unsupported version " + version);
        FrameKind kind = FrameKind.fromValue(Byte.toUnsignedInt(in.get()));
        int flags = Short.toUnsignedInt(in.getShort());
        int headerLen = Short.toUnsignedInt(in.getShort());
        long payloadLenLong = Integer.toUnsignedLong(in.getInt());
        if (headerLen < FIXED_HEADER_LEN) throw new ProtocolException("header shorter than fixed header");
        if (headerLen != FIXED_HEADER_LEN) throw new ProtocolException("header extensions are not supported in Beta 0.1");
        int negotiatedMax = Math.min(Math.max(maxPayload, 0), MAX_PAYLOAD);
        if (payloadLenLong > negotiatedMax) throw new ProtocolException("payload exceeds negotiated maximum");

        byte[] callId = new byte[16];
        byte[] sessionId = new byte[16];
        in.get(callId);
        in.get(sessionId);
        long requestId = Integer.toUnsignedLong(in.getInt());
        long reserved = Integer.toUnsignedLong(in.getInt());
        if (reserved != 0) throw new ProtocolException("reserved field must be zero");

        int payloadLen = (int) payloadLenLong;
        int total = headerLen + payloadLen;
        if (data.length < total) throw new ProtocolException("incomplete frame payload");
        byte[] payload = Arrays.copyOfRange(data, headerLen, total);
        return new Decoded(new AduFrame(kind, flags, callId, sessionId, requestId, payload), total);
    }
}
