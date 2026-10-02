package io.circuitdrift.androidialup.protocol;

/** FLOW_STATUS payload (S1 wire protocol section 8). Both fields are u32. */
public record FlowStatus(long receiveWindowBytes, long queuedBytes) implements AduMessage {
    public FlowStatus {
        MessageLimits.u32(receiveWindowBytes, "receive_window_bytes");
        MessageLimits.u32(queuedBytes, "queued_bytes");
    }
}
