package io.circuitdrift.androidialup.platform.relay;

import android.net.Network;

import io.circuitdrift.androidialup.platform.BoundNetworkSockets;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Android {@link RelayTlsTransport.Connector}: resolves the relay host and opens TCP only
 * through the selected {@link Network} ({@link BoundNetworkSockets#openTcp}, which uses
 * {@code Network.getAllByName} and {@code Network.getSocketFactory()}), then layers TLS 1.3 over
 * exactly that connected socket with
 * {@code SSLSocketFactory.createSocket(socket, host, port, true)} via {@link RelayTls#wrap}.
 *
 * <p>No process-wide network binding is used (S1_NETWORK_THREADING section 6). Must run off the
 * main thread; {@link RelayTlsTransport} calls it on its I/O thread.
 */
public final class NetworkBoundRelayConnector implements RelayTlsTransport.Connector {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000;

    private final Network network;
    private final String host;
    private final int port;
    private final SSLSocketFactory tlsFactory;
    private final HostnameVerifier hostnameVerifier;
    private final int connectTimeoutMs;
    private final int handshakeTimeoutMs;

    /** Platform trust store and platform hostname verifier, default timeouts. */
    public NetworkBoundRelayConnector(Network network, String host, int port, SSLSocketFactory tlsFactory) {
        this(network, host, port, tlsFactory, HttpsURLConnection.getDefaultHostnameVerifier(),
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_HANDSHAKE_TIMEOUT_MS);
    }

    public NetworkBoundRelayConnector(Network network, String host, int port, SSLSocketFactory tlsFactory,
                                      HostnameVerifier hostnameVerifier, int connectTimeoutMs,
                                      int handshakeTimeoutMs) {
        this.network = Objects.requireNonNull(network, "network");
        this.host = Objects.requireNonNull(host, "host");
        if (port < 1 || port > 0xffff) throw new IllegalArgumentException("port out of range");
        this.port = port;
        this.tlsFactory = Objects.requireNonNull(tlsFactory, "tlsFactory");
        this.hostnameVerifier = Objects.requireNonNull(hostnameVerifier, "hostnameVerifier");
        this.connectTimeoutMs = connectTimeoutMs;
        this.handshakeTimeoutMs = handshakeTimeoutMs;
    }

    /** The Network every socket of this connector is bound to (device acceptance evidence). */
    public Network network() {
        return network;
    }

    @Override
    public Socket connect() throws IOException {
        Socket tcp;
        try {
            tcp = BoundNetworkSockets.openTcp(network, host, port, connectTimeoutMs);
        } catch (IOException failure) {
            throw RelayConnectException.classify(failure);
        }
        return RelayTls.wrap(tcp, host, port, tlsFactory, hostnameVerifier, handshakeTimeoutMs);
    }
}
