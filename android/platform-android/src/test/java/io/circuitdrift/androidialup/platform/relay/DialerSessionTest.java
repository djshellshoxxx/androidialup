package io.circuitdrift.androidialup.platform.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.circuitdrift.androidialup.modem.ModemState;
import io.circuitdrift.androidialup.platform.dialer.CallLog;
import io.circuitdrift.androidialup.platform.dialer.CallLogRecord;
import io.circuitdrift.androidialup.platform.dialer.DestinationInput;
import io.circuitdrift.androidialup.platform.dialer.DialMethod;
import io.circuitdrift.androidialup.platform.dialer.DialerSession;
import io.circuitdrift.androidialup.protocol.FrameKind;
import io.circuitdrift.androidialup.protocol.Messages;
import io.circuitdrift.androidialup.session.RelaySessionMachine;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Developer dialer over the real modem + relay transport against the scripted relay. */
public class DialerSessionTest {
    private FakeRelayServer relay;
    private DialerSession dialer;
    private final CallLog log = new CallLog();
    private final BlockingQueue<CallLogRecord> records = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> rejections = new LinkedBlockingQueue<>();
    private volatile DialerSession.Snapshot latest;

    @Before
    public void setUp() throws Exception {
        relay = new FakeRelayServer();
        log.addListener(records::add);
        dialer = new DialerSession(listener -> new RelayModemSessionPort.Connection(
                new RelayTlsTransport(new RelaySessionMachine(new byte[32], c -> new byte[] {1},
                        () -> FakeRelayServer.filled(0x31)), relay.connector(), listener,
                        () -> System.nanoTime() / 1_000_000, RelayTlsTransport.Config.DEFAULT),
                Messages.NetworkTransport.WIFI),
                log, new DialerSession.Listener() {
                    @Override public void onSnapshot(DialerSession.Snapshot snapshot) { latest = snapshot; }
                    @Override public void onRejected(String reason) { rejections.add(reason); }
                }, () -> System.nanoTime() / 1_000_000, System::nanoTime);
        Thread.sleep(100); // ATE0 / AT+DIAG=1 / ATS12=1 init
    }

    @After
    public void tearDown() throws IOException {
        dialer.close();
        relay.close();
    }

    private void await(Predicate<DialerSession.Snapshot> condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            DialerSession.Snapshot s = latest;
            if (s != null && condition.test(s)) return;
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting for " + what + "; last " + latest);
    }

    @Test
    public void connectThenHangUpRecordsOneConnectCall() throws Exception {
        dialer.dial(new DestinationInput("5551212", DialMethod.TONE, 10_000));
        await(s -> s.modem().state() == ModemState.ONLINE_DATA && s.modem().signals().dcd(), "ONLINE_DATA + DCD");
        assertEquals("CONNECT", latest.lastResult());
        assertTrue(latest.callInProgress());

        dialer.hangUp(); // ONLINE_DATA: guarded +++ then ATH
        CallLogRecord record = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(record);
        assertEquals(CallLogRecord.Outcome.CONNECT, record.outcome());
        assertEquals("LOCAL_HANGUP", record.internalReason());
        assertEquals("xxx1212", record.targetRedacted());
        assertEquals(DialMethod.TONE, record.dialMethod());
        assertEquals("ROUTING", record.progress().get(0).phase());
        assertTrue(record.endedAtMonotonicMs() >= record.startedAtMonotonicMs());
        assertNotNull(relay.awaitKind(FrameKind.HANGUP_REQUEST, 2000));
        await(s -> s.modem().state() == ModemState.COMMAND && !s.callInProgress(), "COMMAND");
        assertFalse(log.exportNdjson().contains("5551212"));
    }

    @Test
    public void busyIsRecordedAndNothingRedials() throws Exception {
        relay.dialFailure = Messages.DialFailure.BUSY;
        dialer.dial(new DestinationInput("5551212", DialMethod.AUTO));
        CallLogRecord record = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(record);
        assertEquals(CallLogRecord.Outcome.BUSY, record.outcome());
        Thread.sleep(300);
        assertNull("no automatic redial or advance", records.poll());
        assertEquals(1, relay.received.stream().filter(f -> f.kind() == FrameKind.DIAL_REQUEST).count());
    }

    @Test
    public void cancelDuringDialingIsCancelled() throws Exception {
        relay.ignoreDial = true;
        dialer.dial(new DestinationInput("5551212", DialMethod.PULSE, 10_000));
        await(s -> s.modem().state() == ModemState.DIALING, "DIALING");
        dialer.cancel();
        CallLogRecord record = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(record);
        assertEquals(CallLogRecord.Outcome.CANCELLED, record.outcome());
        assertEquals("LOCAL_HANGUP", record.internalReason());
    }

    @Test
    public void perCallTimeoutIsNoAnswer() throws Exception {
        relay.ignoreDial = true;
        dialer.dial(new DestinationInput("5551212", DialMethod.TONE, 200));
        CallLogRecord record = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(record);
        assertEquals(CallLogRecord.Outcome.NO_ANSWER, record.outcome());
    }

    @Test
    public void secondDialWhileBusyIsRejected() throws Exception {
        relay.ignoreDial = true;
        dialer.dial(new DestinationInput("5551212", DialMethod.TONE, 10_000));
        await(s -> s.modem().state() == ModemState.DIALING, "DIALING");
        dialer.dial(new DestinationInput("5550000", DialMethod.TONE, 10_000));
        assertNotNull(rejections.poll(2, TimeUnit.SECONDS));
        dialer.cancel();
        assertNotNull(records.poll(5, TimeUnit.SECONDS));
    }
}
