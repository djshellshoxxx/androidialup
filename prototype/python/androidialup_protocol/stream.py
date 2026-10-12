from __future__ import annotations

from .frame import FIXED_HEADER_LEN, MAX_PAYLOAD, Frame, ProtocolError, decode_frame


class FrameStreamDecoder:
    def __init__(self, *, max_payload: int = MAX_PAYLOAD, max_buffer: int | None = None) -> None:
        if max_payload < 0:
            raise ValueError("max_payload must be nonnegative")
        self._max_payload = min(max_payload, MAX_PAYLOAD)
        self._max_buffer = (FIXED_HEADER_LEN + self._max_payload) * 2 if max_buffer is None else max_buffer
        if self._max_buffer < FIXED_HEADER_LEN:
            raise ValueError("max_buffer must fit a fixed header")
        self._buffer = bytearray()

    @property
    def buffered_bytes(self) -> int:
        return len(self._buffer)

    def feed(self, data: bytes) -> list[Frame]:
        frames: list[Frame] = []
        offset = 0

        # A TCP read can hold many complete frames. Bound only the unconsumed
        # bytes; an aggregate read need not fit in the decoder buffer.
        while offset < len(data):
            available = self._max_buffer - len(self._buffer)
            if available <= 0:
                raise ProtocolError("stream buffer limit exceeded")
            chunk = data[offset:offset + available]
            self._buffer.extend(chunk)
            offset += len(chunk)

            while len(self._buffer) >= FIXED_HEADER_LEN:
                header_len = int.from_bytes(self._buffer[8:10], "big")
                payload_len = int.from_bytes(self._buffer[10:14], "big")
                if header_len < FIXED_HEADER_LEN:
                    raise ProtocolError("header shorter than fixed header")
                if header_len != FIXED_HEADER_LEN:
                    raise ProtocolError("header extensions are not supported in Beta 0.1")
                if payload_len > self._max_payload:
                    raise ProtocolError("payload exceeds negotiated maximum")
                total = header_len + payload_len
                if total > self._max_buffer:
                    raise ProtocolError("frame exceeds stream buffer limit")
                if len(self._buffer) < total:
                    break

                frame, consumed = decode_frame(bytes(self._buffer[:total]), max_payload=self._max_payload)
                frames.append(frame)
                del self._buffer[:consumed]

        return frames
