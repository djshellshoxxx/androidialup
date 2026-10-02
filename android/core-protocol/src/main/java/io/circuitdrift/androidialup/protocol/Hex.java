package io.circuitdrift.androidialup.protocol;

/** Lower-case hex rendering for diagnostics (Java 17 has no {@code HexFormat} on Android API levels we target). */
final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    static String of(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = DIGITS[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
