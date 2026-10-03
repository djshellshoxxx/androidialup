"""Connection liveness and S1 deadline primitives (sans-IO).

Everything here works on monotonic seconds supplied by the caller. Nothing
reads wall-clock time; production code passes ``time.monotonic`` (or the
event loop's monotonic ``loop.time``) and tests pass a :class:`ManualClock`
or simply use sub-second intervals.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import math
import secrets
import time
from typing import Callable

from .frame import ProtocolError

Clock = Callable[[], float]

#: Monotonic clock used by default. Never ``time.time``.
monotonic: Clock = time.monotonic

#: S1_SPEC_FREEZE section 12 error taxonomy. Every disconnect has exactly one.
ERROR_TAXONOMY = frozenset(
    {
        "LOCAL_CONFIG",
        "NO_ELIGIBLE_NETWORK",
        "DNS_FAILURE",
        "CONNECT_TIMEOUT",
        "TLS_FAILURE",
        "AUTH_FAILURE",
        "PROTOCOL_VERSION_MISMATCH",
        "PROTOCOL_VIOLATION",
        "RELAY_UNAVAILABLE",
        "NO_ROUTE",
        "BACKEND_UNAVAILABLE",
        "BACKEND_NO_DIALTONE",
        "BACKEND_BUSY",
        "BACKEND_NO_ANSWER",
        "REMOTE_HANGUP",
        "NETWORK_LOST",
        "FLOW_CONTROL_TIMEOUT",
        "QUEUE_OVERFLOW",
        "DTE_DISCONNECTED",
        "INTERNAL_ERROR",
    }
)

# Structured details (free-form in S1, fixed here so tests can assert them).
DETAIL_HEARTBEAT_TIMEOUT = "HEARTBEAT_TIMEOUT"
DETAIL_TRANSPORT_CLOSED = "TRANSPORT_CLOSED"
DETAIL_TRANSPORT_ERROR = "TRANSPORT_ERROR"
DETAIL_AUTH_TIMEOUT = "AUTH_TIMEOUT"
DETAIL_DIAL_ACK_TIMEOUT = "DIAL_ACK_TIMEOUT"
DETAIL_CONNECT_TIMEOUT = "CONNECT_TIMEOUT"
DETAIL_BACKEND_FAILED = "BACKEND_FAILED"


@dataclass(frozen=True, slots=True)
class LinkFailure:
    """One terminal reason from the S1 taxonomy plus optional structured detail."""

    reason: str
    detail: str | None = None

    def __post_init__(self) -> None:
        if self.reason not in ERROR_TAXONOMY:
            raise ValueError(f"{self.reason!r} is not an S1 error-taxonomy reason")


@dataclass(frozen=True, slots=True)
class TimeoutConfig:
    """S1_SPEC_FREEZE section 7 deadlines, in seconds. All injectable."""

    connect: float = 10.0
    relay_auth: float = 8.0
    dial_ack: float = 5.0
    backend_dial_setup: float = 60.0
    heartbeat_interval: float = 10.0
    heartbeat_failure: float = 30.0
    disconnect_grace: float = 3.0

    def __post_init__(self) -> None:
        for name in (
            "connect",
            "relay_auth",
            "dial_ack",
            "backend_dial_setup",
            "heartbeat_interval",
            "heartbeat_failure",
            "disconnect_grace",
        ):
            value = getattr(self, name)
            if not (isinstance(value, (int, float)) and value > 0 and math.isfinite(value)):
                raise ValueError(f"{name} must be a positive finite number of seconds")

    @property
    def heartbeat_seconds_advertised(self) -> int:
        """HELLO_ACK.heartbeat_seconds is whole seconds; never advertise 0 (disabled)."""
        return max(1, math.ceil(self.heartbeat_interval))


DEFAULT_TIMEOUTS = TimeoutConfig()


class ManualClock:
    """Deterministic monotonic clock for tests."""

    def __init__(self, start: float = 0.0) -> None:
        self._now = float(start)

    def __call__(self) -> float:
        return self._now

    def advance(self, seconds: float) -> None:
        if seconds < 0:
            raise ValueError("monotonic clock cannot go backwards")
        self._now += seconds


@dataclass(frozen=True, slots=True)
class HeartbeatAction:
    kind: str  # "NONE" | "PING" | "TIMEOUT"
    nonce: int = 0

    @staticmethod
    def none() -> "HeartbeatAction":
        return HeartbeatAction("NONE")


def _random_nonce() -> int:
    return secrets.randbits(64)


@dataclass(slots=True)
class HeartbeatMonitor:
    """S1 section 9 heartbeat for one side of a connection.

    * A PING is due once ``interval`` has passed with no inbound frame
      (SSH ServerAliveInterval / QUIC idle-timer semantics: only data
      *received from the peer* proves liveness).
    * At most one PING is outstanding. Only a PONG echoing its nonce answers it;
      other inbound traffic does not (the peer must answer PONG, section 9).
    * No PONG within ``failure`` seconds of sending the PING -> one TIMEOUT.
    * PONG with an unknown nonce, or with no PING outstanding, is a protocol
      violation.
    """

    interval: float
    failure: float
    now: float
    nonce_factory: Callable[[], int] = _random_nonce
    last_inbound: float = field(init=False)
    outstanding: bool = field(init=False, default=False)
    outstanding_nonce: int = field(init=False, default=0)
    ping_sent_at: float = field(init=False, default=0.0)
    timed_out: bool = field(init=False, default=False)

    def __post_init__(self) -> None:
        if not self.interval > 0:
            raise ValueError("heartbeat interval must be positive")
        if not self.failure > 0:
            raise ValueError("heartbeat failure interval must be positive")
        self.last_inbound = self.now

    def on_inbound(self, now: float) -> None:
        self.last_inbound = max(self.last_inbound, now)

    def on_pong(self, nonce: int, now: float) -> None:
        if not self.outstanding:
            raise ProtocolError("unsolicited PONG")
        if nonce != self.outstanding_nonce:
            raise ProtocolError("PONG nonce does not match outstanding PING")
        self.outstanding = False
        self.on_inbound(now)

    def next_deadline(self) -> float:
        if self.outstanding:
            return self.ping_sent_at + self.failure
        return self.last_inbound + self.interval

    def poll(self, now: float) -> HeartbeatAction:
        if self.timed_out:
            return HeartbeatAction.none()
        if self.outstanding:
            if now - self.ping_sent_at >= self.failure:
                self.timed_out = True
                self.outstanding = False
                return HeartbeatAction("TIMEOUT")
            return HeartbeatAction.none()
        if now - self.last_inbound >= self.interval:
            nonce = self.nonce_factory() & 0xFFFFFFFFFFFFFFFF
            self.outstanding = True
            self.outstanding_nonce = nonce
            self.ping_sent_at = now
            return HeartbeatAction("PING", nonce)
        return HeartbeatAction.none()
