package io.circuitdrift.androidialup.protocol;

/** AUTH_FAIL payload (S1 wire protocol section 4). */
public record AuthFail(String reason) implements AduMessage {
    public AuthFail {
        MessageLimits.string(reason, "reason");
    }
}
