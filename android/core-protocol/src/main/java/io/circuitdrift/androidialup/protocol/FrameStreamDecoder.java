package io.circuitdrift.androidialup.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Incremental ADUP v1 frame decoder for a reliable byte stream. Mirrors
 * prototype/python/androidialup_protocol/stream.py.
 *
 * <p>Feed arbitrary chunks (split headers, split payloads, several frames at once) and receive
 * every frame that became complete. Once {@link #feed(byte[])} throws, the connection must be
 * closed: the decoder makes no attempt to resynchronise.
 *
 * <p>Not thread-safe: a single reader thread owns an instance.
 */
public final class FrameStreamDecoder {
    private final int maxPayload;
    private final int maxBuffer;
    private byte[] buffer = new byte[256];
    private int length;

    /** Decoder for the protocol maximum payload with the default buffer limit. */
    public FrameStreamDecoder() {
        this(FrameCodec.MAX_PAYLOAD, 0);
    }

    /** Decoder for a negotiated payload maximum (clamped to the protocol maximum). */
    public FrameStreamDecoder(int maxPayload) {
        this(maxPayload, 0);
    }

    /**
     * @param maxPayload negotiated payload maximum; clamped to {@link FrameCodec#MAX_PAYLOAD}
     * @param maxBuffer hard limit on buffered bytes; {@code <= 0} selects the default of
     *     {@code (FIXED_HEADER_LEN + maxPayload) * 2}
     */
    public FrameStreamDecoder(int maxPayload, int maxBuffer) {
        this.maxPayload = Math.min(Math.max(maxPayload, 0), FrameCodec.MAX_PAYLOAD);
        this.maxBuffer = maxBuffer > 0 ? maxBuffer : (FrameCodec.FIXED_HEADER_LEN + this.maxPayload) * 2;
    }

    public int maxPayload() { return maxPayload; }
    public int maxBuffer() { return maxBuffer; }

    /** Number of bytes held while waiting for the rest of a frame. */
    public int bufferedBytes() { return length; }

    /**
     * Appends {@code data} and returns every frame completed by it, in stream order.
     *
     * @throws ProtocolException if the buffer limit would be exceeded (the data is then not
     *     buffered), if a header is malformed, or if a frame is larger than the negotiated
     *     payload maximum or the buffer limit
     */
    public List<AduFrame> feed(byte[] data) {
        Objects.requireNonNull(data, "data");
        if ((long) length + data.length > maxBuffer) throw new ProtocolException("stream buffer limit exceeded");
        append(data);
        List<AduFrame> frames = new ArrayList<>();

        while (length >= FrameCodec.FIXED_HEADER_LEN) {
            int headerLen = ((buffer[8] & 0xFF) << 8) | (buffer[9] & 0xFF);
            long payloadLen = ((long) (buffer[10] & 0xFF) << 24) | ((buffer[11] & 0xFF) << 16)
                    | ((buffer[12] & 0xFF) << 8) | (buffer[13] & 0xFF);
            if (headerLen < FrameCodec.FIXED_HEADER_LEN) throw new ProtocolException("header shorter than fixed header");
            if (headerLen != FrameCodec.FIXED_HEADER_LEN) throw new ProtocolException("header extensions are not supported in Beta 0.1");
            if (payloadLen > maxPayload) throw new ProtocolException("payload exceeds negotiated maximum");
            long total = headerLen + payloadLen;
            if (total > maxBuffer) throw new ProtocolException("frame exceeds stream buffer limit");
            if (length < total) break;

            FrameCodec.Decoded decoded = FrameCodec.decode(Arrays.copyOf(buffer, (int) total), maxPayload);
            frames.add(decoded.frame());
            consume(decoded.bytesConsumed());
        }
        return frames;
    }

    private void append(byte[] data) {
        int needed = length + data.length;
        if (needed > buffer.length) {
            int grown = Math.max(needed, Math.min(buffer.length * 2, maxBuffer));
            buffer = Arrays.copyOf(buffer, grown);
        }
        System.arraycopy(data, 0, buffer, length, data.length);
        length = needed;
    }

    private void consume(int count) {
        int rest = length - count;
        if (rest > 0) System.arraycopy(buffer, count, buffer, 0, rest);
        length = rest;
    }
}
