package io.circuitdrift.androidialup.platform.relay;

import io.circuitdrift.androidialup.protocol.AduFrame;
import io.circuitdrift.androidialup.protocol.FrameKind;
import io.circuitdrift.androidialup.protocol.Messages;
import io.circuitdrift.androidialup.protocol.PayloadCodec;
import io.circuitdrift.androidialup.protocol.ProtocolException;
import io.circuitdrift.androidialup.session.RelaySessionMachine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client side of the Beta relay auth method {@code device-credential-hmac-sha256-v1}
 * ({@code prototype/python/androidialup_relay/auth.py}, S1_WIRE_PROTOCOL section 4):
 *
 * <pre>
 * proof = HMAC-SHA256(device_secret,
 *     "ADUP-AUTH-v1" || u16be(len relay_id) || relay_id_utf8 || u16be(len nonce) || nonce || endpoint_id)
 * </pre>
 *
 * <p>The proof binds the {@code relay_id} from HELLO_ACK, which
 * {@link RelaySessionMachine.AuthProofProvider} does not receive. This class therefore also
 * observes inbound frames (wire it as the transport's inbound observer); both callbacks run on
 * the transport owner thread. The secret is never exposed through {@code toString()}.
 */
public final class DeviceCredentialAuth implements RelaySessionMachine.AuthProofProvider, Consumer<AduFrame> {
    public static final String METHOD = "device-credential-hmac-sha256-v1";
    private static final byte[] PREFIX = "ADUP-AUTH-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MIN_NONCE = 16;
    private static final int MAX_NONCE = 0xffff;

    private final byte[] secret;
    private final byte[] endpointId;
    private volatile String relayId;

    public DeviceCredentialAuth(byte[] deviceSecret, byte[] endpointId) {
        Objects.requireNonNull(deviceSecret, "deviceSecret");
        Objects.requireNonNull(endpointId, "endpointId");
        if (deviceSecret.length == 0) throw new IllegalArgumentException("device secret must not be empty");
        if (endpointId.length != 32) throw new IllegalArgumentException("endpointId must be 32 bytes");
        this.secret = deviceSecret.clone();
        this.endpointId = endpointId.clone();
    }

    /** Inbound frame observer: captures {@code relay_id} from HELLO_ACK. */
    @Override
    public void accept(AduFrame frame) {
        if (frame.kind() != FrameKind.HELLO_ACK) return;
        try {
            relayId = ((Messages.HelloAck) PayloadCodec.decode(frame.kind(), frame.payload())).relayId();
        } catch (RuntimeException malformed) {
            relayId = null; // the session machine rejects the frame itself
        }
    }

    @Override
    public byte[] proofFor(Messages.AuthChallenge challenge) {
        if (!METHOD.equals(challenge.method())) {
            throw new ProtocolException("unsupported relay auth method");
        }
        String relay = relayId;
        if (relay == null) throw new ProtocolException("AUTH_CHALLENGE before HELLO_ACK relay_id");
        return proof(secret, challenge.nonce(), endpointId, relay);
    }

    /** {@code HMAC-SHA256(secret, transcript(nonce, endpointId, relayId))}, 32 bytes. */
    public static byte[] proof(byte[] secret, byte[] nonce, byte[] endpointId, String relayId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(transcript(nonce, endpointId, relayId));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    /** The exact MACed bytes; see the class comment. */
    public static byte[] transcript(byte[] nonce, byte[] endpointId, String relayId) {
        if (nonce.length < MIN_NONCE || nonce.length > MAX_NONCE) {
            throw new ProtocolException("relay auth nonce must be 16..65535 bytes");
        }
        if (endpointId.length != 32) throw new IllegalArgumentException("endpointId must be 32 bytes");
        byte[] relay = relayId.getBytes(StandardCharsets.UTF_8);
        if (relay.length > 0xffff) throw new ProtocolException("relay_id too long");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PREFIX, 0, PREFIX.length);
        out.write(relay.length >>> 8);
        out.write(relay.length & 0xff);
        out.write(relay, 0, relay.length);
        out.write(nonce.length >>> 8);
        out.write(nonce.length & 0xff);
        out.write(nonce, 0, nonce.length);
        out.write(endpointId, 0, endpointId.length);
        return out.toByteArray();
    }

    @Override
    public String toString() {
        return "DeviceCredentialAuth[method=" + METHOD + "]";
    }
}
