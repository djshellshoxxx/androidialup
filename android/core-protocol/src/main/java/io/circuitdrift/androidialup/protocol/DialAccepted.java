package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;
import java.util.Objects;

/** DIAL_ACCEPTED payload (S1 wire protocol section 5). */
public record DialAccepted(byte[] callId, byte[] assignedSessionId, String selectedGatewayId, Mode selectedMode)
        implements AduMessage {
    public DialAccepted {
        MessageLimits.id16(callId, "call_id");
        MessageLimits.id16(assignedSessionId, "assigned_session_id");
        MessageLimits.string(selectedGatewayId, "selected_gateway_id");
        Objects.requireNonNull(selectedMode, "selectedMode");
        callId = callId.clone();
        assignedSessionId = assignedSessionId.clone();
    }

    @Override public byte[] callId() { return callId.clone(); }
    @Override public byte[] assignedSessionId() { return assignedSessionId.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof DialAccepted that
                && Arrays.equals(callId, that.callId) && Arrays.equals(assignedSessionId, that.assignedSessionId)
                && selectedGatewayId.equals(that.selectedGatewayId) && selectedMode == that.selectedMode;
    }

    @Override public int hashCode() {
        int h = Objects.hash(selectedGatewayId, selectedMode);
        h = 31 * h + Arrays.hashCode(callId);
        return 31 * h + Arrays.hashCode(assignedSessionId);
    }

    @Override public String toString() {
        return "DialAccepted[callId=" + Hex.of(callId) + ", assignedSessionId=" + Hex.of(assignedSessionId)
                + ", selectedGatewayId=" + selectedGatewayId + ", selectedMode=" + selectedMode + "]";
    }
}
