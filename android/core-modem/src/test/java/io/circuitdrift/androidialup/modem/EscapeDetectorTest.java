package io.circuitdrift.androidialup.modem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Mirrors prototype/python/tests/test_escape.py. Times are injected monotonic nanoseconds; the
 * Python millisecond fixtures are scaled with {@link #ms(long)}.
 */
class EscapeDetectorTest {

    private static final int PLUS = '+';
    private static final int ESC = 43;
    private static final long GUARD = ms(1000);

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    @Test
    void exactPreAndPostGuardProducesEscape() {
        EscapeDetector detector = new EscapeDetector(0);
        assertTrue(detector.feed(PLUS, ms(1000), ESC, GUARD).held());
        assertTrue(detector.feed(PLUS, ms(1100), ESC, GUARD).held());
        assertTrue(detector.feed(PLUS, ms(1200), ESC, GUARD).held());
        assertFalse(detector.timer(ms(2199)).escaped());
        EscapeAction action = detector.timer(ms(2200));
        assertTrue(action.escaped());
        assertEquals(0, action.forward().length);
        assertFalse(detector.timer(ms(5000)).escaped(), "escape must be reported once");
    }

    @Test
    void tooShortPreGuardForwardsPlusAsData() {
        EscapeDetector detector = new EscapeDetector(ms(500));
        EscapeAction action = detector.feed(PLUS, ms(1000), ESC, GUARD);
        assertArrayEquals(new byte[] {'+'}, action.forward());
        assertFalse(action.held());
    }

    @Test
    void interveningByteFlushesHeldPlusBytes() {
        EscapeDetector detector = new EscapeDetector(0);
        detector.feed(PLUS, ms(1000), ESC, GUARD);
        detector.feed(PLUS, ms(1100), ESC, GUARD);
        EscapeAction action = detector.feed('A', ms(1200), ESC, GUARD);
        assertArrayEquals("++A".getBytes(), action.forward());
        assertFalse(action.escaped());
    }

    @Test
    void slowSecondPlusFlushesFirstAndRestartsCandidate() {
        EscapeDetector detector = new EscapeDetector(0);
        detector.feed(PLUS, ms(1000), ESC, GUARD);
        // Over the guard since the held '+': flush it, and the new '+' is itself forwarded
        // because the flush just updated the last-forwarded time.
        EscapeAction action = detector.feed(PLUS, ms(2001), ESC, GUARD);
        assertArrayEquals("++".getBytes(), action.forward());
        assertFalse(action.held());
    }

    @Test
    void extraFourthPlusCancelsEscapeAndForwardsAll() {
        EscapeDetector detector = new EscapeDetector(0);
        detector.feed(PLUS, ms(1000), ESC, GUARD);
        detector.feed(PLUS, ms(1100), ESC, GUARD);
        detector.feed(PLUS, ms(1200), ESC, GUARD);
        EscapeAction action = detector.feed(PLUS, ms(1300), ESC, GUARD);
        assertArrayEquals("++++".getBytes(), action.forward());
        assertFalse(detector.timer(ms(3000)).escaped());
    }

    @Test
    void escapeDetectionDisabledWhenS2Over127() {
        EscapeDetector detector = new EscapeDetector(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long when : new long[] {ms(1000), ms(1100), ms(1200)}) {
            out.writeBytes(detector.feed(PLUS, when, 200, GUARD).forward());
        }
        assertArrayEquals("+++".getBytes(), out.toByteArray());
        assertFalse(detector.timer(ms(3000)).escaped());
    }

    @Test
    void incompleteCandidateWaitsFullGuardBeforeFlush() {
        EscapeDetector detector = new EscapeDetector(0);
        detector.feed(PLUS, ms(1000), ESC, GUARD);
        assertEquals(0, detector.timer(ms(1999)).forward().length);
        assertEquals(0, detector.timer(ms(2000)).forward().length);
        EscapeAction action = detector.timer(ms(2001));
        assertArrayEquals(new byte[] {'+'}, action.forward());
        assertFalse(action.escaped());
        assertEquals(0, detector.timer(ms(3000)).forward().length, "flushed once only");
    }

    @Test
    void twoPlusCandidateUsesLastCharacterForTimeout() {
        EscapeDetector detector = new EscapeDetector(0);
        detector.feed(PLUS, ms(1000), ESC, GUARD);
        detector.feed(PLUS, ms(1500), ESC, GUARD);
        assertEquals(0, detector.timer(ms(2500)).forward().length);
        assertArrayEquals("++".getBytes(), detector.timer(ms(2501)).forward());
    }

    @Test
    void timerWithoutCandidateIsNoOp() {
        EscapeDetector detector = new EscapeDetector(0);
        EscapeAction action = detector.timer(ms(1000));
        assertFalse(action.escaped());
        assertFalse(action.held());
        assertEquals(0, action.forward().length);
    }

    @Test
    void binaryNonEscapeBytesAreForwardedUnchanged() {
        EscapeDetector detector = new EscapeDetector(0);
        int[] values = {0x00, 0x0D, 0xFF};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long now = ms(10);
        for (int value : values) {
            out.writeBytes(detector.feed(value, now, ESC, GUARD).forward());
            now += ms(10);
        }
        assertArrayEquals(new byte[] {0x00, 0x0D, (byte) 0xFF}, out.toByteArray());
    }

    @Test
    void invalidArgumentsAreRejected() {
        EscapeDetector detector = new EscapeDetector(0);
        assertThrows(IllegalArgumentException.class, () -> detector.feed(256, 0, ESC, GUARD));
        assertThrows(IllegalArgumentException.class, () -> detector.feed(-1, 0, ESC, GUARD));
        assertThrows(IllegalArgumentException.class, () -> detector.feed(PLUS, 0, ESC, -1));
    }
}
