from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class BackendState(str, Enum):
    CLOSED = "CLOSED"
    READY = "READY"
    DIALING = "DIALING"
    CONNECTED = "CONNECTED"


class BackendEventType(str, Enum):
    PROGRESS = "PROGRESS"
    CONNECTED = "CONNECTED"
    DATA = "DATA"
    FAILED = "FAILED"
    HANGUP = "HANGUP"


@dataclass(frozen=True, slots=True)
class BackendEvent:
    type: BackendEventType
    detail: str | None = None
    data: bytes = b""
    rate: int | None = None
    protocol: str | None = None
