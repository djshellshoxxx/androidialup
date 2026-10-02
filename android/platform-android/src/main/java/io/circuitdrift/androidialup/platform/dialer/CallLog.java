package io.circuitdrift.androidialup.platform.dialer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Call-progress log (S1_DIALER_GUI section 5). The GUI renders it through {@link Listener};
 * the storage and the view are deliberately decoupled so the on-device view can change without
 * touching dialing. Thread-safe.
 */
public final class CallLog {

    /** Log view hook. Called on the thread that appended (the dialer's modem thread). */
    public interface Listener {
        void onRecordAppended(CallLogRecord record);

        default void onHeaderAppended(HeaderEntry entry) {}
    }

    /** Log header line, e.g. a test-list authorization attestation and its acknowledgement. */
    public record HeaderEntry(String kind, String text, long atMonotonicMs) {
        public HeaderEntry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
        }

        String toJson() {
            return "{\"header\":" + CallLogRecord.Json.string(kind) + ",\"text\":"
                    + CallLogRecord.Json.string(text) + ",\"at_monotonic_ms\":" + atMonotonicMs + "}";
        }
    }

    public static final int DEFAULT_CAPACITY = 500;

    private final int capacity;
    private final Deque<CallLogRecord> records = new ArrayDeque<>();
    private final List<HeaderEntry> header = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public CallLog() {
        this(DEFAULT_CAPACITY);
    }

    public CallLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void append(CallLogRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (this) {
            if (records.size() == capacity) records.removeFirst();
            records.addLast(record);
        }
        for (Listener listener : listeners) listener.onRecordAppended(record);
    }

    public void appendHeader(HeaderEntry entry) {
        Objects.requireNonNull(entry, "entry");
        synchronized (this) {
            header.add(entry);
        }
        for (Listener listener : listeners) listener.onHeaderAppended(entry);
    }

    public synchronized List<CallLogRecord> records() {
        return List.copyOf(records);
    }

    public synchronized List<HeaderEntry> header() {
        return List.copyOf(header);
    }

    /** Newline-delimited JSON: header lines first, then one line per call record. */
    public synchronized String exportNdjson() {
        StringBuilder out = new StringBuilder();
        for (HeaderEntry entry : header) out.append(entry.toJson()).append('\n');
        for (CallLogRecord record : records) out.append(record.toJson()).append('\n');
        return out.toString();
    }
}
