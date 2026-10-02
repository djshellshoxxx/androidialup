package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.modem.ModemController;
import io.circuitdrift.androidialup.modem.ModemState;
import io.circuitdrift.androidialup.protocol.FrameKind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * In-process end to end: DTE bytes -> {@link ModemController} -> {@link ModemRelayBridge} ->
 * {@link RelaySessionMachine} -> wire bytes -> {@link FakeRelay} (loopback backend) and back,
 * on one thread with a stepped monotonic clock.
 */
class ModemRelayEndToEndTest {
    private static final byte[] SECRET = new byte[32];
    static { Arrays.fill(SECRET, (byte) 0x2a); }

    private final List<byte[]> dteWrites = new ArrayList<>();
    private FakeRelay relay;
    private ModemRelayBridge bridge;
    private ModemController controller;
    private long nowNanos;

    @BeforeEach
    void setUp() {
        relay = new FakeRelay(SECRET);
        byte[] endpoint = new byte[32];
        Arrays.fill(endpoint, (byte) 0x11);
        Random ids = new Random(7);
        bridge = new ModemRelayBridge(
                () -> new RelaySessionMachine(endpoint, new DeviceCredentialProof(SECRET), () -> {
                    byte[] id = new byte[16];
                    ids.nextBytes(id);
                    id[0] |= 1;
                    return id;
                }),
                relay, NetworkTransport.WIFI, () -> nowNanos / 1_000_000L);
        controller = new ModemController(bridge, data -> dteWrites.add(data.clone()), "e2e-relay");
        bridge.setListener(controller);
        controller.profile().setEcho(false);
    }

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    private byte[] dteBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dteWrites.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private List<String> resultLines() {
        List<String> lines = new ArrayList<>();
        for (byte[] chunk : dteWrites) {
            String s = new String(chunk, StandardCharsets.ISO_8859_1);
            if (s.endsWith("\r\n")) lines.add(s);
        }
        return lines;
    }

    /** One host loop iteration: deliver relay frames, then service both timers. */
    private void hostTick() {
        relay.pump(bridge);
        bridge.onTimer();
        relay.pump(bridge);
        controller.onTimeAdvanced(nowNanos);
    }

    private void sleepMs(long millis) {
        long deadline = nowNanos + millis * 1_000_000L;
        while (nowNanos < deadline) {
            nowNanos = Math.min(deadline, nowNanos + 5_000_000L);
            hostTick();
        }
    }

    private List<String> command(String raw) {
        dteWrites.clear();
        controller.feedDte(b(raw), nowNanos);
        hostTick();
        return resultLines();
    }

    /** ATD triggers the connect request; the host's TLS transport then reports the connection. */
    private void dialLoopback() {
        dteWrites.clear();
        controller.feedDte(b("ATDloopback\r"), nowNanos);
        assertEquals(ModemState.DIALING, controller.state());
        assertEquals(1, relay.connectRequests);
        nowNanos += 50_000_000L; // TLS handshake time
        bridge.onTlsConnected();
        hostTick();
        assertEquals(List.of("CONNECT\r\n"), resultLines());
        assertEquals(ModemState.ONLINE_DATA, controller.state());
        assertTrue(controller.signals().dcd());
    }

    @Test
    void dialDataBothWaysEscapeAndHangupFollowCanonicalOrdering() {
        assertEquals(List.of("OK\r\n"), command("AT\r"));
        assertEquals(List.of("OK\r\n"), command("ATS12=1\r"));
        dialLoopback();

        // Binary payload, including NUL and 0xFF and a would-be escape pattern, crosses the
        // relay and comes back through the loopback backend unchanged.
        byte[] payload = new byte[96 * 1024];
        new Random(0xAD1A1L).nextBytes(payload);
        payload[0] = 0; payload[1] = (byte) 0xff; payload[2] = '+'; payload[3] = '+'; payload[4] = '+';
        dteWrites.clear();
        controller.feedDte(payload, nowNanos);
        hostTick();
        assertArrayEquals(payload, dteBytes());
        assertArrayEquals(payload, relay.echoed.toByteArray());
        RelaySessionSnapshot during = bridge.snapshot();
        assertEquals(payload.length, during.outboundSeq());
        assertEquals(payload.length, during.inboundSeq());
        assertEquals(0, during.pendingOutboundBytes());

        // Relay-originated PING mid-call is answered without disturbing the data path.
        relay.ping(42);
        hostTick();
        assertEquals(ModemState.ONLINE_DATA, controller.state());

        // Timed +++ -> OK in ONLINE_COMMAND; the call stays up.
        sleepMs(30);
        dteWrites.clear();
        controller.feedDte(b("+++"), nowNanos);
        sleepMs(30);
        assertEquals(List.of("OK\r\n"), resultLines());
        assertEquals(ModemState.ONLINE_COMMAND, controller.state());
        assertEquals(RelaySessionMachine.State.CONNECTED, bridge.snapshot().state());

        // ATH -> OK, DCD low, relay confirms with HANGUP_ACK then CALL_TERMINATED.
        assertEquals(List.of("OK\r\n"), command("ATH\r"));
        assertEquals(ModemState.COMMAND, controller.state());
        assertFalse(controller.signals().dcd());
        sleepMs(10);
        assertEquals(List.of(), resultLines().stream().filter(l -> l.contains("NO CARRIER")).toList());
        assertEquals(RelaySessionMachine.State.IDLE, bridge.snapshot().state());
        assertEquals("LOCAL_HANGUP", bridge.snapshot().terminalReason());
        assertTrue(relay.closes.isEmpty());

        assertEquals(List.of(
                "C:HELLO", "R:HELLO_ACK", "C:AUTH_BEGIN", "R:AUTH_CHALLENGE", "C:AUTH_RESPONSE", "R:AUTH_OK",
                "C:DIAL_REQUEST", "R:DIAL_ACCEPTED", "R:CALL_PROGRESS", "C:DATA_BYTES", "R:DATA_BYTES",
                "C:HANGUP_REQUEST", "R:HANGUP_ACK", "R:CALL_TERMINATED"), canonical(relay.wire));

        // The transport is reusable: a second call on the same TLS session.
        dteWrites.clear();
        controller.feedDte(b("ATDloopback\r"), nowNanos);
        hostTick();
        assertEquals(List.of("CONNECT\r\n"), resultLines());
        assertEquals(1, relay.connectRequests);
    }

    @Test
    void heartbeatTimeoutDropsCarrierWithNoCarrierAndDiagnosticReason() {
        assertEquals(List.of("OK\r\n"), command("AT+DIAG=1\r"));
        dialLoopback();
        relay.answerPings = false;
        dteWrites.clear();

        sleepMs(10_000); // negotiated 10 s heartbeat -> PING
        assertTrue(bridge.snapshot().heartbeatOutstanding());
        assertTrue(resultLines().isEmpty());
        sleepMs(29_990);
        assertEquals(ModemState.ONLINE_DATA, controller.state());
        sleepMs(10);

        assertEquals(List.of("+ADIAG: " + RelaySessionMachine.HEARTBEAT_TIMEOUT + "\r\n", "NO CARRIER\r\n"), resultLines());
        assertEquals(ModemState.COMMAND, controller.state());
        assertFalse(controller.signals().dcd());
        assertEquals(List.of(RelaySessionMachine.HEARTBEAT_TIMEOUT), relay.closes);
        assertEquals(RelaySessionMachine.HEARTBEAT_TIMEOUT, bridge.snapshot().terminalReason());

        // Exactly one terminal result: more time produces nothing further.
        dteWrites.clear();
        sleepMs(60_000);
        assertTrue(dteWrites.isEmpty());
    }

    @Test
    void busyDialReturnsBusyAndStaysInCommandMode() {
        relay.dialOutcome = FakeRelay.DialOutcome.BUSY;
        dteWrites.clear();
        controller.feedDte(b("ATDbusy\r"), nowNanos);
        bridge.onTlsConnected();
        hostTick();
        assertEquals(List.of("BUSY\r\n"), resultLines());
        assertEquals(ModemState.COMMAND, controller.state());
        assertEquals("BACKEND_BUSY", bridge.snapshot().terminalReason());
    }

    /** Drops heartbeat/flow chatter and collapses repeats, leaving the section 16 skeleton. */
    private static List<String> canonical(List<String> wire) {
        List<String> out = new ArrayList<>();
        for (String entry : wire) {
            if (entry.endsWith(FrameKind.FLOW_STATUS.name()) || entry.endsWith(FrameKind.PING.name())
                    || entry.endsWith(FrameKind.PONG.name())) continue;
            if (!out.isEmpty() && out.get(out.size() - 1).equals(entry)) continue;
            out.add(entry);
        }
        // Stop at the first call's CALL_TERMINATED.
        int end = out.indexOf("R:CALL_TERMINATED");
        return end < 0 ? out : out.subList(0, end + 1);
    }
}
