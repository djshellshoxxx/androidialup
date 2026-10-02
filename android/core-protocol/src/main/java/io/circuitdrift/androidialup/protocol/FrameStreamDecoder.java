package io.circuitdrift.androidialup.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FrameStreamDecoder {
    private final int maxPayload;
    private final int maxBuffer;
    private byte[] buffer = new byte[0];

    public FrameStreamDecoder() {
        this(FrameCodec.MAX_PAYLOAD, (FrameCodec.FIXED_HEADER_LEN + FrameCodec.MAX_PAYLOAD) * 2);
    }

    public FrameStreamDecoder(int maxPayload, int maxBuffer) {
        if (maxPayload < 0 || maxPayload > FrameCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException("maxPayload out of range");
        }
        if (maxBuffer < FrameCodec.FIXED_HEADER_LEN) {
            throw new IllegalArgumentException("maxBuffer smaller than fixed header");
        }
        this.maxPayload = maxPayload;
        this.maxBuffer = maxBuffer;
    }

    public int bufferedBytes() {
        return buffer.length;
    }

    public List<AduFrame> feed(byte[] input) {
        if (input == null) throw new NullPointerException("input");
        if ((long) buffer.length + input.length > maxBuffer) {
            throw new ProtocolException("stream buffer limit exceeded");
        }

        byte[] joined = Arrays.copyOf(buffer, buffer.length + input.length);
        System.arraycopy(input, 0, joined, buffer.length, input.length);
        buffer = joined;

        List<AduFrame> frames = new ArrayList<>();
        while (buffer.length >= FrameCodec.FIXED_HEADER_LEN) {
            int headerLen = ((buffer[8] & 0xff) << 8) | (buffer[9] & 0xff);
            long payloadLen = ((long) (buffer[10] & 0xff) << 24)
                    | ((long) (buffer[11] & 0xff) << 16)
                    | ((long) (buffer[12] & 0xff) << 8)
                    | (long) (buffer[13] & 0xff);

            if (headerLen < FrameCodec.FIXED_HEADER_LEN) {
                throw new ProtocolException("header shorter than fixed header");
            }
            if (headerLen != FrameCodec.FIXED_HEADER_LEN) {
                throw new ProtocolException("header extensions are not supported in Beta 0.1");
            }
            if (payloadLen > maxPayload) {
                throw new ProtocolException("payload exceeds negotiated maximum");
            }
            long totalLong = (long) headerLen + payloadLen;
            if (totalLong > maxBuffer) {
                throw new ProtocolException("frame exceeds stream buffer limit");
            }
            int total = (int) totalLong;
            if (buffer.length < total) break;

            FrameCodec.Decoded decoded = FrameCodec.decode(Arrays.copyOf(buffer, total), maxPayload);
            frames.add(decoded.frame());
            buffer = Arrays.copyOfRange(buffer, decoded.bytesConsumed(), buffer.length);
        }
        return List.copyOf(frames);
    }
}
