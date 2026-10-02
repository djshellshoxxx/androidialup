package io.circuitdrift.androidialup.platform.relay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import io.circuitdrift.androidialup.protocol.AduFrame;
import io.circuitdrift.androidialup.protocol.FrameCodec;
import io.circuitdrift.androidialup.protocol.FrameKind;
import io.circuitdrift.androidialup.protocol.Messages;
import io.circuitdrift.androidialup.protocol.PayloadCodec;
import io.circuitdrift.androidialup.protocol.ProtocolException;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.Test;

public class DeviceCredentialAuthTest {
    private static byte[] range(int from, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) out[i] = (byte) (from + i);
        return out;
    }

    private static AduFrame helloAck(String relayId) {
        return new AduFrame(FrameKind.HELLO_ACK, 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID, 1,
                PayloadCodec.encode(new Messages.HelloAck(1, relayId, FrameCodec.MAX_PAYLOAD, 30, List.of())));
    }

    /** Vector from prototype/python androidialup_relay.auth.compute_proof. */
    @Test
    public void proofMatchesPythonReference() {
        byte[] nonce = new byte[16];
        Arrays.fill(nonce, (byte) 0xA5);
        assertArrayEquals(
                HexFormat.of().parseHex("7e404de0cb5e659f20d42d4409e79f07a87d50d781edf803e0380fae32a62ba9"),
                DeviceCredentialAuth.proof(range(1, 32), nonce, range(100, 32), "relay-test"));
    }

    @Test
    public void proofUsesRelayIdFromHelloAck() {
        byte[] nonce = new byte[16];
        Arrays.fill(nonce, (byte) 0xA5);
        DeviceCredentialAuth auth = new DeviceCredentialAuth(range(1, 32), range(100, 32));
        auth.accept(helloAck("relay-test"));
        byte[] proof = auth.proofFor(null, null, new Messages.AuthChallenge(nonce, DeviceCredentialAuth.METHOD));
        assertArrayEquals(HexFormat.of().parseHex(
                "7e404de0cb5e659f20d42d4409e79f07a87d50d781edf803e0380fae32a62ba9"), proof);
        assertEquals(32, proof.length);
    }

    @Test
    public void rejectsMissingRelayIdUnknownMethodAndShortNonce() {
        DeviceCredentialAuth auth = new DeviceCredentialAuth(range(1, 32), range(100, 32));
        assertThrows(ProtocolException.class,
                () -> auth.proofFor(null, null, new Messages.AuthChallenge(new byte[16], DeviceCredentialAuth.METHOD)));
        auth.accept(helloAck("r"));
        assertThrows(ProtocolException.class,
                () -> auth.proofFor(null, null, new Messages.AuthChallenge(new byte[16], "other-method")));
        assertThrows(ProtocolException.class,
                () -> auth.proofFor(null, null, new Messages.AuthChallenge(new byte[15], DeviceCredentialAuth.METHOD)));
    }

    @Test
    public void toStringNeverContainsSecret() {
        assertFalse(new DeviceCredentialAuth(range(1, 32), range(100, 32)).toString().contains("0102"));
    }
}
