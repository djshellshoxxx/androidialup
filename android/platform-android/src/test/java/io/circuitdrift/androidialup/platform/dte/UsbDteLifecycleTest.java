package io.circuitdrift.androidialup.platform.dte;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class UsbDteLifecycleTest {

    @Test
    public void attachStartsOneTransportAndDetachClosesIt() {
        List<String> events = new ArrayList<>();
        FakeTransport transport = new FakeTransport("a", events);
        UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> transport, events::add);

        assertTrue(lifecycle.attach("a"));
        assertEquals("a", lifecycle.activeKey());
        assertTrue(transport.started);

        lifecycle.detach("a");
        assertNull(lifecycle.activeKey());
        assertTrue(transport.closed);
        assertEquals(List.of("started:a", "detached:a"), events);
    }

    @Test
    public void replacementClosesPreviousBeforeStartingNext() {
        List<String> events = new ArrayList<>();
        FakeTransport first = new FakeTransport("a", events);
        FakeTransport second = new FakeTransport("b", events);
        UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> key.equals("a") ? first : second, events::add);

        assertTrue(lifecycle.attach("a"));
        assertTrue(lifecycle.attach("b"));

        assertTrue(first.closed);
        assertTrue(second.started);
        assertEquals("b", lifecycle.activeKey());
        assertEquals(List.of("started:a", "replaced:a", "started:b"), events);
    }

    @Test
    public void failedStartLeavesNoActiveTransportAndReportsReason() {
        List<String> events = new ArrayList<>();
        UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> new UsbDteLifecycle.Transport() {
            @Override public void start() throws IOException { throw new IOException("open failed"); }
            @Override public void close() {}
        }, events::add);

        assertFalse(lifecycle.attach("a"));
        assertNull(lifecycle.activeKey());
        assertEquals(1, events.size());
        assertTrue(events.get(0).startsWith("start_failed:a:IOException"));
    }

    @Test
    public void unrelatedDetachDoesNotCloseActiveTransport() {
        List<String> events = new ArrayList<>();
        FakeTransport transport = new FakeTransport("a", events);
        UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> transport, events::add);

        assertTrue(lifecycle.attach("a"));
        lifecycle.detach("b");

        assertFalse(transport.closed);
        assertEquals("a", lifecycle.activeKey());
    }

    private static final class FakeTransport implements UsbDteLifecycle.Transport {
        private final String key;
        private final List<String> events;
        boolean started;
        boolean closed;

        FakeTransport(String key, List<String> events) {
            this.key = key;
            this.events = events;
        }

        @Override public void start() {
            started = true;
            events.add("started:" + key);
        }

        @Override public void close() {
            closed = true;
        }
    }
}
