package io.circuitdrift.androidialup.platform.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.junit.After;
import org.junit.Test;

/**
 * Exercises the relay TLS policy over real loopback TLS on the JVM. Keystores under
 * {@code src/test/resources/relay-tls} are throwaway self-signed test credentials
 * (CN/SAN localhost + 127.0.0.1), never used outside tests.
 */
public class RelayTlsTest {
    private static final char[] PASSWORD = "test-only".toCharArray();

    private SSLServerSocket server;
    private Thread serverThread;
    private final AtomicReference<String> serverProtocol = new AtomicReference<>();

    @After
    public void tearDown() throws Exception {
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(2000);
    }

    private void startServer(String keystore, String... protocols) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = resource(keystore)) {
            ks.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.setEnabledProtocols(protocols);
        serverThread = new Thread(() -> {
            try (SSLSocket accepted = (SSLSocket) server.accept()) {
                accepted.startHandshake();
                serverProtocol.set(accepted.getSession().getProtocol());
                accepted.getOutputStream().write('K');
                accepted.getOutputStream().flush();
                accepted.getInputStream().read();
            } catch (IOException expectedOnRejectedHandshake) {
                // client aborted
            }
        }, "tls-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static InputStream resource(String name) {
        InputStream in = RelayTlsTest.class.getResourceAsStream("/relay-tls/" + name);
        if (in == null) throw new AssertionError("missing test resource " + name);
        return in;
    }

    private static SSLSocketFactory trustingTestCa() throws Exception {
        try (InputStream pem = resource("relay-test-cert.pem")) {
            return RelayTls.socketFactory(RelayTls.trustStoreOf(pem));
        }
    }

    private Socket tcp() throws IOException {
        return new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    @Test
    public void trustedRelayNegotiatesTls13AndCarriesBytes() throws Exception {
        startServer("relay-test.p12", "TLSv1.3", "TLSv1.2");
        Socket tcp = tcp();
        try (SSLSocket tls = RelayTls.wrap(tcp, "localhost", server.getLocalPort(), trustingTestCa(),
                (host, session) -> true, 5000)) {
            assertEquals("TLSv1.3", tls.getSession().getProtocol());
            assertEquals('K', tls.getInputStream().read());
            assertEquals(0, tls.getSoTimeout());
        }
        serverThread.join(2000);
        assertEquals("TLSv1.3", serverProtocol.get());
    }

    @Test
    public void ipLiteralHostIsVerifiedAgainstIpSan() throws Exception {
        startServer("relay-test.p12", "TLSv1.3");
        try (SSLSocket tls = RelayTls.wrap(tcp(), "127.0.0.1", server.getLocalPort(), trustingTestCa(),
                null, 5000)) {
            assertEquals("TLSv1.3", tls.getSession().getProtocol());
        }
    }

    @Test
    public void hostnameMismatchFailsDuringHandshake() throws Exception {
        startServer("relay-test.p12", "TLSv1.3");
        Socket tcp = tcp();
        expectTlsFailure(tcp, "relay.example.net", trustingTestCa(), null);
    }

    @Test
    public void untrustedCertificateFails() throws Exception {
        startServer("untrusted.p12", "TLSv1.3");
        expectTlsFailure(tcp(), "localhost", trustingTestCa(), null);
    }

    @Test
    public void tls12OnlyRelayIsRejected() throws Exception {
        startServer("relay-test.p12", "TLSv1.2");
        expectTlsFailure(tcp(), "localhost", trustingTestCa(), null);
    }

    @Test
    public void explicitHostnameVerifierVetoFails() throws Exception {
        startServer("relay-test.p12", "TLSv1.3");
        expectTlsFailure(tcp(), "localhost", trustingTestCa(), (host, session) -> false);
    }

    @Test
    public void ipLiteralDetection() {
        assertTrue(RelayTls.isIpLiteral("127.0.0.1"));
        assertTrue(RelayTls.isIpLiteral("::1"));
        assertFalse(RelayTls.isIpLiteral("relay.example.net"));
        assertFalse(RelayTls.isIpLiteral("localhost"));
    }

    private void expectTlsFailure(Socket tcp, String host, SSLSocketFactory factory,
                                  javax.net.ssl.HostnameVerifier verifier) {
        try {
            RelayTls.wrap(tcp, host, server.getLocalPort(), factory, verifier, 5000).close();
            fail("handshake should have failed");
        } catch (RelayConnectException expected) {
            assertEquals(RelayConnectException.TLS_FAILURE, expected.reason());
        } catch (IOException unexpected) {
            throw new AssertionError(unexpected);
        }
        assertTrue("underlying TCP socket must be closed", tcp.isClosed());
    }
}
