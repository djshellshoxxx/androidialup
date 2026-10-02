package io.circuitdrift.androidialup.protocol;

/** HELLO_REJECT payload (S1 wire protocol section 3). */
public record HelloReject(String reason) implements AduMessage {
    public HelloReject {
        MessageLimits.string(reason, "reason");
    }
}
