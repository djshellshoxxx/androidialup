from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class EscapeAction:
    forward: bytes = b""
    held: bool = False
    escaped: bool = False


class EscapeDetector:
    def __init__(self, *, last_forwarded_ms: int = 0) -> None:
        self.last_forwarded_ms = int(last_forwarded_ms)
        self._candidate_count = 0
        self._candidate_last_ms = 0
        self._candidate_guard_ms = 0
        self._post_guard_deadline_ms: int | None = None

    def _reset_candidate(self) -> None:
        self._candidate_count = 0
        self._candidate_last_ms = 0
        self._candidate_guard_ms = 0
        self._post_guard_deadline_ms = None

    def _cancel_and_forward(self, current_byte: int | None, now_ms: int) -> EscapeAction:
        data = bytearray(b"+" * self._candidate_count)
        if current_byte is not None:
            data.append(current_byte)
        self._reset_candidate()
        self.last_forwarded_ms = int(now_ms)
        return EscapeAction(forward=bytes(data))

    def feed(self, byte: int, now_ms: int, escape_char: int, guard_ms: int) -> EscapeAction:
        if not 0 <= byte <= 255:
            raise ValueError("byte must fit u8")
        if guard_ms < 0:
            raise ValueError("guard_ms must be nonnegative")

        if escape_char > 127 or byte != escape_char and self._candidate_count == 0:
            self.last_forwarded_ms = int(now_ms)
            return EscapeAction(forward=bytes([byte]))

        if self._candidate_count == 0:
            if byte == escape_char and now_ms - self.last_forwarded_ms >= guard_ms:
                self._candidate_count = 1
                self._candidate_last_ms = int(now_ms)
                self._candidate_guard_ms = int(guard_ms)
                return EscapeAction(held=True)
            self.last_forwarded_ms = int(now_ms)
            return EscapeAction(forward=bytes([byte]))

        if byte == escape_char and self._candidate_count < 3 and now_ms - self._candidate_last_ms <= guard_ms:
            self._candidate_count += 1
            self._candidate_last_ms = int(now_ms)
            self._candidate_guard_ms = int(guard_ms)
            if self._candidate_count == 3:
                self._post_guard_deadline_ms = int(now_ms + guard_ms)
            return EscapeAction(held=True)

        return self._cancel_and_forward(byte, now_ms)

    def timer(self, now_ms: int) -> EscapeAction:
        if self._candidate_count == 0:
            return EscapeAction()

        if self._candidate_count == 3 and self._post_guard_deadline_ms is not None:
            if now_ms >= self._post_guard_deadline_ms:
                self._reset_candidate()
                return EscapeAction(escaped=True)
            return EscapeAction()

        # An incomplete candidate is data, not an escape. Preserve it for one
        # full guard interval after the most recent held escape character.
        if now_ms > self._candidate_last_ms + self._candidate_guard_ms:
            return self._cancel_and_forward(None, now_ms)
        return EscapeAction()
