package io.circuitdrift.androidialup.protocol;

/**
 * Marker for every typed ADUP v1 payload that {@link PayloadCodec} can encode and decode.
 *
 * <p>Each implementation is an immutable record whose constructor enforces the same limits as
 * the Python reference ({@code messages.py}): violations throw {@link IllegalArgumentException}
 * (the Python {@code ValueError}) and {@code null} required fields throw
 * {@link NullPointerException}. Byte-array fields are defensively copied on the way in and out,
 * and compare by content.
 *
 * <p>Unsigned 64-bit wire fields are represented as Java {@code long} holding the raw bit
 * pattern; use {@link Long#toUnsignedString(long)} and friends to interpret them. Unsigned
 * 32-bit fields are {@code long} restricted to 0..0xFFFFFFFF and unsigned 16-bit fields are
 * {@code int} restricted to 0..0xFFFF.
 */
public sealed interface AduMessage permits
        Hello, HelloAck, HelloReject,
        AuthBegin, AuthChallenge, AuthResponse, AuthOk, AuthFail,
        DialRequest, DialAccepted, DialFailed, CallProgress, CallTerminated,
        DataBytes, FlowStatus, Ping, Pong, HangupRequest, HangupAck {
}
