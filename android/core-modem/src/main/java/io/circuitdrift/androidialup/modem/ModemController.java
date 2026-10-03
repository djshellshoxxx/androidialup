package io.circuitdrift.androidialup.modem;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-writer modem state owner: the Java port of the Python {@code ModemController} reducer.
 *
 * <p>Owns the COMMAND / DIALING / ONLINE_DATA / ONLINE_COMMAND state, the DCD signal and the
 * command-line accumulator. DTE bytes enter through {@link #feedDte(byte[], long)}, session
 * events through the {@link SessionListener} methods, and the host services the escape guard
 * timer through {@link #onTimeAdvanced(long)} while the DTE is idle. Every entry point must be
 * called from one thread (or otherwise serialized); the controller never reads the system clock.
 *
 * <p>Online data is scanned bytewise for the escape sequence, but all ordinary bytes from one
 * DTE read are aggregated into a single {@link SessionPort#writeData(byte[])} call. This is the
 * I1 backpressure fix: per-octet session writes exhausted the relay transmit queue.
 */
public final class ModemController implements SessionListener {

    /** Reason passed to {@link SessionPort#hangup(String)} for ATH/ATZ. */
    public static final String LOCAL_HANGUP = "LOCAL_HANGUP";
    /** Reason passed to {@link SessionPort#hangup(String)} when the DTE goes away. */
    public static final String DTE_DISCONNECTED = "DTE_DISCONNECTED";

    private static final long GUARD_UNIT_NANOS = 20_000_000L; // S12 is in 1/50 s.

    private final SessionPort session;
    private final DteWriter dte;
    private final AtEngine engine;
    private final AtLineParser parser = new AtLineParser();
    private final EscapeDetector escape = new EscapeDetector(0);
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    private ModemState state = ModemState.COMMAND;
    private DteSignals signals = DteSignals.INITIAL;
    private String terminalReason;
    private boolean discardLine;
    private boolean dialTerminalEmitted;

    public ModemController(SessionPort session, DteWriter dte) {
        this(session, dte, "dev");
    }

    /** Creates a controller in COMMAND state with a factory profile. */
    public ModemController(SessionPort session, DteWriter dte, String buildId) {
        this.session = Objects.requireNonNull(session, "session");
        this.dte = Objects.requireNonNull(dte, "dte");
        this.engine = new AtEngine(buildId);
    }

    public ModemState state() {
        return state;
    }

    public DteSignals signals() {
        return signals;
    }

    /** Reason of the most recent dial failure or call termination. */
    public Optional<String> terminalReason() {
        return Optional.ofNullable(terminalReason);
    }

    public ModemSnapshot snapshot() {
        return new ModemSnapshot(state, signals, terminalReason());
    }

    /** The active profile (E/Q/V, S-registers, mode, policy, diagnostics flag). */
    public ModemProfile profile() {
        return engine.profile();
    }

    // ---- DTE side -------------------------------------------------------------------------

    /**
     * Feeds one DTE read. In ONLINE_DATA the bytes are escape-scanned and forwarded; otherwise
     * they are accumulated into command lines and executed on each terminator.
     *
     * @param data the bytes read from the DTE
     * @param nowNanos monotonic time of the read
     */
    public void feedDte(byte[] data, long nowNanos) {
        Objects.requireNonNull(data, "data");
        if (state == ModemState.ONLINE_DATA) {
            feedOnline(data, nowNanos);
        } else {
            feedCommand(data);
        }
    }

    /**
     * Services the escape guard timer. Hosts call this periodically (or at the post-guard
     * deadline) while in ONLINE_DATA; it is a no-op in any other state.
     */
    public void onTimeAdvanced(long nowNanos) {
        if (state != ModemState.ONLINE_DATA) {
            return;
        }
        EscapeAction action = escape.timer(nowNanos);
        byte[] forward = action.forward();
        if (forward.length > 0) {
            session.writeData(forward);
        }
        if (action.escaped()) {
            state = ModemState.ONLINE_COMMAND;
            emitResult(ResultCode.OK);
        }
    }

    /** The DTE transport closed: hang up any call and discard partial command input. */
    public void onDteDisconnected() {
        if (state.hasSession()) {
            session.hangup(DTE_DISCONNECTED);
        }
        state = ModemState.COMMAND;
        setDcd(false);
        line.reset();
        discardLine = false;
    }

    // ---- session side ---------------------------------------------------------------------

    @Override
    public void onCallConnected() {
        if (state != ModemState.DIALING || dialTerminalEmitted) {
            return;
        }
        state = ModemState.ONLINE_DATA;
        setDcd(true);
        dialTerminalEmitted = true;
        emitResult(ResultCode.CONNECT);
    }

    @Override
    public void onDialFailed(ResultCode failure) {
        Objects.requireNonNull(failure, "failure");
        if (state != ModemState.DIALING || dialTerminalEmitted) {
            return;
        }
        ResultCode result = failure.isDialFailure() ? failure : ResultCode.NO_CARRIER;
        state = ModemState.COMMAND;
        setDcd(false);
        dialTerminalEmitted = true;
        terminalReason = result.name();
        emitDiagnostic(terminalReason);
        emitResult(result);
    }

    @Override
    public void onCallTerminated(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (!state.hasSession()) {
            return;
        }
        boolean wasDialing = state == ModemState.DIALING;
        state = ModemState.COMMAND;
        setDcd(false);
        terminalReason = reason;
        if (wasDialing && dialTerminalEmitted) {
            return;
        }
        dialTerminalEmitted = true;
        emitDiagnostic(reason);
        emitResult(ResultCode.NO_CARRIER);
    }

    @Override
    public void onRemoteData(byte[] data) {
        if (state == ModemState.ONLINE_DATA && signals.dcd()) {
            dte.write(data.clone());
        }
    }

    // ---- command mode ---------------------------------------------------------------------

    private void feedCommand(byte[] data) {
        ModemProfile profile = engine.profile();
        for (byte b : data) {
            int value = b & 0xFF;
            // Re-read each byte: the terminator or echo setting may change mid-buffer.
            int terminator = profile.sRegister(ModemProfile.S3_TERMINATOR);
            int lineFeed = profile.sRegister(ModemProfile.S4_LINE_FEED);
            int backspace = profile.sRegister(ModemProfile.S5_BACKSPACE);

            if (value == lineFeed && line.size() == 0) {
                continue;
            }
            if (profile.echo()) {
                dte.write(new byte[] {b});
            }
            if (discardLine) {
                if (value == terminator) {
                    discardLine = false;
                    line.reset();
                    emitResult(ResultCode.ERROR);
                }
                continue;
            }
            if (value == backspace) {
                popLineByte();
                continue;
            }
            if (value != terminator) {
                if (line.size() >= AtLineParser.MAX_COMMAND_LINE) {
                    discardLine = true;
                } else {
                    line.write(value);
                }
                continue;
            }
            byte[] raw = line.toByteArray();
            line.reset();
            if (raw.length > 0) {
                executeLine(raw);
            }
        }
    }

    private void popLineByte() {
        byte[] current = line.toByteArray();
        line.reset();
        if (current.length > 1) {
            line.write(current, 0, current.length - 1);
        }
    }

    private void executeLine(byte[] raw) {
        List<AtCommand> commands;
        try {
            commands = parser.parse(raw);
        } catch (AtParseException e) {
            emitResult(ResultCode.ERROR);
            return;
        }
        AtExecution execution = engine.execute(commands, context());
        for (String output : execution.outputLines()) {
            emitLine(output);
        }
        for (AtEffect effect : execution.effects()) {
            applyEffect(effect);
        }
    }

    private void applyEffect(AtEffect effect) {
        switch (effect.type()) {
            case DIAL -> {
                state = ModemState.DIALING;
                terminalReason = null;
                dialTerminalEmitted = false;
                setDcd(false);
                session.dial(effect.value() == null ? "" : effect.value());
            }
            case HANGUP -> {
                if (state.hasSession()) {
                    session.hangup(LOCAL_HANGUP);
                }
                // A late result for a cancelled dial must not produce a second result code.
                dialTerminalEmitted = true;
                state = ModemState.COMMAND;
                setDcd(false);
            }
            case ANSWER -> session.answer();
            case RESUME_ONLINE -> {
                if (signals.dcd()) {
                    state = ModemState.ONLINE_DATA;
                }
            }
        }
    }

    private AtContext context() {
        return AtContext.DEFAULT
                .withActiveCall(state.isActiveCall())
                .withOnlineCommand(state == ModemState.ONLINE_COMMAND)
                .withStates(state.name(), signals.dcd() ? "ESTABLISHED" : "IDLE");
    }

    // ---- online mode ----------------------------------------------------------------------

    private void feedOnline(byte[] data, long nowNanos) {
        ModemProfile profile = engine.profile();
        int escapeChar = profile.sRegister(ModemProfile.S2_ESCAPE_CHAR);
        long guardNanos = profile.sRegister(ModemProfile.S12_GUARD_TIME) * GUARD_UNIT_NANOS;
        ByteArrayOutputStream forward = new ByteArrayOutputStream(data.length);
        for (byte b : data) {
            EscapeAction action = escape.feed(b & 0xFF, nowNanos, escapeChar, guardNanos);
            if (!action.held()) {
                { byte[] f = action.forward(); forward.write(f, 0, f.length); }
            }
        }
        if (forward.size() > 0) {
            // One bounded handoff per DTE read; the adapter chunks to protocol size itself.
            session.writeData(forward.toByteArray());
        }
    }

    // ---- output ---------------------------------------------------------------------------

    private void setDcd(boolean value) {
        signals = signals.withDcd(value);
    }

    private void emitLine(String text) {
        ModemProfile profile = engine.profile();
        byte[] body = text.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[body.length + 2];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = (byte) profile.sRegister(ModemProfile.S3_TERMINATOR);
        out[body.length + 1] = (byte) profile.sRegister(ModemProfile.S4_LINE_FEED);
        dte.write(out);
    }

    private void emitResult(ResultCode result) {
        if (!engine.profile().quiet()) {
            emitLine(engine.formatResult(result));
        }
    }

    /** {@code +ADIAG: <reason>} informational line, only when extended diagnostics are on. */
    private void emitDiagnostic(String reason) {
        if (engine.profile().extendedDiagnostics() && !engine.profile().quiet()) {
            emitLine("+ADIAG: " + reason);
        }
    }
}
