package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** HELLO payload (S1 wire protocol section 3). */
public record Hello(String clientName, String clientVersion, int protocolMin, int protocolMax,
                    byte[] endpointId, List<String> capabilities) implements AduMessage {
    public Hello {
        MessageLimits.u16(protocolMin, "protocol_min");
        MessageLimits.u16(protocolMax, "protocol_max");
        MessageLimits.endpointId(endpointId);
        MessageLimits.string(clientName, "client_name");
        MessageLimits.string(clientVersion, "client_version");
        capabilities = MessageLimits.stringList(capabilities, "capabilities");
        endpointId = endpointId.clone();
    }

    @Override public byte[] endpointId() { return endpointId.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof Hello that
                && protocolMin == that.protocolMin && protocolMax == that.protocolMax
                && clientName.equals(that.clientName) && clientVersion.equals(that.clientVersion)
                && Arrays.equals(endpointId, that.endpointId) && capabilities.equals(that.capabilities);
    }

    @Override public int hashCode() {
        return 31 * Objects.hash(clientName, clientVersion, protocolMin, protocolMax, capabilities)
                + Arrays.hashCode(endpointId);
    }

    @Override public String toString() {
        return "Hello[clientName=" + clientName + ", clientVersion=" + clientVersion + ", protocolMin=" + protocolMin
                + ", protocolMax=" + protocolMax + ", endpointId=" + Hex.of(endpointId) + ", capabilities=" + capabilities + "]";
    }
}
