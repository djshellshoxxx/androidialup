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
        List<String> transportOrder = new ArrayList<>();
        FakeTransport transport = new FakeTransport("a", transportOrder);
        UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> transport, events::add);

        assertTrue(lifecycle.attach("a"));
        assertEquals("a", lifecycle.activeKey());
        assertTrue(transport.started);

        lifecycle.detach("a");
        assertNull(lifecycle.activeKey());
        assertTrue(transport.closed);
        assertEquals(List.of("started:a", "detached:a"), events);
        assertEquals(List.of("start:a", "close:a"), transportOrder);
    }

    @Test
    public void replacementClosesPreviousBeforeStartingNext() {
        List<String> events = new ArrayList<>();
        List<String> transportOrder = new ArrayList<>();
        FakeTransport first = new FakeTransport("a", transportOrder);
        FakeTransport second = new FakeTransport("b", transportOrder);
        UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> key.equals("a") ? first : second, events::add);

        assertTrue(lifecycle.attach("a"));
        assertTrue(lifecycle.attach("b"));

        assertTrue(first.closed);
        assertTrue(second.started);
        assertEquals("b", lifecycle.activeKey());
        assertEquals(List.of("started:a", "replaced:a", "started:b"), events);
        assertEquals(List.of("start:a", "close:a", "start:b"), transportOrder);
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
        List<String> transportOrder = new ArrayList<>();
        FakeTransport transport = new FakeTransport("a", transportOrder);
        UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> transport, events::add);

        assertTrue(lifecycle.attach("a"));
        lifecycle.detach("b");

        assertFalse(transport.closed);
        assertEquals("a", lifecycle.activeKey());
        assertEquals(List.of("start:a"), transportOrder);
    }

    private static final class FakeTransport implements UsbDteLifecycle.Transport {
        private final String key;
        private final List<String> order;
        boolean started;
        boolean closed;

        FakeTransport(String key, List<String> order) {
            this.key = key;
            this.order = order;
        }

        @Override public void start() {
            started = true;
            order.add("start:" + key);
        }

        @Override public void close() {
            closed = true;
            order.add("close:" + key);
        }
    }
}
