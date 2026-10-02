package io.circuitdrift.androidialup.platform.dialer;

import io.circuitdrift.androidialup.modem.ModemController;
import io.circuitdrift.androidialup.modem.ModemSnapshot;
import io.circuitdrift.androidialup.modem.ModemState;
import io.circuitdrift.androidialup.platform.relay.RelayModemSessionPort;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The developer dialer's modem (S1_DIALER_GUI section 6): an in-process DTE that drives a real
 * {@link ModemController} with AT command lines on a single owner thread, exactly as a TCP DTE
 * client would, and wires it to the relay through {@link RelayModemSessionPort}.
 *
 * <p>The GUI only calls {@link #dial}, {@link #cancel()} and {@link #hangUp()} and renders
 * {@link Snapshot}s, which are built from {@link ModemController#snapshot()} and the modem's own
 * result-code lines; nothing is synthesized. Cancel is {@code ATH} during DIALING; Hang Up is
 * {@code ATH} from ONLINE_COMMAND, preceded by a real guarded {@code +++} escape when the call is
 * in ONLINE_DATA.
 *
 * <p>One call at a time, one call per {@link #dial} invocation. There is no queue, no list
 * iteration and no automatic redial or advance: a dial while a call is in progress is rejected.
 */
public final class DialerSession implements AutoCloseable {

    /** Immutable state for the GUI. */
    public record Snapshot(ModemSnapshot modem, String lastResult, boolean callInProgress,
                           String currentTargetRedacted) {}

    /** GUI hooks, called on the dialer's modem thread; post to the UI thread yourself. */
    public interface Listener {
        void onSnapshot(Snapshot snapshot);

        /** A dial request was refused before reaching the modem (validation or busy). */
        default void onRejected(String reason) {}

        /** One raw modem output line (result codes, +ADIAG), for a console view. */
        default void onModemLine(String line) {}
    }

    private static final long TICK_MS = 10;
    /** Escape guard used by this in-process DTE (S12=1, 20 ms). */
    private static final long ESCAPE_GUARD_MS = 20;

    private final ScheduledThreadPoolExecutor owner;
    private final RelayModemSessionPort port;
    private final ModemController controller;
    private final CallLog log;
    private final Listener listener;
    private final LongSupplier clockMs;
    private final LongSupplier nanoClock;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

    // Owner-thread state.
    private CallBuilder call;
    private boolean initDone;
    private int initOksPending;
    private String lastResult = "";
    private boolean pendingHangup;
    private volatile Snapshot snapshot;
    private volatile boolean closed;

    /**
     * @param factory opens a relay transport on the selected network for each call
     * @param log call-progress log the session appends to
     * @param listener GUI hooks
     * @param clockMs monotonic milliseconds (Android: {@code SystemClock::elapsedRealtime})
     * @param nanoClock monotonic nanoseconds for the modem escape timer
     */
    public DialerSession(RelayModemSessionPort.TransportFactory factory, CallLog log, Listener listener,
                         LongSupplier clockMs, LongSupplier nanoClock) {
        this.log = Objects.requireNonNull(log, "log");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.owner = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "dialer-modem");
            thread.setDaemon(true);
            return thread;
        });
        owner.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        owner.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.port = new RelayModemSessionPort(factory, this::post);
        this.controller = new ModemController(port, this::onModemOutput, "dialer");
        port.bind(controller);
        port.setProgressObserver((phase, detail) -> {
            if (call != null) call.progress.add(new CallLogRecord.Progress(phase.name(), detail, clockMs.getAsLong()));
        });
        this.snapshot = new Snapshot(controller.snapshot(), "", false, null);
        post(() -> {
            // Quiet echo, extended diagnostics (+ADIAG reasons for the log), short escape guard.
            initOksPending = 3;
            feed("ATE0");
            feed("AT+DIAG=1");
            feed("ATS12=1");
        });
        owner.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Starts exactly one call. Rejected if a call is in progress or the input is invalid. */
    public void dial(DestinationInput input) {
        dial(input, null);
    }

    /** As {@link #dial(DestinationInput)}, tagging the record with a test-list entry index. */
    public void dial(DestinationInput input, Integer testListEntry) {
        Objects.requireNonNull(input, "input");
        post(() -> {
            if (!initDone) {
                listener.onRejected("modem is initializing");
                return;
            }
            if (call != null || controller.state() != ModemState.COMMAND) {
                listener.onRejected("a call is already in progress (" + controller.state() + ")");
                return;
            }
            port.setDialTimeoutMs(input.perCallTimeoutMs());
            call = new CallBuilder(clockMs.getAsLong(), input, testListEntry);
            lastResult = "";
            feed(input.commandLine());
            publish();
        });
    }

    /** Cancels a dial in progress ({@code ATH} during DIALING). */
    public void cancel() {
        post(() -> {
            if (controller.state() != ModemState.DIALING) return;
            if (call != null) call.operatorEnded = true;
            feed("ATH");
        });
    }

    /** Hangs up the current call ({@code ATH}, after a guarded escape if in ONLINE_DATA). */
    public void hangUp() {
        post(() -> {
            ModemState state = controller.state();
            if (state == ModemState.DIALING) {
                if (call != null) call.operatorEnded = true;
                feed("ATH");
            } else if (state == ModemState.ONLINE_COMMAND) {
                if (call != null) call.operatorEnded = true;
                feed("ATH");
            } else if (state == ModemState.ONLINE_DATA && !pendingHangup) {
                if (call != null) call.operatorEnded = true;
                pendingHangup = true;
                // Real V.250 escape: guard silence, "+++", guard silence (serviced by tick()).
                owner.schedule(() -> feedRaw("+++"), 2 * ESCAPE_GUARD_MS + TICK_MS, TimeUnit.MILLISECONDS);
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        post(() -> {
            controller.onDteDisconnected();
            port.close();
            owner.shutdown();
        });
    }

    // ---- owner thread --------------------------------------------------------------------

    private void post(Runnable task) {
        try {
            owner.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException failure) {
                    listener.onRejected("internal error: " + failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException shutDown) {
            // closed
        }
    }

    private void tick() {
        controller.onTimeAdvanced(nanoClock.getAsLong());
    }

    private void feed(String commandLine) {
        feedRaw(commandLine + "\r");
    }

    private void feedRaw(String text) {
        controller.feedDte(text.getBytes(StandardCharsets.US_ASCII), nanoClock.getAsLong());
        // ATH prints OK before the controller applies the hangup effect, so the operator-ended
        // call is closed here, once the controller itself reports COMMAND.
        if (call != null && call.operatorEnded && call.okAfterOperatorEnd
                && controller.state() == ModemState.COMMAND) {
            finishOperatorEnded();
        }
        publish();
    }

    private void onModemOutput(byte[] data) {
        for (byte b : data) {
            // CR or LF ends a line: result lines are CRLF-terminated and the echo of the first
            // init command (before ATE0 takes effect) ends with a bare CR.
            if (b == '\n' || b == '\r') {
                String line = new String(lineBuffer.toByteArray(), StandardCharsets.US_ASCII).trim();
                lineBuffer.reset();
                if (!line.isEmpty()) onLine(line);
            } else {
                lineBuffer.write(b);
            }
        }
    }

    private void onLine(String line) {
        listener.onModemLine(line);
        if (!initDone) {
            if (line.equals("OK") && --initOksPending == 0) initDone = true;
            publish();
            return;
        }
        if (line.startsWith("+ADIAG: ")) {
            if (call != null) call.internalReason = line.substring("+ADIAG: ".length()).trim();
            return;
        }
        CallLogRecord.Outcome outcome = outcomeOf(line);
        if (outcome == null && !line.equals("OK")) return; // informational text
        lastResult = line;
        if (call == null) {
            publish();
            return;
        }
        if (outcome == CallLogRecord.Outcome.CONNECT) {
            if (call.outcome == null) call.outcome = outcome;
            if (line.length() > "CONNECT".length()) {
                call.negotiated = new CallLogRecord.Negotiated(line.substring("CONNECT".length()).trim(), null);
            }
        } else if (outcome != null) {
            // BUSY / NO DIALTONE / NO ANSWER / NO CARRIER / ERROR end the call.
            if (call.outcome == null) call.outcome = outcome;
            finish();
        } else if (pendingHangup && controller.state() == ModemState.ONLINE_COMMAND) {
            pendingHangup = false; // escape completed: hang up as a separate step,
            post(() -> feed("ATH")); // never re-entering the controller from its own output
            return;
        } else if (call.operatorEnded) {
            call.okAfterOperatorEnd = true;
            if (controller.state() == ModemState.COMMAND) finishOperatorEnded();
        }
        publish();
    }

    private void finishOperatorEnded() {
        if (call.outcome == null) call.outcome = CallLogRecord.Outcome.CANCELLED;
        if (call.internalReason == null) call.internalReason = ModemController.LOCAL_HANGUP;
        finish();
    }

    private void finish() {
        CallBuilder done = call;
        call = null;
        pendingHangup = false;
        String reason = done.internalReason != null ? done.internalReason
                : controller.terminalReason().orElse(done.outcome.name());
        log.append(new CallLogRecord(done.startedAt, CallLogRecord.redact(done.input.target()),
                done.input.method(), done.progress, Collections.emptyList(), Collections.emptyList(), done.negotiated, done.outcome,
                reason, clockMs.getAsLong(), done.testListEntry));
    }

    private void publish() {
        Snapshot next = new Snapshot(controller.snapshot(), lastResult, call != null,
                call == null ? null : CallLogRecord.redact(call.input.target()));
        snapshot = next;
        listener.onSnapshot(next);
    }

    static CallLogRecord.Outcome outcomeOf(String line) {
        if (line.equals("CONNECT") || line.startsWith("CONNECT ")) return CallLogRecord.Outcome.CONNECT;
        return switch (line) {
            case "BUSY" -> CallLogRecord.Outcome.BUSY;
            case "NO DIALTONE" -> CallLogRecord.Outcome.NO_DIALTONE;
            case "NO ANSWER" -> CallLogRecord.Outcome.NO_ANSWER;
            case "NO CARRIER" -> CallLogRecord.Outcome.NO_CARRIER;
            case "ERROR" -> CallLogRecord.Outcome.ERROR;
            default -> null;
        };
    }

    private static final class CallBuilder {
        final long startedAt;
        final DestinationInput input;
        final Integer testListEntry;
        final List<CallLogRecord.Progress> progress = new ArrayList<>();
        CallLogRecord.Outcome outcome;
        CallLogRecord.Negotiated negotiated;
        String internalReason;
        boolean operatorEnded;
        boolean okAfterOperatorEnd;

        CallBuilder(long startedAt, DestinationInput input, Integer testListEntry) {
            this.startedAt = startedAt;
            this.input = input;
            this.testListEntry = testListEntry;
        }
    }
}
