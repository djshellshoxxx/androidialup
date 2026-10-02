package io.circuitdrift.androidialup.protocol;

import java.util.List;

/** HELLO_ACK payload (S1 wire protocol section 3). {@code heartbeatSeconds} is u32. */
public record HelloAck(int selectedVersion, String relayId, int maxFramePayload, long heartbeatSeconds,
                       List<String> capabilities) implements AduMessage {
    public HelloAck {
        MessageLimits.u16(selectedVersion, "selected_version");
        MessageLimits.string(relayId, "relay_id");
        if (maxFramePayload < 1 || maxFramePayload > FrameCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException("max_frame_payload out of range");
        }
        MessageLimits.u32(heartbeatSeconds, "heartbeat_seconds");
        capabilities = MessageLimits.stringList(capabilities, "capabilities");
    }
}
