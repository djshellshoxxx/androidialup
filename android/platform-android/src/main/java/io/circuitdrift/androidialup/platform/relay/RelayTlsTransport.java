package io.circuitdrift.androidialup.platform.relay;

import io.circuitdrift.androidialup.protocol.AduFrame;
import io.circuitdrift.androidialup.protocol.FrameCodec;
import io.circuitdrift.androidialup.protocol.FrameKind;
import io.circuitdrift.androidialup.protocol.FrameStreamDecoder;
import io.circuitdrift.androidialup.protocol.Messages;
import io.circuitdrift.androidialup.protocol.PayloadCodec;
import io.circuitdrift.androidialup.protocol.ProtocolException;
import io.circuitdrift.androidialup.session.RelaySessionMachine;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One ADUP relay control connection (I1 relay-session plan, Task 4).
 *
 * <p>Ownership (S1_NETWORK_THREADING sections 7-9, 12-13):
 * <ul>
 *   <li>{@code relay-owner}: a single-thread scheduled executor and the <em>only</em> thread
 *       that calls {@link RelaySessionMachine}. Every public method posts to it. It never does
 *       blocking I/O.</li>
 *   <li>{@code relay-io}: runs the {@link Connector} (TCP through the selected Android
 *       {@code Network}, then TLS 1.3), then is the single read owner, feeding a
 *       {@link FrameStreamDecoder} and posting each decoded frame to the owner.</li>
 *   <li>{@code relay-write}: the single write owner, draining a bounded frame queue. A full
 *       queue fails the transport with {@code QUEUE_OVERFLOW}; frames are never dropped.</li>
 *   <li>A monotonic timer tick on the owner calls {@link RelaySessionMachine#onTimer(long)}.</li>
 * </ul>
 *
 * <p>The transport terminates exactly once: {@link Listener#onTransportClosed(String)} is called
 * once with one reason, after which no other listener method fires. Late completions (a socket
 * that finishes connecting after {@link #close(String)}) are closed and ignored.
 *
 * <p>Only the public {@code RelaySessionMachine} API ({@code onTlsConnected(long)},
 * {@code onFrame(AduFrame,long)}, {@code onTimer(long)}, {@code dial}, {@code writeData},
 * {@code hangup}, {@code state()}) and its {@code Action} records are used; unknown future
 * action types are ignored so that core-session can grow without breaking this adapter.
 *
 * <p>No Android types: the Android-specific part is the {@link Connector}
 * ({@link NetworkBoundRelayConnector}), so this class is unit tested on the JVM over loopback.
 */
public final class RelayTlsTransport implements AutoCloseable {

    /** Opens the connected, handshaken byte stream to the relay. Runs on the I/O thread. */
    @FunctionalInterface
    public interface Connector {
        Socket connect() throws IOException;
    }

    /** Transport events, all delivered on the owner thread. */
    public interface Listener {
        /** HELLO/AUTH completed; {@link #dial} is now allowed. */
        default void onReady() {}
        /**
         * Advisory CALL_PROGRESS phase (ROUTING..CONNECTED) accepted by the session machine,
         * for the call-progress log (S1_DIALER_GUI section 5). Never drives modem state.
         */
        default void onCallProgress(Messages.ProgressPhase phase, String detail) {}
        default void onCallConnected(String detail) {}
        default void onRemoteData(byte[] data) {}
        default void onDialFailed(Messages.DialFailure reason, String detail) {}
        default void onCallTerminated(String reason) {}
        /** A request was refused locally (wrong state); the transport stays up. */
        default void onRequestRejected(String operation, String detail) {}
        /** Terminal: called exactly once with one S1 taxonomy reason. */
        void onTransportClosed(String reason);
    }

    /** Tunables. */
    public record Config(int writeQueueFrames, long timerTickMs, long hangupTimeoutMs, int readBufferBytes) {
        public static final Config DEFAULT = new Config(256, 1_000, 5_000, 16 * 1024);

        public Config {
            if (writeQueueFrames < 1) throw new IllegalArgumentException("writeQueueFrames must be positive");
            if (timerTickMs < 1) throw new IllegalArgumentException("timerTickMs must be positive");
            if (hangupTimeoutMs < 1) throw new IllegalArgumentException("hangupTimeoutMs must be positive");
            if (readBufferBytes < 1) throw new IllegalArgumentException("readBufferBytes must be positive");
        }
    }

    /** Immutable diagnostics view, safe to read from any thread. */
    public record Snapshot(RelaySessionMachine.State state, boolean closed, String closeReason,
                           int queuedFrames, long framesSent, long framesReceived) {}

    public static final String QUEUE_OVERFLOW = "QUEUE_OVERFLOW";
    public static final String NETWORK_LOST = "NETWORK_LOST";
    public static final String PROTOCOL_VIOLATION = "PROTOCOL_VIOLATION";
    public static final String AUTH_FAILURE = "AUTH_FAILURE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final RelaySessionMachine machine;
    private final Connector connector;
    private final Listener listener;
    private final LongSupplier clockMs;
    private final Config config;
    private final Consumer<AduFrame> inboundObserver;
    private final ScheduledExecutorService owner;
    private final BlockingQueue<AduFrame> writeQueue;
    private final Object socketLock = new Object();

    // Guarded by socketLock.
    private Socket socket;
    private boolean socketClosed;
    private boolean started;

    private volatile Thread ioThread;
    private volatile Thread writerThread;
    private volatile RelaySessionMachine.State lastState;
    private volatile boolean closed;
    private volatile String closeReason;
    private volatile long framesSent;
    private volatile long framesReceived;

    // Owner-thread only.
    private ScheduledFuture<?> timer;

    /**
     * @param machine a fresh machine in state NEW; from now on owned by this transport
     * @param connector produces the connected TLS socket on the I/O thread
     * @param listener receives events on the owner thread
     * @param clockMs monotonic milliseconds (Android: {@code SystemClock::elapsedRealtime})
     */
    public RelayTlsTransport(RelaySessionMachine machine, Connector connector, Listener listener,
                             LongSupplier clockMs, Config config) {
        this(machine, connector, listener, clockMs, config, null);
    }

    /**
     * As above, plus {@code inboundObserver}, called on the owner thread with every decoded
     * inbound frame just before the machine sees it (e.g. {@link DeviceCredentialAuth}, which
     * needs the HELLO_ACK relay_id). May be null.
     */
    public RelayTlsTransport(RelaySessionMachine machine, Connector connector, Listener listener,
                             LongSupplier clockMs, Config config, Consumer<AduFrame> inboundObserver) {
        this.inboundObserver = inboundObserver;
        this.machine = Objects.requireNonNull(machine, "machine");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.config = Objects.requireNonNull(config, "config");
        this.writeQueue = new ArrayBlockingQueue<>(config.writeQueueFrames());
        this.owner = singleThreadOwner("relay-owner");
        this.lastState = machine.state();
    }

    /** Starts connecting in the background. May be called once. */
    public void start() {
        synchronized (socketLock) {
            if (started) throw new IllegalStateException("already started");
            started = true;
            if (socketClosed) return;
        }
        Thread io = daemon(this::ioMain, "relay-io");
        ioThread = io;
        io.start();
    }

    /** Sends DIAL_REQUEST. Only valid after {@link Listener#onReady()}. */
    public void dial(String target, Messages.NetworkTransport bearer) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bearer, "bearer");
        post(() -> {
            if (closed) return;
            try {
                apply(machine.dial(target, bearer));
            } catch (IllegalStateException | IllegalArgumentException refused) {
                listener.onRequestRejected("dial", refused.getMessage());
            }
        });
    }

    /** Queues online data for BYTE_RELAY; the machine does protocol-sized chunking. */
    public void writeData(byte[] data) {
        if (data == null || data.length == 0) return;
        byte[] copy = data.clone();
        post(() -> {
            if (closed) return;
            if (machine.state() != RelaySessionMachine.State.CONNECTED) {
                listener.onRequestRejected("writeData", "not connected: " + machine.state());
                return;
            }
            try {
                apply(machine.writeData(copy));
            } catch (IllegalStateException overflow) {
                fail(QUEUE_OVERFLOW); // local pending limit: never drop relay bytes
            }
        });
    }

    /**
     * Requests a call hangup. If no session ID was assigned yet (dial in flight) the request
     * cannot be expressed in ADUP and the transport is closed with {@code reason}. Otherwise
     * HANGUP_REQUEST is sent and the transport closes on CALL_TERMINATED, or after
     * {@link Config#hangupTimeoutMs()}.
     */
    public void hangup(String reason) {
        Objects.requireNonNull(reason, "reason");
        post(() -> {
            if (closed) return;
            try {
                apply(machine.hangup(reason));
            } catch (IllegalStateException noSession) {
                fail(reason);
                return;
            }
            if (closed) return;
            // The session machine treats a host-initiated hangup as already known and emits no
            // terminal action (it suppresses the echoing CALL_TERMINATED), so once HANGUP_REQUEST
            // is in flight the transport reports the termination itself, then closes on the ack or
            // after the hangup timeout.
            if (machine.state() == RelaySessionMachine.State.HANGING_UP) {
                listener.onCallTerminated(reason);
            }
            owner.schedule(() -> fail(reason), config.hangupTimeoutMs(), TimeUnit.MILLISECONDS);
        });
    }

    /** Closes the transport with {@code reason} (idempotent, any thread). */
    public void close(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (!post(() -> fail(reason))) {
            closeSocket();
        }
    }

    @Override
    public void close() {
        close("LOCAL_CLOSE");
    }

    public boolean isClosed() {
        return closed;
    }

    public Snapshot snapshot() {
        return new Snapshot(lastState, closed, closeReason, writeQueue.size(), framesSent, framesReceived);
    }

    // ---- I/O threads ---------------------------------------------------------------------

    private void ioMain() {
        Socket connected;
        try {
            connected = connector.connect();
        } catch (IOException failure) {
            String reason = RelayConnectException.classify(failure).reason();
            post(() -> fail(reason));
            return;
        } catch (RuntimeException failure) {
            post(() -> fail(INTERNAL_ERROR));
            return;
        }
        synchronized (socketLock) {
            if (socketClosed) {
                closeQuietly(connected); // late completion after close: ignore
                return;
            }
            socket = connected;
        }
        InputStream in;
        OutputStream out;
        try {
            in = connected.getInputStream();
            out = new BufferedOutputStream(connected.getOutputStream(), 64 * 1024);
        } catch (IOException failure) {
            post(() -> fail(NETWORK_LOST));
            return;
        }
        Thread writer = daemon(() -> writeLoop(out), "relay-write");
        writerThread = writer;
        writer.start();
        post(this::onConnected);
        readLoop(in);
    }

    private void readLoop(InputStream in) {
        FrameStreamDecoder decoder = new FrameStreamDecoder();
        byte[] buffer = new byte[config.readBufferBytes()];
        String reason;
        try {
            while (true) {
                int n = in.read(buffer);
                if (n < 0) {
                    reason = NETWORK_LOST;
                    break;
                }
                if (n == 0) continue;
                List<AduFrame> frames = decoder.feed(Arrays.copyOf(buffer, n));
                for (AduFrame frame : frames) {
                    if (!post(() -> onFrame(frame))) return;
                }
            }
        } catch (ProtocolException violation) {
            reason = PROTOCOL_VIOLATION;
        } catch (IOException failure) {
            reason = NETWORK_LOST;
        } catch (RuntimeException failure) {
            reason = PROTOCOL_VIOLATION;
        }
        String terminal = reason;
        post(() -> fail(terminal));
    }

    private void writeLoop(OutputStream out) {
        try {
            while (true) {
                AduFrame frame = writeQueue.take();
                out.write(FrameCodec.encode(frame));
                framesSent++;
                if (writeQueue.isEmpty()) out.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            post(() -> fail(NETWORK_LOST));
        }
    }

    // ---- owner thread --------------------------------------------------------------------

    private void onConnected() {
        if (closed) return;
        runMachine(() -> machine.onTlsConnected(clockMs.getAsLong()));
        if (closed) return;
        long tick = config.timerTickMs();
        timer = owner.scheduleWithFixedDelay(this::onTick, tick, tick, TimeUnit.MILLISECONDS);
    }

    private void onTick() {
        if (closed) return;
        runMachine(() -> machine.onTimer(clockMs.getAsLong()));
    }

    private void onFrame(AduFrame frame) {
        if (closed) return;
        framesReceived++;
        if (inboundObserver != null) {
            try {
                inboundObserver.accept(frame);
            } catch (RuntimeException failure) {
                fail(INTERNAL_ERROR);
                return;
            }
        }
        RelaySessionMachine.State before = machine.state();
        runMachine(() -> machine.onFrame(frame, clockMs.getAsLong()));
        if (closed) return;
        if (frame.kind() == FrameKind.CALL_PROGRESS) {
            Messages.CallProgress progress = (Messages.CallProgress) PayloadCodec.decode(frame.kind(), frame.payload());
            listener.onCallProgress(progress.phase(), progress.detail());
        }
        RelaySessionMachine.State after = machine.state();
        if (after == RelaySessionMachine.State.FAILED) {
            fail(before == RelaySessionMachine.State.AUTH_RESPONSE_SENT ? AUTH_FAILURE : PROTOCOL_VIOLATION);
        } else if (before == RelaySessionMachine.State.AUTH_RESPONSE_SENT && after == RelaySessionMachine.State.IDLE) {
            listener.onReady();
        } else if (before == RelaySessionMachine.State.HANGING_UP && after == RelaySessionMachine.State.IDLE) {
            fail("LOCAL_HANGUP"); // hangup confirmed; one call per transport in I1
        }
    }

    private interface MachineStep {
        List<RelaySessionMachine.Action> run();
    }

    private void runMachine(MachineStep step) {
        List<RelaySessionMachine.Action> actions;
        try {
            actions = step.run();
        } catch (ProtocolException violation) {
            fail(PROTOCOL_VIOLATION);
            return;
        } catch (RuntimeException failure) {
            fail(INTERNAL_ERROR);
            return;
        }
        apply(actions);
    }

    private void apply(List<RelaySessionMachine.Action> actions) {
        lastState = machine.state();
        for (RelaySessionMachine.Action action : actions) {
            if (closed) return;
            if (action instanceof RelaySessionMachine.Outbound outbound) {
                if (!writeQueue.offer(outbound.frame())) {
                    fail(QUEUE_OVERFLOW);
                    return;
                }
            } else if (action instanceof RelaySessionMachine.CallConnected connected) {
                listener.onCallConnected(connected.detail());
            } else if (action instanceof RelaySessionMachine.InboundData inbound) {
                listener.onRemoteData(inbound.data());
            } else if (action instanceof RelaySessionMachine.CallFailed failed) {
                listener.onDialFailed(failed.reason(), failed.detail());
            } else if (action instanceof RelaySessionMachine.CallTerminatedAction terminated) {
                listener.onCallTerminated(terminated.reason());
            } else if (action instanceof RelaySessionMachine.TransportFailed failed) {
                fail(failed.reason());
                return;
            }
            // Unknown future action types are ignored deliberately (forward compatibility).
        }
    }

    /** Owner thread only. The single terminal transition. */
    private void fail(String reason) {
        if (closed) return;
        closed = true;
        closeReason = reason;
        lastState = machine.state();
        if (timer != null) timer.cancel(false);
        closeSocket();
        Thread writer = writerThread;
        if (writer != null) writer.interrupt();
        writeQueue.clear();
        try {
            listener.onTransportClosed(reason);
        } finally {
            owner.shutdown();
        }
    }

    private void closeSocket() {
        Socket toClose;
        synchronized (socketLock) {
            socketClosed = true;
            toClose = socket;
        }
        if (toClose != null) closeQuietly(toClose);
    }

    private boolean post(Runnable task) {
        try {
            owner.execute(task);
            return true;
        } catch (RejectedExecutionException shutDown) {
            return false;
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }

    /** Single owner thread whose pending delayed tasks are dropped on shutdown. */
    static ScheduledExecutorService singleThreadOwner(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> daemon(r, name));
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
