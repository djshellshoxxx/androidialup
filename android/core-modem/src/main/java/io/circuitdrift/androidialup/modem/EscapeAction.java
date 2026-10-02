package io.circuitdrift.androidialup.modem;

/**
 * What the {@link EscapeDetector} decided for one byte or timer tick.
 *
 * @param forward bytes to forward to the session as ordinary data (possibly empty)
 * @param held true when the byte was retained as an escape candidate
 * @param escaped true when a complete {@code +++} guard sequence was recognised; the held
 *     escape characters are discarded and the modem should enter ONLINE_COMMAND
 */
public record EscapeAction(byte[] forward, boolean held, boolean escaped) {

    static final EscapeAction NONE = new EscapeAction(new byte[0], false, false);
    static final EscapeAction HELD = new EscapeAction(new byte[0], true, false);
    static final EscapeAction ESCAPED = new EscapeAction(new byte[0], false, true);

    public EscapeAction {
        forward = forward == null ? new byte[0] : forward.clone();
    }

    static EscapeAction forward(byte[] data) {
        return new EscapeAction(data, false, false);
    }

    /** Defensive copy of the bytes to forward. */
    @Override
    public byte[] forward() {
        return forward.clone();
    }
}
