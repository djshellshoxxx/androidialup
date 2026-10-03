package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.protocol.*;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * BYTE_RELAY sender/receiver and hangup-race behaviour, checked against
 * prototype/python/androidialup_protocol/byte_relay.py and S1_WIRE_PROTOCOL sections 7, 8, 10.
 */
class RelaySessionByteRelayTest {
    private final byte[] endpoint = new byte[32];
    private final byte[] callId = filled(0x43);
    private final byte[] sessionId = filled(0x53);
    private RelaySessionMachine machine = newMachine(RelaySessionMachine.Options.DEFAULTS);
    private long dialRequestId;

    private RelaySessionMachine newMachine(RelaySessionMachine.Options options) {
        return new RelaySessionMachine(endpoint, (r, e, c) -> new byte[]{1}, callId::clone, options);
    }

    private static byte[] filled(int value) {
        byte[] id = new byte[16];
        Arrays.fill(id, (byte) value);
        return id;
    }

    private AduFrame frame(FrameKind kind, Message message, byte[] call, byte[] session, long requestId) {
        return new AduFrame(kind, 0, call, session, requestId, PayloadCodec.encode(message));
    }

    private AduFrame active(Message message) { return active(message, 0); }

    private AduFrame active(Message message, long requestId) {
        return frame(PayloadCodec.kindFor(message), message, callId, sessionId, requestId);
    }

    private static AduFrame only(List<RelaySessionMachine.Action> actions) {
        assertEquals(1, actions.size(), actions.toString());
        return assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0)).frame();
    }

    private static <T extends Message> T payload(AduFrame frame, Class<T> type) {
        return type.cast(PayloadCodec.decode(frame.kind(), frame.payload()));
    }

    private void dialing() {
        AduFrame hello = only(machine.onTlsConnected(0));
        AduFrame begin = only(machine.onFrame(frame(FrameKind.HELLO_ACK,
                new HelloAck(1, "relay", FrameCodec.MAX_PAYLOAD, 0, List.of()), AduFrame.ZERO_ID, AduFrame.ZERO_ID, hello.requestId()), 0));
        AduFrame response = only(machine.onFrame(frame(FrameKind.AUTH_CHALLENGE,
                new AuthChallenge(new byte[16], "m"), AduFrame.ZERO_ID, AduFrame.ZERO_ID, begin.requestId()), 0));
        machine.onFrame(frame(FrameKind.AUTH_OK, new AuthOk(endpoint, List.of()), AduFrame.ZERO_ID, AduFrame.ZERO_ID, response.requestId()), 0);
        dialRequestId = only(machine.dial("loopback", NetworkTransport.WIFI, 0)).requestId();
    }

    private void accepted() {
        dialing();
        assertTrue(machine.onFrame(frame(FrameKind.DIAL_ACCEPTED,
                new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY), callId, sessionId, dialRequestId), 0).isEmpty());
    }

    private void connected() {
        accepted();
        machine.onFrame(active(new CallProgress(ProgressPhase.CONNECTED, null)), 0);
        assertEquals(RelaySessionMachine.State.CONNECTED, machine.state());
    }

    @Test
    void senderChunksAtMaxDataBytesWithByteOffsets() {
        connected();
        byte[] data = new byte[256 * 300];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        List<RelaySessionMachine.Action> actions = machine.writeData(data);
        assertEquals(3, actions.size());
        long[] seqs = new long[3];
        int[] sizes = new int[3];
        for (int i = 0; i < 3; i++) {
            DataBytes chunk = payload(((RelaySessionMachine.Outbound) actions.get(i)).frame(), DataBytes.class);
            seqs[i] = chunk.streamSeq();
            sizes[i] = chunk.data().length;
        }
        assertArrayEquals(new long[]{0, 32768, 65536}, seqs);
        assertArrayEquals(new int[]{32768, 32768, data.length - 65536}, sizes);
        assertEquals(data.length, machine.outboundSeq());
        assertEquals(RelaySessionMachine.DEFAULT_RECEIVE_WINDOW - data.length, machine.peerWindow());
    }

    @Test
    void zeroWindowBuffersWithoutConsumingSequenceAndRecordsPeerQueue() {
        connected();
        assertTrue(machine.onFrame(active(new FlowStatus(0, 4096))).isEmpty());
        assertEquals(4096, machine.peerQueuedBytes());
        assertTrue(machine.writeData(new byte[]{1, 2, 3}).isEmpty());
        assertEquals(3, machine.pendingOutboundBytes());
        assertEquals(0, machine.outboundSeq());
        AduFrame drained = only(machine.onFrame(active(new FlowStatus(10, 0))));
        assertEquals(0, payload(drained, DataBytes.class).streamSeq());
        assertEquals(7, machine.peerWindow());
    }

    @Test
    void localPendingOverflowTerminatesCallWithQueueOverflowInsteadOfDroppingBytes() {
        connected();
        machine.onFrame(active(new FlowStatus(0, 0)));
        assertTrue(machine.writeData(new byte[RelaySessionMachine.MAX_LOCAL_PENDING]).isEmpty());
        List<RelaySessionMachine.Action> actions = machine.writeData(new byte[]{1});
        assertEquals(2, actions.size(), actions.toString());
        AduFrame hangup = assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0)).frame();
        assertEquals(FrameKind.HANGUP_REQUEST, hangup.kind());
        assertEquals(RelaySessionMachine.QUEUE_OVERFLOW, payload(hangup, HangupRequest.class).reason());
        assertEquals(RelaySessionMachine.QUEUE_OVERFLOW,
                assertInstanceOf(RelaySessionMachine.CallTerminatedAction.class, actions.get(1)).reason());
        assertEquals(RelaySessionMachine.State.HANGING_UP, machine.state());
        assertEquals(0, machine.pendingOutboundBytes());
    }

    @Test
    void receiverNamesGapAndDuplicateLikeThePythonReference() {
        connected();
        machine.onFrame(active(new DataBytes(0, new byte[]{1, 2, 3, 4})));
        ProtocolException overlap = assertThrows(ProtocolException.class, () -> machine.onFrame(active(new DataBytes(2, new byte[]{9}))));
        assertTrue(overlap.getMessage().contains("duplicate/overlap"), overlap.getMessage());
        ProtocolException gap = assertThrows(ProtocolException.class, () -> machine.onFrame(active(new DataBytes(5, new byte[]{9}))));
        assertTrue(gap.getMessage().contains("gap"), gap.getMessage());
        assertEquals(4, machine.inboundSeq());
    }

    @Test
    void defaultModeAdvertisesFullWindowAfterSynchronousDeliveryLikePython() {
        connected();
        List<RelaySessionMachine.Action> actions = machine.onFrame(active(new DataBytes(0, new byte[]{7, 8})));
        assertEquals(2, actions.size());
        assertArrayEquals(new byte[]{7, 8}, ((RelaySessionMachine.InboundData) actions.get(0)).data());
        FlowStatus flow = payload(((RelaySessionMachine.Outbound) actions.get(1)).frame(), FlowStatus.class);
        assertEquals(new FlowStatus(RelaySessionMachine.DEFAULT_RECEIVE_WINDOW, 0), flow);
        assertEquals(0, machine.inboundUnconsumedBytes());
    }

    @Test
    void manualConsumptionAdvertisesRemainingWindowAndQueuedBytes() {
        machine = newMachine(RelaySessionMachine.Options.DEFAULTS.withManualInboundConsumption(true));
        connected();
        List<RelaySessionMachine.Action> actions = machine.onFrame(active(new DataBytes(0, new byte[1000])));
        FlowStatus flow = payload(((RelaySessionMachine.Outbound) actions.get(1)).frame(), FlowStatus.class);
        assertEquals(new FlowStatus(RelaySessionMachine.DEFAULT_RECEIVE_WINDOW - 1000, 1000), flow);
        assertEquals(1000, machine.inboundUnconsumedBytes());

        // Small consumption does not chatter: the window grew by less than one DATA_BYTES chunk.
        assertTrue(machine.onInboundConsumed(1000).isEmpty());
        assertEquals(0, machine.inboundUnconsumedBytes());
        assertThrows(IllegalArgumentException.class, () -> machine.onInboundConsumed(1));
    }

    @Test
    void manualConsumptionReopensClosedWindow() {
        machine = newMachine(RelaySessionMachine.Options.DEFAULTS.withManualInboundConsumption(true));
        connected();
        long seq = 0;
        byte[] chunk = new byte[Messages.MAX_DATA_BYTES];
        List<RelaySessionMachine.Action> last = List.of();
        for (int i = 0; i < 8; i++) {
            last = machine.onFrame(active(new DataBytes(seq, chunk)));
            seq += chunk.length;
        }
        assertEquals(new FlowStatus(0, RelaySessionMachine.DEFAULT_RECEIVE_WINDOW),
                payload(((RelaySessionMachine.Outbound) last.get(1)).frame(), FlowStatus.class));
        AduFrame reopened = only(machine.onInboundConsumed(100));
        assertEquals(new FlowStatus(100, RelaySessionMachine.DEFAULT_RECEIVE_WINDOW - 100), payload(reopened, FlowStatus.class));
        assertTrue(machine.onInboundConsumed(100).isEmpty());
        AduFrame big = only(machine.onInboundConsumed(Messages.MAX_DATA_BYTES));
        assertEquals(200 + Messages.MAX_DATA_BYTES, payload(big, FlowStatus.class).receiveWindowBytes());
    }

    @Test
    void inboundBeyondReceiveBufferTerminatesWithQueueOverflow() {
        machine = newMachine(RelaySessionMachine.Options.DEFAULTS.withManualInboundConsumption(true));
        connected();
        long seq = 0;
        byte[] chunk = new byte[Messages.MAX_DATA_BYTES];
        for (int i = 0; i < 8; i++) {
            machine.onFrame(active(new DataBytes(seq, chunk)));
            seq += chunk.length;
        }
        List<RelaySessionMachine.Action> actions = machine.onFrame(active(new DataBytes(seq, new byte[]{1})));
        assertEquals(2, actions.size(), actions.toString());
        assertEquals(FrameKind.HANGUP_REQUEST, assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0)).frame().kind());
        assertEquals(RelaySessionMachine.QUEUE_OVERFLOW,
                assertInstanceOf(RelaySessionMachine.CallTerminatedAction.class, actions.get(1)).reason());
    }

    @Test
    void hangupDiscardsPendingAndStopsSendingData() {
        connected();
        machine.onFrame(active(new FlowStatus(0, 0)));
        machine.writeData(new byte[]{1, 2, 3});
        AduFrame hangup = only(machine.hangup("LOCAL_HANGUP"));
        assertEquals(FrameKind.HANGUP_REQUEST, hangup.kind());
        assertEquals(0, machine.pendingOutboundBytes());
        assertTrue(machine.onFrame(active(new FlowStatus(1000, 0))).isEmpty());
        assertTrue(machine.drainPending().isEmpty());
        assertThrows(IllegalStateException.class, () -> machine.writeData(new byte[]{4}));
        assertTrue(machine.hangup("LOCAL_HANGUP").isEmpty(), "hangup is idempotent while hanging up");
    }

    @Test
    void inFlightDataAndProgressDuringHangupAreValidatedButNotDelivered() {
        connected();
        AduFrame hangup = only(machine.hangup("LOCAL_HANGUP"));
        assertTrue(machine.onFrame(active(new DataBytes(0, new byte[]{1, 2}))).isEmpty());
        assertEquals(2, machine.inboundSeq());
        assertThrows(ProtocolException.class, () -> machine.onFrame(active(new DataBytes(5, new byte[]{1}))));
        assertTrue(machine.onFrame(active(new HangupAck(), hangup.requestId())).isEmpty());
        assertTrue(machine.onFrame(active(new CallTerminated("LOCAL_HANGUP", TerminationSource.GATEWAY, null))).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
        assertEquals("LOCAL_HANGUP", machine.terminalReason());
    }

    @Test
    void lateConnectedAfterLocalHangupIsNotReported() {
        accepted();
        only(machine.hangup("LOCAL_HANGUP"));
        assertTrue(machine.onFrame(active(new CallProgress(ProgressPhase.CONNECTED, null))).isEmpty());
        assertEquals(RelaySessionMachine.State.HANGING_UP, machine.state());
    }

    @Test
    void hangupBeforeDialAcceptedIsDeferredUntilSessionAssigned() {
        dialing();
        assertTrue(machine.hangup("LOCAL_HANGUP").isEmpty());
        assertEquals(RelaySessionMachine.State.DIALING, machine.state());
        AduFrame hangup = only(machine.onFrame(frame(FrameKind.DIAL_ACCEPTED,
                new DialAccepted(callId, sessionId, "gw", Mode.BYTE_RELAY), callId, sessionId, dialRequestId), 0));
        assertEquals(FrameKind.HANGUP_REQUEST, hangup.kind());
        assertArrayEquals(sessionId, hangup.sessionId());
        assertEquals(RelaySessionMachine.State.HANGING_UP, machine.state());
        assertTrue(machine.onFrame(active(new CallProgress(ProgressPhase.CONNECTED, null))).isEmpty());
        assertTrue(machine.onFrame(active(new HangupAck(), hangup.requestId())).isEmpty());
        assertTrue(machine.onFrame(active(new CallTerminated("LOCAL_HANGUP", TerminationSource.RELAY, null))).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
    }

    @Test
    void hangupBeforeDialFailedSuppressesSecondTerminal() {
        dialing();
        assertTrue(machine.hangup("LOCAL_HANGUP").isEmpty());
        assertTrue(machine.onFrame(frame(FrameKind.DIAL_FAILED, new DialFailed(callId, DialFailure.BUSY, false, null),
                callId, AduFrame.ZERO_ID, dialRequestId), 0).isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
        assertEquals("LOCAL_HANGUP", machine.terminalReason());
    }

    @Test
    void relayInitiatedHangupIsAcknowledgedAndTerminatesOnce() {
        connected();
        AduFrame ack = only(machine.onFrame(active(new HangupRequest("REMOTE_HANGUP"), 77)));
        assertEquals(FrameKind.HANGUP_ACK, ack.kind());
        assertEquals(77, ack.requestId());
        assertArrayEquals(sessionId, ack.sessionId());
        assertEquals(RelaySessionMachine.State.HANGING_UP, machine.state());
        List<RelaySessionMachine.Action> actions = machine.onFrame(active(
                new CallTerminated("REMOTE_HANGUP", TerminationSource.REMOTE, null)));
        assertEquals("REMOTE_HANGUP", assertInstanceOf(RelaySessionMachine.CallTerminatedAction.class, actions.get(0)).reason());
        assertEquals(RelaySessionMachine.State.IDLE, machine.state());
    }

    @Test
    void hangupAckMustAnswerOurHangupRequest() {
        connected();
        assertThrows(ProtocolException.class, () -> machine.onFrame(active(new HangupAck(), 1)));
        AduFrame hangup = only(machine.hangup("LOCAL_HANGUP"));
        assertThrows(ProtocolException.class, () -> machine.onFrame(active(new HangupAck(), hangup.requestId() + 1)));
    }

    @Test
    void dataBeforeConnectedIsProtocolViolation() {
        accepted();
        assertThrows(ProtocolException.class, () -> machine.onFrame(active(new DataBytes(0, new byte[]{1}))));
    }
}
