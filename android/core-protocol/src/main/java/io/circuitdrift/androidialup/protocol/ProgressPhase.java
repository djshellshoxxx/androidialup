package io.circuitdrift.androidialup.protocol;

import java.util.HashMap;
import java.util.Map;

/** CALL_PROGRESS phase (S1 wire protocol section 6). Mirrors {@code ProgressPhase} in messages.py. */
public enum ProgressPhase {
    ROUTING(1), GATEWAY_CONNECTING(2), DIALING(3), RINGBACK(4), NEGOTIATING(5), CONNECTED(6);

    private static final Map<Integer, ProgressPhase> BY_VALUE = new HashMap<>();
    static { for (ProgressPhase v : values()) BY_VALUE.put(v.value, v); }

    private final int value;
    ProgressPhase(int value) { this.value = value; }
    public int value() { return value; }

    /** @throws ProtocolException when {@code value} is not a known wire value. */
    public static ProgressPhase fromValue(int value) {
        ProgressPhase out = BY_VALUE.get(value);
        if (out == null) throw new ProtocolException("unknown progress phase value " + value);
        return out;
    }
}
