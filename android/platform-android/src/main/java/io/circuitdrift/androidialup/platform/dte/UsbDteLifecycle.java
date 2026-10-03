package io.circuitdrift.androidialup.platform.dte;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Android-free lifecycle owner for one USB DTE transport.
 *
 * <p>The key is deliberately opaque to this class so Android can use a {@code UsbAccessory}
 * while JVM tests use strings. At most one transport is active. Replacing or detaching it closes
 * the old transport before any successor is started.
 */
public final class UsbDteLifecycle<K> implements AutoCloseable {

    public interface Transport extends AutoCloseable {
        void start() throws IOException;
        @Override void close();
    }

    private final Function<K, Transport> factory;
    private final Consumer<String> events;
    private K activeKey;
    private Transport activeTransport;

    public UsbDteLifecycle(Function<K, Transport> factory, Consumer<String> events) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.events = events == null ? ignored -> {} : events;
    }

    /** Starts {@code key}, replacing any existing transport. Returns false when startup fails. */
    public synchronized boolean attach(K key) {
        Objects.requireNonNull(key, "key");
        if (Objects.equals(activeKey, key) && activeTransport != null) return true;

        if (activeTransport != null) {
            K previous = activeKey;
            closeActive();
            events.accept("replaced:" + previous);
        }

        Transport candidate = factory.apply(key);
        if (candidate == null) throw new IllegalStateException("USB DTE factory returned null");
        try {
            candidate.start();
        } catch (IOException | RuntimeException failure) {
            try { candidate.close(); } catch (RuntimeException ignored) {}
            events.accept("start_failed:" + key + ":" + failure.getClass().getSimpleName());
            return false;
        }
        activeKey = key;
        activeTransport = candidate;
        events.accept("started:" + key);
        return true;
    }

    /** Detaches only when {@code key} is the active USB endpoint. */
    public synchronized void detach(K key) {
        if (!Objects.equals(activeKey, key) || activeTransport == null) return;
        K previous = activeKey;
        closeActive();
        events.accept("detached:" + previous);
    }

    public synchronized K activeKey() {
        return activeKey;
    }

    public synchronized boolean isActive() {
        return activeTransport != null;
    }

    @Override
    public synchronized void close() {
        closeActive();
    }

    private void closeActive() {
        Transport current = activeTransport;
        activeTransport = null;
        activeKey = null;
        if (current != null) current.close();
    }
}
