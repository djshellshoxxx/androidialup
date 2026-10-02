package io.circuitdrift.androidialup.platform.relay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.circuitdrift.androidialup.protocol.AduFrame;
import io.circuitdrift.androidialup.protocol.FrameKind;
import io.circuitdrift.androidialup.protocol.Messages;
import io.circuitdrift.androidialup.session.RelaySessionMachine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RelayTlsTransportTest {
    private FakeRelayServer relay;
    private RelayTlsTransport transport;
    private final Recorder events = new Recorder();

    /** Records every listener event and the thread it arrived on. */
    static final class Recorder implements RelayTlsTransport.Listener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> data = new LinkedBlockingQueue<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicLong closeCount = new AtomicLong();
        volatile String closeReason;

        private void record(String event) {
            threads.add(Thread.currentThread().getName());
            events.add(event);
        }

        @Override public void onReady() { record("READY"); }
        @Override public void onCallConnected(String detail) { record("CONNECTED"); }
        @Override public void onRemoteData(byte[] bytes) { threads.add(Thread.currentThread().getName()); data.add(bytes); }
        @Override public void onDialFailed(Messages.DialFailure reason, String detail) { record("DIAL_FAILED:" + reason); }
        @Override public void onCallTerminated(String reason) { record("TERMINATED:" + reason); }
        @Override public void onRequestRejected(String operation, String detail) { record("REJECTED:" + operation); }
        @Override public void onTransportClosed(String reason) {
            closeCount.incrementAndGet();
            closeReason = reason;
            record("CLOSED:" + reason);
            closed.countDown();
        }

        String next() throws InterruptedException {
            return events.poll(5, TimeUnit.SECONDS);
        }
    }

    @Before
    public void setUp() throws IOException {
        relay = new FakeRelayServer();
    }

    @After
    public void tearDown() throws IOException {
        if (transport != null) transport.close("TEST_DONE");
        relay.close();
    }

    private static RelaySessionMachine machine() {
        byte[] call = FakeRelayServer.filled(0x43);
        return new RelaySessionMachine(new byte[32], (r, e, challenge) -> new byte[] {1, 2, 3}, call::clone);
    }

    private RelayTlsTransport start(RelayTlsTransport.Connector connector, AtomicLong clock, long tickMs) {
        transport = new RelayTlsTransport(machine(), connector, events, clock::get,
                new RelayTlsTransport.Config(256, tickMs, 2_000, 4096));
        transport.start();
        return transport;
    }

    @Test
    public void handshakeDialEchoAndHangupRunOnTheOwnerThread() throws Exception {
        start(relay.connector(), new AtomicLong(), 50);
        assertEquals("READY", events.next());
        assertEquals(FrameKind.HELLO, relay.received.take().kind()); // HELLO is the first frame

        transport.dial("loopback", Messages.NetworkTransport.WIFI);
        assertEquals("CONNECTED", events.next());

        byte[] payload = new byte[200_000];
        new Random(7).nextBytes(payload);
        for (int offset = 0; offset < payload.length; offset += 50_000) {
            transport.writeData(java.util.Arrays.copyOfRange(payload, offset, offset + 50_000));
        }
        java.io.ByteArrayOutputStream echoed = new java.io.ByteArrayOutputStream();
        while (echoed.size() < payload.length) {
            byte[] chunk = events.data.poll(5, TimeUnit.SECONDS);
            assertNotNull("echo stalled at " + echoed.size(), chunk);
            echoed.writeBytes(chunk);
        }
        assertArrayEquals(payload, echoed.toByteArray());

        transport.hangup("LOCAL_HANGUP");
        assertEquals("TERMINATED:LOCAL_HANGUP", events.next());
        assertTrue(events.closed.await(5, TimeUnit.SECONDS));
        assertEquals("LOCAL_HANGUP", events.closeReason);
        assertNotNull(relay.awaitKind(FrameKind.HANGUP_REQUEST, 1000));

        for (String thread : events.threads) {
            assertEquals("relay-owner", thread);
        }
    }

    @Test
    public void authFailureClosesWithAuthFailure() throws Exception {
        relay.rejectAuth = true;
        start(relay.connector(), new AtomicLong(), 50);
        assertTrue(events.closed.await(5, TimeUnit.SECONDS));
        assertEquals(RelayTlsTransport.AUTH_FAILURE, events.closeReason);
    }

    @Test
    public void relayDeathDuringCallIsNetworkLostExactlyOnce() throws Exception {
        start(relay.connector(), new AtomicLong(), 50);
        assertEquals("READY", events.next());
        transport.dial("loopback", Messages.NetworkTransport.CELLULAR);
        assertEquals("CONNECTED", events.next());
        relay.dropConnections();
        assertTrue(events.closed.await(5, TimeUnit.SECONDS));
        assertEquals(RelayTlsTransport.NETWORK_LOST, events.closeReason);
        transport.close("LATE");
        Thread.sleep(100);
        assertEquals(1, events.closeCount.get());
    }

    @Test
    public void dialFailureIsReported() throws Exception {
        relay.dialFailure = Messages.DialFailure.BUSY;
        start(relay.connector(), new AtomicLong(), 50);
        assertEquals("READY", events.next());
        transport.dial("555", Messages.NetworkTransport.WIFI);
        assertEquals("DIAL_FAILED:BUSY", events.next());
    }

    @Test
    public void dnsFailureMapsToTaxonomyReason() throws Exception {
        start(() -> { throw new UnknownHostException("relay.invalid"); }, new AtomicLong(), 50);
        assertTrue(events.closed.await(5, TimeUnit.SECONDS));
        assertEquals(RelayConnectException.DNS_FAILURE, events.closeReason);
    }

    @Test
    public void lateSocketAfterCloseIsClosedAndIgnored() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Socket[] produced = new Socket[1];
        start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            produced[0] = new Socket(InetAddress.getLoopbackAddress(), relay.port());
            return produced[0];
        }, new AtomicLong(), 50);
        transport.close("LOCAL_HANGUP");
        assertTrue(events.closed.await(5, TimeUnit.SECONDS));
        release.countDown();
        long deadline = System.currentTimeMillis() + 2000;
        while ((produced[0] == null || !produced[0].isClosed()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("late socket must be closed", produced[0].isClosed());
        assertEquals("LOCAL_HANGUP", events.closeReason);
        assertEquals(1, events.closeCount.get());
        assertNull(relay.awaitKind(FrameKind.HELLO, 200));
    }

    @Test
    public void unansweredHeartbeatFailsTransportFromTimerTick() throws Exception {
        relay.heartbeatSeconds = 1;
        relay.answerPings = false;
        AtomicLong clock = new AtomicLong(1_000);
        start(relay.connector(), clock, 5);
        assertEquals("READY", events.next());

        clock.addAndGet(1_000); // heartbeat interval elapsed without peer activity
        AduFrame ping = relay.awaitKind(FrameKind.PING, 2000);
        assertNotNull("timer tick must send PING", ping);

        clock.addAndGet(RelaySessionMachine.HEARTBEAT_FAILURE_MS);
        assertTrue(events.closed.await(5, TimeUnit.SECONDS));
        assertEquals("HEARTBEAT_TIMEOUT", events.closeReason);
    }

    @Test
    public void answeredHeartbeatKeepsTransportUp() throws Exception {
        relay.heartbeatSeconds = 1;
        AtomicLong clock = new AtomicLong(1_000);
        start(relay.connector(), clock, 5);
        assertEquals("READY", events.next());
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(1_000);
            assertNotNull(relay.awaitKind(FrameKind.PING, 2000));
            Thread.sleep(50); // PONG arrives and clears the outstanding nonce
        }
        clock.addAndGet(RelaySessionMachine.HEARTBEAT_FAILURE_MS - 1);
        Thread.sleep(100);
        assertEquals(0, events.closeCount.get());
    }
}
