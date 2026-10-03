package io.circuitdrift.androidialup.platform.dte;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class UsbAccessoryCoordinatorTest {

    @Test
    public void discoveredAuthorizedAccessoryStartsImmediately() {
        Harness h = new Harness();
        h.authorized.add("a");

        h.coordinator.discovered("a");

        assertEquals("a", h.lifecycle.activeKey());
        assertEquals(List.of(), h.permissionRequests);
        assertEquals("USB DTE active", h.status.get(h.status.size() - 1));
    }

    @Test
    public void discoveredUnauthorizedAccessoryRequestsPermissionOnce() {
        Harness h = new Harness();

        h.coordinator.discovered("a");
        h.coordinator.discovered("a");

        assertNull(h.lifecycle.activeKey());
        assertEquals(List.of("a"), h.permissionRequests);
        assertEquals("USB permission required", h.status.get(h.status.size() - 1));
    }

    @Test
    public void grantedPermissionStartsPendingAccessory() {
        Harness h = new Harness();
        h.coordinator.discovered("a");
        h.authorized.add("a");

        h.coordinator.permissionResult("a", true);

        assertEquals("a", h.lifecycle.activeKey());
        assertEquals("USB DTE active", h.status.get(h.status.size() - 1));
    }

    @Test
    public void deniedPermissionClearsPendingAndReportsStatus() {
        Harness h = new Harness();
        h.coordinator.discovered("a");

        h.coordinator.permissionResult("a", false);
        h.coordinator.discovered("a");

        assertNull(h.lifecycle.activeKey());
        assertEquals(List.of("a", "a"), h.permissionRequests);
        assertTrue(h.status.contains("USB permission denied"));
    }

    @Test
    public void detachClearsPendingOrActiveAccessory() {
        Harness h = new Harness();
        h.coordinator.discovered("pending");
        h.coordinator.detached("pending");
        h.authorized.add("active");
        h.coordinator.discovered("active");

        h.coordinator.detached("active");

        assertNull(h.lifecycle.activeKey());
        assertEquals("USB accessory detached", h.status.get(h.status.size() - 1));
    }

    @Test
    public void unexpectedTransportClosureClearsActiveAndAllowsRestart() {
        Harness h = new Harness();
        h.authorized.add("a");
        h.coordinator.discovered("a");

        h.coordinator.transportClosed("a", "DTE_READ_FAILURE");
        assertNull(h.lifecycle.activeKey());
        assertEquals("USB DTE closed: DTE_READ_FAILURE", h.status.get(h.status.size() - 1));

        h.coordinator.discovered("a");
        assertEquals("a", h.lifecycle.activeKey());
        assertEquals("USB DTE active", h.status.get(h.status.size() - 1));
    }

    private static final class Harness {
        final Set<String> authorized = new HashSet<>();
        final List<String> permissionRequests = new ArrayList<>();
        final List<String> status = new ArrayList<>();
        final UsbDteLifecycle<String> lifecycle = new UsbDteLifecycle<>(key -> new UsbDteLifecycle.Transport() {
            @Override public void start() {}
            @Override public void close() {}
        }, ignored -> {});
        final UsbAccessoryCoordinator<String> coordinator = new UsbAccessoryCoordinator<>(
                lifecycle,
                authorized::contains,
                permissionRequests::add,
                status::add);
    }
}
