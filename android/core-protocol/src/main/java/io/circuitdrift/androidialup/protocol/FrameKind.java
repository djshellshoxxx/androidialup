package io.circuitdrift.androidialup.protocol;

import java.util.HashMap;
import java.util.Map;

public enum FrameKind {
    HELLO(1), HELLO_ACK(2), HELLO_REJECT(3),
    AUTH_BEGIN(10), AUTH_CHALLENGE(11), AUTH_RESPONSE(12), AUTH_OK(13), AUTH_FAIL(14),
    DIAL_REQUEST(20), DIAL_ACCEPTED(21), DIAL_FAILED(22), CALL_PROGRESS(23), CALL_TERMINATED(24),
    DATA_BYTES(30), FLOW_STATUS(31),
    PING(40), PONG(41),
    HANGUP_REQUEST(50), HANGUP_ACK(51),
    INCOMING_CALL(60), ANSWER_REQUEST(61), ANSWER_ACCEPTED(62), ANSWER_FAILED(63);

    private static final Map<Integer, FrameKind> BY_VALUE = new HashMap<>();
    static { for (FrameKind kind : values()) BY_VALUE.put(kind.value, kind); }

    private final int value;
    FrameKind(int value) { this.value = value; }
    public int value() { return value; }

    public static FrameKind fromValue(int value) {
        FrameKind kind = BY_VALUE.get(value);
        if (kind == null) throw new ProtocolException("unknown frame kind " + value);
        return kind;
    }
}
