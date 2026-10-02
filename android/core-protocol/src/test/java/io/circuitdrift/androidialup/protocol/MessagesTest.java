package io.circuitdrift.androidialup.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mirrors the constructor validation of prototype/python/androidialup_protocol/messages.py. */
class MessagesTest {
    private static byte[] fill(int n, char c) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, (byte) c);
        return out;
    }

    private static List<String> strings(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("s" + i);
        return out;
    }

    private static List<Map.Entry<String, String>> entries(int n) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(Map.entry("k" + i, "v" + i));
        return out;
    }

    @Test
    void limitsMatchPythonReference() {
        assertEquals(4096, MessageLimits.MAX_SHORT_STRING);
        assertEquals(256, MessageLimits.MAX_TARGET_UTF8);
        assertEquals(128, MessageLimits.MAX_CAPABILITIES);
        assertEquals(128, MessageLimits.MAX_MAP_ENTRIES);
        assertEquals(65535, MessageLimits.MAX_BLOB);
        assertEquals(32768, MessageLimits.MAX_DATA_BYTES);
    }

    @Test
    void helloValidation() {
        byte[] ep = fill(32, 'E');
        new Hello("n", "v", 0, 0xffff, ep, List.of());
        assertThrows(IllegalArgumentException.class, () -> new Hello("n", "v", -1, 1, ep, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Hello("n", "v", 1, 0x10000, ep, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Hello("n", "v", 1, 1, fill(31, 'E'), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Hello("n", "v", 1, 1, fill(33, 'E'), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Hello("x".repeat(4097), "v", 1, 1, ep, List.of()));
        // 4096 UTF-8 bytes is allowed; "é" is two bytes, so 2049 of them is over the limit.
        new Hello("x".repeat(4096), "v", 1, 1, ep, List.of());
        new Hello("é".repeat(2048), "v", 1, 1, ep, List.of());
        assertThrows(IllegalArgumentException.class, () -> new Hello("é".repeat(2049), "v", 1, 1, ep, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Hello("n", "x".repeat(4097), 1, 1, ep, List.of()));
        new Hello("n", "v", 1, 1, ep, strings(128));
        assertThrows(IllegalArgumentException.class, () -> new Hello("n", "v", 1, 1, ep, strings(129)));
        assertThrows(IllegalArgumentException.class, () -> new Hello("n", "v", 1, 1, ep, List.of("x".repeat(4097))));
        assertThrows(NullPointerException.class, () -> new Hello(null, "v", 1, 1, ep, List.of()));
        assertThrows(NullPointerException.class, () -> new Hello("n", "v", 1, 1, null, List.of()));
        assertThrows(NullPointerException.class, () -> new Hello("n", "v", 1, 1, ep, null));
    }

    @Test
    void helloAckValidation() {
        new HelloAck(1, "r", 1, 0, List.of());
        new HelloAck(0xffff, "r", FrameCodec.MAX_PAYLOAD, 0xffff_ffffL, List.of());
        assertThrows(IllegalArgumentException.class, () -> new HelloAck(0x10000, "r", 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HelloAck(1, "r", 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HelloAck(1, "r", FrameCodec.MAX_PAYLOAD + 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HelloAck(1, "r", 1, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HelloAck(1, "r", 1, 0x1_0000_0000L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HelloAck(1, "x".repeat(4097), 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HelloAck(1, "r", 1, 0, strings(129)));
    }

    @Test
    void shortStringMessagesValidation() {
        new HelloReject("x".repeat(4096));
        assertThrows(IllegalArgumentException.class, () -> new HelloReject("x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> new AuthFail("x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> new HangupRequest("x".repeat(4097)));
        assertThrows(NullPointerException.class, () -> new HelloReject(null));
    }

    @Test
    void authValidation() {
        new AuthChallenge(new byte[0], "m");
        new AuthChallenge(new byte[65535], "m");
        assertThrows(IllegalArgumentException.class, () -> new AuthChallenge(new byte[65536], "m"));
        assertThrows(IllegalArgumentException.class, () -> new AuthChallenge(new byte[1], "x".repeat(4097)));
        new AuthResponse(new byte[65535]);
        assertThrows(IllegalArgumentException.class, () -> new AuthResponse(new byte[65536]));
        new AuthOk(fill(32, 'E'), entries(128));
        assertThrows(IllegalArgumentException.class, () -> new AuthOk(fill(16, 'E'), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthOk(fill(32, 'E'), entries(129)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthOk(fill(32, 'E'), List.of(Map.entry("x".repeat(4097), "v"))));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthOk(fill(32, 'E'), List.of(Map.entry("k", "x".repeat(4097)))));
        assertEquals(new AuthBegin(), new AuthBegin());
        assertEquals(new HangupAck(), new HangupAck());
    }

    @Test
    void dialRequestValidation() {
        new DialRequest("t", Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0, List.of());
        new DialRequest("x".repeat(256), Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0xffff_ffffL, List.of());
        new DialRequest("é".repeat(128), Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new DialRequest("", Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DialRequest("x".repeat(257), Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DialRequest("é".repeat(129), Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DialRequest("t", Mode.BYTE_RELAY, ProtocolTransport.WIFI, strings(129), 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DialRequest("t", Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), -1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DialRequest("t", Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0x1_0000_0000L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DialRequest("t", Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0, entries(129)));
        assertThrows(NullPointerException.class,
                () -> new DialRequest("t", null, ProtocolTransport.WIFI, List.of(), 0, List.of()));
        assertThrows(NullPointerException.class,
                () -> new DialRequest("t", Mode.BYTE_RELAY, null, List.of(), 0, List.of()));
    }

    @Test
    void dialAcceptedAndFailedValidation() {
        new DialAccepted(fill(16, 'C'), fill(16, 'S'), "gw", Mode.BYTE_RELAY);
        assertThrows(IllegalArgumentException.class, () -> new DialAccepted(fill(15, 'C'), fill(16, 'S'), "gw", Mode.BYTE_RELAY));
        assertThrows(IllegalArgumentException.class, () -> new DialAccepted(fill(16, 'C'), fill(17, 'S'), "gw", Mode.BYTE_RELAY));
        assertThrows(IllegalArgumentException.class, () -> new DialAccepted(fill(16, 'C'), fill(16, 'S'), "x".repeat(4097), Mode.BYTE_RELAY));
        assertThrows(NullPointerException.class, () -> new DialAccepted(fill(16, 'C'), fill(16, 'S'), "gw", null));

        new DialFailed(fill(16, 'C'), DialFailure.BUSY, true, null);
        new DialFailed(fill(16, 'C'), DialFailure.BUSY, false, "x".repeat(4096));
        assertThrows(IllegalArgumentException.class, () -> new DialFailed(fill(16, 'C'), DialFailure.BUSY, false, "x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> new DialFailed(fill(32, 'C'), DialFailure.BUSY, false, null));
        assertThrows(NullPointerException.class, () -> new DialFailed(fill(16, 'C'), null, false, null));
    }

    @Test
    void progressAndTerminatedValidation() {
        new CallProgress(ProgressPhase.CONNECTED, null);
        new CallProgress(ProgressPhase.CONNECTED, "");
        assertThrows(IllegalArgumentException.class, () -> new CallProgress(ProgressPhase.CONNECTED, "x".repeat(4097)));
        assertThrows(NullPointerException.class, () -> new CallProgress(null, null));

        new CallTerminated("r", TerminationSource.LOCAL, null);
        assertThrows(IllegalArgumentException.class, () -> new CallTerminated("x".repeat(4097), TerminationSource.LOCAL, null));
        assertThrows(IllegalArgumentException.class, () -> new CallTerminated("r", TerminationSource.LOCAL, "x".repeat(4097)));
        assertThrows(NullPointerException.class, () -> new CallTerminated(null, TerminationSource.LOCAL, null));
        assertThrows(NullPointerException.class, () -> new CallTerminated("r", null, null));
    }

    @Test
    void dataBytesValidation() {
        new DataBytes(0, new byte[1]);
        new DataBytes(-1L, new byte[32768]); // -1 is u64 0xffff_ffff_ffff_ffff
        assertThrows(IllegalArgumentException.class, () -> new DataBytes(0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new DataBytes(0, new byte[32769]));
        assertThrows(NullPointerException.class, () -> new DataBytes(0, null));
    }

    @Test
    void flowPingPongValidation() {
        new FlowStatus(0, 0xffff_ffffL);
        assertThrows(IllegalArgumentException.class, () -> new FlowStatus(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FlowStatus(0, 0x1_0000_0000L));
        new Ping(Long.MIN_VALUE, Long.MAX_VALUE);
        new Pong(-1L);
    }

    @Test
    void byteArrayFieldsAreDefensivelyCopiedAndCompareByValue() {
        byte[] nonce = fill(4, 'N');
        var challenge = new AuthChallenge(nonce, "m");
        nonce[0] = 0;
        assertEquals('N', challenge.nonce()[0]);
        challenge.nonce()[1] = 0;
        assertEquals('N', challenge.nonce()[1]);
        assertEquals(new AuthChallenge(fill(4, 'N'), "m"), challenge);
        assertEquals(new AuthChallenge(fill(4, 'N'), "m").hashCode(), challenge.hashCode());
        assertNotEquals(new AuthChallenge(fill(4, 'X'), "m"), challenge);

        byte[] data = {1, 2, 3};
        var db = new DataBytes(5, data);
        data[0] = 9;
        assertEquals(1, db.data()[0]);
        assertEquals(new DataBytes(5, new byte[]{1, 2, 3}), db);

        byte[] ep = fill(32, 'E');
        var ok = new AuthOk(ep, List.of(Map.entry("a", "b")));
        ep[0] = 0;
        assertEquals('E', ok.endpointId()[0]);
        assertEquals(new AuthOk(fill(32, 'E'), List.of(Map.entry("a", "b"))), ok);

        var accepted = new DialAccepted(fill(16, 'C'), fill(16, 'S'), "gw", Mode.BYTE_RELAY);
        assertEquals(new DialAccepted(fill(16, 'C'), fill(16, 'S'), "gw", Mode.BYTE_RELAY), accepted);
        var failed = new DialFailed(fill(16, 'C'), DialFailure.BUSY, true, "d");
        assertEquals(new DialFailed(fill(16, 'C'), DialFailure.BUSY, true, "d"), failed);
        assertNotEquals(new DialFailed(fill(16, 'C'), DialFailure.BUSY, true, null), failed);
        var hello = new Hello("n", "v", 1, 1, fill(32, 'E'), List.of("a"));
        assertEquals(new Hello("n", "v", 1, 1, fill(32, 'E'), List.of("a")), hello);
        var resp = new AuthResponse(fill(3, 'P'));
        assertEquals(new AuthResponse(fill(3, 'P')), resp);
    }

    @Test
    void listFieldsAreImmutableSnapshots() {
        List<String> caps = new ArrayList<>(List.of("a"));
        var hello = new Hello("n", "v", 1, 1, fill(32, 'E'), caps);
        caps.add("b");
        assertEquals(List.of("a"), hello.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> hello.capabilities().add("c"));

        List<Map.Entry<String, String>> policy = new ArrayList<>(List.of(Map.entry("k", "v")));
        var ok = new AuthOk(fill(32, 'E'), policy);
        policy.clear();
        assertEquals(List.of(Map.entry("k", "v")), ok.policy());
    }
}
