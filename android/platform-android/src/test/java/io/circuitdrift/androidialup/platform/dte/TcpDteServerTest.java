package io.circuitdrift.androidialup.platform.dte;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.circuitdrift.androidialup.modem.ModemController;
import io.circuitdrift.androidialup.modem.SessionListener;
import io.circuitdrift.androidialup.modem.SessionPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TcpDteServerTest {

    /** Asynchronous fake session: answers dials on the client's modem thread, echoes data. */
    static final class FakeSession implements SessionPort {
        final Executor modemThread;
        final BlockingQueue<String> hangups = new LinkedBlockingQueue<>();
        final List<String> callThreads = new CopyOnWriteArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        SessionListener listener;

        FakeSession(Executor modemThread) {
            this.modemThread = modemThread;
        }

        @Override public void dial(String target) {
            callThreads.add(Thread.currentThread().getName());
            modemThread.execute(() -> listener.onCallConnected());
        }

        @Override public void writeData(byte[] data) {
            callThreads.add(Thread.currentThread().getName());
            synchronized (written) {
                written.writeBytes(data);
            }
            modemThread.execute(() -> listener.onRemoteData(data));
        }

        @Override public void hangup(String reason) {
            callThreads.add(Thread.currentThread().getName());
            hangups.add(reason);
        }

        @Override public void answer() {}
    }

    private TcpDteServer server;
    private final BlockingQueue<FakeSession> sessions = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> closedReasons = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws IOException {
        server = new TcpDteServer(InetAddress.getLoopbackAddress(), 0, client -> {
            FakeSession session = new FakeSession(client.modemExecutor());
            ModemController controller = new ModemController(session, client.writer(), "tcp-dte-test");
            session.listener = controller;
            sessions.add(session);
            return controller;
        }, new TcpDteServer.Listener() {
            @Override public void onClientClosed(TcpDteServer.DteClient client, String reason) {
                closedReasons.add(reason);
            }
        }, 5, TcpDteServer.DEFAULT_MAX_QUEUED_DTE_BYTES, System::nanoTime);
        server.start();
    }

    @After
    public void tearDown() {
        server.close();
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.boundPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(100);
        return socket;
    }

    private static void send(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Reads until {@code expected} has arrived in full, or fails after 5 s. */
    private static String readUntil(Socket socket, String expected) throws IOException {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[65536];
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String text = got.toString(StandardCharsets.ISO_8859_1);
            if (text.contains(expected)) return text;
            try {
                int n = in.read(buffer);
                if (n < 0) break;
                got.write(buffer, 0, n);
            } catch (SocketTimeoutException idle) {
                // keep polling
            }
        }
        throw new AssertionError("expected " + printable(expected) + " but got "
                + printable(got.toString(StandardCharsets.ISO_8859_1)));
    }

    private static String printable(String s) {
        return "'" + s.replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    @Test
    public void fragmentedCommandProducesOneResult() throws Exception {
        try (Socket dte = connect()) {
            send(dte, "A");
            Thread.sleep(20);
            send(dte, "T");
            Thread.sleep(20);
            send(dte, "\r");
            assertEquals("AT\rOK\r\n", readUntil(dte, "OK\r\n"));
        }
    }

    @Test
    public void coalescedCommandsAreExecutedInOrder() throws Exception {
        try (Socket dte = connect()) {
            send(dte, "ATE0\rAT\rATI0\r");
            String out = readUntil(dte, "AndroidDialup\r\nOK\r\n");
            assertEquals("ATE0\rOK\r\nOK\r\nAndroidDialup\r\nOK\r\n", out);
        }
    }

    @Test
    public void eachClientHasItsOwnController() throws Exception {
        try (Socket first = connect(); Socket second = connect()) {
            send(first, "ATE0\r");
            readUntil(first, "OK\r\n");
            send(second, "AT\r");
            assertEquals("AT\rOK\r\n", readUntil(second, "OK\r\n"));
            send(first, "AT\r");
            assertEquals("OK\r\n", readUntil(first, "OK\r\n"));
            assertEquals(2, server.clientCount());
        }
    }

    @Test
    public void idleTimerCompletesEscapeWithoutFurtherInput() throws Exception {
        try (Socket dte = connect()) {
            send(dte, "ATE0\rATS12=1\r");
            readUntil(dte, "OK\r\nOK\r\n");
            send(dte, "ATDloopback\r");
            readUntil(dte, "CONNECT\r\n");

            send(dte, "hello");
            readUntil(dte, "hello");

            Thread.sleep(60); // pre-guard silence (S12=1 -> 20 ms)
            send(dte, "+++");
            // No more DTE bytes: only the idle timer can complete the post-guard interval.
            assertTrue(readUntil(dte, "OK\r\n").endsWith("OK\r\n"));

            send(dte, "ATH\r");
            readUntil(dte, "OK\r\n");
            FakeSession session = sessions.take();
            assertEquals("LOCAL_HANGUP", session.hangups.poll(2, TimeUnit.SECONDS));
            synchronized (session.written) {
                assertEquals("hello", session.written.toString(StandardCharsets.ISO_8859_1));
            }
            for (String thread : session.callThreads) {
                assertTrue(thread, thread.startsWith("dte-modem-"));
            }
        }
    }

    @Test
    public void dteDisconnectDuringCallHangsUp() throws Exception {
        Socket dte = connect();
        send(dte, "ATE0\rATDloopback\r");
        readUntil(dte, "CONNECT\r\n");
        dte.close();
        FakeSession session = sessions.take();
        assertEquals(ModemController.DTE_DISCONNECTED, session.hangups.poll(5, TimeUnit.SECONDS));
        assertNotNull(closedReasons.poll(5, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 2000;
        while (server.clientCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(0, server.clientCount());
    }

    @Test
    public void serverCloseDisconnectsClients() throws Exception {
        try (Socket dte = connect()) {
            send(dte, "AT\r");
            readUntil(dte, "OK\r\n");
            server.close();
            assertEquals("SERVER_CLOSED", closedReasons.poll(5, TimeUnit.SECONDS));
            dte.setSoTimeout(2000);
            assertEquals(-1, dte.getInputStream().read());
        }
    }
}
