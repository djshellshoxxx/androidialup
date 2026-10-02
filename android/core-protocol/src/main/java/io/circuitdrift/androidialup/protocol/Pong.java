package io.circuitdrift.androidialup.protocol;

/** PONG payload (S1 wire protocol section 9). {@code nonce} is a raw u64 bit pattern. */
public record Pong(long nonce) implements AduMessage {
}
