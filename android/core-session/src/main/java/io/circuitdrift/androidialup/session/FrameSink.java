package io.circuitdrift.androidialup.session;

import io.circuitdrift.androidialup.protocol.AduFrame;

/**
 * Outbound side of the relay transport, implemented by the Android network-bound TLS stream
 * (plan Task 4). {@link ModemRelayBridge} calls it from its single owner thread.
 *
 * <p>Implementations must not block and must not call back into the bridge synchronously:
 * {@link #send(AduFrame)} hands the frame to the one serialized writer (S1_NETWORK_THREADING
 * section 9), and connection results come back later as {@link ModemRelayBridge#onTlsConnected()}
 * or {@link ModemRelayBridge#onTransportClosed(String)} posted to the owner thread.
 */
public interface FrameSink {

    /** Queues one frame for the serialized writer. */
    void send(AduFrame frame);

    /**
     * Tears the transport down. {@code reason} is the S1 section 12 terminal reason; after this
     * the bridge ignores further inbound frames until the next {@link ModemRelayBridge#onTlsConnected()}.
     */
    void close(String reason);

    /**
     * Asks the host to open a TLS connection on the selected network because a dial is waiting.
     * Idempotent from the bridge's view: it is called at most once per pending dial.
     */
    void requestConnect();
}
