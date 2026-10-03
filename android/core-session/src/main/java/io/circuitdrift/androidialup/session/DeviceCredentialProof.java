package io.circuitdrift.androidialup.session;

import io.circuitdrift.androidialup.protocol.Messages.AuthChallenge;
import io.circuitdrift.androidialup.protocol.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client side of the Beta relay authentication method {@value #METHOD}
 * (S1_WIRE_PROTOCOL section 4.1; Java port of {@code androidialup_relay.auth.compute_proof}).
 *
 * <pre>
 * proof = HMAC-SHA256(key = device_secret, message = transcript)
 * transcript = "ADUP-AUTH-v1"
 *           || u16be(len(relay_id_utf8)) || relay_id_utf8
 *           || u16be(len(nonce))         || nonce          (16..65535 bytes)
 *           || endpoint_id                                  (32 raw bytes)
 * </pre>
 *
 * <p>The secret is copied on construction and never appears in {@link #toString()}; proofs are
 * returned to the session machine only and are not logged. Android hosts should source the
 * secret from Keystore-backed storage (S1_SPEC_FREEZE section 11).
 */
public final class DeviceCredentialProof implements RelaySessionMachine.AuthProofProvider {
    public static final String METHOD = "device-credential-hmac-sha256-v1";
    public static final int MIN_NONCE_LENGTH = 16;
    public static final int MAX_NONCE_LENGTH = 0xFFFF;
    public static final int ENDPOINT_ID_LENGTH = 32;
    public static final int PROOF_LENGTH = 32;
    private static final byte[] PREFIX = "ADUP-AUTH-v1".getBytes(StandardCharsets.US_ASCII);
    private static final String HMAC = "HmacSHA256";

    private final byte[] deviceSecret;

    public DeviceCredentialProof(byte[] deviceSecret) {
        this.deviceSecret = validateSecret(deviceSecret).clone();
    }

    /**
     * Rejects anything but {@value #METHOD} and nonces shorter than {@value #MIN_NONCE_LENGTH}
     * bytes with {@link ProtocolException}, so the machine never sends {@code AUTH_RESPONSE}.
     */
    @Override
    public byte[] proofFor(String relayId, byte[] endpointId, AuthChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge");
        if (!METHOD.equals(challenge.method())) {
            throw new ProtocolException("unsupported relay auth method " + challenge.method());
        }
        byte[] nonce = challenge.nonce();
        if (nonce.length < MIN_NONCE_LENGTH) {
            throw new ProtocolException("relay auth nonce is too short");
        }
        return compute(deviceSecret, nonce, endpointId, relayId);
    }

    /** HMAC-SHA256 proof; throws {@link IllegalArgumentException} for out-of-range inputs. */
    public static byte[] compute(byte[] deviceSecret, byte[] nonce, byte[] endpointId, String relayId) {
        byte[] transcript = transcript(nonce, endpointId, relayId);
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(validateSecret(deviceSecret), HMAC));
            return mac.doFinal(transcript);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** The exact bytes that are MACed. */
    public static byte[] transcript(byte[] nonce, byte[] endpointId, String relayId) {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(relayId, "relayId");
        if (nonce.length < MIN_NONCE_LENGTH || nonce.length > MAX_NONCE_LENGTH) {
            throw new IllegalArgumentException("nonce must be 16..65535 bytes");
        }
        if (endpointId.length != ENDPOINT_ID_LENGTH) {
            throw new IllegalArgumentException("endpointId must be exactly 32 bytes");
        }
        byte[] relay = relayId.getBytes(StandardCharsets.UTF_8);
        if (relay.length > 0xFFFF) {
            throw new IllegalArgumentException("relayId exceeds 65535 UTF-8 bytes");
        }
        byte[] out = new byte[PREFIX.length + 2 + relay.length + 2 + nonce.length + endpointId.length];
        int at = 0;
        System.arraycopy(PREFIX, 0, out, at, PREFIX.length); at += PREFIX.length;
        out[at++] = (byte) (relay.length >>> 8); out[at++] = (byte) relay.length;
        System.arraycopy(relay, 0, out, at, relay.length); at += relay.length;
        out[at++] = (byte) (nonce.length >>> 8); out[at++] = (byte) nonce.length;
        System.arraycopy(nonce, 0, out, at, nonce.length); at += nonce.length;
        System.arraycopy(endpointId, 0, out, at, endpointId.length);
        return out;
    }

    private static byte[] validateSecret(byte[] secret) {
        Objects.requireNonNull(secret, "deviceSecret");
        if (secret.length == 0) throw new IllegalArgumentException("deviceSecret must not be empty");
        return secret;
    }

    @Override
    public String toString() {
        return "DeviceCredentialProof(method=" + METHOD + ")";
    }
}
