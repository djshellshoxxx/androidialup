package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.modem.ResultCode;
import io.circuitdrift.androidialup.modem.SessionListener;
import io.circuitdrift.androidialup.protocol.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ModemRelayBridgeTest {
    private static final byte[] SECRET = new byte[32];
    static { Arrays.fill(SECRET, (byte) 0x2a); }

    /** Records listener callbacks as strings in arrival order. */
    static final class RecordingListener implements SessionListener {
        final List<String> events = new ArrayList<>();
        @Override public void onCallConnected() { events.add("connected"); }
        @Override public void onDialFailed(ResultCode failure) { events.add("failed:" + failure); }
        @Override public void onCallTerminated(String reason) { events.add("terminated:" + reason); }
        @Override public void onRemoteData(byte[] data) { events.add("data:" + new String(data, java.nio.charset.StandardCharsets.ISO_8859_1)); }
    }

    private final byte[] endpoint = filled(0x11);
    private FakeRelay relay;
    private RecordingListener listener;
    private ModemRelayBridge bridge;
    private long nowMs;
    private int callCounter;

    private static byte[] filled(int value) {
        byte[] out = new byte[32];
        Arrays.fill(out, (byte) value);
        return out;
    }

    private ModemRelayBridge newBridge(byte[] secret) {
        return new ModemRelayBridge(
                () -> new RelaySessionMachine(endpoint, new DeviceCredentialProof(secret), () -> {
                    byte[] id = new byte[16];
                    id[0] = 1;
                    id[15] = (byte) ++callCounter;
                    return id;
                }),
                relay, NetworkTransport.WIFI, () -> nowMs);
    }

    @BeforeEach
    void setUp() {
        relay = new FakeRelay(SECRET);
        listener = new RecordingListener();
        bridge = newBridge(SECRET);
        bridge.setListener(listener);
    }

    private void connectAndAuthenticate() {
        bridge.onTlsConnected();
        relay.pump(bridge);
        assertEquals(RelaySessionMachine.State.IDLE, bridge.snapshot().state());
    }

    @Test
    void dialBeforeTransportRequestsConnectAndIsSentAfterAuthOk() {
        bridge.dial("loopback");
        assertEquals(1, relay.connectRequests);
        assertTrue(relay.received.isEmpty());
        bridge.onTlsConnected();
        relay.pump(bridge);
        assertEquals(List.of(FrameKind.HELLO, FrameKind.AUTH_BEGIN, FrameKind.AUTH_RESPONSE, FrameKind.DIAL_REQUEST), relay.received);
        assertEquals(List.of("connected"), listener.events);
        assertEquals(RelaySessionMachine.State.CONNECTED, bridge.snapshot().state());
    }

    @Test
    void dataFlowsBothWaysAndHangupCompletesCleanly() {
        connectAndAuthenticate();
        bridge.dial("loopback");
        relay.pump(bridge);
        bridge.writeData("hello".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        relay.pump(bridge);
        assertEquals(List.of("connected", "data:hello"), listener.events);
        bridge.hangup("LOCAL_HANGUP");
        relay.pump(bridge);
        assertEquals(List.of("connected", "data:hello"), listener.events, "local hangup produces no callback");
        assertEquals(RelaySessionMachine.State.IDLE, bridge.snapshot().state());
        assertEquals("LOCAL_HANGUP", bridge.snapshot().terminalReason());
    }

    @ParameterizedTest
    @CsvSource({"BUSY,BUSY", "NO_DIALTONE,NO_DIALTONE", "NO_ANSWER,NO_ANSWER", "GATEWAY_UNAVAILABLE,NO_CARRIER"})
    void dialFailuresMapToV250ResultCodes(FakeRelay.DialOutcome outcome, ResultCode expected) {
        connectAndAuthenticate();
        relay.dialOutcome = outcome;
        bridge.dial("x");
        relay.pump(bridge);
        assertEquals(List.of("failed:" + expected), listener.events);
        assertEquals(RelaySessionMachine.State.IDLE, bridge.snapshot().state());
    }

    @Test
    void everyDialFailureMapsToAPermittedDialResult() {
        for (DialFailure failure : DialFailure.values()) {
            assertTrue(ModemRelayBridge.resultFor(failure).isDialFailure(), failure.name());
        }
        assertEquals(ResultCode.NO_CARRIER, ModemRelayBridge.resultFor(DialFailure.TIMEOUT));
        assertEquals(ResultCode.NO_CARRIER, ModemRelayBridge.resultFor(DialFailure.AUTHORIZATION_DENIED));
    }

    @Test
    void remoteTerminationReachesListenerWithRelayReason() {
        connectAndAuthenticate();
        bridge.dial("loopback");
        relay.pump(bridge);
        relay.remoteHangup("REMOTE_HANGUP");
        relay.pump(bridge);
        assertEquals(List.of("connected", "terminated:REMOTE_HANGUP"), listener.events);
    }

    @Test
    void authFailureClosesTransportAndFailsPendingDial() {
        bridge = newBridge(filled(0x01));
        bridge.setListener(listener);
        bridge.dial("loopback");
        bridge.onTlsConnected();
        relay.pump(bridge);
        assertEquals(List.of(RelaySessionMachine.AUTH_FAILURE), relay.closes);
        assertEquals(List.of("terminated:" + RelaySessionMachine.AUTH_FAILURE), listener.events);
        assertFalse(relay.received.contains(FrameKind.DIAL_REQUEST));
        assertEquals(RelaySessionMachine.State.FAILED, bridge.snapshot().state());
    }

    @Test
    void protocolViolationClosesTransportOnce() {
        connectAndAuthenticate();
        bridge.dial("loopback");
        relay.pump(bridge);
        byte[] wrongCall = new byte[16];
        wrongCall[0] = 9;
        bridge.onFrame(new AduFrame(FrameKind.DATA_BYTES, 0, wrongCall, wrongCall, 0, PayloadCodec.encode(new DataBytes(0, new byte[]{1}))));
        assertEquals(List.of(RelaySessionMachine.PROTOCOL_VIOLATION), relay.closes);
        assertEquals(List.of("connected", "terminated:" + RelaySessionMachine.PROTOCOL_VIOLATION), listener.events);
        // Late frames after the failure are dropped rather than thrown at the reader.
        bridge.onFrame(new AduFrame(FrameKind.DATA_BYTES, 0, wrongCall, wrongCall, 0, PayloadCodec.encode(new DataBytes(0, new byte[]{1}))));
        assertEquals(1, relay.closes.size());
    }

    @Test
    void transportLossDuringCallTerminatesWithNetworkLost() {
        connectAndAuthenticate();
        bridge.dial("loopback");
        relay.pump(bridge);
        bridge.onTransportClosed(RelaySessionMachine.NETWORK_LOST);
        assertEquals(List.of("connected", "terminated:NETWORK_LOST"), listener.events);
        assertEquals(List.of(RelaySessionMachine.NETWORK_LOST), relay.closes);
    }

    @Test
    void connectFailureBeforeTlsFailsPendingDial() {
        bridge.dial("loopback");
        bridge.onTransportClosed("CONNECT_TIMEOUT");
        assertEquals(List.of("terminated:CONNECT_TIMEOUT"), listener.events);
    }

    @Test
    void hangupBeforeDialIsSentCancelsQueuedDial() {
        bridge.dial("loopback");
        bridge.hangup("LOCAL_HANGUP");
        bridge.onTlsConnected();
        relay.pump(bridge);
        assertFalse(relay.received.contains(FrameKind.DIAL_REQUEST));
        assertTrue(listener.events.isEmpty());
        assertEquals(RelaySessionMachine.State.IDLE, bridge.snapshot().state());
    }

    @Test
    void dialAckDeadlineFailsTheCallWithRelayUnavailable() {
        connectAndAuthenticate();
        relay.dialOutcome = FakeRelay.DialOutcome.SILENT;
        bridge.dial("loopback");
        nowMs += 4_999;
        bridge.onTimer();
        assertTrue(listener.events.isEmpty());
        nowMs += 1;
        bridge.onTimer();
        assertEquals(List.of("terminated:" + RelaySessionMachine.RELAY_UNAVAILABLE), listener.events);
        assertEquals(List.of(RelaySessionMachine.RELAY_UNAVAILABLE), relay.closes);
    }

    @Test
    void backendSetupDeadlineReportsNoCarrierOnce() {
        connectAndAuthenticate();
        relay.dialOutcome = FakeRelay.DialOutcome.ACCEPT_ONLY;
        bridge.dial("loopback");
        relay.pump(bridge);
        nowMs += 60_000;
        bridge.onTimer();
        relay.pump(bridge);
        assertEquals(List.of("failed:NO_CARRIER"), listener.events);
        assertEquals(RelaySessionMachine.BACKEND_NO_ANSWER, bridge.snapshot().terminalReason());
        assertEquals(RelaySessionMachine.State.IDLE, bridge.snapshot().state());
        assertTrue(relay.closes.isEmpty(), "the transport stays reusable");
    }

    @Test
    void reconnectAfterFailureUsesFreshMachineAndCanDialAgain() {
        connectAndAuthenticate();
        bridge.onTransportClosed(RelaySessionMachine.NETWORK_LOST);
        assertEquals(RelaySessionMachine.State.FAILED, bridge.snapshot().state());
        bridge.dial("loopback");
        assertEquals(1, relay.connectRequests);
        bridge.onTlsConnected();
        relay.pump(bridge);
        assertEquals(List.of("connected"), listener.events);
    }

    @Test
    void writeDataOutsideConnectedCallIsIgnored() {
        connectAndAuthenticate();
        bridge.writeData(new byte[]{1});
        assertEquals(List.of(FrameKind.HELLO, FrameKind.AUTH_BEGIN, FrameKind.AUTH_RESPONSE), relay.received);
    }
}
