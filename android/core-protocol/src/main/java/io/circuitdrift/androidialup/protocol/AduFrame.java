package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;
import java.util.Objects;

public final class AduFrame {
    public static final byte[] ZERO_ID = new byte[16];

    private final FrameKind kind;
    private final int flags;
    private final byte[] callId;
    private final byte[] sessionId;
    private final long requestId;
    private final byte[] payload;

    public AduFrame(FrameKind kind, int flags, byte[] callId, byte[] sessionId, long requestId, byte[] payload) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (flags < 0 || flags > 0xffff) throw new IllegalArgumentException("flags must fit u16");
        if (requestId < 0 || requestId > 0xffff_ffffL) throw new IllegalArgumentException("requestId must fit u32");
        if (callId.length != 16 || sessionId.length != 16) throw new IllegalArgumentException("IDs must be 16 bytes");
        this.flags = flags;
        this.callId = callId.clone();
        this.sessionId = sessionId.clone();
        this.requestId = requestId;
        this.payload = Objects.requireNonNull(payload, "payload").clone();
    }

    public static AduFrame simple(FrameKind kind) {
        return new AduFrame(kind, 0, ZERO_ID, ZERO_ID, 0, new byte[0]);
    }

    public FrameKind kind() { return kind; }
    public int flags() { return flags; }
    public byte[] callId() { return callId.clone(); }
    public byte[] sessionId() { return sessionId.clone(); }
    public long requestId() { return requestId; }
    public byte[] payload() { return payload.clone(); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AduFrame that)) return false;
        return flags == that.flags && requestId == that.requestId && kind == that.kind
                && Arrays.equals(callId, that.callId)
                && Arrays.equals(sessionId, that.sessionId)
                && Arrays.equals(payload, that.payload);
    }

    @Override public int hashCode() {
        int result = Objects.hash(kind, flags, requestId);
        result = 31 * result + Arrays.hashCode(callId);
        result = 31 * result + Arrays.hashCode(sessionId);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
