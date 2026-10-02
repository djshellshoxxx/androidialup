package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * DATA_BYTES payload (S1 wire protocol section 7). {@code streamSeq} is the raw u64 bit pattern;
 * {@code data} must be 1..32768 bytes.
 */
public record DataBytes(long streamSeq, byte[] data) implements AduMessage {
    public DataBytes {
        Objects.requireNonNull(data, "data");
        if (data.length < 1 || data.length > MessageLimits.MAX_DATA_BYTES) {
            throw new IllegalArgumentException("DATA_BYTES data must be 1..32768 bytes");
        }
        data = data.clone();
    }

    @Override public byte[] data() { return data.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof DataBytes that && streamSeq == that.streamSeq && Arrays.equals(data, that.data);
    }

    @Override public int hashCode() { return 31 * Long.hashCode(streamSeq) + Arrays.hashCode(data); }

    @Override public String toString() {
        return "DataBytes[streamSeq=" + Long.toUnsignedString(streamSeq) + ", data=" + data.length + " bytes]";
    }
}
