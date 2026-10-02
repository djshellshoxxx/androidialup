package io.circuitdrift.androidialup.protocol;

/** PING payload (S1 wire protocol section 9). Both fields are raw u64 bit patterns. */
public record Ping(long nonce, long monotonicHint) implements AduMessage {
}
