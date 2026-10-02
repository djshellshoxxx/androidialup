package io.circuitdrift.androidialup.modem;

import java.util.Arrays;

/**
 * Timed {@code +++} escape detector (S1 AT/DTE section 9).
 *
 * <p>Time is injected by the caller as a monotonic clock in nanoseconds; this class never reads
 * the system clock. The guard interval is also passed per call so S12 changes apply immediately.
 *
 * <p>Boundary semantics pinned by the Python reference tests and I1 lessons learned:
 *
 * <ul>
 *   <li>a {@code +} starts a candidate only when at least one guard interval has elapsed since
 *       the last forwarded data byte;
 *   <li>subsequent {@code +} bytes extend the candidate when they arrive within one guard
 *       interval of the previously held {@code +};
 *   <li>the third {@code +} arms a post-guard deadline exactly one guard interval later; the
 *       timer reports {@link EscapeAction#escaped()} once that deadline is reached;
 *   <li>an incomplete one- or two-plus candidate is <em>not</em> flushed immediately by the
 *       timer; it remains held until strictly more than one guard interval has elapsed after the
 *       most recently held {@code +}, then is flushed as ordinary data;
 *   <li>any other byte, or a fourth {@code +}, flushes the held bytes followed by that byte.
 * </ul>
 */
public final class EscapeDetector {

    private static final int MAX_HELD = 3;

    private long lastForwarded;
    private int candidateCount;
    private int candidateChar;
    private long candidateLast;
    private long candidateGuard;
    private long postGuardDeadline;
    private boolean postGuardArmed;

    /** Creates a detector whose last forwarded-data time is {@code lastForwardedNanos}. */
    public EscapeDetector(long lastForwardedNanos) {
        this.lastForwarded = lastForwardedNanos;
    }

    /**
     * Processes one online-data byte.
     *
     * @param value the byte value 0..255
     * @param nowNanos monotonic time of arrival
     * @param escapeChar the S2 value; values above 127 disable detection
     * @param guardNanos the guard interval derived from S12
     */
    public EscapeAction feed(int value, long nowNanos, int escapeChar, long guardNanos) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("byte must fit u8: " + value);
        }
        if (guardNanos < 0) {
            throw new IllegalArgumentException("guard must be non-negative");
        }

        if (escapeChar > 127 || (value != escapeChar && candidateCount == 0)) {
            lastForwarded = nowNanos;
            return EscapeAction.forward(new byte[] {(byte) value});
        }

        if (candidateCount == 0) {
            if (nowNanos - lastForwarded >= guardNanos) {
                candidateCount = 1;
                candidateChar = escapeChar;
                candidateLast = nowNanos;
                candidateGuard = guardNanos;
                return EscapeAction.HELD;
            }
            lastForwarded = nowNanos;
            return EscapeAction.forward(new byte[] {(byte) value});
        }

        if (value == escapeChar && candidateCount < MAX_HELD && nowNanos - candidateLast <= guardNanos) {
            candidateCount++;
            candidateLast = nowNanos;
            candidateGuard = guardNanos;
            if (candidateCount == MAX_HELD) {
                postGuardDeadline = nowNanos + guardNanos;
                postGuardArmed = true;
            }
            return EscapeAction.HELD;
        }

        return cancelAndForward(value, nowNanos);
    }

    /**
     * Services the guard timer. Call periodically while the DTE is idle in ONLINE_DATA.
     *
     * @param nowNanos current monotonic time
     */
    public EscapeAction timer(long nowNanos) {
        if (candidateCount == 0) {
            return EscapeAction.NONE;
        }
        if (candidateCount == MAX_HELD && postGuardArmed) {
            if (nowNanos >= postGuardDeadline) {
                resetCandidate();
                return EscapeAction.ESCAPED;
            }
            return EscapeAction.NONE;
        }
        // An incomplete candidate is data, not an escape. Preserve it for one full guard
        // interval after the most recently held escape character.
        if (nowNanos > candidateLast + candidateGuard) {
            return cancelAndForward(-1, nowNanos);
        }
        return EscapeAction.NONE;
    }

    private EscapeAction cancelAndForward(int currentByte, long nowNanos) {
        int length = candidateCount + (currentByte >= 0 ? 1 : 0);
        byte[] data = new byte[length];
        Arrays.fill(data, 0, candidateCount, (byte) candidateChar);
        if (currentByte >= 0) {
            data[length - 1] = (byte) currentByte;
        }
        resetCandidate();
        lastForwarded = nowNanos;
        return EscapeAction.forward(data);
    }

    private void resetCandidate() {
        candidateCount = 0;
        candidateLast = 0;
        candidateGuard = 0;
        postGuardDeadline = 0;
        postGuardArmed = false;
    }
}
