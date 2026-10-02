package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * AUTH_OK payload (S1 wire protocol section 4). {@code policy} is an ordered list of key/value
 * pairs, matching the Python tuple-of-pairs representation (order is preserved on the wire and
 * duplicate keys are not rejected).
 */
public record AuthOk(byte[] endpointId, List<Map.Entry<String, String>> policy) implements AduMessage {
    public AuthOk {
        MessageLimits.endpointId(endpointId);
        policy = MessageLimits.stringMap(policy, "policy");
        endpointId = endpointId.clone();
    }

    @Override public byte[] endpointId() { return endpointId.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof AuthOk that && Arrays.equals(endpointId, that.endpointId) && policy.equals(that.policy);
    }

    @Override public int hashCode() { return 31 * policy.hashCode() + Arrays.hashCode(endpointId); }

    @Override public String toString() {
        return "AuthOk[endpointId=" + Hex.of(endpointId) + ", policy=" + policy + "]";
    }
}
