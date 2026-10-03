package io.circuitdrift.androidialup.platform.dte;

import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android Open Accessory (AOA) adapter for the I2 stream DTE engine.
 *
 * <p>In AOA mode the computer/accessory is the USB host and Android is the USB device. This class
 * does not request permission or perform the AOA host-side switch: the application owns the
 * permission UI and passes an already attached {@link UsbAccessory}. Once permission exists, the
 * adapter opens the accessory file descriptor and runs the byte stream through
 * {@link StreamDteSession}.
 *
 * <p>AOA is an unprivileged USB path but is not CDC-ACM and does not by itself create a standard
 * COM port on the computer. A host-side AOA bridge may expose a local tty/COM-style endpoint.
 */
public final class UsbAccessoryDteTransport implements UsbDteLifecycle.Transport {

    public interface Listener {
        default void onStarted(UsbAccessory accessory) {}
        default void onClosed(UsbAccessory accessory, String reason) {}
    }

    private final UsbManager usbManager;
    private final UsbAccessory accessory;
    private final StreamDteSession.ControllerFactory controllerFactory;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ParcelFileDescriptor descriptor;
    private StreamDteSession session;

    public UsbAccessoryDteTransport(UsbManager usbManager, UsbAccessory accessory,
                                    StreamDteSession.ControllerFactory controllerFactory) {
        this(usbManager, accessory, controllerFactory, null);
    }

    public UsbAccessoryDteTransport(UsbManager usbManager, UsbAccessory accessory,
                                    StreamDteSession.ControllerFactory controllerFactory,
                                    Listener listener) {
        this.usbManager = Objects.requireNonNull(usbManager, "usbManager");
        this.accessory = Objects.requireNonNull(accessory, "accessory");
        this.controllerFactory = Objects.requireNonNull(controllerFactory, "controllerFactory");
        this.listener = listener == null ? new Listener() {} : listener;
    }

    /** True when Android has temporary permission for this attached accessory. */
    public boolean hasPermission() {
        return usbManager.hasPermission(accessory);
    }

    /**
     * Opens the accessory and starts the shared stream DTE session.
     *
     * @throws SecurityException if the application has not been granted accessory permission
     * @throws IOException if Android cannot open the accessory
     */
    @Override
    public synchronized void start() throws IOException {
        if (closed.get()) throw new IllegalStateException("transport is closed");
        if (session != null) return;
        if (!usbManager.hasPermission(accessory)) {
            throw new SecurityException("USB accessory permission has not been granted");
        }

        ParcelFileDescriptor opened = usbManager.openAccessory(accessory);
        if (opened == null) throw new IOException("UsbManager.openAccessory returned null");

        FileInputStream input = new FileInputStream(opened.getFileDescriptor());
        FileOutputStream output = new FileOutputStream(opened.getFileDescriptor());
        try {
            StreamDteSession created = new StreamDteSession(
                    input, output, controllerFactory,
                    new StreamDteSession.Listener() {
                        @Override public void onStarted() {
                            listener.onStarted(accessory);
                        }

                        @Override public void onClosed(String reason) {
                            closeDescriptor();
                            listener.onClosed(accessory, reason);
                        }
                    },
                    StreamDteSession.DEFAULT_TIMER_TICK_MS,
                    StreamDteSession.DEFAULT_MAX_QUEUED_DTE_BYTES,
                    StreamDteSession.DEFAULT_MAX_QUEUED_WRITES,
                    System::nanoTime);
            descriptor = opened;
            session = created;
            created.start();
        } catch (RuntimeException failure) {
            try { input.close(); } catch (IOException ignored) {}
            try { output.close(); } catch (IOException ignored) {}
            try { opened.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }

    public synchronized boolean isRunning() {
        return session != null && !session.isClosed();
    }

    public UsbAccessory accessory() {
        return accessory;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        StreamDteSession current;
        synchronized (this) {
            current = session;
        }
        if (current != null) current.close();
        closeDescriptor();
    }

    private synchronized void closeDescriptor() {
        ParcelFileDescriptor current = descriptor;
        descriptor = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) {}
        }
    }
}
