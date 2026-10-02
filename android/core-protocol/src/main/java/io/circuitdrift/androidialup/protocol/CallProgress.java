package io.circuitdrift.androidialup.protocol;

import java.util.Objects;

/** CALL_PROGRESS payload (S1 wire protocol section 6). {@code detail} may be {@code null}. */
public record CallProgress(ProgressPhase phase, String detail) implements AduMessage {
    public CallProgress {
        Objects.requireNonNull(phase, "phase");
        MessageLimits.optionalString(detail, "detail");
    }
}
