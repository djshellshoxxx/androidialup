package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;

/** AUTH_RESPONSE payload (S1 wire protocol section 4). */
public record AuthResponse(byte[] proof) implements AduMessage {
    public AuthResponse {
        MessageLimits.blob(proof, "proof");
        proof = proof.clone();
    }

    @Override public byte[] proof() { return proof.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof AuthResponse that && Arrays.equals(proof, that.proof);
    }

    @Override public int hashCode() { return Arrays.hashCode(proof); }

    @Override public String toString() { return "AuthResponse[proof=" + Hex.of(proof) + "]"; }
}
