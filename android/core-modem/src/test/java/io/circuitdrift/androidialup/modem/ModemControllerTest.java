package io.circuitdrift.androidialup.modem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Mirrors prototype/python/tests/test_modem_controller.py plus S1 section 17 reducer cases. */
class ModemControllerTest {

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private FakeSessionPort port;
    private RecordingDte out;
    private ModemController controller;

    @BeforeEach
    void setUp() {
        port = new FakeSessionPort();
        out = new RecordingDte();
        controller = new ModemController(port, out, "test-build");
        // Echo is covered separately; reducer tests focus on state and results.
        controller.profile().setEcho(false);
    }

    private void connect() {
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onCallConnected();
        out.clear();
        port.clear();
    }

    @Test
    void initialSignalsAndState() {
        assertEquals(ModemState.COMMAND, controller.state());
        assertEquals(new DteSignals(true, false, true, false), controller.signals());
        assertEquals(Optional.empty(), controller.terminalReason());
        ModemSnapshot snapshot = controller.snapshot();
        assertEquals(ModemState.COMMAND, snapshot.state());
        assertFalse(snapshot.signals().dcd());
    }

    @Test
    void atDialConnectAndBinaryData() {
        controller.feedDte(b("AT\r"), 0);
        assertEquals(List.of("OK\r\n"), out.resultLines());
        out.clear();

        controller.feedDte(b("ATDloopback\r"), ms(10));
        assertEquals(ModemState.DIALING, controller.state());
        assertEquals(List.of("loopback"), port.dials);
        assertEquals(0, out.writes.size(), "ATD produces no immediate OK");

        controller.onCallConnected();
        assertEquals(ModemState.ONLINE_DATA, controller.state());
        assertTrue(controller.signals().dcd());
        assertEquals(List.of("CONNECT\r\n"), out.resultLines());

        out.clear();
        byte[] payload = {0x00, 'a', 'b', 'c', (byte) 0xFF, '\r'};
        controller.feedDte(payload, ms(100));
        assertArrayEquals(payload, port.written());
        assertEquals(0, out.writes.size());
    }

    @Test
    void largeOnlineReadIsForwardedInBoundedChunksNotPerByte() {
        connect();
        byte[] payload = new byte[64 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        controller.feedDte(payload, ms(100));
        assertArrayEquals(payload, port.written());
        assertTrue(port.writes.size() <= 4, "writes=" + port.writes.size());
    }

    @Test
    void onlineReadWithTrailingPlusCandidateForwardsTheRestInOneWrite() {
        connect();
        byte[] payload = b("hello world+");
        controller.feedDte(payload, ms(2000));
        // Trailing '+' is not a candidate: data was forwarded at the same instant.
        assertArrayEquals(payload, port.written());
        assertEquals(1, port.writes.size());
    }

    @Test
    void failedDialMapsToTerminalResultOnce() {
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onDialFailed(ResultCode.BUSY);
        controller.onDialFailed(ResultCode.NO_CARRIER);
        assertEquals(List.of("BUSY\r\n"), out.resultLines());
        assertEquals(ModemState.COMMAND, controller.state());
        assertFalse(controller.signals().dcd());
        assertEquals(Optional.of("BUSY"), controller.terminalReason());
    }

    @Test
    void eachDialFailureCodeIsPassedThroughAndOthersMapToNoCarrier() {
        for (ResultCode code : List.of(ResultCode.NO_DIALTONE, ResultCode.NO_ANSWER, ResultCode.NO_CARRIER)) {
            out.clear();
            controller.feedDte(b("ATDloopback\r"), 0);
            controller.onDialFailed(code);
            assertEquals(List.of(code.text() + "\r\n"), out.resultLines());
            assertEquals(ModemState.COMMAND, controller.state());
        }
        out.clear();
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onDialFailed(ResultCode.ERROR);
        assertEquals(List.of("NO CARRIER\r\n"), out.resultLines());
    }

    @Test
    void dialFailureUsesNumericCodeInV0() {
        controller.feedDte(b("ATV0\r"), 0);
        out.clear();
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onDialFailed(ResultCode.NO_DIALTONE);
        assertEquals(List.of("6\r\n"), out.resultLines());
    }

    @Test
    void extendedDiagnosticsLinePrecedesFailureResult() {
        controller.feedDte(b("AT+DIAG=1\r"), 0);
        out.clear();
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onDialFailed(ResultCode.BUSY);
        assertEquals(List.of("+ADIAG: BUSY\r\n", "BUSY\r\n"), out.resultLines());

        connect();
        controller.onCallTerminated("NETWORK_LOST");
        assertEquals(List.of("+ADIAG: NETWORK_LOST\r\n", "NO CARRIER\r\n"), out.resultLines());
    }

    @Test
    void connectedCallbackOutsideDialingIsIgnored() {
        controller.onCallConnected();
        assertEquals(ModemState.COMMAND, controller.state());
        assertFalse(controller.signals().dcd());
        assertEquals(0, out.writes.size());
    }

    @Test
    void escapeAtoAndAth() {
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onCallConnected();
        out.clear();

        controller.feedDte(b("+"), ms(1000));
        controller.feedDte(b("+"), ms(1001));
        controller.feedDte(b("+"), ms(1002));
        controller.onTimeAdvanced(ms(2001));
        assertEquals(ModemState.ONLINE_DATA, controller.state());
        controller.onTimeAdvanced(ms(2002));
        assertEquals(ModemState.ONLINE_COMMAND, controller.state());
        assertEquals(List.of("OK\r\n"), out.resultLines());
        assertEquals(0, port.writes.size());
        assertTrue(controller.signals().dcd(), "call stays up in ONLINE_COMMAND");

        out.clear();
        controller.feedDte(b("ATO\r"), ms(2100));
        assertEquals(ModemState.ONLINE_DATA, controller.state());
        assertEquals(List.of("CONNECT\r\n"), out.resultLines());

        out.clear();
        controller.feedDte(b("+"), ms(3100));
        controller.feedDte(b("+"), ms(3101));
        controller.feedDte(b("+"), ms(3102));
        controller.onTimeAdvanced(ms(4102));
        assertEquals(ModemState.ONLINE_COMMAND, controller.state());
        out.clear();
        controller.feedDte(b("ATH\r"), ms(4200));
        assertEquals(List.of("LOCAL_HANGUP"), port.hangups);
        assertEquals(ModemState.COMMAND, controller.state());
        assertFalse(controller.signals().dcd());
        assertEquals(List.of("OK\r\n"), out.resultLines());
    }

    @Test
    void incompleteEscapeCandidateIsFlushedByTimerAsData() {
        connect();
        controller.feedDte(b("+"), ms(1000));
        controller.feedDte(b("+"), ms(1100));
        controller.onTimeAdvanced(ms(2100));
        assertEquals(0, port.writes.size());
        controller.onTimeAdvanced(ms(2101));
        assertArrayEquals(b("++"), port.written());
        assertEquals(ModemState.ONLINE_DATA, controller.state());
    }

    @Test
    void timerIsIgnoredOutsideOnlineData() {
        controller.onTimeAdvanced(ms(5000));
        assertEquals(ModemState.COMMAND, controller.state());
        assertEquals(0, out.writes.size());
    }

    @Test
    void atoWithoutActiveCallIsError() {
        controller.feedDte(b("ATO\r"), 0);
        assertEquals(List.of("ERROR\r\n"), out.resultLines());
        assertEquals(ModemState.COMMAND, controller.state());
    }

    @Test
    void athDuringDialingCancelsAndReturnsOk() {
        controller.feedDte(b("ATDloopback\r"), 0);
        assertEquals(ModemState.DIALING, controller.state());
        controller.feedDte(b("ATH\r"), ms(10));
        assertEquals(List.of("LOCAL_HANGUP"), port.hangups);
        assertEquals(ModemState.COMMAND, controller.state());
        assertEquals(List.of("OK\r\n"), out.resultLines());
        // A late failure from the cancelled dial must not emit a second result.
        controller.onDialFailed(ResultCode.NO_CARRIER);
        assertEquals(List.of("OK\r\n"), out.resultLines());
    }

    @Test
    void athInCommandStateDoesNotTouchSession() {
        controller.feedDte(b("ATH\r"), 0);
        assertEquals(List.of(), port.hangups);
        assertEquals(List.of("OK\r\n"), out.resultLines());
    }

    @Test
    void atzWithActiveCallHangsUp() {
        connect();
        controller.feedDte(b("+++"), ms(5000));
        controller.onTimeAdvanced(ms(6000));
        assertEquals(ModemState.ONLINE_COMMAND, controller.state());
        out.clear();
        controller.feedDte(b("ATZ\r"), ms(6100));
        assertEquals(List.of("LOCAL_HANGUP"), port.hangups);
        assertEquals(ModemState.COMMAND, controller.state());
        assertTrue(controller.profile().echo(), "ATZ restores the default profile");
    }

    @Test
    void remoteDataAndRemoteHangup() {
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onCallConnected();
        out.clear();
        byte[] data = {'x', 'y', 'z', 0x00, (byte) 0xFF};
        controller.onRemoteData(data);
        assertEquals(1, out.writes.size());
        assertArrayEquals(data, out.writes.get(0));
        out.clear();
        controller.onCallTerminated("REMOTE_HANGUP");
        assertEquals(ModemState.COMMAND, controller.state());
        assertFalse(controller.signals().dcd());
        assertEquals(List.of("NO CARRIER\r\n"), out.resultLines());
        assertEquals(Optional.of("REMOTE_HANGUP"), controller.terminalReason());
    }

    @Test
    void remoteDataIsDroppedOutsideOnlineData() {
        controller.onRemoteData(b("junk"));
        assertEquals(0, out.writes.size());
        connect();
        controller.feedDte(b("+++"), ms(5000));
        controller.onTimeAdvanced(ms(6000));
        out.clear();
        controller.onRemoteData(b("junk"));
        assertEquals(0, out.writes.size());
    }

    @Test
    void remoteTerminationDuringDialingEmitsNoCarrierOnce() {
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onCallTerminated("RELAY_UNAVAILABLE");
        controller.onDialFailed(ResultCode.BUSY);
        assertEquals(List.of("NO CARRIER\r\n"), out.resultLines());
        assertEquals(ModemState.COMMAND, controller.state());
    }

    @Test
    void remoteTerminationInCommandStateIsIgnored() {
        controller.onCallTerminated("REMOTE_HANGUP");
        assertEquals(0, out.writes.size());
        assertEquals(Optional.empty(), controller.terminalReason());
    }

    @Test
    void dteDisconnectHangsUpActiveCall() {
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onCallConnected();
        controller.onDteDisconnected();
        assertFalse(controller.signals().dcd());
        assertEquals(List.of("DTE_DISCONNECTED"), port.hangups);
        assertEquals(ModemState.COMMAND, controller.state());
    }

    @Test
    void dteDisconnectDiscardsPartialCommandLine() {
        controller.feedDte(b("ATE"), 0);
        controller.onDteDisconnected();
        controller.feedDte(b("0\r"), 0);
        assertEquals(List.of("ERROR\r\n"), out.resultLines(), "\"0\" alone is not a command");
    }

    @Test
    void echoLfAfterCrAndBackspace() {
        controller.profile().setEcho(true);
        controller.feedDte(b("AT\r\n"), 0);
        assertEquals("AT\rOK\r\n", out.text(), "LF after CR is ignored and not echoed");
        out.clear();
        controller.feedDte(b("ATX\b\r"), 0);
        assertEquals("ATX\bOK\r\n", out.text(), "backspace removes the previous byte");
    }

    @Test
    void fragmentedAndCoalescedCommandInput() {
        controller.feedDte(b("A"), 0);
        controller.feedDte(b("T"), 0);
        assertEquals(0, out.writes.size());
        controller.feedDte(b("\rATI1\rAT"), 0);
        assertEquals(List.of("OK\r\n", "Beta 0.1\r\n", "OK\r\n"), out.resultLines());
        out.clear();
        controller.feedDte(b("\r"), 0);
        assertEquals(List.of("OK\r\n"), out.resultLines());
    }

    @Test
    void emptyLinesAreIgnoredAndParseErrorsGiveError() {
        controller.feedDte(b("\r\r\n\r"), 0);
        assertEquals(0, out.writes.size());
        controller.feedDte(b("ATV0X\r"), 0);
        assertEquals(List.of("ERROR\r\n"), out.resultLines());
        assertTrue(controller.profile().verbose(), "no partial application");
    }

    @Test
    void overlengthLineReturnsErrorAndDiscardsToTerminator() {
        byte[] line = new byte[600];
        line[0] = 'A';
        line[1] = 'T';
        for (int i = 2; i < line.length; i++) {
            line[i] = 'E';
        }
        controller.feedDte(line, 0);
        assertEquals(0, out.writes.size(), "nothing until the terminator");
        controller.feedDte(b("0\rAT\r"), 0);
        assertEquals(List.of("ERROR\r\n", "OK\r\n"), out.resultLines());
    }

    @Test
    void exactly512ByteLineIsAccepted() {
        StringBuilder sb = new StringBuilder("AT");
        while (sb.length() < 512) {
            sb.append("E1");
        }
        controller.feedDte(b(sb + "\r"), 0);
        assertEquals(List.of("OK\r\n"), out.resultLines());
    }

    @Test
    void quietModeSuppressesResultsAndNumericModeFormatsThem() {
        controller.feedDte(b("ATQ1\r"), 0);
        controller.feedDte(b("ATI0\rATX\r"), 0);
        assertEquals(0, out.writes.size());
        controller.feedDte(b("ATDloopback\r"), 0);
        controller.onDialFailed(ResultCode.BUSY);
        assertEquals(0, out.writes.size());
        controller.feedDte(b("ATQ0V0\r"), 0);
        assertEquals(List.of("0\r\n"), out.resultLines());
    }

    @Test
    void diagQueryReflectsModemAndSessionState() {
        controller.feedDte(b("AT+DIAG?\r"), 0);
        assertEquals(List.of("+DIAG: modem=COMMAND,session=IDLE,extended=0\r\n", "OK\r\n"), out.resultLines());
        connect();
        controller.feedDte(b("+++"), ms(5000));
        controller.onTimeAdvanced(ms(6000));
        out.clear();
        controller.feedDte(b("AT+DIAG?\r"), ms(6100));
        assertEquals(
                List.of("+DIAG: modem=ONLINE_COMMAND,session=ESTABLISHED,extended=0\r\n", "OK\r\n"),
                out.resultLines());
        controller.feedDte(b("AT+MODE=BYTE_RELAY\r"), ms(6200));
        assertEquals("ERROR\r\n", out.resultLines().get(out.resultLines().size() - 1));
    }

    @Test
    void customTerminatorAndLineFeedRegistersAreHonoured() {
        controller.feedDte(b("ATS3=10S4=13\r"), 0);
        out.clear();
        controller.feedDte(b("AT\n"), 0);
        assertEquals("OK\n\r", out.text());
    }

    @Test
    void answerIsForwardedToSession() {
        controller.feedDte(b("ATA\r"), 0);
        assertEquals(1, port.answers);
        assertEquals(0, out.writes.size(), "ATA defers its result like ATD");
    }
}
