package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.circuitdrift.androidialup.protocol.*;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceCredentialProofTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] SECRET = HEX.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] NONCE = HEX.parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
    private static final byte[] ENDPOINT = HEX.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final String RELAY = "relay-prototype";
    private static final String KNOWN_PROOF = "2d68e358e8f25193c1470e207683c93ac0521796480d323fd0682abac59d71a3";

    @Test
    void knownAnswerVectorMatchesPythonReference() {
        assertEquals(KNOWN_PROOF, HEX.formatHex(DeviceCredentialProof.compute(SECRET, NONCE, ENDPOINT, RELAY)));
    }

    @Test
    void transcriptLayoutIsExactlyAsSpecified() {
        String expected = "414455502d415554482d7631"
                + "000f" + "72656c61792d70726f746f74797065"
                + "0020" + HEX.formatHex(NONCE)
                + HEX.formatHex(ENDPOINT);
        assertEquals(expected, HEX.formatHex(DeviceCredentialProof.transcript(NONCE, ENDPOINT, RELAY)));
    }

    @Test
    void relayIdLengthIsCountedInUtf8BytesNotChars() {
        // Cross-checked with prototype/python compute_proof(bytes(range(32)), bytes(range(16)), bytes(range(32)), "relé-ü").
        byte[] nonce16 = new byte[16];
        for (int i = 0; i < 16; i++) nonce16[i] = (byte) i;
        assertEquals("61d27468ce672db2ecb8d0f565402df6cd59c03c77ade2b309b6ca895de03b4b",
                HEX.formatHex(DeviceCredentialProof.compute(SECRET, nonce16, ENDPOINT, "relé-ü")));
        byte[] transcript = DeviceCredentialProof.transcript(nonce16, ENDPOINT, "relé-ü");
        assertEquals(0, transcript[12]);
        assertEquals(8, transcript[13]); // 6 chars, 8 UTF-8 bytes
    }

    @Test
    void proofIsBoundToRelayIdNonceEndpointAndSecret() {
        String base = HEX.formatHex(DeviceCredentialProof.compute(SECRET, NONCE, ENDPOINT, RELAY));
        assertNotEquals(base, HEX.formatHex(DeviceCredentialProof.compute(SECRET, NONCE, ENDPOINT, "relay-other")));
        byte[] otherNonce = NONCE.clone(); otherNonce[0] ^= 1;
        assertNotEquals(base, HEX.formatHex(DeviceCredentialProof.compute(SECRET, otherNonce, ENDPOINT, RELAY)));
        byte[] otherEndpoint = ENDPOINT.clone(); otherEndpoint[31] ^= 1;
        assertNotEquals(base, HEX.formatHex(DeviceCredentialProof.compute(SECRET, NONCE, otherEndpoint, RELAY)));
        byte[] otherSecret = SECRET.clone(); otherSecret[5] ^= 1;
        assertNotEquals(base, HEX.formatHex(DeviceCredentialProof.compute(otherSecret, NONCE, ENDPOINT, RELAY)));
    }

    @Test
    void computeValidatesInputs() {
        assertThrows(IllegalArgumentException.class, () -> DeviceCredentialProof.compute(new byte[0], NONCE, ENDPOINT, RELAY));
        assertThrows(IllegalArgumentException.class, () -> DeviceCredentialProof.compute(SECRET, new byte[15], ENDPOINT, RELAY));
        assertThrows(IllegalArgumentException.class, () -> DeviceCredentialProof.compute(SECRET, NONCE, new byte[31], RELAY));
        assertThrows(IllegalArgumentException.class, () -> DeviceCredentialProof.compute(SECRET, new byte[65536], ENDPOINT, RELAY));
        assertEquals(32, DeviceCredentialProof.compute(SECRET, new byte[16], ENDPOINT, RELAY).length);
        assertThrows(IllegalArgumentException.class, () -> new DeviceCredentialProof(new byte[0]));
    }

    @Test
    void providerAnswersTheBetaMethod() {
        var provider = new DeviceCredentialProof(SECRET);
        byte[] proof = provider.proofFor(RELAY, ENDPOINT, new AuthChallenge(NONCE, DeviceCredentialProof.METHOD));
        assertEquals(KNOWN_PROOF, HEX.formatHex(proof));
    }

    @Test
    void providerRejectsUnknownMethodAsProtocolViolation() {
        var provider = new DeviceCredentialProof(SECRET);
        assertThrows(ProtocolException.class, () -> provider.proofFor(RELAY, ENDPOINT, new AuthChallenge(NONCE, "test-proof")));
        assertThrows(ProtocolException.class, () -> provider.proofFor(RELAY, ENDPOINT,
                new AuthChallenge(NONCE, DeviceCredentialProof.METHOD.toUpperCase())));
    }

    @Test
    void providerRejectsShortNonceAsProtocolViolation() {
        var provider = new DeviceCredentialProof(SECRET);
        assertThrows(ProtocolException.class, () -> provider.proofFor(RELAY, ENDPOINT,
                new AuthChallenge(new byte[15], DeviceCredentialProof.METHOD)));
        assertEquals(32, provider.proofFor(RELAY, ENDPOINT, new AuthChallenge(new byte[16], DeviceCredentialProof.METHOD)).length);
    }

    @Test
    void providerDoesNotRetainCallerSecretArrayOrLeakItInToString() {
        byte[] secret = SECRET.clone();
        var provider = new DeviceCredentialProof(secret);
        java.util.Arrays.fill(secret, (byte) 0x77);
        assertEquals(KNOWN_PROOF, HEX.formatHex(provider.proofFor(RELAY, ENDPOINT, new AuthChallenge(NONCE, DeviceCredentialProof.METHOD))));
        String text = provider.toString();
        assertFalse(text.contains(HEX.formatHex(SECRET)));
        assertFalse(text.contains("000102"));
        assertTrue(text.contains(DeviceCredentialProof.METHOD));
    }

    @Test
    void machineSendsHmacProofBoundToRelayIdFromHelloAck() {
        byte[] call = new byte[16]; call[0] = 1;
        var machine = new RelaySessionMachine(ENDPOINT, new DeviceCredentialProof(SECRET), () -> call.clone());
        AduFrame hello = only(machine.onTlsConnected(0));
        AduFrame begin = only(machine.onFrame(handshake(FrameKind.HELLO_ACK,
                new HelloAck(1, RELAY, FrameCodec.MAX_PAYLOAD, 10, List.of("BYTE_RELAY")), hello.requestId()), 0));
        AduFrame response = only(machine.onFrame(handshake(FrameKind.AUTH_CHALLENGE,
                new AuthChallenge(NONCE, DeviceCredentialProof.METHOD), begin.requestId()), 0));
        assertEquals(FrameKind.AUTH_RESPONSE, response.kind());
        assertEquals(KNOWN_PROOF, HEX.formatHex(((AuthResponse) PayloadCodec.decode(response.kind(), response.payload())).proof()));
        assertEquals(RELAY, machine.relayId());
    }

    @Test
    void machineSendsNoAuthResponseForUnknownMethod() {
        var machine = new RelaySessionMachine(ENDPOINT, new DeviceCredentialProof(SECRET), () -> new byte[16]);
        AduFrame hello = only(machine.onTlsConnected(0));
        AduFrame begin = only(machine.onFrame(handshake(FrameKind.HELLO_ACK,
                new HelloAck(1, RELAY, FrameCodec.MAX_PAYLOAD, 10, List.of()), hello.requestId()), 0));
        assertThrows(ProtocolException.class, () -> machine.onFrame(handshake(FrameKind.AUTH_CHALLENGE,
                new AuthChallenge(NONCE, "test-proof"), begin.requestId()), 0));
        assertEquals(RelaySessionMachine.State.AUTH_BEGIN_SENT, machine.state());
    }

    private static AduFrame handshake(FrameKind kind, Message message, long requestId) {
        return new AduFrame(kind, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, requestId, PayloadCodec.encode(message));
    }

    private static AduFrame only(List<RelaySessionMachine.Action> actions) {
        assertEquals(1, actions.size());
        return assertInstanceOf(RelaySessionMachine.Outbound.class, actions.get(0)).frame();
    }
}
