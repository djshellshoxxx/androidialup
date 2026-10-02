from __future__ import annotations

from dataclasses import dataclass, field

from .frame import ProtocolError
from .messages import DataBytes, FlowStatus, MAX_DATA_BYTES

DEFAULT_RECEIVE_WINDOW = 256 * 1024
MAX_LOCAL_PENDING = 256 * 1024


class FlowControlBlocked(RuntimeError):
    """Local bounded pending storage cannot accept more user bytes."""


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
    _pending: bytearray = field(default_factory=bytearray, repr=False)

    def update_flow(self, status: FlowStatus) -> None:
        self.peer_receive_window = status.receive_window_bytes
        self.peer_queued_bytes = status.queued_bytes

    @property
    def available_window(self) -> int:
        return self.peer_receive_window

    @property
    def pending_bytes(self) -> int:
        return len(self._pending)

    def build(self, data: bytes) -> list[DataBytes]:
        raw = bytes(data)
        if raw:
            if len(self._pending) + len(raw) > MAX_LOCAL_PENDING:
                raise FlowControlBlocked("BYTE_RELAY local pending limit exceeded")
            self._pending.extend(raw)
        return self.drain()

    def drain(self) -> list[DataBytes]:
        messages: list[DataBytes] = []
        while self._pending and self.peer_receive_window > 0:
            take = min(MAX_DATA_BYTES, self.peer_receive_window, len(self._pending))
            chunk = bytes(self._pending[:take])
            del self._pending[:take]
            messages.append(DataBytes(self.next_stream_seq, chunk))
            self.next_stream_seq += len(chunk)
            self.sent_bytes += len(chunk)
            self.peer_receive_window -= len(chunk)
        return messages
