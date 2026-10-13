package io.circuitdrift.androidialup.platform.dte;

import io.circuitdrift.androidialup.modem.DteWriter;
import io.circuitdrift.androidialup.modem.ModemController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * One modem DTE session over an arbitrary blocking byte-stream pair.
 *
 * <p>This is the transport-neutral I2 foundation shared by Android Open Accessory streams and
 * privileged CDC-ACM/gadget tty streams. It intentionally contains no Android APIs.
 *
 * <p>Thread ownership mirrors {@link TcpDteServer}: one reader owns the input stream, one writer
 * owns the output stream, and one single-thread scheduled executor is the only thread that calls
 * {@link ModemController}. A blocked DTE is disconnected on bounded writer overflow rather than
 * silently dropping modem output.
 */
public final class StreamDteSession implements AutoCloseable {

    public static final long DEFAULT_TIMER_TICK_MS = 10;
    public static final int DEFAULT_MAX_QUEUED_DTE_BYTES = 1024 * 1024;
    public static final int DEFAULT_MAX_QUEUED_WRITES = 1024;
    /** Maximum USB/stream DTE bytes pending consumption on the modem executor. */
    public static final int DEFAULT_MAX_PENDING_INPUT_BYTES = 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final byte[] STOP = new byte[0];
    private static final AtomicInteger IDS = new AtomicInteger();

    /** Builds the modem controller and any session adapter that must post callbacks to it. */
    @FunctionalInterface
    public interface ControllerFactory {
        ModemController create(DteWriter writer, Executor modemExecutor);
    }

    /** Optional lifecycle observer. */
    public interface Listener {
        default void onStarted() {}
        default void onClosed(String reason) {}
    }

    private final int id = IDS.incrementAndGet();
    private final InputStream input;
    private final OutputStream output;
    private final Listener listener;
    private final long timerTickMs;
    private final int maxQueuedDteBytes;
    private final int maxPendingInputBytes;
    private final LongSupplier nanoClock;
    private final ArrayBlockingQueue<byte[]> writeQueue;
    private final AtomicInteger queuedBytes = new AtomicInteger();
    private final AtomicInteger pendingInputBytes = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean disconnectDelivered = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor modemExecutor;
    private final ModemController controller;
    private final DteWriter dteWriter = this::enqueueOutput;

    private volatile Thread readThread;
    private volatile Thread writeThread;
    private volatile ScheduledFuture<?> timerTask;

    public StreamDteSession(InputStream input, OutputStream output, ControllerFactory factory) {
        this(input, output, factory, null, DEFAULT_TIMER_TICK_MS,
                DEFAULT_MAX_QUEUED_DTE_BYTES, DEFAULT_MAX_QUEUED_WRITES, System::nanoTime);
    }

    public StreamDteSession(InputStream input, OutputStream output, ControllerFactory factory,
                            Listener listener, long timerTickMs, int maxQueuedDteBytes,
                            int maxQueuedWrites, LongSupplier nanoClock) {
        this(input, output, factory, listener, timerTickMs, maxQueuedDteBytes, maxQueuedWrites,
                nanoClock, DEFAULT_MAX_PENDING_INPUT_BYTES);
    }

    /** Adds a configurable cap on DTE input awaiting processing by the modem owner. */
    public StreamDteSession(InputStream input, OutputStream output, ControllerFactory factory,
                            Listener listener, long timerTickMs, int maxQueuedDteBytes,
                            int maxQueuedWrites, LongSupplier nanoClock, int maxPendingInputBytes) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        Objects.requireNonNull(factory, "factory");
        this.listener = listener == null ? new Listener() {} : listener;
        this.timerTickMs = Math.max(1, timerTickMs);
        if (maxQueuedDteBytes <= 0) {
            throw new IllegalArgumentException("maxQueuedDteBytes must be positive");
        }
        if (maxQueuedWrites <= 0) {
            throw new IllegalArgumentException("maxQueuedWrites must be positive");
        }
        if (maxPendingInputBytes <= 0) {
            throw new IllegalArgumentException("maxPendingInputBytes must be positive");
        }
        this.maxQueuedDteBytes = maxQueuedDteBytes;
        this.maxPendingInputBytes = maxPendingInputBytes;
        this.writeQueue = new ArrayBlockingQueue<>(maxQueuedWrites);
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.modemExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "stream-dte-modem-" + id);
            thread.setDaemon(true);
            return thread;
        });
        this.modemExecutor.setRemoveOnCancelPolicy(true);
        this.modemExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.modemExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.controller = Objects.requireNonNull(factory.create(dteWriter, modemExecutor),
                "controller factory returned null");
    }

    /** Starts reader, writer and modem idle-timer ownership. Idempotent until closed. */
    public void start() {
        if (closed.get()) throw new IllegalStateException("session is closed");
        if (!started.compareAndSet(false, true)) return;

        writeThread = new Thread(this::writeLoop, "stream-dte-write-" + id);
        writeThread.setDaemon(true);
        readThread = new Thread(this::readLoop, "stream-dte-read-" + id);
        readThread.setDaemon(true);

        timerTask = modemExecutor.scheduleAtFixedRate(() -> {
            if (!closed.get()) {
                controller.onTimeAdvanced(nanoClock.getAsLong());
            }
        }, timerTickMs, timerTickMs, TimeUnit.MILLISECONDS);

        writeThread.start();
        readThread.start();
        listener.onStarted();
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int queuedOutputBytes() {
        return queuedBytes.get();
    }

    /** Executor that session/network adapters use for every callback into ModemController. */
    public Executor modemExecutor() {
        return modemExecutor;
    }

    /** Writer passed to the controller; exposed for adapters/tests that need the local DTE sink. */
    public DteWriter writer() {
        return dteWriter;
    }

    @Override
    public void close() {
        requestClose("CLOSED");
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        try {
            while (!closed.get()) {
                int count = input.read(buffer);
                if (count < 0) {
                    requestClose("DTE_EOF");
                    return;
                }
                if (count == 0) continue;
                // Keep modem executor memory bounded even if a USB host sends data faster
                // than the single controller owner can process it.
                if (!reservePendingInput(count)) {
                    requestClose("DTE_INPUT_QUEUE_OVERFLOW");
                    return;
                }
                byte[] chunk = new byte[count];
                System.arraycopy(buffer, 0, chunk, 0, count);
                if (!postToModem(() -> {
                    try {
                        controller.feedDte(chunk, nanoClock.getAsLong());
                    } finally {
                        pendingInputBytes.addAndGet(-chunk.length);
                    }
                })) {
                    pendingInputBytes.addAndGet(-count);
                    return;
                }
            }
        } catch (IOException failure) {
            if (!closed.get()) requestClose("DTE_READ_FAILURE");
        } catch (RuntimeException failure) {
            if (!closed.get()) requestClose("DTE_READ_FAILURE");
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] chunk = writeQueue.take();
                if (chunk == STOP) return;
                try {
                    output.write(chunk);
                    output.flush();
                } finally {
                    queuedBytes.addAndGet(-chunk.length);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!closed.get()) requestClose("DTE_WRITE_FAILURE");
        } catch (IOException failure) {
            if (!closed.get()) requestClose("DTE_WRITE_FAILURE");
        } catch (RuntimeException failure) {
            if (!closed.get()) requestClose("DTE_WRITE_FAILURE");
        }
    }

    private void enqueueOutput(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0 || closed.get()) return;
        byte[] copy = data.clone();

        while (true) {
            int current = queuedBytes.get();
            long next = (long) current + copy.length;
            if (next > maxQueuedDteBytes) {
                requestClose("DTE_WRITE_QUEUE_OVERFLOW");
                return;
            }
            if (queuedBytes.compareAndSet(current, (int) next)) break;
        }

        if (!writeQueue.offer(copy)) {
            queuedBytes.addAndGet(-copy.length);
            requestClose("DTE_WRITE_QUEUE_OVERFLOW");
        }
    }

    private boolean reservePendingInput(int bytes) {
        while (true) {
            int current = pendingInputBytes.get();
            if ((long) current + bytes > maxPendingInputBytes) return false;
            if (pendingInputBytes.compareAndSet(current, current + bytes)) return true;
        }
    }

    private boolean postToModem(Runnable task) {
        if (closed.get()) return false;
        try {
            // Once a read has been accepted, preserve executor ordering even if EOF arrives
            // immediately afterward. requestClose() queues onDteDisconnected() behind this task.
            modemExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            // Shutdown won the race before the task could be accepted.
            return false;
        }
    }

    private void requestClose(String reason) {
        if (!closed.compareAndSet(false, true)) return;

        ScheduledFuture<?> timer = timerTask;
        if (timer != null) timer.cancel(false);
        closeQuietly(input);
        closeQuietly(output);
        writeQueue.offer(STOP);

        try {
            modemExecutor.execute(() -> {
                if (disconnectDelivered.compareAndSet(false, true)) {
                    controller.onDteDisconnected();
                }
                listener.onClosed(reason);
                modemExecutor.shutdown();
            });
        } catch (RejectedExecutionException rejected) {
            listener.onClosed(reason);
            modemExecutor.shutdownNow();
        }
    }

    private static void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) {}
    }

    private static void closeQuietly(OutputStream stream) {
        try { stream.close(); } catch (IOException ignored) {}
    }
}
