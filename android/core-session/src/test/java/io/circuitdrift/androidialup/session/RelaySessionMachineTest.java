package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.protocol.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelaySessionMachineTest {
    private RelaySessionMachine machine;
    private byte[] endpoint;
    private byte[] callId;
    private byte[] sessionId;

    @BeforeEach
    void setUp() {
        endpoint = new byte[32];
        callId = new byte[16]; java.util.Arrays.fill(callId, (byte) 0x43);
        sessionId = new byte[16]; java.util.Arrays.fill(sessionId, (byte) 0x53);
        machine = new RelaySessionMachine(endpoint, challenge -> new byte[]{0x01,0x02}, () -> callId.clone());
    }

    private RelaySessionMachine.Outbound onlyOutbound(List<RelaySessionMachine.Action> actions) {
        assertEquals(1, actions.size());
        assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0));
        return (RelaySessionMachine.Outbound) actions.get(0);
    }

    private void authenticate() {
        var hello = onlyOutbound(machine.onTlsConnected()).frame();
        assertEquals(FrameKind.HELLO, hello.kind());
        assertArrayEquals(AduFrame.ZERO_ID, hello.callId());

        var ack = new HelloAck(1, "relay", FrameCodec.MAX_PAYLOAD, 10, List.of("BYTE_RELAY"));
        var authBegin = onlyOutbound(machine.onFrame(frame(FrameKind.HELLO_ACK, ack, AduFrame.ZERO_ID, AduFrame.ZERO_ID, hello.requestId()))).frame();
        assertEquals(FrameKind.AUTH_BEGIN, authBegin.kind());

        var challenge = new AuthChallenge(new byte[]{9,8,7}, "device-credential");
        var response = onlyOutbound(machine.onFrame(frame(FrameKind.AUTH_CHALLENGE, challenge, AduFrame.ZERO_ID, AduFrame.ZERO_ID, authBegin.requestId()))).frame();
        assertEquals(FrameKind.AUTH_RESPONSE, response.kind());
        assertArrayEquals(new byte[]{0x01,0x02}, ((AuthResponse) PayloadCodec.decode(response.kind(), response.payload())).proof());

        var ok = new AuthOk(endpoint, List.of(new KeyValue("dial", "allowed")));
        assertTrue(machine.onFrame(frame(FrameKind.AUTH_OK, ok, AduFrame.ZERO_ID, AduFrame.ZERO_ID, response.requestId())).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
    }

    private AduFrame frame(FrameKind kind, Message payload, byte[] call, byte[] session, long requestId) {
        return new AduFrame(kind, 0, call, session, requestId, PayloadCodec.encode(payload));
    }

    @Test
    void handshakeMustOccurInStrictOrder() {
        assertThrows(ProtocolException.class, () -> machine.onFrame(frame(
                FrameKind.HELLO_ACK,
                new HelloAck(1, "relay", FrameCodec.MAX_PAYLOAD, 10, List.of()),
                AduFrame.ZERO_ID, AduFrame.ZERO_ID, 1)));
        authenticate();
    }

    @Test
    void dialAcceptConnectDataAndTerminationUseStrictIds() {
        authenticate();
        var dial = onlyOutbound(machine.dial("loopback", NetworkTransport.WIFI)).frame();
        assertEquals(FrameKind.DIAL_REQUEST, dial.kind());
        assertArrayEquals(callId, dial.callId());
        assertArrayEquals(AduFrame.ZERO_ID, dial.sessionId());
        assertEquals(RelaySessionMachine.State.DIALING, machine.state());

        var accepted = new DialAccepted(callId, sessionId, "gw-loop", Mode.BYTE_RELAY);
        assertTrue(machine.onFrame(frame(FrameKind.DIAL_ACCEPTED, accepted, callId, sessionId, dial.requestId())).isEmpty());
        assertArrayEquals(sessionId, machine.sessionId());

        var connectActions = machine.onFrame(frame(FrameKind.CALL_PROGRESS,
                new CallProgress(ProgressPhase.CONNECTED, "ready"), callId, sessionId, 0));
        assertEquals(RelaySessionMachine.State.CONNECTED, machine.state());
        assertEquals(1, connectActions.size());
        assertInstanceOf(RelaySessionMachine.CallConnected.class, connectActions.get(0));

        var outgoing = machine.writeData(new byte[]{0,1,2,3});
        var dataFrame = onlyOutbound(outgoing).frame();
        var data = (DataBytes) PayloadCodec.decode(dataFrame.kind(), dataFrame.payload());
        assertEquals(0, data.streamSeq());
        assertArrayEquals(new byte[]{0,1,2,3}, data.data());

        var inboundActions = machine.onFrame(frame(FrameKind.DATA_BYTES,
                new DataBytes(0, new byte[]{9,8,7}), callId, sessionId, 0));
        assertEquals(2, inboundActions.size());
        assertInstanceOf(RelaySessionMachine.InboundData.class, inboundActions.get(0));
        assertArrayEquals(new byte[]{9,8,7}, ((RelaySessionMachine.InboundData) inboundActions.get(0)).data());
        assertInstanceOf(RelaySessionMachine.Outbound.class, inboundActions.get(1));
        assertEquals(FrameKind.FLOW_STATUS, ((RelaySessionMachine.Outbound) inboundActions.get(1)).frame().kind());

        assertThrows(ProtocolException.class, () -> machine.onFrame(frame(FrameKind.DATA_BYTES,
                new DataBytes(3, new byte[]{1}), new byte[16], sessionId, 0)));

        var terminated = machine.onFrame(frame(FrameKind.CALL_TERMINATED,
                new CallTerminated("REMOTE_HANGUP", TerminationSource.GATEWAY, null), callId, sessionId, 0));
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
        assertEquals(1, terminated.size());
        assertInstanceOf(RelaySessionMachine.CallTerminatedAction.class, terminated.get(0));
        assertArrayEquals(AduFrame.ZERO_ID, machine.callId());
        assertArrayEquals(AduFrame.ZERO_ID, machine.sessionId());
    }

    @Test
    void inboundByteOffsetsMustBeExactlyContiguous() {
        authenticate();
        var dial = onlyOutbound(machine.dial("loopback", NetworkTransport.WIFI)).frame();
        machine.onFrame(frame(FrameKind.DIAL_ACCEPTED, new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY), callId, sessionId, dial.requestId()));
        machine.onFrame(frame(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.CONNECTED, null), callId, sessionId, 0));
        machine.onFrame(frame(FrameKind.DATA_BYTES, new DataBytes(0, new byte[]{1,2,3}), callId, sessionId, 0));
        assertThrows(ProtocolException.class, () -> machine.onFrame(frame(FrameKind.DATA_BYTES,
                new DataBytes(2, new byte[]{9}), callId, sessionId, 0)));
        assertThrows(ProtocolException.class, () -> machine.onFrame(frame(FrameKind.DATA_BYTES,
                new DataBytes(4, new byte[]{9}), callId, sessionId, 0)));
    }

    @Test
    void dialFailureReturnsIdleWithSingleFailureAction() {
        authenticate();
        var dial = onlyOutbound(machine.dial("busy", NetworkTransport.CELLULAR)).frame();
        var failed = new DialFailed(callId, DialFailure.BUSY, false, "busy");
        var actions = machine.onFrame(frame(FrameKind.DIAL_FAILED, failed, callId, AduFrame.ZERO_ID, dial.requestId()));
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
        assertEquals(1, actions.size());
        assertEquals(DialFailure.BUSY, ((RelaySessionMachine.CallFailed) actions.get(0)).reason());
    }

    @Test
    void hangupUsesActiveIdsAndReturnsIdleOnlyOnTermination() {
        authenticate();
        var dial = onlyOutbound(machine.dial("loopback", NetworkTransport.WIFI)).frame();
        machine.onFrame(frame(FrameKind.DIAL_ACCEPTED, new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY), callId, sessionId, dial.requestId()));
        machine.onFrame(frame(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.CONNECTED, null), callId, sessionId, 0));
        var hang = onlyOutbound(machine.hangup("LOCAL_HANGUP")).frame();
        assertEquals(FrameKind.HANGUP_REQUEST, hang.kind());
        assertArrayEquals(callId, hang.callId());
        assertArrayEquals(sessionId, hang.sessionId());
        assertEquals(RelaySessionMachine.State.HANGING_UP, machine.state());
    }

    @Test
    void peerFlowWindowLimitsOutgoingDataWithoutAdvancingLostBytes() {
        authenticate();
        var dial = onlyOutbound(machine.dial("loopback", NetworkTransport.WIFI)).frame();
        machine.onFrame(frame(FrameKind.DIAL_ACCEPTED, new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY), callId, sessionId, dial.requestId()));
        machine.onFrame(frame(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.CONNECTED, null), callId, sessionId, 0));
        machine.onFrame(frame(FrameKind.FLOW_STATUS, new FlowStatus(3, 0), callId, sessionId, 0));
        var first = machine.writeData(new byte[]{1,2,3,4,5});
        assertEquals(1, first.size());
        var m = (DataBytes) PayloadCodec.decode(((RelaySessionMachine.Outbound) first.get(0)).frame().kind(), ((RelaySessionMachine.Outbound) first.get(0)).frame().payload());
        assertArrayEquals(new byte[]{1,2,3}, m.data());
        assertEquals(2, machine.pendingOutboundBytes());
        machine.onFrame(frame(FrameKind.FLOW_STATUS, new FlowStatus(10, 0), callId, sessionId, 0));
        var drained = machine.drainPending();
        assertEquals(1, drained.size());
        var remainder = (DataBytes) PayloadCodec.decode(((RelaySessionMachine.Outbound) drained.get(0)).frame().kind(), ((RelaySessionMachine.Outbound) drained.get(0)).frame().payload());
        assertEquals(3, remainder.streamSeq());
        assertArrayEquals(new byte[]{4,5}, remainder.data());
    }
}
