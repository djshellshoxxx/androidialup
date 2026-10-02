package io.circuitdrift.androidialup.platform.relay;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Objects;
import javax.net.ssl.SSLException;

/**
 * Connect-phase failure carrying exactly one S1 error taxonomy reason
 * ({@code docs/spec/S1_SPEC_FREEZE.md} section 12), e.g. {@code NO_ELIGIBLE_NETWORK},
 * {@code DNS_FAILURE}, {@code CONNECT_TIMEOUT}, {@code TLS_FAILURE} or {@code RELAY_UNAVAILABLE}.
 */
public final class RelayConnectException extends IOException {
    private static final long serialVersionUID = 1L;

    public static final String NO_ELIGIBLE_NETWORK = "NO_ELIGIBLE_NETWORK";
    public static final String DNS_FAILURE = "DNS_FAILURE";
    public static final String CONNECT_TIMEOUT = "CONNECT_TIMEOUT";
    public static final String TLS_FAILURE = "TLS_FAILURE";
    public static final String RELAY_UNAVAILABLE = "RELAY_UNAVAILABLE";

    private final String reason;

    public RelayConnectException(String reason, String detail) {
        super(reason + ": " + detail);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public RelayConnectException(String reason, String detail, Throwable cause) {
        super(reason + ": " + detail, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** The S1 taxonomy reason. */
    public String reason() {
        return reason;
    }

    /** Maps a raw I/O failure from TCP connect or the TLS handshake onto the taxonomy. */
    public static RelayConnectException classify(IOException failure) {
        if (failure instanceof RelayConnectException typed) return typed;
        String detail = failure.getClass().getSimpleName();
        if (failure instanceof UnknownHostException) return new RelayConnectException(DNS_FAILURE, detail, failure);
        if (failure instanceof SocketTimeoutException) return new RelayConnectException(CONNECT_TIMEOUT, detail, failure);
        if (failure instanceof SSLException) return new RelayConnectException(TLS_FAILURE, detail, failure);
        return new RelayConnectException(RELAY_UNAVAILABLE, detail, failure);
    }
}
