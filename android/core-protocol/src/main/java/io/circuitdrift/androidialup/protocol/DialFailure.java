package io.circuitdrift.androidialup.protocol;

import java.util.HashMap;
import java.util.Map;

/** DIAL_FAILED reason (S1 wire protocol section 14). Mirrors {@code DialFailure} in messages.py. */
public enum DialFailure {
    NO_ROUTE(1), GATEWAY_UNAVAILABLE(2), BUSY(3), NO_DIALTONE(4), NO_ANSWER(5),
    AUTHORIZATION_DENIED(6), UNSUPPORTED_MODE(7), TIMEOUT(8), INTERNAL_ERROR(9);

    private static final Map<Integer, DialFailure> BY_VALUE = new HashMap<>();
    static { for (DialFailure v : values()) BY_VALUE.put(v.value, v); }

    private final int value;
    DialFailure(int value) { this.value = value; }
    public int value() { return value; }

    /** @throws ProtocolException when {@code value} is not a known wire value. */
    public static DialFailure fromValue(int value) {
        DialFailure out = BY_VALUE.get(value);
        if (out == null) throw new ProtocolException("unknown dial failure value " + value);
        return out;
    }
}
