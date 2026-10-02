package io.circuitdrift.androidialup.platform.dialer;

import io.circuitdrift.androidialup.modem.AtCommand;
import io.circuitdrift.androidialup.modem.AtLineParser;
import io.circuitdrift.androidialup.modem.AtParseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * One operator-chosen destination (S1_DIALER_GUI section 2). Construction validates the target
 * against the same {@link AtLineParser} the modem uses, so an invalid target is rejected in the
 * GUI without starting a call. Only BYTE_RELAY exists in Beta, so there is no mode field.
 *
 * @param target dial target, 1..256 UTF-8 bytes, passed verbatim to gateway policy
 * @param method AUTO / TONE / PULSE, i.e. ATD / ATDT / ATDP
 * @param perCallTimeoutMs bound on DIALING before NO ANSWER (default 60000)
 */
public record DestinationInput(String target, DialMethod method, long perCallTimeoutMs) {
    public static final long DEFAULT_TIMEOUT_MS = 60_000;
    public static final long MAX_TIMEOUT_MS = 0xffff_ffffL;

    public DestinationInput {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        if (perCallTimeoutMs < 1 || perCallTimeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("per-call timeout must be 1.." + MAX_TIMEOUT_MS + " ms");
        }
        validate(target, method);
    }

    public DestinationInput(String target, DialMethod method) {
        this(target, method, DEFAULT_TIMEOUT_MS);
    }

    /** The exact command line fed to the modem, without the terminator. */
    public String commandLine() {
        return method.prefix() + target;
    }

    private static void validate(String target, DialMethod method) {
        int bytes = target.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1 || bytes > AtLineParser.MAX_DIAL_TARGET_UTF8) {
            throw new IllegalArgumentException("target must be 1..256 UTF-8 bytes");
        }
        for (int i = 0; i < target.length(); i++) {
            if (Character.isISOControl(target.charAt(i))) {
                throw new IllegalArgumentException("target must not contain control characters");
            }
        }
        if (!target.equals(target.trim())) {
            throw new IllegalArgumentException("target must not start or end with whitespace");
        }
        List<AtCommand> commands;
        try {
            commands = new AtLineParser().parse((method.prefix() + target).getBytes(StandardCharsets.UTF_8));
        } catch (AtParseException invalid) {
            throw new IllegalArgumentException("invalid dial target: " + invalid.getMessage(), invalid);
        }
        if (commands.size() != 1 || !(commands.get(0) instanceof AtCommand.Dial dial)
                || !dial.target().equals(target)) {
            // e.g. AUTO with a target starting with T/P, which ATD would read as a modifier.
            throw new IllegalArgumentException(
                    "target is ambiguous as an AT dial string; choose TONE or PULSE explicitly");
        }
    }
}
