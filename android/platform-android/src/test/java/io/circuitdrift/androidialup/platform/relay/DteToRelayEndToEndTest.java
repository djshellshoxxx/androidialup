package io.circuitdrift.androidialup.platform.relay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.circuitdrift.androidialup.modem.ModemController;
import io.circuitdrift.androidialup.modem.ResultCode;
import io.circuitdrift.androidialup.platform.dte.TcpDteServer;
import io.circuitdrift.androidialup.protocol.FrameKind;
import io.circuitdrift.androidialup.protocol.Messages;
import io.circuitdrift.androidialup.session.RelaySessionMachine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * JVM port of the Python terminal acceptance (I1_AT_DTE_STATUS steps 1-8): TCP DTE client ->
 * TcpDteServer -> ModemController -> RelayModemSessionPort -> RelayTlsTransport -> scripted relay
 * (plain loopback TCP; the TLS layer is covered by RelayTlsTest, the Network binding by the
 * device-only androidTest).
 */
public class DteToRelayEndToEndTest {
    private FakeRelayServer relay;
    private TcpDteServer server;
    private volatile boolean noNetwork;
    private volatile long dialTimeoutMs = RelayModemSessionPort.DEFAULT_DIAL_TIMEOUT_MS;
    private final java.util.List<Messages.ProgressPhase> progress = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws IOException {
        relay = new FakeRelayServer();
        server = new TcpDteServer(InetAddress.getLoopbackAddress(), 0, client -> {
            RelayModemSessionPort port = new RelayModemSessionPort(listener -> {
                if (noNetwork) {
                    throw new RelayConnectException(RelayConnectException.NO_ELIGIBLE_NETWORK, "test");
                }
                RelaySessionMachine machine = new RelaySessionMachine(new byte[32], c -> new byte[] {1},
                        () -> FakeRelayServer.filled(0x21));
                RelayTlsTransport transport = new RelayTlsTransport(machine, relay.connector(), listener,
                        () -> System.nanoTime() / 1_000_000, RelayTlsTransport.Config.DEFAULT);
                return new RelayModemSessionPort.Connection(transport, Messages.NetworkTransport.WIFI);
            }, client.modemExecutor());
            port.setDialTimeoutMs(dialTimeoutMs);
            port.setProgressObserver((phase, detail) -> progress.add(phase));
            ModemController controller = new ModemController(port, client.writer(), "e2e");
            port.bind(controller);
            client.addCloseHook(port::close);
            return controller;
        }, null, 5, TcpDteServer.DEFAULT_MAX_QUEUED_DTE_BYTES, System::nanoTime);
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        relay.close();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.boundPort());
        socket.setSoTimeout(100);
        return socket;
    }

    private static void send(Socket socket, byte[] data) throws IOException {
        socket.getOutputStream().write(data);
        socket.getOutputStream().flush();
    }

    private static void send(Socket socket, String text) throws IOException {
        send(socket, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] readBytes(Socket socket, int count) throws IOException {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[65536];
        long deadline = System.currentTimeMillis() + 10_000;
        while (got.size() < count && System.currentTimeMillis() < deadline) {
            try {
                int n = in.read(buffer, 0, Math.min(buffer.length, count - got.size()));
                if (n < 0) break;
                got.write(buffer, 0, n);
            } catch (SocketTimeoutException idle) {
                // poll again
            }
        }
        return got.toByteArray();
    }

    private static String readUntil(Socket socket, String expected) throws IOException {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String text = got.toString(StandardCharsets.ISO_8859_1);
            if (text.endsWith(expected)) return text;
            try {
                int b = in.read();
                if (b < 0) break;
                got.write(b);
            } catch (SocketTimeoutException idle) {
                // poll again
            }
        }
        throw new AssertionError("expected '" + expected.replace("\r\n", "\\r\\n") + "', got '"
                + got.toString(StandardCharsets.ISO_8859_1).replace("\r\n", "\\r\\n") + "'");
    }

    @Test
    public void terminalDialBinaryEscapeResumeAndHangup() throws Exception {
        try (Socket dte = connect()) {
            send(dte, "ATE0\r");
            readUntil(dte, "OK\r\n");
            send(dte, "ATS12=1\r");
            readUntil(dte, "OK\r\n");

            send(dte, "ATDloopback\r");
            readUntil(dte, "CONNECT\r\n");
            assertNotNull(relay.awaitKind(FrameKind.DIAL_REQUEST, 2000));

            byte[] payload = new byte[64 * 1024];
            new Random(1).nextBytes(payload);
            // Strip '+' so random data cannot form an escape candidate.
            for (int i = 0; i < payload.length; i++) if (payload[i] == '+') payload[i] = '*';
            send(dte, payload);
            assertArrayEquals(payload, readBytes(dte, payload.length));

            Thread.sleep(60);
            send(dte, "+++");
            readUntil(dte, "OK\r\n");
            send(dte, "ATO\r");
            readUntil(dte, "CONNECT\r\n");

            Thread.sleep(60);
            send(dte, "+++");
            readUntil(dte, "OK\r\n");
            send(dte, "ATH\r");
            readUntil(dte, "OK\r\n");
            assertNotNull("ATH must send HANGUP_REQUEST", relay.awaitKind(FrameKind.HANGUP_REQUEST, 2000));
        }
    }

    @Test
    public void relayLossDuringCallIsNoCarrier() throws Exception {
        try (Socket dte = connect()) {
            send(dte, "ATE0\r");
            readUntil(dte, "OK\r\n");
            send(dte, "AT+DIAG=1\r");
            readUntil(dte, "OK\r\n");
            send(dte, "ATDloopback\r");
            readUntil(dte, "CONNECT\r\n");
            relay.dropConnections();
            String out = readUntil(dte, ResultCode.NO_CARRIER.text() + "\r\n");
            assertTrue(out, out.contains("+ADIAG: NETWORK_LOST\r\n"));
        }
    }

    @Test
    public void callProgressPhasesReachTheObserverInOrder() throws Exception {
        try (Socket dte = connect()) {
            send(dte, "ATE0\rATDloopback\r");
            readUntil(dte, "CONNECT\r\n");
            long deadline = System.currentTimeMillis() + 2000;
            while (progress.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            org.junit.Assert.assertEquals(
                    java.util.List.of(Messages.ProgressPhase.ROUTING, Messages.ProgressPhase.CONNECTED), progress);
        }
    }

    @Test
    public void perCallTimeoutYieldsNoAnswer() throws Exception {
        relay.ignoreDial = true;
        dialTimeoutMs = 300;
        try (Socket dte = connect()) {
            send(dte, "ATE0\r");
            readUntil(dte, "OK\r\n");
            long start = System.currentTimeMillis();
            send(dte, "ATD5551212\r");
            readUntil(dte, "NO ANSWER\r\n");
            assertTrue(System.currentTimeMillis() - start >= 250);
            send(dte, "AT\r");
            readUntil(dte, "OK\r\n");
        }
    }

    @Test
    public void relayBusyIsBusy() throws Exception {
        relay.dialFailure = Messages.DialFailure.BUSY;
        try (Socket dte = connect()) {
            send(dte, "ATE0\r");
            readUntil(dte, "OK\r\n");
            send(dte, "ATD5551212\r");
            readUntil(dte, "BUSY\r\n");
            send(dte, "AT\r");
            readUntil(dte, "OK\r\n");
        }
    }

    @Test
    public void noEligibleNetworkIsNoCarrier() throws Exception {
        noNetwork = true;
        try (Socket dte = connect()) {
            send(dte, "ATE0\r");
            readUntil(dte, "OK\r\n");
            send(dte, "ATDloopback\r");
            readUntil(dte, "NO CARRIER\r\n");
        }
    }

    @Test
    public void dteDisconnectDuringCallClosesRelayConnection() throws Exception {
        Socket dte = connect();
        send(dte, "ATE0\rATDloopback\r");
        readUntil(dte, "CONNECT\r\n");
        dte.close();
        // DTE_DISCONNECTED -> HANGUP_REQUEST on the relay
        assertNotNull(relay.awaitKind(FrameKind.HANGUP_REQUEST, 5000));
    }
}
