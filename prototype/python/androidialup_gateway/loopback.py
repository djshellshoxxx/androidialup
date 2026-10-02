from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import time

from .backend import BackendEvent, BackendEventType, BackendState


class LoopbackMode(str, Enum):
    ECHO = "ECHO"
    SINK = "SINK"
    PATTERN = "PATTERN"
    DELAYED = "DELAYED"


@dataclass(slots=True)
class _ScheduledEvent:
    due: float
    event: BackendEvent


class LoopbackBackend:
    def __init__(
        self,
        *,
        mode: LoopbackMode = LoopbackMode.ECHO,
        pattern: bytes = b"ANDROIDIALUP",
        delay_seconds: float = 0.0,
    ) -> None:
        self.mode = mode
        self.pattern = bytes(pattern)
        self.delay_seconds = max(0.0, float(delay_seconds))
        self.state = BackendState.CLOSED
        self._events: list[BackendEvent] = []
        self._scheduled: list[_ScheduledEvent] = []
        self._hangup_reported = False

    def open(self) -> None:
        if self.state == BackendState.CLOSED:
            self.state = BackendState.READY
            self._hangup_reported = False

    def close(self) -> None:
        self.state = BackendState.CLOSED
        self._events.clear()
        self._scheduled.clear()
        self._hangup_reported = False

    def dial(self, target: str, options: dict | None = None) -> None:
        if self.state != BackendState.READY:
            raise RuntimeError("backend is not ready")
        self.state = BackendState.DIALING
        self._hangup_reported = False
        self._events.append(BackendEvent(BackendEventType.PROGRESS, detail="DIALING"))

        normalized = target.strip().lower()
        if normalized in {"busy", "loopback:busy"}:
            self.state = BackendState.READY
            self._events.append(BackendEvent(BackendEventType.FAILED, detail="BUSY"))
            return
        if normalized in {"noanswer", "loopback:noanswer"}:
            self.state = BackendState.READY
            self._events.append(BackendEvent(BackendEventType.FAILED, detail="NO_ANSWER"))
            return
        if normalized in {"nodialtone", "loopback:nodialtone"}:
            self.state = BackendState.READY
            self._events.append(BackendEvent(BackendEventType.FAILED, detail="NO_DIALTONE"))
            return

        self.state = BackendState.CONNECTED
        self._events.append(
            BackendEvent(
                BackendEventType.CONNECTED,
                detail="loopback connected",
                protocol="BYTE_RELAY",
            )
        )

    def write(self, data: bytes) -> int:
        if self.state != BackendState.CONNECTED:
            raise RuntimeError("backend is not connected")
        raw = bytes(data)
        if not raw:
            return 0

        if self.mode == LoopbackMode.SINK:
            return len(raw)
        if self.mode == LoopbackMode.PATTERN:
            emitted = self.pattern
        else:
            emitted = raw

        event = BackendEvent(BackendEventType.DATA, data=emitted)
        if self.mode == LoopbackMode.DELAYED and self.delay_seconds > 0:
            self._scheduled.append(_ScheduledEvent(time.monotonic() + self.delay_seconds, event))
        else:
            self._events.append(event)
        return len(raw)

    def hangup(self, reason: str = "LOCAL_HANGUP") -> None:
        if self.state == BackendState.CLOSED:
            return
        if self.state == BackendState.READY and self._hangup_reported:
            return
        was_active = self.state in {BackendState.DIALING, BackendState.CONNECTED}
        self.state = BackendState.READY
        self._scheduled.clear()
        if was_active and not self._hangup_reported:
            self._events.append(BackendEvent(BackendEventType.HANGUP, detail=reason))
            self._hangup_reported = True

    def poll_events(self, *, now: float | None = None) -> list[BackendEvent]:
        current = time.monotonic() if now is None else now
        if self._scheduled:
            due = [item for item in self._scheduled if item.due <= current]
            self._scheduled = [item for item in self._scheduled if item.due > current]
            self._events.extend(item.event for item in due)
        out = self._events
        self._events = []
        return out
