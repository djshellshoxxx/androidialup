package io.circuitdrift.androidialup.modem;

/**
 * Thrown by {@link AtLineParser} when a command line is malformed. Because the whole line is
 * parsed before anything executes, a parse failure never partially applies a command line.
 */
public final class AtParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AtParseException(String message) {
        super(message);
    }

    public AtParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
