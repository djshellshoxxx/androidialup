package io.circuitdrift.androidialup.platform.dte;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared I2 DTE over a privileged/provider-managed serial gadget endpoint.
 *
 * <p>This class does not configure USB gadget mode itself. A concrete {@link SerialGadgetProvider}
 * is responsible for provisioning/opening the endpoint on a supported rooted/system/vendor build.
 */
public final class PrivilegedCdcAcmDteTransport implements UsbDteLifecycle.Transport {
    private final SerialGadgetProvider provider;
    private final StreamDteSession.ControllerFactory controllerFactory;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SerialGadgetProvider.Endpoint endpoint;
    private StreamDteSession session;
    private SerialGadgetProvider.Capabilities capabilities = SerialGadgetProvider.Capabilities.NONE;

    public PrivilegedCdcAcmDteTransport(SerialGadgetProvider provider,
                                        StreamDteSession.ControllerFactory controllerFactory) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.controllerFactory = Objects.requireNonNull(controllerFactory, "controllerFactory");
    }

    @Override
    public synchronized void start() throws IOException {
        if (closed.get()) throw new IllegalStateException("transport is closed");
        if (session != null) return;

        SerialGadgetProvider.Endpoint opened = provider.open();
        if (opened == null) throw new IOException("serial gadget provider returned null endpoint");
        try {
            StreamDteSession created = new StreamDteSession(
                    Objects.requireNonNull(opened.input(), "provider input"),
                    Objects.requireNonNull(opened.output(), "provider output"),
                    controllerFactory);
            endpoint = opened;
            capabilities = opened.capabilities() == null
                    ? SerialGadgetProvider.Capabilities.NONE
                    : opened.capabilities();
            session = created;
            created.start();
        } catch (IOException | RuntimeException failure) {
            try { opened.close(); } catch (IOException ignored) {}
            endpoint = null;
            session = null;
            capabilities = SerialGadgetProvider.Capabilities.NONE;
            throw failure;
        }
    }

    public synchronized boolean isRunning() {
        return session != null && !session.isClosed();
    }

    public synchronized SerialGadgetProvider.Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        StreamDteSession currentSession;
        SerialGadgetProvider.Endpoint currentEndpoint;
        synchronized (this) {
            currentSession = session;
            currentEndpoint = endpoint;
            session = null;
            endpoint = null;
            capabilities = SerialGadgetProvider.Capabilities.NONE;
        }
        if (currentSession != null) currentSession.close();
        if (currentEndpoint != null) {
            try { currentEndpoint.close(); } catch (IOException ignored) {}
        }
    }
}
