package io.circuitdrift.androidialup.platform.dialer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One call's progress record (S1_DIALER_GUI section 5). Immutable; built by
 * {@link DialerSession} from modem result codes, {@code +ADIAG} reasons and relay
 * CALL_PROGRESS phases. Contains no credential, proof, nonce or payload byte; the target is
 * redacted by {@link #redact(String)}.
 *
 * <p>{@code tones}, {@code dtmfDetected} and {@code negotiated} stay empty until a backend
 * reports them (none does over BYTE_RELAY in Beta).
 */
public record CallLogRecord(
        long startedAtMonotonicMs,
        String targetRedacted,
        DialMethod dialMethod,
        List<Progress> progress,
        List<Tone> tones,
        List<Dtmf> dtmfDetected,
        Negotiated negotiated,
        Outcome outcome,
        String internalReason,
        long endedAtMonotonicMs,
        Integer testListEntry) {

    public enum Outcome { CONNECT, BUSY, NO_DIALTONE, NO_ANSWER, NO_CARRIER, ERROR, CANCELLED }

    public record Progress(String phase, String detail, long atMs) {}

    public record Tone(String tone, long atMs) {}

    public record Dtmf(String digit, long atMs) {}

    public record Negotiated(String rate, String protocol) {}

    public CallLogRecord {
        Objects.requireNonNull(targetRedacted, "targetRedacted");
        Objects.requireNonNull(dialMethod, "dialMethod");
        progress = immutable(progress);
        tones = immutable(tones);
        dtmfDetected = immutable(dtmfDetected);
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(internalReason, "internalReason");
    }

    /** Immutable copy without java.util.List.copyOf (API 31 on Android). */
    static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    /** Display policy: all but the last four characters are replaced by {@code x}. */
    public static String redact(String target) {
        int keep = Math.min(4, target.length());
        StringBuilder out = new StringBuilder(target.length());
        for (int i = 0; i < target.length() - keep; i++) out.append('x');
        return out.append(target, target.length() - keep, target.length()).toString();
    }

    /** One NDJSON line (no trailing newline). */
    public String toJson() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"started_at_monotonic_ms\":").append(startedAtMonotonicMs)
                .append(",\"target_redacted\":").append(Json.string(targetRedacted))
                .append(",\"dial_method\":").append(Json.string(dialMethod.name()))
                .append(",\"progress\":[");
        for (int i = 0; i < progress.size(); i++) {
            Progress p = progress.get(i);
            if (i > 0) json.append(',');
            json.append("{\"phase\":").append(Json.string(p.phase()));
            if (p.detail() != null) json.append(",\"detail\":").append(Json.string(p.detail()));
            json.append(",\"at_ms\":").append(p.atMs()).append('}');
        }
        json.append("],\"tones\":[");
        for (int i = 0; i < tones.size(); i++) {
            if (i > 0) json.append(',');
            json.append("{\"tone\":").append(Json.string(tones.get(i).tone()))
                    .append(",\"at_ms\":").append(tones.get(i).atMs()).append('}');
        }
        json.append("],\"dtmf_detected\":[");
        for (int i = 0; i < dtmfDetected.size(); i++) {
            if (i > 0) json.append(',');
            json.append("{\"digit\":").append(Json.string(dtmfDetected.get(i).digit()))
                    .append(",\"at_ms\":").append(dtmfDetected.get(i).atMs()).append('}');
        }
        json.append("],\"negotiated\":{");
        if (negotiated != null) {
            boolean first = true;
            if (negotiated.rate() != null) {
                json.append("\"rate\":").append(Json.string(negotiated.rate()));
                first = false;
            }
            if (negotiated.protocol() != null) {
                if (!first) json.append(',');
                json.append("\"protocol\":").append(Json.string(negotiated.protocol()));
            }
        }
        json.append("},\"outcome\":").append(Json.string(outcome.name()))
                .append(",\"internal_reason\":").append(Json.string(internalReason))
                .append(",\"ended_at_monotonic_ms\":").append(endedAtMonotonicMs);
        if (testListEntry != null) json.append(",\"test_list_entry\":").append(testListEntry.intValue());
        return json.append('}').toString();
    }

    /** Minimal JSON string escaping (no org.json: this class is JVM-tested). */
    static final class Json {
        private Json() {}

        static String string(String value) {
            StringBuilder out = new StringBuilder(value.length() + 2).append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.append('"').toString();
        }
    }
}
