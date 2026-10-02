package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.protocol.*;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelaySessionSnapshotTest {
    private static final byte[] SECRET = HexFormat.of().parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
    private final byte[] endpoint = new byte[32];
    private final byte[] callId = HexFormat.of().parseHex("0123456789abcdef0011223344556677");
    private final byte[] sessionId = HexFormat.of().parseHex("fedcba98765432100011223344556677");
    private final RelaySessionMachine machine =
            new RelaySessionMachine(endpoint, new DeviceCredentialProof(SECRET), callId::clone);
    private byte[] proof;

    private AduFrame frame(FrameKind kind, Message message, byte[] call, byte[] session, long requestId) {
        return new AduFrame(kind, 0, call, session, requestId, PayloadCodec.encode(message));
    }

    private AduFrame active(Message message) {
        return frame(PayloadCodec.kindFor(message), message, callId, sessionId, 0);
    }

    private static AduFrame only(List<RelaySessionMachine.Action> actions) {
        assertEquals(1, actions.size());
        return assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0)).frame();
    }

    private void connectAt(long t) {
        AduFrame hello = only(machine.onTlsConnected(t));
        AduFrame begin = only(machine.onFrame(frame(FrameKind.HELLO_ACK,
                new HelloAck(1, "relay-prototype", FrameCodec.MAX_PAYLOAD, 10, List.of()), AduFrame.ZERO_ID, AduFrame.ZERO_ID, hello.requestId()), t));
        byte[] nonce = new byte[32];
        Arrays.fill(nonce, (byte) 0x5a);
        AduFrame response = only(machine.onFrame(frame(FrameKind.AUTH_CHALLENGE,
                new AuthChallenge(nonce, DeviceCredentialProof.METHOD), AduFrame.ZERO_ID, AduFrame.ZERO_ID, begin.requestId()), t));
        proof = ((AuthResponse) PayloadCodec.decode(response.kind(), response.payload())).proof();
        machine.onFrame(frame(FrameKind.AUTH_OK, new AuthOk(endpoint, List.of()), AduFrame.ZERO_ID, AduFrame.ZERO_ID, response.requestId()), t);
    }

    @Test
    void newMachineSnapshotIsEmpty() {
        RelaySessionSnapshot snap = machine.snapshot(0);
        assertEquals(RelaySessionMachine.State.NEW, snap.state());
        assertEquals("", snap.callIdPrefix());
        assertEquals("", snap.sessionIdPrefix());
        assertNull(snap.relayId());
        assertNull(snap.terminalReason());
        assertEquals(RelaySessionMachine.Deadline.NONE, snap.activeDeadline());
        assertEquals(-1, snap.deadlineRemainingMs());
    }

    @Test
    void handshakeSnapshotShowsAuthenticationDeadline() {
        machine.onTlsConnected(1000);
        RelaySessionSnapshot snap = machine.snapshot(3000);
        assertEquals(RelaySessionMachine.State.HELLO_SENT, snap.state());
        assertEquals(RelaySessionMachine.Deadline.AUTHENTICATION, snap.activeDeadline());
        assertEquals(6000, snap.deadlineRemainingMs());
    }

    @Test
    void connectedSnapshotCarriesCountersWindowsAndHeartbeat() {
        connectAt(0);
        AduFrame dial = only(machine.dial("loopback", NetworkTransport.WIFI, 1000));
        assertEquals(RelaySessionMachine.Deadline.DIAL_ACK, machine.snapshot(1000).activeDeadline());
        machine.onFrame(frame(FrameKind.DIAL_ACCEPTED, new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY),
                callId, sessionId, dial.requestId()), 1100);
        RelaySessionSnapshot dialing = machine.snapshot(1100);
        assertEquals(RelaySessionMachine.Deadline.DIAL_SETUP, dialing.activeDeadline());
        assertEquals(60_000, dialing.deadlineRemainingMs());
        machine.onFrame(active(new CallProgress(ProgressPhase.CONNECTED, null)), 1200);
        machine.onFrame(active(new FlowStatus(5, 9)), 1300);
        machine.writeData(new byte[]{1, 2, 3, 4, 5, 6, 7});
        machine.onFrame(active(new DataBytes(0, new byte[]{1, 2, 3})), 1400);
        AduFrame ping = only(machine.onTimer(11_400));
        assertEquals(FrameKind.PING, ping.kind());

        RelaySessionSnapshot snap = machine.snapshot(12_000);
        assertEquals(RelaySessionMachine.State.CONNECTED, snap.state());
        assertEquals("relay-prototype", snap.relayId());
        assertEquals("01234567", snap.callIdPrefix());
        assertEquals("fedcba98", snap.sessionIdPrefix());
        assertEquals(5, snap.outboundSeq());
        assertEquals(3, snap.inboundSeq());
        assertEquals(2, snap.pendingOutboundBytes());
        assertEquals(0, snap.peerWindow());
        assertEquals(9, snap.peerQueuedBytes());
        assertEquals(RelaySessionMachine.DEFAULT_RECEIVE_WINDOW, snap.advertisedReceiveWindow());
        assertEquals(0, snap.inboundUnconsumedBytes());
        assertEquals(10_000, snap.heartbeatIntervalMs());
        assertEquals(10_600, snap.heartbeatAgeMs());
        assertTrue(snap.heartbeatOutstanding());
        assertEquals(600, snap.heartbeatOutstandingMs());
        assertEquals(RelaySessionMachine.Deadline.NONE, snap.activeDeadline());
        assertNull(snap.terminalReason());
    }

    @Test
    void terminalReasonIsRetainedAfterCallEnds() {
        connectAt(0);
        AduFrame dial = only(machine.dial("busy", NetworkTransport.WIFI, 10));
        machine.onFrame(frame(FrameKind.DIAL_FAILED, new DialFailed(callId, DialFailure.BUSY, false, null),
                callId, AduFrame.ZERO_ID, dial.requestId()), 20);
        RelaySessionSnapshot snap = machine.snapshot(30);
        assertEquals(RelaySessionMachine.State.IDLE, snap.state());
        assertEquals("BACKEND_BUSY", snap.terminalReason());
        assertEquals("", snap.callIdPrefix());
    }

    @Test
    void snapshotNeverExposesFullIdsSecretsOrProofs() {
        connectAt(0);
        AduFrame dial = only(machine.dial("loopback", NetworkTransport.WIFI, 10));
        machine.onFrame(frame(FrameKind.DIAL_ACCEPTED, new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY),
                callId, sessionId, dial.requestId()), 20);
        String text = machine.snapshot(30).toString().toLowerCase();
        HexFormat hex = HexFormat.of();
        assertFalse(text.contains(hex.formatHex(callId)));
        assertFalse(text.contains(hex.formatHex(sessionId)));
        assertFalse(text.contains(hex.formatHex(SECRET).substring(0, 16)));
        assertFalse(text.contains(hex.formatHex(proof).substring(0, 16)));
        assertTrue(text.contains("01234567"));
    }
}
