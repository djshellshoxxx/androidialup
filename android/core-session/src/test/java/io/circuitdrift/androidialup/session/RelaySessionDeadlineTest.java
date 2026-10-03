package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.protocol.*;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S1_SPEC_FREEZE section 7 protocol deadlines serviced by {@code onTimer(nowMs)}. */
class RelaySessionDeadlineTest {
    private RelaySessionMachine machine;
    private final byte[] endpoint = new byte[32];
    private final byte[] callId = filled(0x43);
    private final byte[] sessionId = filled(0x53);

    @BeforeEach
    void setUp() {
        machine = new RelaySessionMachine(endpoint, (relayId, endpointId, challenge) -> new byte[]{1}, callId::clone);
    }

    private static byte[] filled(int value) {
        byte[] id = new byte[16];
        Arrays.fill(id, (byte) value);
        return id;
    }

    private AduFrame frame(FrameKind kind, Message message, byte[] call, byte[] session, long requestId) {
        return new AduFrame(kind, 0, call, session, requestId, PayloadCodec.encode(message));
    }

    private AduFrame handshake(FrameKind kind, Message message, long requestId) {
        return frame(kind, message, AduFrame.ZERO_ID, AduFrame.ZERO_ID, requestId);
    }

    private static AduFrame only(List<RelaySessionMachine.Action> actions) {
        assertEquals(1, actions.size(), actions.toString());
        return assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0)).frame();
    }

    /** Returns the AUTH_RESPONSE request id; heartbeat 0 keeps PINGs out of the way. */
    private long runUntilAuthResponse(long tlsAt) {
        AduFrame hello = only(machine.onTlsConnected(tlsAt));
        AduFrame begin = only(machine.onFrame(handshake(FrameKind.HELLO_ACK,
                new HelloAck(1, "relay", FrameCodec.MAX_PAYLOAD, 0, List.of("BYTE_RELAY")), hello.requestId()), tlsAt + 100));
        return only(machine.onFrame(handshake(FrameKind.AUTH_CHALLENGE,
                new AuthChallenge(new byte[16], "m"), begin.requestId()), tlsAt + 200)).requestId();
    }

    private void authenticate(long tlsAt) {
        long request = runUntilAuthResponse(tlsAt);
        assertTrue(machine.onFrame(handshake(FrameKind.AUTH_OK, new AuthOk(endpoint, List.of()), request), tlsAt + 300).isEmpty());
    }

    private AduFrame acceptDial(long dialAt, long acceptAt) {
        AduFrame dial = only(machine.dial("loopback", NetworkTransport.WIFI, dialAt));
        assertTrue(machine.onFrame(frame(FrameKind.DIAL_ACCEPTED,
                new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY), callId, sessionId, dial.requestId()), acceptAt).isEmpty());
        return dial;
    }

    @Test
    void authenticationDeadlineRunsFromTlsConnectAndFiresOnce() {
        runUntilAuthResponse(1000);
        assertTrue(machine.onTimer(8999).isEmpty());
        List<RelaySessionMachine.Action> actions = machine.onTimer(9000);
        assertEquals(1, actions.size());
        var failed = assertInstanceOf(RelaySessionMachine.TransportFailed.class, actions.get(0));
        assertEquals(RelaySessionMachine.AUTH_FAILURE, failed.reason());
        assertTrue(failed.detail().contains("deadline"));
        assertEquals(RelaySessionMachine.State.FAILED, machine.state());
        assertEquals(RelaySessionMachine.AUTH_FAILURE, machine.terminalReason());
        assertTrue(machine.onTimer(9001).isEmpty());
        assertTrue(machine.onTimer(100_000).isEmpty());
    }

    @Test
    void authenticationDeadlineCoversHelloAckWait() {
        machine.onTlsConnected(0);
        assertTrue(machine.onTimer(7999).isEmpty());
        assertInstanceOf(RelaySessionMachine.TransportFailed.class, machine.onTimer(8000).get(0));
    }

    @Test
    void authOkCancelsAuthenticationDeadline() {
        authenticate(0);
        assertTrue(machine.onTimer(8000).isEmpty());
        assertTrue(machine.onTimer(1_000_000).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
    }

    @Test
    void authFailSurfacesExplicitTransportFailure() {
        long request = runUntilAuthResponse(0);
        List<RelaySessionMachine.Action> actions = machine.onFrame(
                handshake(FrameKind.AUTH_FAIL, new AuthFail("authentication failed"), request), 400);
        assertEquals(1, actions.size());
        var failed = assertInstanceOf(RelaySessionMachine.TransportFailed.class, actions.get(0));
        assertEquals(RelaySessionMachine.AUTH_FAILURE, failed.reason());
        assertEquals("authentication failed", failed.detail());
        assertEquals(RelaySessionMachine.State.FAILED, machine.state());
        assertTrue(machine.onTimer(10_000).isEmpty(), "auth deadline must not fire after AUTH_FAIL");
    }

    @Test
    void helloRejectSurfacesVersionMismatch() {
        AduFrame hello = only(machine.onTlsConnected(0));
        List<RelaySessionMachine.Action> actions = machine.onFrame(
                handshake(FrameKind.HELLO_REJECT, new HelloReject("unsupported version"), hello.requestId()), 10);
        var failed = assertInstanceOf(RelaySessionMachine.TransportFailed.class, actions.get(0));
        assertEquals("PROTOCOL_VERSION_MISMATCH", failed.reason());
        assertEquals(RelaySessionMachine.State.FAILED, machine.state());
    }

    @Test
    void dialRequestAcknowledgementDeadlineFiresOnceWithDistinctReason() {
        authenticate(0);
        only(machine.dial("loopback", NetworkTransport.WIFI, 20_000));
        assertTrue(machine.onTimer(24_999).isEmpty());
        List<RelaySessionMachine.Action> actions = machine.onTimer(25_000);
        assertEquals(1, actions.size());
        var failed = assertInstanceOf(RelaySessionMachine.TransportFailed.class, actions.get(0));
        assertEquals(RelaySessionMachine.RELAY_UNAVAILABLE, failed.reason());
        assertEquals(RelaySessionMachine.State.FAILED, machine.state());
        assertTrue(machine.onTimer(30_000).isEmpty());
    }

    @Test
    void dialAcceptedCancelsAckDeadlineAndStartsBackendSetupDeadline() {
        authenticate(0);
        acceptDial(20_000, 21_000);
        assertTrue(machine.onTimer(25_000).isEmpty());
        assertTrue(machine.onTimer(80_999).isEmpty());
        List<RelaySessionMachine.Action> actions = machine.onTimer(81_000);
        assertEquals(2, actions.size(), actions.toString());
        AduFrame hangup = assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0)).frame();
        assertEquals(FrameKind.HANGUP_REQUEST, hangup.kind());
        assertArrayEquals(sessionId, hangup.sessionId());
        var failed = assertInstanceOf(RelaySessionMachine.CallFailed.class, actions.get(1));
        assertEquals(DialFailure.TIMEOUT, failed.reason());
        assertEquals(RelaySessionMachine.BACKEND_NO_ANSWER, failed.terminalReason());
        assertEquals(RelaySessionMachine.State.HANGING_UP, machine.state());
        assertTrue(machine.onTimer(83_999).isEmpty(), "only the 3 s disconnect grace remains armed");

        // The relay completes the hangup; the call already had its single terminal action.
        assertTrue(machine.onFrame(frame(FrameKind.HANGUP_ACK, new HangupAck(), callId, sessionId, hangup.requestId()), 81_010).isEmpty());
        assertTrue(machine.onFrame(frame(FrameKind.CALL_TERMINATED,
                new CallTerminated("LOCAL_HANGUP", TerminationSource.GATEWAY, null), callId, sessionId, 0), 81_020).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
        assertEquals(RelaySessionMachine.BACKEND_NO_ANSWER, machine.terminalReason());
    }

    @Test
    void connectedCancelsBackendSetupDeadline() {
        authenticate(0);
        acceptDial(20_000, 21_000);
        machine.onFrame(frame(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.CONNECTED, null), callId, sessionId, 0), 30_000);
        assertTrue(machine.onTimer(81_000).isEmpty());
        assertTrue(machine.onTimer(500_000).isEmpty());
        assertEquals(RelaySessionMachine.State.CONNECTED, machine.state());
    }

    @Test
    void progressEventsDoNotExtendBackendSetupDeadline() {
        authenticate(0);
        acceptDial(20_000, 21_000);
        machine.onFrame(frame(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.RINGBACK, null), callId, sessionId, 0), 70_000);
        assertEquals(2, machine.onTimer(81_000).size());
    }

    @Test
    void dialFailedCancelsDeadlines() {
        authenticate(0);
        AduFrame dial = only(machine.dial("busy", NetworkTransport.WIFI, 20_000));
        machine.onFrame(frame(FrameKind.DIAL_FAILED, new DialFailed(callId, DialFailure.BUSY, false, null),
                callId, AduFrame.ZERO_ID, dial.requestId()), 20_500);
        assertTrue(machine.onTimer(25_000).isEmpty());
        assertTrue(machine.onTimer(90_000).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
    }

    @Test
    void cleanDisconnectGraceClosesTransportWhenRelayNeverConfirmsHangup() {
        authenticate(0);
        acceptDial(20_000, 21_000);
        machine.onFrame(frame(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.CONNECTED, null), callId, sessionId, 0), 22_000);
        only(machine.hangup("LOCAL_HANGUP"));
        // hangup() takes no time argument: the grace runs from the latest time the machine saw.
        assertEquals(RelaySessionMachine.Deadline.HANGUP_GRACE, machine.activeDeadline());
        assertTrue(machine.onTimer(24_999).isEmpty());
        List<RelaySessionMachine.Action> actions = machine.onTimer(25_000);
        var failed = assertInstanceOf(RelaySessionMachine.TransportFailed.class, actions.get(0));
        assertEquals(RelaySessionMachine.RELAY_UNAVAILABLE, failed.reason());
        assertEquals(RelaySessionMachine.State.FAILED, machine.state());
    }

    @Test
    void callTerminatedWithinGraceCancelsIt() {
        authenticate(0);
        acceptDial(20_000, 21_000);
        AduFrame hangup = only(machine.hangup("LOCAL_HANGUP"));
        machine.onFrame(frame(FrameKind.HANGUP_ACK, new HangupAck(), callId, sessionId, hangup.requestId()), 21_100);
        machine.onFrame(frame(FrameKind.CALL_TERMINATED, new CallTerminated("LOCAL_HANGUP", TerminationSource.RELAY, null),
                callId, sessionId, 0), 21_200);
        assertEquals(RelaySessionMachine.Deadline.NONE, machine.activeDeadline());
        assertTrue(machine.onTimer(100_000).isEmpty());
    }

    @Test
    void threeDeadlineReasonsAreDistinctTaxonomyNames() {
        List<String> reasons = List.of(RelaySessionMachine.AUTH_FAILURE, RelaySessionMachine.RELAY_UNAVAILABLE,
                RelaySessionMachine.BACKEND_NO_ANSWER);
        assertEquals(3, reasons.stream().distinct().count());
    }

    @Test
    void hostReportedTransportLossFailsOnce() {
        authenticate(0);
        acceptDial(20_000, 21_000);
        List<RelaySessionMachine.Action> actions = machine.fail("NETWORK_LOST");
        var failed = assertInstanceOf(RelaySessionMachine.TransportFailed.class, actions.get(0));
        assertEquals("NETWORK_LOST", failed.reason());
        assertEquals(RelaySessionMachine.State.FAILED, machine.state());
        assertTrue(machine.fail("NETWORK_LOST").isEmpty());
        assertTrue(machine.onTimer(500_000).isEmpty());
    }

    @Test
    void customTimeoutsAreHonoured() {
        machine = new RelaySessionMachine(endpoint, (r, e, c) -> new byte[]{1}, callId::clone,
                RelaySessionMachine.Options.DEFAULTS.withDeadlines(1000, 500, 2000));
        machine.onTlsConnected(0);
        assertTrue(machine.onTimer(999).isEmpty());
        assertEquals(1, machine.onTimer(1000).size());
    }
}
