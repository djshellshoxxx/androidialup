package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.protocol.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelaySessionHeartbeatTest {
    private RelaySessionMachine machine;
    private final byte[] endpoint = new byte[32];

    @BeforeEach
    void setUp() {
        byte[] call = new byte[16];
        call[0] = 1;
        machine = new RelaySessionMachine(endpoint, challenge -> new byte[]{1}, () -> call.clone());
    }

    private AduFrame frame(FrameKind kind, Message message, long requestId) {
        return new AduFrame(kind, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, requestId, PayloadCodec.encode(message));
    }

    private RelaySessionMachine.Outbound oneOutbound(List<RelaySessionMachine.Action> actions) {
        assertEquals(1, actions.size());
        return assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0));
    }

    private void authenticateAt(long nowMs, long heartbeatSeconds) {
        AduFrame hello = oneOutbound(machine.onTlsConnected(nowMs)).frame();
        AduFrame authBegin = oneOutbound(machine.onFrame(
                frame(FrameKind.HELLO_ACK,
                        new HelloAck(1, "relay", FrameCodec.MAX_PAYLOAD, heartbeatSeconds, List.of("BYTE_RELAY")),
                        hello.requestId()), nowMs)).frame();
        AduFrame response = oneOutbound(machine.onFrame(
                frame(FrameKind.AUTH_CHALLENGE, new AuthChallenge(new byte[]{9}, "device"), authBegin.requestId()), nowMs)).frame();
        assertTrue(machine.onFrame(
                frame(FrameKind.AUTH_OK, new AuthOk(endpoint, List.of()), response.requestId()), nowMs).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
    }

    @Test
    void idleTimerSendsPingAtNegotiatedIntervalAndAcceptsMatchingPong() {
        authenticateAt(1000, 10);
        assertTrue(machine.onTimer(10999).isEmpty());
        AduFrame pingFrame = oneOutbound(machine.onTimer(11000)).frame();
        assertEquals(FrameKind.PING, pingFrame.kind());
        Ping ping = (Ping) PayloadCodec.decode(pingFrame.kind(), pingFrame.payload());
        assertEquals(11000, ping.monotonicHint());
        assertTrue(machine.heartbeatOutstanding());

        assertTrue(machine.onFrame(frame(FrameKind.PONG, new Pong(ping.nonce()), 0), 11050).isEmpty());
        assertFalse(machine.heartbeatOutstanding());
        assertEquals(11050, machine.lastPeerActivityMs());
    }

    @Test
    void relayPingGetsImmediatePongAndRefreshesActivity() {
        authenticateAt(0, 10);
        Ping ping = new Ping(99, 1234);
        AduFrame pongFrame = oneOutbound(machine.onFrame(frame(FrameKind.PING, ping, 77), 5000)).frame();
        assertEquals(FrameKind.PONG, pongFrame.kind());
        assertEquals(77, pongFrame.requestId());
        assertEquals(99, ((Pong) PayloadCodec.decode(FrameKind.PONG, pongFrame.payload())).nonce());
        assertEquals(5000, machine.lastPeerActivityMs());
    }

    @Test
    void missingPongProducesSingleTransportFailureAtThirtySeconds() {
        authenticateAt(0, 10);
        oneOutbound(machine.onTimer(10000));
        assertTrue(machine.onTimer(39999).isEmpty());
        List<RelaySessionMachine.Action> actions = machine.onTimer(40000);
        assertEquals(1, actions.size());
        var failed = assertInstanceOf(RelaySessionMachine.TransportFailed.class, actions.get(0));
        assertEquals("HEARTBEAT_TIMEOUT", failed.reason());
        assertEquals(RelaySessionMachine.State.FAILED, machine.state());
        assertTrue(machine.onTimer(50000).isEmpty());
    }

    @Test
    void wrongPongNonceIsProtocolViolation() {
        authenticateAt(0, 10);
        AduFrame pingFrame = oneOutbound(machine.onTimer(10000)).frame();
        Ping ping = (Ping) PayloadCodec.decode(FrameKind.PING, pingFrame.payload());
        assertThrows(ProtocolException.class, () -> machine.onFrame(
                frame(FrameKind.PONG, new Pong(ping.nonce() + 1), 0), 10010));
    }

    @Test
    void unsolicitedPongIsProtocolViolation() {
        authenticateAt(0, 10);
        assertThrows(ProtocolException.class, () -> machine.onFrame(frame(FrameKind.PONG, new Pong(1), 0), 100));
    }

    @Test
    void zeroHeartbeatIntervalDisablesPings() {
        authenticateAt(0, 0);
        assertTrue(machine.onTimer(1_000_000).isEmpty());
        assertFalse(machine.heartbeatOutstanding());
    }

    @Test
    void noPingBeforeAuthentication() {
        machine.onTlsConnected(0);
        assertTrue(machine.onTimer(60_000).isEmpty());
    }

    @Test
    void inboundTrafficDefersNextPing() {
        authenticateAt(0, 10);
        machine.onFrame(frame(FrameKind.PING, new Ping(5, 0), 0), 8000);
        assertTrue(machine.onTimer(17999).isEmpty());
        assertEquals(FrameKind.PING, oneOutbound(machine.onTimer(18000)).frame().kind());
    }
}
