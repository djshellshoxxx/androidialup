package io.circuitdrift.androidialup.protocol;

import java.util.Objects;

/** CALL_TERMINATED payload (S1 wire protocol section 6). {@code diagnosticCode} may be {@code null}. */
public record CallTerminated(String reason, TerminationSource source, String diagnosticCode) implements AduMessage {
    public CallTerminated {
        MessageLimits.string(reason, "reason");
        Objects.requireNonNull(source, "source");
        MessageLimits.optionalString(diagnosticCode, "diagnostic_code");
    }
}
