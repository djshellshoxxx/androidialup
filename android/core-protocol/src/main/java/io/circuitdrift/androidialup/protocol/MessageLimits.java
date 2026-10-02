package io.circuitdrift.androidialup.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Payload field limits shared by the message constructors and {@link PayloadCodec}.
 * Values mirror prototype/python/androidialup_protocol/messages.py exactly.
 */
public final class MessageLimits {
    public static final int MAX_SHORT_STRING = 4096;
    public static final int MAX_TARGET_UTF8 = 256;
    public static final int MAX_CAPABILITIES = 128;
    public static final int MAX_MAP_ENTRIES = 128;
    public static final int MAX_BLOB = 65535;
    public static final int MAX_DATA_BYTES = 32768;

    private MessageLimits() {}

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static void id16(byte[] value, String name) {
        if (Objects.requireNonNull(value, name).length != 16) {
            throw new IllegalArgumentException(name + " must be exactly 16 bytes");
        }
    }

    static void endpointId(byte[] value) {
        if (Objects.requireNonNull(value, "endpointId").length != 32) {
            throw new IllegalArgumentException("endpoint_id must be exactly 32 bytes");
        }
    }

    static void u16(int value, String name) {
        if (value < 0 || value > 0xFFFF) throw new IllegalArgumentException(name + " must fit u16");
    }

    static void u32(long value, String name) {
        if (value < 0 || value > 0xFFFF_FFFFL) throw new IllegalArgumentException(name + " must fit u32");
    }

    /** Validates a short UTF-8 string; {@code null} is rejected. */
    static void string(String value, String name) {
        if (utf8Length(Objects.requireNonNull(value, name)) > MAX_SHORT_STRING) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_SHORT_STRING + " UTF-8 bytes");
        }
    }

    /** Validates an optional short string; {@code null} means absent. */
    static void optionalString(String value, String name) {
        if (value != null) string(value, name);
    }

    static void blob(byte[] value, String name) {
        if (Objects.requireNonNull(value, name).length > MAX_BLOB) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_BLOB + " bytes");
        }
    }

    /** Validates a string list and returns an immutable snapshot. */
    static List<String> stringList(List<String> values, String name) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > MAX_CAPABILITIES) throw new IllegalArgumentException(name + " has too many entries");
        for (String value : copy) string(value, name);
        return copy;
    }

    /** Validates an ordered string map and returns an immutable snapshot. */
    static List<Map.Entry<String, String>> stringMap(List<Map.Entry<String, String>> values, String name) {
        List<Map.Entry<String, String>> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > MAX_MAP_ENTRIES) throw new IllegalArgumentException(name + " has too many entries");
        for (Map.Entry<String, String> entry : copy) {
            string(entry.getKey(), name);
            string(entry.getValue(), name);
        }
        return copy;
    }
}
