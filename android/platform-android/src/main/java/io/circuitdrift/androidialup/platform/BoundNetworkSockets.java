package io.circuitdrift.androidialup.platform;

import android.net.Network;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

public final class BoundNetworkSockets {
    private BoundNetworkSockets() {}

    public static InetAddress[] resolve(Network network, String host) throws IOException {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(host, "host");
        return network.getAllByName(host);
    }

    public static Socket openTcp(Network network, String host, int port, int timeoutMs) throws IOException {
        Objects.requireNonNull(network, "network");
        InetAddress[] addresses = resolve(network, host);
        IOException last = null;
        for (InetAddress address : addresses) {
            Socket socket = network.getSocketFactory().createSocket();
            try {
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(address, port), timeoutMs);
                return socket;
            } catch (IOException failure) {
                last = failure;
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
        if (last != null) throw last;
        throw new IOException("selected network returned no DNS addresses for " + host);
    }

    public static DatagramSocket openUdp(Network network, InetAddress remote, int port) throws IOException {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(remote, "remote");
        DatagramSocket socket = new DatagramSocket(null);
        try {
            network.bindSocket(socket);
            socket.connect(remote, port);
            return socket;
        } catch (IOException | RuntimeException failure) {
            socket.close();
            throw failure;
        }
    }
}
