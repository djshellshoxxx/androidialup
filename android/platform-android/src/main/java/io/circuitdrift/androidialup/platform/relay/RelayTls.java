package io.circuitdrift.androidialup.platform.relay;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS policy for the relay control stream (S1_SPEC_FREEZE section 11, S1_WIRE_PROTOCOL
 * {@code CONTROL}): TLS 1.3 only, certificate validation against a configurable trust store and
 * mandatory hostname verification.
 *
 * <p>Uses only {@code javax.net.ssl}, so the exact same code runs on the JVM unit tests and on
 * Android (Conscrypt). Hostname verification is enforced twice, deliberately:
 * <ol>
 *   <li>{@link SSLParameters#setEndpointIdentificationAlgorithm(String)} with {@code "HTTPS"}
 *       (API 24+), which makes JSSE/Conscrypt's X509ExtendedTrustManager check the name during
 *       the handshake;</li>
 *   <li>an explicit {@link HostnameVerifier} on the established session, because the Android
 *       security guide states that {@code SSLSocket} does not perform hostname verification and
 *       tells apps to call {@code HttpsURLConnection.getDefaultHostnameVerifier()} themselves.</li>
 * </ol>
 *
 * <p>There is no TLS 1.2 fallback. TLS 1.3 exists on Android from API 29; on API 26-28 every
 * relay connect fails closed with {@code TLS_FAILURE} (see
 * {@code docs/implementation/I1_ANDROID_PLATFORM_STATUS.md}, Decisions).
 */
public final class RelayTls {
    public static final String TLS_1_3 = "TLSv1.3";

    private RelayTls() {}

    /**
     * Builds a client socket factory that trusts exactly {@code trustStore}, or the platform's
     * default CA set when {@code trustStore} is null.
     */
    public static SSLSocketFactory socketFactory(KeyStore trustStore) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        // "TLS" is available on every API level; the protocol set is narrowed per socket below.
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    /**
     * Loads X.509 certificates (PEM or DER, one or more) into a fresh in-memory trust store,
     * e.g. a private relay CA shipped with a developer build.
     */
    public static KeyStore trustStoreOf(InputStream certificates) throws GeneralSecurityException, IOException {
        Objects.requireNonNull(certificates, "certificates");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = factory.generateCertificates(certificates);
        if (certs.isEmpty()) throw new GeneralSecurityException("no certificates in trust input");
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int index = 0;
        for (Certificate cert : certs) {
            store.setCertificateEntry("relay-ca-" + index++, cert);
        }
        return store;
    }

    /**
     * Layers TLS 1.3 over an already connected (network-bound) TCP socket and completes the
     * handshake. The returned socket owns {@code connected}: closing it closes both. On any
     * failure {@code connected} is closed and a {@code TLS_FAILURE} is thrown.
     *
     * @param connected connected TCP socket created through the selected {@code Network}
     * @param host relay host name used for SNI and hostname verification
     * @param port relay port
     * @param factory socket factory carrying the trust policy, see {@link #socketFactory}
     * @param hostnameVerifier explicit post-handshake verifier; on Android pass
     *        {@code HttpsURLConnection.getDefaultHostnameVerifier()}. May be null only where the
     *        endpoint identification algorithm is known to be enforced (JVM tests).
     * @param handshakeTimeoutMs read timeout applied during the handshake only
     */
    public static SSLSocket wrap(Socket connected, String host, int port, SSLSocketFactory factory,
                                 HostnameVerifier hostnameVerifier, int handshakeTimeoutMs)
            throws RelayConnectException {
        Objects.requireNonNull(connected, "connected");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(factory, "factory");
        SSLSocket ssl = null;
        try {
            ssl = (SSLSocket) factory.createSocket(connected, host, port, true);
            if (!Arrays.asList(ssl.getSupportedProtocols()).contains(TLS_1_3)) {
                throw new RelayConnectException(RelayConnectException.TLS_FAILURE,
                        "TLSv1.3 is not supported by this platform (Android API 29+ required)");
            }
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setProtocols(new String[] {TLS_1_3});
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!isIpLiteral(host)) {
                parameters.setServerNames(List.of(new SNIHostName(host)));
            }
            ssl.setSSLParameters(parameters);
            ssl.setUseClientMode(true);
            ssl.setSoTimeout(Math.max(0, handshakeTimeoutMs));
            ssl.startHandshake();

            SSLSession session = ssl.getSession();
            if (!TLS_1_3.equals(session.getProtocol())) {
                throw new RelayConnectException(RelayConnectException.TLS_FAILURE,
                        "negotiated " + session.getProtocol() + " instead of TLSv1.3");
            }
            if (hostnameVerifier != null && !hostnameVerifier.verify(host, session)) {
                throw new RelayConnectException(RelayConnectException.TLS_FAILURE,
                        "relay certificate does not match host " + host);
            }
            ssl.setSoTimeout(0);
            return ssl;
        } catch (RelayConnectException failure) {
            closeQuietly(ssl, connected);
            throw failure;
        } catch (IOException | RuntimeException failure) {
            closeQuietly(ssl, connected);
            throw new RelayConnectException(RelayConnectException.TLS_FAILURE,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure);
        }
    }

    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true; // IPv6 literal
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return !host.isEmpty();
    }

    private static void closeQuietly(Socket ssl, Socket connected) {
        try {
            if (ssl != null) ssl.close();
        } catch (IOException ignored) {
        }
        try {
            connected.close();
        } catch (IOException ignored) {
        }
    }
}
