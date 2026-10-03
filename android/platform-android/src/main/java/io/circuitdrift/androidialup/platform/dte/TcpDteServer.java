package io.circuitdrift.androidialup.platform.dte;

import io.circuitdrift.androidialup.modem.DteWriter;
import io.circuitdrift.androidialup.modem.ModemController;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * TCP loopback/LAN DTE developer transport (S1 AT/DTE section 14, I1 required transport): the
 * Java port of {@code prototype/python/androidialup_modem/tcp_dte.py}.
 *
 * <p>Every accepted client gets its own {@link ModemController} and three owners, following
 * S1_NETWORK_THREADING section 9:
 * <ul>
 *   <li>{@code dte-read-N}: the single read owner of the client socket. Each read (however the
 *       TCP stream fragmented or coalesced the bytes) is handed to the modem as one
 *       {@link ModemController#feedDte(byte[], long)} call.</li>
 *   <li>{@code dte-modem-N}: a single-thread executor that is the only thread ever touching the
 *       controller: DTE input, the idle timer tick ({@link ModemController#onTimeAdvanced(long)}),
 *       session callbacks posted through {@link DteClient#modemExecutor()} and the final
 *       {@link ModemController#onDteDisconnected()}.</li>
 *   <li>{@code dte-write-N}: the single write owner. Modem output is queued (bounded) and written
 *       in order; a persistently blocked DTE is disconnected rather than having bytes dropped
 *       (S1_NETWORK_THREADING section 11).</li>
 * </ul>
 *
 * <p>This class uses only {@code java.net} and is unit tested on the JVM with real loopback
 * sockets. It binds to the loopback address by default; binding a LAN address exposes an
 * unauthenticated modem to that network and is a developer-only choice.
 */
public final class TcpDteServer implements AutoCloseable {

    /** Default timer tick: 10 ms, as in the Python reference. */
    public static final long DEFAULT_TIMER_TICK_MS = 10;
    /** Maximum modem-to-DTE bytes queued for one client before the client is disconnected. */
    public static final int DEFAULT_MAX_QUEUED_DTE_BYTES = 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 64 * 1024;

    /** Creates one controller per client. Called on that client's modem thread. */
    @FunctionalInterface
    public interface ControllerFactory {
        ModemController create(DteClient client);
    }

    /** Optional lifecycle observer (diagnostics / tests). Called from internal threads. */
    public interface Listener {
        default void onClientConnected(DteClient client) {}
        default void onClientClosed(DteClient client, String reason) {}
    }

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final ControllerFactory factory;
    private final Listener listener;
    private final long timerTickMs;
    private final int maxQueuedDteBytes;
    private final LongSupplier nanoClock;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger clientIds = new AtomicInteger();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean closed;

    /** Loopback listener on {@code port} (0 = ephemeral) with default tick and limits. */
    public TcpDteServer(int port, ControllerFactory factory) {
        this(InetAddress.getLoopbackAddress(), port, factory, null,
                DEFAULT_TIMER_TICK_MS, DEFAULT_MAX_QUEUED_DTE_BYTES, System::nanoTime);
    }

    /**
     * @param bindAddress local address to listen on (loopback for the default developer setup)
     * @param port TCP port, 0 for an ephemeral port
     * @param factory per-client controller factory
     * @param listener optional lifecycle observer, may be null
     * @param timerTickMs idle timer period servicing the escape guard time
     * @param maxQueuedDteBytes bound on queued modem-to-DTE bytes per client
     * @param nanoClock monotonic nanosecond clock passed to the controller
     */
    public TcpDteServer(InetAddress bindAddress, int port, ControllerFactory factory, Listener listener,
                        long timerTickMs, int maxQueuedDteBytes, LongSupplier nanoClock) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (port < 0 || port > 0xffff) throw new IllegalArgumentException("port out of range");
        this.requestedPort = port;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.listener = listener == null ? new Listener() {} : listener;
        this.timerTickMs = Math.max(1, timerTickMs);
        if (maxQueuedDteBytes <= 0) throw new IllegalArgumentException("maxQueuedDteBytes must be positive");
        this.maxQueuedDteBytes = maxQueuedDteBytes;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Binds and starts accepting. Idempotent while running; a closed server cannot restart. */
    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("server is closed");
        if (serverSocket != null) return;
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress, requestedPort), 16);
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
        serverSocket = socket;
        acceptThread = new Thread(() -> acceptLoop(socket), "dte-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** The bound local port. */
    public synchronized int boundPort() {
        if (serverSocket == null) throw new IllegalStateException("server is not started");
        return serverSocket.getLocalPort();
    }

    public synchronized boolean isRunning() {
        return serverSocket != null && !closed;
    }

    /** Number of currently connected DTE clients. */
    public int clientCount() {
        return clients.size();
    }

    /** Stops listening and disconnects every client (each modem sees a DTE disconnect). */
    @Override
    public void close() {
        ServerSocket socket;
        synchronized (this) {
            if (closed) return;
            closed = true;
            socket = serverSocket;
        }
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        for (Client client : new ArrayList<>(clients)) {
            client.requestClose("SERVER_CLOSED");
        }
    }

    private void acceptLoop(ServerSocket socket) {
        while (!closed) {
            Socket accepted;
            try {
                accepted = socket.accept();
            } catch (IOException failure) {
                if (closed || socket.isClosed()) return;
                continue;
            }
            if (closed) {
                closeQuietly(accepted);
                return;
            }
            try {
                accepted.setTcpNoDelay(true);
                Client client = new Client(clientIds.incrementAndGet(), accepted);
                clients.add(client);
                client.start();
            } catch (IOException | RuntimeException failure) {
                closeQuietly(accepted);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }

    /** Handle given to the controller factory for one DTE client. */
    public interface DteClient {
        /** Stable small integer for logs and thread names. */
        int id();

        /** Remote socket address (diagnostics only). */
        SocketAddress remoteAddress();

        /** Serialized, non-blocking writer toward the DTE; for the controller only. */
        DteWriter writer();

        /**
         * The client's modem thread. Session adapters must post every
         * {@link io.circuitdrift.androidialup.modem.SessionListener} callback through this
         * executor; the controller is not thread-safe.
         */
        Executor modemExecutor();

        /** Registers a hook run on the modem thread after the controller saw the disconnect. */
        void addCloseHook(Runnable hook);

        /** Asks the transport to disconnect this client. Safe from any thread. */
        void disconnect();
    }

    private final class Client implements DteClient {
        private final int id;
        private final Socket socket;
        private final ScheduledExecutorService modem;
        private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(4096);
        private final AtomicLong queuedBytes = new AtomicLong();
        private final List<Runnable> closeHooks = new CopyOnWriteArrayList<>();
        private final Thread reader;
        private final Thread writer;

        // Modem-thread-only state.
        private ModemController controller;
        private ScheduledFuture<?> timer;
        private boolean finished;

        Client(int id, Socket socket) {
            this.id = id;
            this.socket = socket;
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "dte-modem-" + id));
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            executor.setRemoveOnCancelPolicy(true);
            this.modem = executor;
            this.reader = daemon(this::readLoop, "dte-read-" + id);
            this.writer = daemon(this::writeLoop, "dte-write-" + id);
        }

        void start() {
            modem.execute(() -> {
                try {
                    controller = Objects.requireNonNull(factory.create(this), "factory returned null");
                } catch (RuntimeException failure) {
                    finish("CONTROLLER_FACTORY_FAILED");
                    return;
                }
                timer = modem.scheduleWithFixedDelay(this::tick, timerTickMs, timerTickMs, TimeUnit.MILLISECONDS);
                listener.onClientConnected(this);
            });
            writer.start();
            reader.start();
        }

        private void tick() {
            if (finished || controller == null) return;
            try {
                controller.onTimeAdvanced(nanoClock.getAsLong());
            } catch (RuntimeException failure) {
                finish("INTERNAL_ERROR");
            }
        }

        private void readLoop() {
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            String reason = "DTE_DISCONNECTED";
            try {
                InputStream in = socket.getInputStream();
                while (true) {
                    int n = in.read(buffer);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byte[] chunk = Arrays.copyOf(buffer, n);
                    if (!post(() -> feed(chunk))) return;
                }
            } catch (IOException failure) {
                reason = "DTE_DISCONNECTED";
            }
            requestClose(reason);
        }

        private void feed(byte[] chunk) {
            if (finished || controller == null) return;
            try {
                controller.feedDte(chunk, nanoClock.getAsLong());
            } catch (RuntimeException failure) {
                finish("INTERNAL_ERROR");
            }
        }

        private void writeLoop() {
            try {
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
                while (true) {
                    byte[] data = outbound.take();
                    if (data.length == 0) {
                        out.flush();
                        return; // close marker
                    }
                    out.write(data);
                    queuedBytes.addAndGet(-data.length);
                    if (outbound.isEmpty()) out.flush();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                requestClose("DTE_DISCONNECTED");
            }
        }

        // ---- DteClient --------------------------------------------------------------------

        @Override public int id() { return id; }

        @Override public SocketAddress remoteAddress() { return socket.getRemoteSocketAddress(); }

        @Override public DteWriter writer() { return this::enqueue; }

        @Override public Executor modemExecutor() { return command -> post(command); }

        @Override public void addCloseHook(Runnable hook) { closeHooks.add(Objects.requireNonNull(hook, "hook")); }

        @Override public void disconnect() { requestClose("LOCAL_DISCONNECT"); }

        private void enqueue(byte[] data) {
            if (data == null || data.length == 0 || finished) return;
            byte[] copy = data.clone();
            if (queuedBytes.addAndGet(copy.length) > maxQueuedDteBytes || !outbound.offer(copy)) {
                queuedBytes.addAndGet(-copy.length);
                // Never silently drop modem output: a DTE that stopped reading is disconnected.
                finish("DTE_WRITE_STALLED");
            }
        }

        private boolean post(Runnable task) {
            try {
                modem.execute(task);
                return true;
            } catch (RejectedExecutionException closedAlready) {
                return false;
            }
        }

        void requestClose(String reason) {
            if (!post(() -> finish(reason))) {
                // Modem executor already shut down: the client is finished.
                closeQuietly(socket);
            }
        }

        /** Modem thread only. Runs exactly once. */
        private void finish(String reason) {
            if (finished) return;
            finished = true;
            if (timer != null) timer.cancel(false);
            if (controller != null) {
                try {
                    controller.onDteDisconnected();
                } catch (RuntimeException ignored) {
                    // Disconnect must complete even if the session adapter misbehaves.
                }
            }
            for (Runnable hook : closeHooks) {
                try { hook.run(); } catch (RuntimeException ignored) {}
            }
            // Let queued output drain (bounded), then close the socket.
            if (!outbound.offer(new byte[0])) writer.interrupt();
            try {
                writer.join(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            writer.interrupt();
            closeQuietly(socket);
            clients.remove(this);
            modem.shutdown();
            listener.onClientClosed(this, reason);
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
