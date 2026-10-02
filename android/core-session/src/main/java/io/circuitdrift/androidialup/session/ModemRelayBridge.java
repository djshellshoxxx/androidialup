package io.circuitdrift.androidialup.session;

import io.circuitdrift.androidialup.modem.ResultCode;
import io.circuitdrift.androidialup.modem.SessionListener;
import io.circuitdrift.androidialup.modem.SessionPort;
import io.circuitdrift.androidialup.protocol.AduFrame;
import io.circuitdrift.androidialup.protocol.Messages.DialFailure;
import io.circuitdrift.androidialup.protocol.Messages.NetworkTransport;
import io.circuitdrift.androidialup.protocol.ProtocolException;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Implements core-modem's {@link SessionPort} on top of {@link RelaySessionMachine} and maps the
 * machine's actions to {@link SessionListener} callbacks (normally the {@link
 * io.circuitdrift.androidialup.modem.ModemController}).
 *
 * <p>Single-threaded and non-blocking: every method, including the {@link SessionPort} calls the
 * controller makes, must run on the one thread that owns the controller (S1_NETWORK_THREADING
 * section 8). Time comes from the injected monotonic millisecond clock; nothing sleeps. The host
 * posts decoded frames to {@link #onFrame(AduFrame)}, ticks {@link #onTimer()}, and reports
 * {@link #onTlsConnected()} / {@link #onTransportClosed(String)}.
 *
 * <p>A dial requested before the relay session is authenticated (or while a previous call is
 * still hanging up) is held and sent as soon as the machine is IDLE; if no transport is up the
 * bridge asks the {@link FrameSink} to connect. A failed machine is replaced by a fresh one on the
 * next TLS connection.
 */
public final class ModemRelayBridge implements SessionPort {
    private final Supplier<RelaySessionMachine> machineFactory;
    private final FrameSink sink;
    private final NetworkTransport transport;
    private final LongSupplier monotonicMs;

    private SessionListener listener;
    private RelaySessionMachine machine;
    private String pendingDial;
    private boolean callOutstanding;
    private boolean connectRequested;

    public ModemRelayBridge(Supplier<RelaySessionMachine> machineFactory, FrameSink sink,
                            NetworkTransport transport, LongSupplier monotonicMs) {
        this.machineFactory = Objects.requireNonNull(machineFactory, "machineFactory");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.monotonicMs = Objects.requireNonNull(monotonicMs, "monotonicMs");
        this.machine = newMachine();
    }

    /** Binds the callback target; the controller is created with this bridge, so bind after. */
    public void setListener(SessionListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Diagnostics for {@code AT+DIAG?} and the UI; never contains secrets. */
    public RelaySessionSnapshot snapshot() {
        return machine.snapshot(monotonicMs.getAsLong());
    }

    // ---- host / transport side ----------------------------------------------------------

    /** The TLS connection on the selected network is established: start HELLO. */
    public void onTlsConnected() {
        connectRequested = false;
        if (machine.state() != RelaySessionMachine.State.NEW) machine = newMachine();
        apply(machine.onTlsConnected(monotonicMs.getAsLong()));
    }

    /** One decoded inbound frame. Protocol violations close the transport with PROTOCOL_VIOLATION. */
    public void onFrame(AduFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (machine.state() == RelaySessionMachine.State.FAILED) return; // late frame after close
        List<RelaySessionMachine.Action> actions;
        try {
            actions = machine.onFrame(frame, monotonicMs.getAsLong());
        } catch (ProtocolException e) {
            actions = machine.fail(RelaySessionMachine.PROTOCOL_VIOLATION);
        }
        apply(actions);
    }

    /** Services protocol deadlines and the heartbeat; call periodically (e.g. every 100-500 ms). */
    public void onTimer() {
        apply(machine.onTimer(monotonicMs.getAsLong()));
    }

    /**
     * The transport closed or could not be opened. {@code reason} is an S1 section 12 name such as
     * NETWORK_LOST, CONNECT_TIMEOUT or TLS_FAILURE. A pending or active call ends with it.
     */
    public void onTransportClosed(String reason) {
        Objects.requireNonNull(reason, "reason");
        connectRequested = false;
        apply(machine.fail(reason));
    }

    // ---- SessionPort (controller side) --------------------------------------------------

    @Override
    public void dial(String target) {
        Objects.requireNonNull(target, "target");
        callOutstanding = true;
        pendingDial = target;
        sendPendingDialIfIdle();
        if (pendingDial != null && !connectRequested && needsConnection()) {
            connectRequested = true;
            sink.requestConnect();
        }
    }

    @Override
    public void writeData(byte[] data) {
        if (machine.state() != RelaySessionMachine.State.CONNECTED) return; // raced with termination
        apply(machine.writeData(data));
    }

    @Override
    public void hangup(String reason) {
        Objects.requireNonNull(reason, "reason");
        callOutstanding = false;
        if (pendingDial != null) {
            pendingDial = null;
            return;
        }
        RelaySessionMachine.State state = machine.state();
        if (state == RelaySessionMachine.State.DIALING || state == RelaySessionMachine.State.CONNECTED) {
            apply(machine.hangup(reason));
        }
    }

    /** Incoming calls are optional in I1 (S1_WIRE_PROTOCOL section 11); there is never one to answer. */
    @Override
    public void answer() {
        // No INCOMING_CALL support yet: nothing to answer.
    }

    // ---- mapping ------------------------------------------------------------------------

    /**
     * DTE result for a wire dial failure (S1_AT_DTE section 7): BUSY, NO DIALTONE and NO ANSWER
     * map directly; everything else, including the S7-style setup TIMEOUT, is NO CARRIER.
     */
    public static ResultCode resultFor(DialFailure failure) {
        return switch (failure) {
            case BUSY -> ResultCode.BUSY;
            case NO_DIALTONE -> ResultCode.NO_DIALTONE;
            case NO_ANSWER -> ResultCode.NO_ANSWER;
            default -> ResultCode.NO_CARRIER;
        };
    }

    private void apply(List<RelaySessionMachine.Action> actions) {
        for (RelaySessionMachine.Action action : actions) {
            if (action instanceof RelaySessionMachine.Outbound outbound) {
                sink.send(outbound.frame());
            } else if (action instanceof RelaySessionMachine.CallConnected) {
                listener().onCallConnected();
            } else if (action instanceof RelaySessionMachine.InboundData data) {
                listener().onRemoteData(data.data());
            } else if (action instanceof RelaySessionMachine.CallFailed failed) {
                callOutstanding = false;
                listener().onDialFailed(resultFor(failed.reason()));
            } else if (action instanceof RelaySessionMachine.CallTerminatedAction terminated) {
                callOutstanding = false;
                listener().onCallTerminated(terminated.reason());
            } else if (action instanceof RelaySessionMachine.TransportFailed failed) {
                sink.close(failed.reason());
                pendingDial = null;
                if (callOutstanding) {
                    callOutstanding = false;
                    listener().onCallTerminated(failed.reason());
                }
            }
        }
        sendPendingDialIfIdle();
    }

    private void sendPendingDialIfIdle() {
        if (pendingDial == null || machine.state() != RelaySessionMachine.State.IDLE) return;
        String target = pendingDial;
        pendingDial = null;
        apply(machine.dial(target, transport, monotonicMs.getAsLong()));
    }

    private boolean needsConnection() {
        RelaySessionMachine.State state = machine.state();
        return state == RelaySessionMachine.State.NEW || state == RelaySessionMachine.State.FAILED;
    }

    private SessionListener listener() {
        if (listener == null) throw new IllegalStateException("setListener must be called before session events");
        return listener;
    }

    private RelaySessionMachine newMachine() {
        return Objects.requireNonNull(machineFactory.get(), "machineFactory returned null");
    }
}
