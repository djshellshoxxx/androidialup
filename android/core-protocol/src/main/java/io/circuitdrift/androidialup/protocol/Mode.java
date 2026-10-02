package io.circuitdrift.androidialup.protocol;

import java.util.HashMap;
import java.util.Map;

/** Call mode (S1 wire protocol section 14). Mirrors {@code Mode} in messages.py. */
public enum Mode {
    BYTE_RELAY(1), PCM_VBD_EXPERIMENTAL(2), V152_RTP_RESERVED(100), V1501_SPRT_RESERVED(101);

    private static final Map<Integer, Mode> BY_VALUE = new HashMap<>();
    static { for (Mode v : values()) BY_VALUE.put(v.value, v); }

    private final int value;
    Mode(int value) { this.value = value; }
    public int value() { return value; }

    /** @throws ProtocolException when {@code value} is not a known wire value. */
    public static Mode fromValue(int value) {
        Mode out = BY_VALUE.get(value);
        if (out == null) throw new ProtocolException("unknown mode value " + value);
        return out;
    }
}
