package io.circuitdrift.androidialup.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * DIAL_REQUEST network transport hint as carried on the wire (S1 wire protocol section 5).
 *
 * <p>This is the wire enum called {@code NetworkTransport} in
 * prototype/python/androidialup_protocol/messages.py. It is deliberately named
 * {@code ProtocolTransport} here because {@code core-network} owns an unrelated
 * {@code NetworkTransport} type; callers map between the two at the module boundary.
 */
public enum ProtocolTransport {
    WIFI(1), CELLULAR(2), ETHERNET(3), OTHER(4);

    private static final Map<Integer, ProtocolTransport> BY_VALUE = new HashMap<>();
    static { for (ProtocolTransport v : values()) BY_VALUE.put(v.value, v); }

    private final int value;
    ProtocolTransport(int value) { this.value = value; }
    public int value() { return value; }

    /** @throws ProtocolException when {@code value} is not a known wire value. */
    public static ProtocolTransport fromValue(int value) {
        ProtocolTransport out = BY_VALUE.get(value);
        if (out == null) throw new ProtocolException("unknown network transport value " + value);
        return out;
    }
}
