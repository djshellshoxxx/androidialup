from __future__ import annotations

from dataclasses import dataclass

from .frame import ProtocolError
from .messages import DataBytes, FlowStatus, MAX_DATA_BYTES

DEFAULT_RECEIVE_WINDOW = 256 * 1024
MAX_LOCAL_PENDING = 256 * 1024


class FlowControlBlocked(RuntimeError):
    """The peer cannot currently accept the requested bytes."""


@dataclass(slots=True)
class ByteRelayReceiver:
    next_expected_stream_seq: int = 0
    received_bytes: int = 0

    def accept(self, message: DataBytes) -> bytes:
        if message.stream_seq != self.next_expected_stream_seq:
            relation = "gap" if message.stream_seq > self.next_expected_stream_seq else "duplicate/overlap"
            raise ProtocolError(
                f"BYTE_RELAY sequence {relation}: expected {self.next_expected_stream_seq}, got {message.stream_seq}"
            )
        data = bytes(message.data)
        self.next_expected_stream_seq += len(data)
        self.received_bytes += len(data)
        return data


@dataclass(slots=True)
class ByteRelaySender:
    next_stream_seq: int = 0
    peer_receive_window: int = DEFAULT_RECEIVE_WINDOW
    peer_queued_bytes: int = 0
    sent_bytes: int = 0

    def update_flow(self, status: FlowStatus) -> None:
        self.peer_receive_window = status.receive_window_bytes
        self.peer_queued_bytes = status.queued_bytes

    @property
    def available_window(self) -> int:
        return self.peer_receive_window

    def build(self, data: bytes) -> list[DataBytes]:
        raw = bytes(data)
        if not raw:
            return []
        if len(raw) > MAX_LOCAL_PENDING:
            raise ValueError("BYTE_RELAY submission exceeds local pending limit")
        if len(raw) > self.peer_receive_window:
            raise FlowControlBlocked(
                f"peer receive window {self.peer_receive_window} cannot accept {len(raw)} bytes"
            )

        messages: list[DataBytes] = []
        offset = 0
        while offset < len(raw):
            chunk = raw[offset : offset + MAX_DATA_BYTES]
            messages.append(DataBytes(self.next_stream_seq, chunk))
            self.next_stream_seq += len(chunk)
            self.sent_bytes += len(chunk)
            offset += len(chunk)

        # Account pessimistically until the peer publishes a newer FLOW_STATUS.
        self.peer_receive_window -= len(raw)
        return messages
