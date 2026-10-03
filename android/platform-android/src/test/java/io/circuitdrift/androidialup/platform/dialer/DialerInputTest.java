package io.circuitdrift.androidialup.platform.dialer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class DialerInputTest {

    @Test
    public void methodsMapToDialPrefixes() {
        assertEquals("ATD5551212", new DestinationInput("5551212", DialMethod.AUTO).commandLine());
        assertEquals("ATDT5551212", new DestinationInput("5551212", DialMethod.TONE).commandLine());
        assertEquals("ATDP5551212", new DestinationInput("5551212", DialMethod.PULSE).commandLine());
        assertEquals(DestinationInput.DEFAULT_TIMEOUT_MS, new DestinationInput("1", DialMethod.AUTO).perCallTimeoutMs());
    }

    @Test
    public void invalidTargetsAreRejectedBeforeDialing() {
        assertThrows(IllegalArgumentException.class, () -> new DestinationInput("", DialMethod.TONE));
        assertThrows(IllegalArgumentException.class, () -> new DestinationInput("555;", DialMethod.TONE));
        assertThrows(IllegalArgumentException.class, () -> new DestinationInput("555\r1", DialMethod.TONE));
        assertThrows(IllegalArgumentException.class, () -> new DestinationInput(" 555", DialMethod.TONE));
        assertThrows(IllegalArgumentException.class, () -> new DestinationInput("5".repeat(257), DialMethod.TONE));
        assertThrows(IllegalArgumentException.class, () -> new DestinationInput("555", DialMethod.TONE, 0));
        // AUTO would read the leading T/P as a dial modifier.
        assertThrows(IllegalArgumentException.class, () -> new DestinationInput("T123", DialMethod.AUTO));
        assertEquals("ATDTT123", new DestinationInput("T123", DialMethod.TONE).commandLine());
    }

    @Test
    public void dtmfCharactersAndPausesAreAccepted() {
        new DestinationInput("1,555*12#A", DialMethod.TONE);
    }

    @Test
    public void testListRequiresIndividualEntriesAndAttestation() {
        assertThrows(IllegalArgumentException.class, () -> TestList.of(List.of(), DialMethod.TONE, 60_000));
        assertThrows(IllegalArgumentException.class,
                () -> TestList.of(List.of("5550100..5550199"), DialMethod.TONE, 60_000));
        assertThrows(IllegalArgumentException.class,
                () -> TestList.of(List.of("555[0-9]"), DialMethod.TONE, 60_000));
        assertThrows(IllegalArgumentException.class,
                () -> TestList.of(List.of("5551212", ""), DialMethod.TONE, 60_000));
        assertThrows(IllegalArgumentException.class,
                () -> TestList.of(java.util.Collections.nCopies(TestList.MAX_ENTRIES + 1, "5551212"),
                        DialMethod.TONE, 60_000));

        TestList list = TestList.of(List.of("5551212", "5551313"), DialMethod.TONE, 30_000);
        assertThrows(IllegalStateException.class, () -> list.entryForDial(0));
        CallLog log = new CallLog();
        list.attest(log, 42);
        assertTrue(list.attested());
        assertEquals("5551313", list.entryForDial(1).target());
        assertEquals(2, log.header().size());
        assertEquals(TestList.ATTESTATION_TEXT, log.header().get(0).text());
        assertTrue(log.exportNdjson().startsWith("{\"header\":\"TEST_LIST_ATTESTATION\""));
    }

    @Test
    public void recordJsonIsRedactedAndEscaped() {
        CallLogRecord record = new CallLogRecord(10, CallLogRecord.redact("5551212"), DialMethod.TONE,
                List.of(new CallLogRecord.Progress("ROUTING", "gw \"1\"", 12)), List.of(), List.of(),
                null, CallLogRecord.Outcome.BUSY, "BUSY", 20, null);
        String json = record.toJson();
        assertEquals("{\"started_at_monotonic_ms\":10,\"target_redacted\":\"xxx1212\",\"dial_method\":\"TONE\","
                + "\"progress\":[{\"phase\":\"ROUTING\",\"detail\":\"gw \\\"1\\\"\",\"at_ms\":12}],"
                + "\"tones\":[],\"dtmf_detected\":[],\"negotiated\":{},\"outcome\":\"BUSY\","
                + "\"internal_reason\":\"BUSY\",\"ended_at_monotonic_ms\":20}", json);
        assertFalse(json.contains("5551212"));
        assertEquals("xxxxback", CallLogRecord.redact("loopback"));
        assertEquals("12", CallLogRecord.redact("12"));
    }
}
