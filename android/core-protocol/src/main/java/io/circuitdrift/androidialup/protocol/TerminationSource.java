package io.circuitdrift.androidialup.protocol;

import java.util.HashMap;
import java.util.Map;

/** CALL_TERMINATED source (S1 wire protocol section 6). Mirrors {@code TerminationSource} in messages.py. */
public enum TerminationSource {
    LOCAL(1), RELAY(2), GATEWAY(3), REMOTE(4);

    private static final Map<Integer, TerminationSource> BY_VALUE = new HashMap<>();
    static { for (TerminationSource v : values()) BY_VALUE.put(v.value, v); }

    private final int value;
    TerminationSource(int value) { this.value = value; }
    public int value() { return value; }

    /** @throws ProtocolException when {@code value} is not a known wire value. */
    public static TerminationSource fromValue(int value) {
        TerminationSource out = BY_VALUE.get(value);
        if (out == null) throw new ProtocolException("unknown termination source value " + value);
        return out;
    }
}
