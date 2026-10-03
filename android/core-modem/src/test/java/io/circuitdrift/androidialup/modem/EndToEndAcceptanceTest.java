package io.circuitdrift.androidialup.modem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * In-process port of the I1 "End-to-end acceptance exercised" sequence
 * (docs/implementation/I1_AT_DTE_STATUS.md, steps 1-8) against a loopback {@link SessionPort}
 * and a stepped monotonic clock standing in for the TCP DTE server's timer tick.
 */
class EndToEndAcceptanceTest {

    /** Loopback session: everything written is echoed back through the listener. */
    private static final class LoopbackSession implements SessionPort {
        SessionListener listener;
        int writes;

        @Override
        public void dial(String target) {
            assertEquals("loopback", target);
            listener.onCallConnected();
        }

        @Override
        public void writeData(byte[] data) {
            writes++;
            listener.onRemoteData(data);
        }

        @Override
        public void hangup(String reason) {
            // Local hangup: the relay would confirm asynchronously; nothing to echo.
        }

        @Override
        public void answer() {}
    }

    private final LoopbackSession session = new LoopbackSession();
    private final RecordingDte dte = new RecordingDte();
    private final ModemController controller = new ModemController(session, dte, "e2e-test");
    private long now;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Advance the clock in 5 ms ticks (TcpDteServer timer_tick_ms=5), servicing the timer. */
    private void sleepMs(long millis) {
        long deadline = now + millis * 1_000_000L;
        while (now < deadline) {
            now = Math.min(deadline, now + 5_000_000L);
            controller.onTimeAdvanced(now);
        }
    }

    private List<String> command(String raw) {
        dte.clear();
        controller.feedDte(b(raw), now);
        return dte.resultLines();
    }

    @Test
    void terminalDialBinaryEscapeResumeAndHangup() {
        session.listener = controller;
        controller.profile().setEcho(false);

        // 1. AT -> OK
        assertEquals(List.of("OK\r\n"), command("AT\r"));

        // 2. Short test guard time: S12=1 means a 20 ms guard interval.
        assertEquals(List.of("OK\r\n"), command("ATS12=1\r"));

        // 3. ATDloopback -> CONNECT
        assertEquals(List.of("CONNECT\r\n"), command("ATDloopback\r"));
        assertEquals(ModemState.ONLINE_DATA, controller.state());
        assertTrue(controller.signals().dcd());

        // 4. Deterministic 64 KiB binary payload round trip, unchanged.
        byte[] payload = new byte[64 * 1024];
        new Random(0xAD1A1L).nextBytes(payload);
        dte.clear();
        controller.feedDte(payload, now);
        assertArrayEquals(payload, dte.bytes());
        assertTrue(session.writes <= 4, "aggregated per DTE read, writes=" + session.writes);

        // 5. Wait pre-guard, send +++, timer satisfies post-guard -> OK, ONLINE_COMMAND.
        sleepMs(30);
        dte.clear();
        controller.feedDte(b("+++"), now);
        assertEquals(0, dte.writes.size(), "escape chars are held, not forwarded");
        sleepMs(30);
        assertEquals(List.of("OK\r\n"), dte.resultLines());
        assertEquals(ModemState.ONLINE_COMMAND, controller.state());
        assertTrue(controller.signals().dcd());

        // 6. ATO -> CONNECT, back to ONLINE_DATA.
        assertEquals(List.of("CONNECT\r\n"), command("ATO\r"));
        assertEquals(ModemState.ONLINE_DATA, controller.state());

        // 7. Escape again and ATH -> OK.
        sleepMs(30);
        dte.clear();
        controller.feedDte(b("+++"), now);
        sleepMs(30);
        assertEquals(List.of("OK\r\n"), dte.resultLines());
        assertEquals(ModemState.ONLINE_COMMAND, controller.state());
        assertEquals(List.of("OK\r\n"), command("ATH\r"));

        // 8. DCD low, modem state COMMAND.
        assertEquals(ModemState.COMMAND, controller.state());
        assertFalse(controller.signals().dcd());
    }

    @Test
    void plusBytesInsideDataStreamAreNotMistakenForEscape() {
        session.listener = controller;
        controller.profile().setEcho(false);
        command("ATS12=1\r");
        command("ATDloopback\r");
        sleepMs(30);
        dte.clear();
        // The data byte before the pluses resets the pre-guard; the trailing bytes cancel it.
        controller.feedDte(b("a+++b"), now);
        assertArrayEquals(b("a+++b"), dte.bytes());
        assertEquals(ModemState.ONLINE_DATA, controller.state());
        sleepMs(100);
        assertEquals(ModemState.ONLINE_DATA, controller.state());
    }
}
