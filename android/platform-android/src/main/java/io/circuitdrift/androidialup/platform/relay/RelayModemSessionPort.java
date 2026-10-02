package io.circuitdrift.androidialup.platform.relay;

import io.circuitdrift.androidialup.modem.ResultCode;
import io.circuitdrift.androidialup.modem.SessionListener;
import io.circuitdrift.androidialup.modem.SessionPort;
import io.circuitdrift.androidialup.protocol.Messages;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@link SessionPort} for one {@code ModemController} that maps modem operations onto ADUP
 * through a {@link RelayTlsTransport} (Java counterpart of the Python
 * {@code RelaySessionPort}).
 *
 * <p>Each {@code ATD} opens a fresh transport on the network selected <em>at dial time</em>
 * (one call per control connection in I1; active calls never migrate). Calls from the modem
 * arrive on the modem thread; transport events arrive on the relay owner thread and are posted
 * back to the modem thread through {@code modemExecutor}, because the controller is
 * single-threaded. A generation counter discards events from a transport that was already
 * abandoned (S1_NETWORK_THREADING section 12: no resurrection of cancelled calls).
 *
 * <p>Result mapping: relay DIAL_FAILED BUSY / NO_DIALTONE / NO_ANSWER map to the same result
 * codes, every other failure maps to NO CARRIER; connect-phase and transport failures are
 * reported as {@link SessionListener#onCallTerminated(String)} with the S1 taxonomy reason so
 * the modem emits NO CARRIER plus the {@code +ADIAG} reason when diagnostics are enabled.
 */
public final class RelayModemSessionPort implements SessionPort, AutoCloseable {

    /** An opened (not yet started) transport and the bearer it is bound to. */
    public record Connection(RelayTlsTransport transport, Messages.NetworkTransport bearer) {
        public Connection {
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(bearer, "bearer");
        }
    }

    /** Creates a transport on the currently selected network, or throws with a taxonomy reason. */
    @FunctionalInterface
    public interface TransportFactory {
        Connection open(RelayTlsTransport.Listener listener) throws RelayConnectException;
    }

    private final TransportFactory factory;
    private final Executor modemExecutor;
    private SessionListener modem;

    // Guarded by this.
    private long generation;
    private RelayTlsTransport current;
    private String pendingTarget;
    private Messages.NetworkTransport bearer;
    private boolean hangingUp;
    private long retiredGeneration = -1;

    public RelayModemSessionPort(TransportFactory factory, Executor modemExecutor) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.modemExecutor = Objects.requireNonNull(modemExecutor, "modemExecutor");
    }

    /** Binds the controller (it is constructed with this port, so binding is a second step). */
    public synchronized void bind(SessionListener listener) {
        this.modem = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void dial(String target) {
        Objects.requireNonNull(target, "target");
        RelayTlsTransport previous;
        long gen;
        synchronized (this) {
            previous = current;
            current = null;
            gen = ++generation;
            hangingUp = false;
        }
        if (previous != null) previous.close("LOCAL_HANGUP");
        if (target.isEmpty()) {
            // DIAL_REQUEST needs a 1..256 byte target; an empty ATD cannot be relayed.
            toModem(gen, listener -> listener.onCallTerminated("LOCAL_CONFIG"));
            return;
        }
        Connection connection;
        try {
            connection = factory.open(new GenerationListener(gen));
        } catch (RelayConnectException failure) {
            toModem(gen, listener -> listener.onCallTerminated(failure.reason()));
            return;
        } catch (RuntimeException failure) {
            toModem(gen, listener -> listener.onCallTerminated("INTERNAL_ERROR"));
            return;
        }
        synchronized (this) {
            if (gen != generation) {
                connection.transport().close("LOCAL_HANGUP");
                return;
            }
            current = connection.transport();
            pendingTarget = target;
            bearer = connection.bearer();
        }
        connection.transport().start();
    }

    @Override
    public void writeData(byte[] data) {
        RelayTlsTransport transport;
        synchronized (this) {
            transport = hangingUp ? null : current;
        }
        if (transport != null) transport.writeData(data);
    }

    @Override
    public void hangup(String reason) {
        RelayTlsTransport transport;
        synchronized (this) {
            transport = current;
            // The modem already returned to COMMAND; nothing from this call may reach it again.
            generation++;
            hangingUp = true;
            current = null;
        }
        if (transport != null) transport.hangup(reason);
    }

    /** Incoming calls are not part of I1; ATA is accepted and ignored. */
    @Override
    public void answer() {}

    /** Abandons any call (DTE client gone). */
    @Override
    public void close() {
        RelayTlsTransport transport;
        synchronized (this) {
            transport = current;
            current = null;
            generation++;
        }
        if (transport != null) transport.close("DTE_DISCONNECTED");
    }

    /** Snapshot of the active transport, or null when idle. */
    public synchronized RelayTlsTransport.Snapshot transportSnapshot() {
        return current == null ? null : current.snapshot();
    }

    static ResultCode mapDialFailure(Messages.DialFailure reason) {
        if (reason == null) return ResultCode.NO_CARRIER;
        return switch (reason) {
            case BUSY -> ResultCode.BUSY;
            case NO_DIALTONE -> ResultCode.NO_DIALTONE;
            case NO_ANSWER -> ResultCode.NO_ANSWER;
            default -> ResultCode.NO_CARRIER;
        };
    }

    private interface ModemEvent {
        void deliver(SessionListener listener);
    }

    private void toModem(long gen, ModemEvent event) {
        try {
            modemExecutor.execute(() -> {
                SessionListener listener;
                synchronized (RelayModemSessionPort.this) {
                    if (gen != generation) return; // stale: call already abandoned
                    listener = modem;
                }
                if (listener != null) event.deliver(listener);
            });
        } catch (RejectedExecutionException modemGone) {
            // DTE client already closed.
        }
    }

    private synchronized boolean isCurrent(long gen) {
        return gen == generation && current != null;
    }

    /** Ends the current call's transport after a terminal call event. */
    private void retire(long gen, String reason) {
        RelayTlsTransport transport;
        synchronized (this) {
            if (gen != generation) return;
            transport = current;
            current = null;
            retiredGeneration = gen; // the modem already got this call's one terminal event
        }
        if (transport != null) transport.close(reason);
    }

    private final class GenerationListener implements RelayTlsTransport.Listener {
        private final long gen;

        GenerationListener(long gen) {
            this.gen = gen;
        }

        @Override
        public void onReady() {
            RelayTlsTransport transport;
            String target;
            Messages.NetworkTransport dialBearer;
            synchronized (RelayModemSessionPort.this) {
                if (!isCurrent(gen)) return;
                transport = current;
                target = pendingTarget;
                dialBearer = bearer;
            }
            transport.dial(target, dialBearer);
        }

        @Override
        public void onCallConnected(String detail) {
            toModem(gen, SessionListener::onCallConnected);
        }

        @Override
        public void onRemoteData(byte[] data) {
            toModem(gen, listener -> listener.onRemoteData(data));
        }

        @Override
        public void onDialFailed(Messages.DialFailure reason, String detail) {
            ResultCode result = mapDialFailure(reason);
            toModem(gen, listener -> listener.onDialFailed(result));
            retire(gen, "DIAL_FAILED");
        }

        @Override
        public void onCallTerminated(String reason) {
            toModem(gen, listener -> listener.onCallTerminated(reason));
            retire(gen, "REMOTE_HANGUP");
        }

        @Override
        public void onRequestRejected(String operation, String detail) {
            if ("dial".equals(operation)) {
                toModem(gen, listener -> listener.onCallTerminated("INTERNAL_ERROR"));
                retire(gen, "INTERNAL_ERROR");
            }
        }

        @Override
        public void onTransportClosed(String reason) {
            synchronized (RelayModemSessionPort.this) {
                if (gen != generation || gen == retiredGeneration) return;
                current = null;
            }
            toModem(gen, listener -> listener.onCallTerminated(reason));
        }
    }
}
