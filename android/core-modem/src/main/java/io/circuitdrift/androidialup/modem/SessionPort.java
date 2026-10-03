package io.circuitdrift.androidialup.modem;

/**
 * Outbound boundary from the modem to the active session/mode adapter (the Python
 * {@code SessionPort} protocol). The Android relay adapter implements this over ADUP and reports
 * results back through {@link SessionListener}.
 *
 * <p>All calls are made from the single thread that drives {@link ModemController}. They must
 * not block on network I/O; results arrive asynchronously through the listener.
 */
public interface SessionPort {

    /** Starts an outgoing call to {@code target} (the validated {@code ATD} target string). */
    void dial(String target);

    /**
     * Forwards transparent online data. The controller aggregates one DTE read into one call
     * after escape scanning; the adapter does its own protocol-sized chunking.
     */
    void writeData(byte[] data);

    /**
     * Terminates the current call or cancels a dial in progress. {@code reason} is an S1 error
     * taxonomy identifier such as {@code LOCAL_HANGUP} or {@code DTE_DISCONNECTED}.
     */
    void hangup(String reason);

    /** Answers an incoming call ({@code ATA}). */
    void answer();
}
