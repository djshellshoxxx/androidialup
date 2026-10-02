package io.circuitdrift.androidialup.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Mirrors prototype/python/tests/test_messages.py. */
class PayloadCodecTest {
    private static byte[] fill(int n, char c) {
        byte[] out = new byte[n];
        Arrays.fill(out, (byte) c);
        return out;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static void roundTrip(FrameKind kind, AduMessage message) {
        assertEquals(kind, PayloadCodec.kindFor(message));
        byte[] encoded = PayloadCodec.encode(message);
        AduMessage decoded = PayloadCodec.decode(kind, encoded);
        assertEquals(message, decoded);
        assertArrayEquals(encoded, PayloadCodec.encode(decoded));
    }

    @Test
    void helloAndAckRoundTrip() {
        roundTrip(FrameKind.HELLO, new Hello("AndroidDialup", "0.1", 1, 1, fill(32, 'E'),
                List.of("BYTE_RELAY", "PCM_VBD_EXPERIMENTAL")));
        roundTrip(FrameKind.HELLO_ACK, new HelloAck(1, "relay-01", 1024 * 1024, 10, List.of("BYTE_RELAY")));
        roundTrip(FrameKind.HELLO_REJECT, new HelloReject("unsupported"));
    }

    @Test
    void authRoundTrip() {
        roundTrip(FrameKind.AUTH_BEGIN, new AuthBegin());
        roundTrip(FrameKind.AUTH_CHALLENGE, new AuthChallenge(fill(32, 'N'), "device-credential"));
        roundTrip(FrameKind.AUTH_RESPONSE, new AuthResponse(fill(64, 'P')));
        roundTrip(FrameKind.AUTH_OK, new AuthOk(fill(32, 'E'), List.of(Map.entry("dial", "allowed"))));
        roundTrip(FrameKind.AUTH_FAIL, new AuthFail("bad proof"));
    }

    @Test
    void dialRoundTrip() {
        roundTrip(FrameKind.DIAL_REQUEST, new DialRequest("loopback", Mode.BYTE_RELAY, ProtocolTransport.WIFI,
                List.of("BYTE_RELAY"), 60000, List.of(Map.entry("test", "1"))));
        roundTrip(FrameKind.DIAL_ACCEPTED, new DialAccepted(fill(16, 'C'), fill(16, 'S'), "gw-loopback", Mode.BYTE_RELAY));
        roundTrip(FrameKind.DIAL_FAILED, new DialFailed(fill(16, 'C'), DialFailure.BUSY, false, "busy"));
        roundTrip(FrameKind.DIAL_FAILED, new DialFailed(fill(16, 'C'), DialFailure.TIMEOUT, true, null));
    }

    @Test
    void progressAndTerminationRoundTrip() {
        roundTrip(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.CONNECTED, "loopback"));
        roundTrip(FrameKind.CALL_PROGRESS, new CallProgress(ProgressPhase.RINGBACK, null));
        roundTrip(FrameKind.CALL_TERMINATED, new CallTerminated("LOCAL_HANGUP", TerminationSource.LOCAL, "OK"));
        roundTrip(FrameKind.CALL_TERMINATED, new CallTerminated("REMOTE_HANGUP", TerminationSource.REMOTE, null));
    }

    @Test
    void dataFlowHeartbeatAndHangupRoundTrip() {
        roundTrip(FrameKind.DATA_BYTES, new DataBytes(123, new byte[]{'a', 'b', 'c', 0, (byte) 0xff}));
        roundTrip(FrameKind.DATA_BYTES, new DataBytes(-1L, new byte[32768]));
        roundTrip(FrameKind.FLOW_STATUS, new FlowStatus(262144, 7));
        roundTrip(FrameKind.PING, new Ping(99, 123456));
        roundTrip(FrameKind.PING, new Ping(Long.MIN_VALUE, -1L));
        roundTrip(FrameKind.PONG, new Pong(99));
        roundTrip(FrameKind.HANGUP_REQUEST, new HangupRequest("user"));
        roundTrip(FrameKind.HANGUP_ACK, new HangupAck());
    }

    @Test
    void nonAsciiTextRoundTrips() {
        roundTrip(FrameKind.HELLO_REJECT, new HelloReject("é€😀 日本"));
        byte[] encoded = PayloadCodec.encode(new HelloReject("é"));
        assertArrayEquals(hex("0002c3a9"), encoded);
    }

    @Test
    void encodingIsBigEndianAndMatchesPythonLayout() {
        assertArrayEquals(hex("0000000000000063"), PayloadCodec.encode(new Pong(99)));
        assertArrayEquals(hex("ffffffffffffffff"), PayloadCodec.encode(new Pong(-1L)));
        assertArrayEquals(hex("0004000000000007"), PayloadCodec.encode(new FlowStatus(262144, 7)));
        assertArrayEquals(hex("00000000000000016162"), PayloadCodec.encode(new DataBytes(1, new byte[]{'a', 'b'})));
        assertArrayEquals(hex("00060100"), PayloadCodec.encode(new CallProgress(ProgressPhase.CONNECTED, "")));
        assertArrayEquals(hex("000600"), PayloadCodec.encode(new CallProgress(ProgressPhase.CONNECTED, null)));
        assertArrayEquals(new byte[0], PayloadCodec.encode(new AuthBegin()));
        assertArrayEquals(new byte[0], PayloadCodec.encode(new HangupAck()));
        // DIAL_REQUEST: text target, u16 mode, u8 transport, u16 count, u32 timeout, u16 map count
        assertArrayEquals(hex("00016100010200000000ea600000"),
                PayloadCodec.encode(new DialRequest("a", Mode.BYTE_RELAY, ProtocolTransport.CELLULAR, List.of(), 60000, List.of())));
        // CALL_TERMINATED: text reason, u8 source, optional text
        assertArrayEquals(hex("000172040100016b"),
                PayloadCodec.encode(new CallTerminated("r", TerminationSource.REMOTE, "k")));
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] payload = Arrays.copyOf(PayloadCodec.encode(new Pong(7)), 9);
        payload[8] = 'x';
        var ex = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.PONG, payload));
        assertTrue(ex.getMessage().contains("trailing"));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.AUTH_BEGIN, new byte[]{0}));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HANGUP_ACK, new byte[]{0}));
    }

    @Test
    void rejectsTruncatedPayloads() {
        var ex = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, hex("00056162")));
        assertTrue(ex.getMessage().contains("truncated"));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.PONG, hex("00000000000000")));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, hex("00")));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.AUTH_OK, fill(31, 'E')));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DATA_BYTES, hex("00000000")));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.CALL_PROGRESS, hex("000601")));
        // string list count says 2 but only one present
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_ACK,
                hex("0001" + "000172" + "00000001" + "00000000" + "0002" + "000161")));
    }

    @Test
    void rejectsInvalidUtf8() {
        var ex = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, hex("0001ff")));
        assertTrue(ex.getMessage().contains("UTF-8"));
        // overlong encoding of '/' and an unpaired surrogate are both invalid UTF-8
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, hex("0002c0af")));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, hex("0003eda080")));
        // truncated multi-byte sequence
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, hex("0001c3")));
    }

    @Test
    void rejectsLengthsOverLimitBeforeReading() {
        // string length 4097 with no body: must fail on the limit, not merely on truncation
        var ex = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, hex("1001")));
        assertTrue(ex.getMessage().contains("string length"));
        // string-list count 129
        var ex2 = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_ACK,
                hex("0001" + "000172" + "00000001" + "00000000" + "0081")));
        assertTrue(ex2.getMessage().contains("string-list"));
        // map count 129
        var ex3 = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.AUTH_OK,
                hex("45".repeat(32) + "0081")));
        assertTrue(ex3.getMessage().contains("map"));
    }

    @Test
    void rejectsInvalidOptionalMarkerAndRetryableFlag() {
        var ex = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.CALL_PROGRESS, hex("000602")));
        assertTrue(ex.getMessage().contains("optional"));
        byte[] failed = PayloadCodec.encode(new DialFailed(fill(16, 'C'), DialFailure.BUSY, false, null));
        failed[18] = 2;
        var ex2 = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DIAL_FAILED, failed));
        assertTrue(ex2.getMessage().contains("retryable"));
    }

    @Test
    void rejectsUnknownEnumValues() {
        var ex = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.CALL_PROGRESS, hex("006300")));
        assertTrue(ex.getMessage().contains("progress phase"));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.CALL_TERMINATED, hex("0001720900")));
        byte[] accepted = PayloadCodec.encode(new DialAccepted(fill(16, 'C'), fill(16, 'S'), "g", Mode.BYTE_RELAY));
        accepted[accepted.length - 1] = 3;
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DIAL_ACCEPTED, accepted));
        byte[] failed = PayloadCodec.encode(new DialFailed(fill(16, 'C'), DialFailure.BUSY, false, null));
        failed[17] = 0;
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DIAL_FAILED, failed));
        byte[] request = PayloadCodec.encode(new DialRequest("a", Mode.BYTE_RELAY, ProtocolTransport.WIFI, List.of(), 0, List.of()));
        request[5] = 9; // transport
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DIAL_REQUEST, request));
    }

    @Test
    void rejectsEmptyAndOversizedDataBytes() {
        var ex = assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DATA_BYTES, new byte[8]));
        assertTrue(ex.getMessage().contains("1..32768"));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DATA_BYTES, new byte[8 + 32769]));
    }

    @Test
    void rejectsKindsWithoutPayloadCodec() {
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.INCOMING_CALL, new byte[0]));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.ANSWER_FAILED, new byte[0]));
    }

    @Test
    void decodeDoesNotRetainCallerBuffer() {
        byte[] payload = PayloadCodec.encode(new AuthResponse(new byte[]{1, 2, 3}));
        var decoded = (AuthResponse) PayloadCodec.decode(FrameKind.AUTH_RESPONSE, payload);
        payload[2] = 9;
        assertEquals(1, decoded.proof()[0]);
    }

    @Test
    void encodedTextLengthIsUtf8ByteLength() {
        String s = "😀"; // 4 UTF-8 bytes, 2 UTF-16 units
        byte[] encoded = PayloadCodec.encode(new HangupRequest(s));
        assertEquals(2 + 4, encoded.length);
        assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(encoded, 2, 6));
    }
}
