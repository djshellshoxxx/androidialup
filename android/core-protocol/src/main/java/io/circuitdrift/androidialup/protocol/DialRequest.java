package io.circuitdrift.androidialup.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** DIAL_REQUEST payload (S1 wire protocol section 5). {@code dialTimeoutMs} is u32. */
public record DialRequest(String target, Mode requestedMode, ProtocolTransport networkTransport,
                          List<String> clientCapabilities, long dialTimeoutMs,
                          List<Map.Entry<String, String>> callerMetadata) implements AduMessage {
    public DialRequest {
        int targetBytes = MessageLimits.utf8Length(Objects.requireNonNull(target, "target"));
        if (targetBytes == 0 || targetBytes > MessageLimits.MAX_TARGET_UTF8) {
            throw new IllegalArgumentException("target must be 1..256 UTF-8 bytes");
        }
        Objects.requireNonNull(requestedMode, "requestedMode");
        Objects.requireNonNull(networkTransport, "networkTransport");
        clientCapabilities = MessageLimits.stringList(clientCapabilities, "client_capabilities");
        MessageLimits.u32(dialTimeoutMs, "dial_timeout_ms");
        callerMetadata = MessageLimits.stringMap(callerMetadata, "caller_metadata");
    }
}
