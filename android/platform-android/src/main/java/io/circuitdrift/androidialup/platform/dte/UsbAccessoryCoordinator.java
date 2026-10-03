package io.circuitdrift.androidialup.platform.dte;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Pure policy layer between USB accessory discovery/permission events and the active DTE.
 * Android broadcasts and UsbManager calls stay outside this class so the lifecycle is JVM-tested.
 */
public final class UsbAccessoryCoordinator<K> implements AutoCloseable {
    private final UsbDteLifecycle<K> lifecycle;
    private final Predicate<K> hasPermission;
    private final Consumer<K> requestPermission;
    private final Consumer<String> status;
    private K pendingPermission;

    public UsbAccessoryCoordinator(UsbDteLifecycle<K> lifecycle,
                                   Predicate<K> hasPermission,
                                   Consumer<K> requestPermission,
                                   Consumer<String> status) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.hasPermission = Objects.requireNonNull(hasPermission, "hasPermission");
        this.requestPermission = Objects.requireNonNull(requestPermission, "requestPermission");
        this.status = status == null ? ignored -> {} : status;
    }

    /** Handles a currently attached accessory discovered by Android. */
    public synchronized void discovered(K key) {
        Objects.requireNonNull(key, "key");
        if (hasPermission.test(key)) {
            pendingPermission = null;
            start(key);
            return;
        }
        if (Objects.equals(pendingPermission, key)) return;
        pendingPermission = key;
        status.accept("USB permission required");
        requestPermission.accept(key);
    }

    /** Handles the result of the app's UsbManager.requestPermission PendingIntent. */
    public synchronized void permissionResult(K key, boolean granted) {
        Objects.requireNonNull(key, "key");
        if (!Objects.equals(pendingPermission, key)) return;
        pendingPermission = null;
        if (!granted) {
            status.accept("USB permission denied");
            return;
        }
        start(key);
    }

    /** Handles accessory removal whether it was active or still waiting on permission. */
    public synchronized void detached(K key) {
        Objects.requireNonNull(key, "key");
        if (Objects.equals(pendingPermission, key)) pendingPermission = null;
        lifecycle.detach(key);
        status.accept("USB accessory detached");
    }

    /** Called by the transport when its byte stream ends or fails before a physical detach. */
    public synchronized void transportClosed(K key, String reason) {
        Objects.requireNonNull(key, "key");
        lifecycle.detach(key);
        status.accept("USB DTE closed: " + Objects.requireNonNull(reason, "reason"));
    }

    public synchronized K pendingPermissionKey() {
        return pendingPermission;
    }

    @Override
    public synchronized void close() {
        pendingPermission = null;
        lifecycle.close();
    }

    private void start(K key) {
        if (lifecycle.attach(key)) {
            status.accept("USB DTE active");
        } else {
            status.accept("USB DTE start failed");
        }
    }
}
