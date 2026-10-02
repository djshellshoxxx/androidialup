package io.circuitdrift.androidialup.modem;

/**
 * Inbound boundary from the session/mode adapter back into the modem (the Python
 * {@code ControllerCallbacks} protocol). {@link ModemController} implements it.
 *
 * <p>Calls must be serialized with every other controller entry point: the controller is a
 * single-writer state owner and is not thread-safe.
 */
public interface SessionListener {

    /** The dial requested by {@link SessionPort#dial(String)} succeeded; the call is up. */
    void onCallConnected();

    /**
     * The dial failed. {@code failure} should be one of {@link ResultCode#BUSY},
     * {@link ResultCode#NO_DIALTONE}, {@link ResultCode#NO_ANSWER} or
     * {@link ResultCode#NO_CARRIER}; any other value is reported as NO CARRIER.
     */
    void onDialFailed(ResultCode failure);

    /**
     * The call or dial ended for a remote or network reason (S1 error taxonomy identifier,
     * e.g. {@code REMOTE_HANGUP}, {@code NETWORK_LOST}). Ignored outside an active call or dial.
     */
    void onCallTerminated(String reason);

    /** Transparent data from the remote side; delivered to the DTE only in ONLINE_DATA. */
    void onRemoteData(byte[] data);
}
