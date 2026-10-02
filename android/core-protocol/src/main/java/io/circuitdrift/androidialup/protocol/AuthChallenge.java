package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;

/** AUTH_CHALLENGE payload (S1 wire protocol section 4). */
public record AuthChallenge(byte[] nonce, String method) implements AduMessage {
    public AuthChallenge {
        MessageLimits.blob(nonce, "nonce");
        MessageLimits.string(method, "method");
        nonce = nonce.clone();
    }

    @Override public byte[] nonce() { return nonce.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof AuthChallenge that && Arrays.equals(nonce, that.nonce) && method.equals(that.method);
    }

    @Override public int hashCode() { return 31 * method.hashCode() + Arrays.hashCode(nonce); }

    @Override public String toString() {
        return "AuthChallenge[nonce=" + Hex.of(nonce) + ", method=" + method + "]";
    }
}
