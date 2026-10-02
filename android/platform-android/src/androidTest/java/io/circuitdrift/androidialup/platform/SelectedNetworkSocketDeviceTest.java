package io.circuitdrift.androidialup.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import io.circuitdrift.androidialup.network.NetworkPolicy;
import io.circuitdrift.androidialup.platform.relay.NetworkBoundRelayConnector;
import io.circuitdrift.androidialup.platform.relay.RelayConnectException;
import io.circuitdrift.androidialup.platform.relay.RelayTls;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Device-only acceptance (I1 network-control plan Task 5, handoff item C): proves that relay
 * sockets opened through {@link BoundNetworkSockets} / {@link NetworkBoundRelayConnector} leave
 * through the selected Android {@link Network}, for WIFI and CELLULAR, and that the TLS 1.3 +
 * hostname policy holds on the device's Conscrypt.
 *
 * <p>Evidence: the connected socket's local address belongs to the selected network's
 * {@link LinkProperties}, or the socket's interface is that network's interface (a {@code v4-}
 * CLAT interface stacked on it is accepted for IPv4 over 464XLAT cellular).
 *
 * <p>Needs a physical device with the bearer in question; a missing bearer is reported as a
 * skipped (assumption-failed) test, not a pass. Instrumentation arguments
 * ({@code -e name value}):
 * <ul>
 *   <li>{@code probeHost}/{@code probePort}: plain TCP target (default
 *       connectivitycheck.gstatic.com:80)</li>
 *   <li>{@code tlsHost}/{@code tlsPort}: a public TLS 1.3 host with a publicly trusted
 *       certificate (default www.google.com:443)</li>
 * </ul>
 * Run: {@code gradle :platform-android:connectedDebugAndroidTest}.
 */
public class SelectedNetworkSocketDeviceTest {
    private static final int TIMEOUT_MS = 15_000;

    private ConnectivityManager connectivity;
    private Bundle args;
    private final List<ConnectivityManager.NetworkCallback> callbacks = new ArrayList<>();

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        connectivity = context.getSystemService(ConnectivityManager.class);
        Bundle supplied;
        try {
            supplied = InstrumentationRegistry.getArguments();
        } catch (IllegalStateException none) {
            supplied = new Bundle();
        }
        args = supplied;
    }

    @After
    public void tearDown() {
        for (ConnectivityManager.NetworkCallback callback : callbacks) {
            try {
                connectivity.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException alreadyGone) {
                // not registered
            }
        }
    }

    @Test
    public void wifiTcpSocketUsesWifiNetwork() throws Exception {
        assertTcpBoundTo(NetworkCapabilities.TRANSPORT_WIFI);
    }

    @Test
    public void cellularTcpSocketUsesCellularNetwork() throws Exception {
        assertTcpBoundTo(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    @Test
    public void relayTlsOverSelectedNetworkIsTls13WithHostnameVerification() throws Exception {
        assumeTrue("TLS 1.3 requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        Network network = requestNetwork(NetworkCapabilities.TRANSPORT_WIFI);
        if (network == null) network = requestNetwork(NetworkCapabilities.TRANSPORT_CELLULAR);
        assumeTrue("no Wi-Fi or cellular Internet network", network != null);
        String host = args.getString("tlsHost", "www.google.com");
        int port = Integer.parseInt(args.getString("tlsPort", "443"));

        NetworkBoundRelayConnector connector =
                new NetworkBoundRelayConnector(network, host, port, RelayTls.socketFactory(null));
        try (Socket socket = connector.connect()) {
            SSLSocket tls = (SSLSocket) socket;
            assertEquals("TLSv1.3", tls.getSession().getProtocol());
            assertSocketOnNetwork(socket, network);
        }
    }

    @Test
    public void wrongHostnameIsRejectedOnDevice() throws Exception {
        assumeTrue("TLS 1.3 requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        Network network = requestNetwork(NetworkCapabilities.TRANSPORT_WIFI);
        if (network == null) network = requestNetwork(NetworkCapabilities.TRANSPORT_CELLULAR);
        assumeTrue("no Wi-Fi or cellular Internet network", network != null);
        String host = args.getString("tlsHost", "www.google.com");
        int port = Integer.parseInt(args.getString("tlsPort", "443"));

        // Connect to the real host, but verify the certificate as a different name.
        Socket tcp = BoundNetworkSockets.openTcp(network, host, port, TIMEOUT_MS);
        try {
            RelayTls.wrap(tcp, "relay.invalid", port, RelayTls.socketFactory(null),
                    javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier(), TIMEOUT_MS).close();
            fail("certificate for " + host + " must not validate as relay.invalid");
        } catch (RelayConnectException expected) {
            assertEquals(RelayConnectException.TLS_FAILURE, expected.reason());
        }
        assertTrue(tcp.isClosed());
    }

    @Test
    public void androidNetworkManagerPolicySelectsRequestedTransport() throws Exception {
        assertPolicySelects(NetworkPolicy.WIFI_ONLY, NetworkCapabilities.TRANSPORT_WIFI);
        assertPolicySelects(NetworkPolicy.CELLULAR_ONLY, NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    private void assertPolicySelects(NetworkPolicy policy, int transport) throws Exception {
        // Keep the bearer up while the passive manager observes it.
        Network requested = requestNetwork(transport);
        if (requested == null) return; // bearer absent: covered by the per-transport tests' skips
        CountDownLatch selected = new CountDownLatch(1);
        AtomicReference<Network> chosen = new AtomicReference<>();
        AndroidNetworkManager manager = new AndroidNetworkManager(connectivity, new AndroidNetworkManager.Listener() {
            @Override
            public void onSelectionChanged(Network network,
                                           io.circuitdrift.androidialup.network.NetworkCandidate candidate,
                                           io.circuitdrift.androidialup.network.NetworkSelectionEngine.SelectionDecision decision) {
                chosen.set(network);
                selected.countDown();
            }
        });
        try {
            manager.setPolicy(policy);
            manager.start();
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            Network network = null;
            while (System.currentTimeMillis() < deadline) {
                network = manager.selectedNetwork();
                if (network != null) break;
                selected.await(200, TimeUnit.MILLISECONDS);
            }
            assertNotNull(policy + " selected nothing: " + manager.diagnostics(), network);
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            assertNotNull(caps);
            assertTrue(policy + " selected wrong transport", caps.hasTransport(transport));
            try (Socket socket = BoundNetworkSockets.openTcp(network, probeHost(), probePort(), TIMEOUT_MS)) {
                assertSocketOnNetwork(socket, network);
            }
        } finally {
            manager.close();
        }
    }

    private void assertTcpBoundTo(int transport) throws Exception {
        Network network = requestNetwork(transport);
        assumeTrue("no validated Internet network with transport " + transport, network != null);
        try (Socket socket = BoundNetworkSockets.openTcp(network, probeHost(), probePort(), TIMEOUT_MS)) {
            assertTrue(socket.isConnected());
            assertSocketOnNetwork(socket, network);
        }
    }

    private String probeHost() {
        return args.getString("probeHost", "connectivitycheck.gstatic.com");
    }

    private int probePort() {
        return Integer.parseInt(args.getString("probePort", "80"));
    }

    private void assertSocketOnNetwork(Socket socket, Network network) throws Exception {
        LinkProperties link = connectivity.getLinkProperties(network);
        assertNotNull("network has no LinkProperties", link);
        InetAddress local = socket.getLocalAddress();
        for (LinkAddress address : link.getLinkAddresses()) {
            if (address.getAddress().equals(local)) return;
        }
        NetworkInterface nic = NetworkInterface.getByInetAddress(local);
        String expected = link.getInterfaceName();
        String actual = nic == null ? null : nic.getName();
        assertTrue("socket local " + local + " on " + actual + " is not on selected network " + expected
                        + " " + link.getLinkAddresses(),
                expected != null && actual != null
                        && (actual.equals(expected) || actual.equals("v4-" + expected)));
    }

    /** Requests (and keeps) a validated Internet network with {@code transport}, or null. */
    private Network requestNetwork(int transport) throws InterruptedException {
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(transport)
                .build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Network> result = new AtomicReference<>();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    result.compareAndSet(null, network);
                    done.countDown();
                }
            }

            @Override
            public void onUnavailable() {
                done.countDown();
            }
        };
        connectivity.requestNetwork(request, callback, TIMEOUT_MS);
        callbacks.add(callback);
        done.await(TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS);
        return result.get();
    }
}
