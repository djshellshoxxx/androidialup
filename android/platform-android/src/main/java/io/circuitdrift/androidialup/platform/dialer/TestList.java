package io.circuitdrift.androidialup.platform.dialer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Confirmed test-list mode (S1_DIALER_GUI section 3): a short explicit list of individual
 * destinations on the operator's own or authorized test lines.
 *
 * <p>Deliberate limits:
 * <ul>
 *   <li>entries are individual destinations; there is no range expression and no expansion,
 *       and entries containing range-like syntax ({@code ..}, {@code ~}, en/em dash, brackets
 *       or braces) are rejected;</li>
 *   <li>at most {@link #MAX_ENTRIES} entries; an empty list or any invalid entry blocks the
 *       mode;</li>
 *   <li>nothing here dials, iterates or schedules: the GUI dials one entry per explicit
 *       operator action through {@link DialerSession#dial(DestinationInput, Integer)}, and
 *       there is no "next" or auto-advance API;</li>
 *   <li>the attestation must be acknowledged before any entry can be dialed, and the text plus
 *       the acknowledgement are written to the call-log header.</li>
 * </ul>
 */
public final class TestList {
    public static final int MAX_ENTRIES = 10;
    public static final String ATTESTATION_TEXT =
            "I confirm every number in this list is a line I own or a test line I am authorized to call.";

    private final List<DestinationInput> entries;
    private boolean attested;

    private TestList(List<DestinationInput> entries) {
        this.entries = CallLogRecord.immutable(entries);
    }

    /**
     * Validates every entry; throws {@link IllegalArgumentException} naming the first bad entry.
     */
    public static TestList of(List<String> targets, DialMethod method, long perCallTimeoutMs) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(method, "method");
        if (targets.isEmpty()) throw new IllegalArgumentException("test list is empty");
        if (targets.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("test list is limited to " + MAX_ENTRIES + " entries");
        }
        List<DestinationInput> validated = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            String target = targets.get(i);
            if (target == null) throw new IllegalArgumentException("entry " + (i + 1) + " is empty");
            if (looksLikeRange(target)) {
                throw new IllegalArgumentException("entry " + (i + 1)
                        + " looks like a range; list individual destinations only");
            }
            try {
                validated.add(new DestinationInput(target, method, perCallTimeoutMs));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("entry " + (i + 1) + ": " + invalid.getMessage(), invalid);
            }
        }
        return new TestList(validated);
    }

    static boolean looksLikeRange(String target) {
        return target.contains("..") || target.indexOf('~') >= 0 || target.indexOf('–') >= 0
                || target.indexOf('—') >= 0 || target.indexOf('[') >= 0 || target.indexOf(']') >= 0
                || target.indexOf('{') >= 0 || target.indexOf('}') >= 0;
    }

    /** Records the operator's acknowledgement in the log header; required before dialing. */
    public synchronized void attest(CallLog log, long nowMonotonicMs) {
        if (attested) return;
        log.appendHeader(new CallLog.HeaderEntry("TEST_LIST_ATTESTATION", ATTESTATION_TEXT, nowMonotonicMs));
        log.appendHeader(new CallLog.HeaderEntry("TEST_LIST_ACKNOWLEDGED",
                "operator acknowledged; entries=" + entries.size(), nowMonotonicMs));
        attested = true;
    }

    public synchronized boolean attested() {
        return attested;
    }

    public List<DestinationInput> entries() {
        return entries;
    }

    /** The entry to dial for one explicit operator action. */
    public synchronized DestinationInput entryForDial(int index) {
        if (!attested) throw new IllegalStateException("attestation required before dialing a test list");
        return entries.get(index);
    }
}
