package io.circuitdrift.androidialup.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.protocol.Messages.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayloadCodecGoldenTest {
    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    @Test
    void helloMatchesPythonGoldenVector() {
        byte[] endpoint = new byte[32];
        for (int i = 0; i < endpoint.length; i++) endpoint[i] = (byte) i;
        var message = new Hello("android", "0.1", 1, 1, endpoint, List.of("BYTE_RELAY"));
        assertArrayEquals(hex("0007616e64726f69640003302e3100010001000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0001000a425954455f52454c4159"), PayloadCodec.encode(message));
        assertEquals(message, PayloadCodec.decode(FrameKind.HELLO, PayloadCodec.encode(message)));
    }

    @Test
    void dialRequestMatchesPythonGoldenVector() {
        var message = new DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI,
                List.of("BYTE_RELAY"), 60000L, List.of());
        assertArrayEquals(hex("00086c6f6f706261636b0001010001000a425954455f52454c41590000ea600000"), PayloadCodec.encode(message));
        assertEquals(message, PayloadCodec.decode(FrameKind.DIAL_REQUEST, PayloadCodec.encode(message)));
    }

    @Test
    void dataFlowHeartbeatProgressAndHangupMatchPythonVectors() {
        var data = new DataBytes(0x0102030405060708L, hex("00ff414243"));
        assertArrayEquals(hex("010203040506070800ff414243"), PayloadCodec.encode(data));
        assertEquals(data, PayloadCodec.decode(FrameKind.DATA_BYTES, PayloadCodec.encode(data)));

        var flow = new FlowStatus(262144L, 1024L);
        assertArrayEquals(hex("0004000000000400"), PayloadCodec.encode(flow));
        assertEquals(flow, PayloadCodec.decode(FrameKind.FLOW_STATUS, PayloadCodec.encode(flow)));

        var ping = new Ping(0x0102030405060708L, 0x1112131415161718L);
        assertArrayEquals(hex("01020304050607081112131415161718"), PayloadCodec.encode(ping));
        assertEquals(ping, PayloadCodec.decode(FrameKind.PING, PayloadCodec.encode(ping)));

        var progress = new CallProgress(ProgressPhase.CONNECTED, "ready");
        assertArrayEquals(hex("00060100057265616479"), PayloadCodec.encode(progress));
        assertEquals(progress, PayloadCodec.decode(FrameKind.CALL_PROGRESS, PayloadCodec.encode(progress)));

        var hangup = new HangupRequest("LOCAL_HANGUP");
        assertArrayEquals(hex("000c4c4f43414c5f48414e475550"), PayloadCodec.encode(hangup));
        assertEquals(hangup, PayloadCodec.decode(FrameKind.HANGUP_REQUEST, PayloadCodec.encode(hangup)));
    }

    @Test
    void remainingI1MessagesRoundTrip() {
        byte[] endpoint = new byte[32];
        byte[] call = new byte[16];
        byte[] session = new byte[16];
        java.util.Arrays.fill(call, (byte) 0x43);
        java.util.Arrays.fill(session, (byte) 0x53);

        List<Message> messages = List.of(
                new HelloAck(1, "relay-a", 1024 * 1024L, 10L, List.of("BYTE_RELAY")),
                new HelloReject("unsupported"),
                new AuthBegin(),
                new AuthChallenge(hex("010203"), "device-key"),
                new AuthResponse(hex("aabbcc")),
                new AuthOk(endpoint, List.of(new KeyValue("tier", "beta"))),
                new AuthFail("bad proof"),
                new DialAccepted(call, session, "gw-loop", Mode.BYTE_RELAY),
                new DialFailed(call, DialFailure.BUSY, false, "busy"),
                new CallTerminated("remote hangup", TerminationSource.REMOTE, "REMOTE_HANGUP"),
                new Pong(1234L),
                new HangupAck()
        );
        for (Message message : messages) {
            FrameKind kind = PayloadCodec.kindFor(message);
            assertEquals(message, PayloadCodec.decode(kind, PayloadCodec.encode(message)), message.getClass().getSimpleName());
        }
    }

    @Test
    void rejectsTrailingTruncatedUnknownEnumAndOversizedData() {
        byte[] pong = PayloadCodec.encode(new Pong(5));
        byte[] trailing = java.util.Arrays.copyOf(pong, pong.length + 1);
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.PONG, trailing));
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.HELLO_REJECT, new byte[]{0, 8, 'a'}));

        byte[] dial = PayloadCodec.encode(new DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI, List.of(), 60000, List.of()));
        int targetLength = ((dial[0] & 0xff) << 8) | (dial[1] & 0xff);
        int modeOffset = 2 + targetLength;
        dial[modeOffset] = 0x03;
        dial[modeOffset + 1] = (byte) 0xe7;
        assertThrows(ProtocolException.class, () -> PayloadCodec.decode(FrameKind.DIAL_REQUEST, dial));

        assertThrows(IllegalArgumentException.class, () -> new DataBytes(0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new DataBytes(0, new byte[32769]));
    }
}
