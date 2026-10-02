package io.circuitdrift.androidialup.protocol;

/** HANGUP_REQUEST payload (S1 wire protocol section 10). */
public record HangupRequest(String reason) implements AduMessage {
    public HangupRequest {
        MessageLimits.string(reason, "reason");
    }
}
