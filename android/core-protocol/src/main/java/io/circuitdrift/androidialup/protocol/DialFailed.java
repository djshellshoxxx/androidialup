package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;
import java.util.Objects;

/** DIAL_FAILED payload (S1 wire protocol section 5). {@code humanDetail} may be {@code null}. */
public record DialFailed(byte[] callId, DialFailure reason, boolean retryable, String humanDetail)
        implements AduMessage {
    public DialFailed {
        MessageLimits.id16(callId, "call_id");
        Objects.requireNonNull(reason, "reason");
        MessageLimits.optionalString(humanDetail, "human_detail");
        callId = callId.clone();
    }

    @Override public byte[] callId() { return callId.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof DialFailed that
                && Arrays.equals(callId, that.callId) && reason == that.reason && retryable == that.retryable
                && Objects.equals(humanDetail, that.humanDetail);
    }

    @Override public int hashCode() {
        return 31 * Objects.hash(reason, retryable, humanDetail) + Arrays.hashCode(callId);
    }

    @Override public String toString() {
        return "DialFailed[callId=" + Hex.of(callId) + ", reason=" + reason + ", retryable=" + retryable
                + ", humanDetail=" + humanDetail + "]";
    }
}
