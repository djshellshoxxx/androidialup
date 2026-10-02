package io.circuitdrift.androidialup.session;

/**
 * Immutable diagnostics view of a {@link RelaySessionMachine} (I1 plan Task 5).
 *
 * <p>Safe to log and to show in {@code AT+DIAG?}: call and session IDs appear only as their first
 * four bytes in hex ({@value #ID_PREFIX_BYTES} bytes, empty string when unassigned); no device
 * secret, proof, nonce or endpoint ID is ever included (S1_WIRE_PROTOCOL 4.1, S1_SPEC_FREEZE 11).
 *
 * @param state machine state
 * @param relayId relay_id from HELLO_ACK, or null before it arrived
 * @param callIdPrefix first 4 bytes of the call ID as lowercase hex, or ""
 * @param sessionIdPrefix first 4 bytes of the session ID as lowercase hex, or ""
 * @param outboundSeq next outbound BYTE_RELAY stream_seq (bytes sent this call)
 * @param inboundSeq next expected inbound stream_seq (bytes received this call)
 * @param pendingOutboundBytes bytes buffered locally waiting for peer window
 * @param peerWindow remaining credit from the peer's latest FLOW_STATUS
 * @param peerQueuedBytes queued_bytes from the peer's latest FLOW_STATUS
 * @param advertisedReceiveWindow receive_window_bytes of the last FLOW_STATUS sent
 * @param inboundUnconsumedBytes inbound bytes handed to the host but not reported consumed
 * @param heartbeatIntervalMs negotiated heartbeat interval, 0 when disabled
 * @param heartbeatAgeMs time since the last accepted peer frame
 * @param heartbeatOutstanding whether a PING awaits its PONG
 * @param heartbeatOutstandingMs age of the outstanding PING, -1 if none
 * @param terminalReason S1 section 12 reason of the latest call/transport termination, or null
 * @param activeDeadline armed protocol deadline
 * @param deadlineRemainingMs time until that deadline fires (0 if overdue), -1 if none
 */
public record RelaySessionSnapshot(
        RelaySessionMachine.State state,
        String relayId,
        String callIdPrefix,
        String sessionIdPrefix,
        long outboundSeq,
        long inboundSeq,
        int pendingOutboundBytes,
        long peerWindow,
        long peerQueuedBytes,
        long advertisedReceiveWindow,
        long inboundUnconsumedBytes,
        long heartbeatIntervalMs,
        long heartbeatAgeMs,
        boolean heartbeatOutstanding,
        long heartbeatOutstandingMs,
        String terminalReason,
        RelaySessionMachine.Deadline activeDeadline,
        long deadlineRemainingMs) {

    public static final int ID_PREFIX_BYTES = 4;

    /** Lowercase hex of the first {@value #ID_PREFIX_BYTES} bytes, or "" for an all-zero ID. */
    static String safePrefix(byte[] id) {
        boolean zero = true;
        for (byte b : id) if (b != 0) { zero = false; break; }
        if (zero) return "";
        StringBuilder out = new StringBuilder(ID_PREFIX_BYTES * 2);
        for (int i = 0; i < ID_PREFIX_BYTES && i < id.length; i++) {
            out.append(Character.forDigit((id[i] >> 4) & 0xf, 16)).append(Character.forDigit(id[i] & 0xf, 16));
        }
        return out.toString();
    }
}
