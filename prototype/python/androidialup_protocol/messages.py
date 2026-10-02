from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
from typing import Any

from .frame import FrameKind, ProtocolError, MAX_PAYLOAD

MAX_SHORT_STRING = 4096
MAX_TARGET_UTF8 = 256
MAX_CAPABILITIES = 128
MAX_MAP_ENTRIES = 128
MAX_BLOB = 65535
MAX_DATA_BYTES = 32768

class Mode(IntEnum):
    BYTE_RELAY = 1
    PCM_VBD_EXPERIMENTAL = 2
    V152_RTP_RESERVED = 100
    V1501_SPRT_RESERVED = 101

class NetworkTransport(IntEnum):
    WIFI = 1
    CELLULAR = 2
    ETHERNET = 3
    OTHER = 4

class DialFailure(IntEnum):
    NO_ROUTE = 1
    GATEWAY_UNAVAILABLE = 2
    BUSY = 3
    NO_DIALTONE = 4
    NO_ANSWER = 5
    AUTHORIZATION_DENIED = 6
    UNSUPPORTED_MODE = 7
    TIMEOUT = 8
    INTERNAL_ERROR = 9

class ProgressPhase(IntEnum):
    ROUTING = 1
    GATEWAY_CONNECTING = 2
    DIALING = 3
    RINGBACK = 4
    NEGOTIATING = 5
    CONNECTED = 6

class TerminationSource(IntEnum):
    LOCAL = 1
    RELAY = 2
    GATEWAY = 3
    REMOTE = 4

@dataclass(frozen=True, slots=True)
class Hello:
    client_name: str
    client_version: str
    protocol_min: int
    protocol_max: int
    endpoint_id: bytes
    capabilities: tuple[str, ...]
    def __post_init__(self) -> None:
        _validate_u16(self.protocol_min, "protocol_min"); _validate_u16(self.protocol_max, "protocol_max")
        if len(self.endpoint_id) != 32: raise ValueError("endpoint_id must be exactly 32 bytes")
        _validate_string(self.client_name, "client_name"); _validate_string(self.client_version, "client_version"); _validate_string_list(self.capabilities, "capabilities")

@dataclass(frozen=True, slots=True)
class HelloAck:
    selected_version: int
    relay_id: str
    max_frame_payload: int
    heartbeat_seconds: int
    capabilities: tuple[str, ...]
    def __post_init__(self) -> None:
        _validate_u16(self.selected_version, "selected_version"); _validate_string(self.relay_id, "relay_id")
        if not 1 <= self.max_frame_payload <= MAX_PAYLOAD: raise ValueError("max_frame_payload out of range")
        _validate_u32(self.heartbeat_seconds, "heartbeat_seconds"); _validate_string_list(self.capabilities, "capabilities")

@dataclass(frozen=True, slots=True)
class HelloReject:
    reason: str
    def __post_init__(self) -> None: _validate_string(self.reason, "reason")

@dataclass(frozen=True, slots=True)
class AuthBegin: pass

@dataclass(frozen=True, slots=True)
class AuthChallenge:
    nonce: bytes
    method: str
    def __post_init__(self) -> None: _validate_blob(self.nonce, "nonce"); _validate_string(self.method, "method")

@dataclass(frozen=True, slots=True)
class AuthResponse:
    proof: bytes
    def __post_init__(self) -> None: _validate_blob(self.proof, "proof")

@dataclass(frozen=True, slots=True)
class AuthOk:
    endpoint_id: bytes
    policy: tuple[tuple[str, str], ...]
    def __post_init__(self) -> None:
        if len(self.endpoint_id) != 32: raise ValueError("endpoint_id must be exactly 32 bytes")
        _validate_map(self.policy, "policy")

@dataclass(frozen=True, slots=True)
class AuthFail:
    reason: str
    def __post_init__(self) -> None: _validate_string(self.reason, "reason")

@dataclass(frozen=True, slots=True)
class DialRequest:
    target: str
    requested_mode: Mode
    network_transport: NetworkTransport
    client_capabilities: tuple[str, ...]
    dial_timeout_ms: int
    caller_metadata: tuple[tuple[str, str], ...] = ()
    def __post_init__(self) -> None:
        target_bytes = self.target.encode("utf-8")
        if not target_bytes or len(target_bytes) > MAX_TARGET_UTF8: raise ValueError("target must be 1..256 UTF-8 bytes")
        _validate_string_list(self.client_capabilities, "client_capabilities"); _validate_u32(self.dial_timeout_ms, "dial_timeout_ms"); _validate_map(self.caller_metadata, "caller_metadata")

@dataclass(frozen=True, slots=True)
class DialAccepted:
    call_id: bytes
    assigned_session_id: bytes
    selected_gateway_id: str
    selected_mode: Mode
    def __post_init__(self) -> None:
        _validate_id16(self.call_id, "call_id"); _validate_id16(self.assigned_session_id, "assigned_session_id"); _validate_string(self.selected_gateway_id, "selected_gateway_id")

@dataclass(frozen=True, slots=True)
class DialFailed:
    call_id: bytes
    reason: DialFailure
    retryable: bool
    human_detail: str | None = None
    def __post_init__(self) -> None:
        _validate_id16(self.call_id, "call_id")
        if self.human_detail is not None: _validate_string(self.human_detail, "human_detail")

@dataclass(frozen=True, slots=True)
class CallProgress:
    phase: ProgressPhase
    detail: str | None = None
    def __post_init__(self) -> None:
        if self.detail is not None: _validate_string(self.detail, "detail")

@dataclass(frozen=True, slots=True)
class CallTerminated:
    reason: str
    source: TerminationSource
    diagnostic_code: str | None = None
    def __post_init__(self) -> None:
        _validate_string(self.reason, "reason")
        if self.diagnostic_code is not None: _validate_string(self.diagnostic_code, "diagnostic_code")

@dataclass(frozen=True, slots=True)
class DataBytes:
    stream_seq: int
    data: bytes
    def __post_init__(self) -> None:
        _validate_u64(self.stream_seq, "stream_seq")
        if not 1 <= len(self.data) <= MAX_DATA_BYTES: raise ValueError("DATA_BYTES data must be 1..32768 bytes")

@dataclass(frozen=True, slots=True)
class FlowStatus:
    receive_window_bytes: int
    queued_bytes: int
    def __post_init__(self) -> None: _validate_u32(self.receive_window_bytes, "receive_window_bytes"); _validate_u32(self.queued_bytes, "queued_bytes")

@dataclass(frozen=True, slots=True)
class Ping:
    nonce: int
    monotonic_hint: int
    def __post_init__(self) -> None: _validate_u64(self.nonce, "nonce"); _validate_u64(self.monotonic_hint, "monotonic_hint")

@dataclass(frozen=True, slots=True)
class Pong:
    nonce: int
    def __post_init__(self) -> None: _validate_u64(self.nonce, "nonce")

@dataclass(frozen=True, slots=True)
class HangupRequest:
    reason: str
    def __post_init__(self) -> None: _validate_string(self.reason, "reason")

@dataclass(frozen=True, slots=True)
class HangupAck: pass

def _validate_id16(value: bytes, name: str) -> None:
    if len(value) != 16: raise ValueError(f"{name} must be exactly 16 bytes")
def _validate_u16(value: int, name: str) -> None:
    if not 0 <= value <= 0xFFFF: raise ValueError(f"{name} must fit u16")
def _validate_u32(value: int, name: str) -> None:
    if not 0 <= value <= 0xFFFFFFFF: raise ValueError(f"{name} must fit u32")
def _validate_u64(value: int, name: str) -> None:
    if not 0 <= value <= 0xFFFFFFFFFFFFFFFF: raise ValueError(f"{name} must fit u64")
def _validate_string(value: str, name: str) -> None:
    if len(value.encode("utf-8")) > MAX_SHORT_STRING: raise ValueError(f"{name} exceeds {MAX_SHORT_STRING} UTF-8 bytes")
def _validate_blob(value: bytes, name: str) -> None:
    if len(value) > MAX_BLOB: raise ValueError(f"{name} exceeds {MAX_BLOB} bytes")
def _validate_string_list(values: tuple[str, ...], name: str) -> None:
    if len(values) > MAX_CAPABILITIES: raise ValueError(f"{name} has too many entries")
    for value in values: _validate_string(value, name)
def _validate_map(values: tuple[tuple[str, str], ...], name: str) -> None:
    if len(values) > MAX_MAP_ENTRIES: raise ValueError(f"{name} has too many entries")
    for key, value in values: _validate_string(key, name); _validate_string(value, name)

class _Writer:
    def __init__(self) -> None: self.buf = bytearray()
    def u8(self, value: int) -> None: self.buf += int(value).to_bytes(1, "big")
    def u16(self, value: int) -> None: self.buf += int(value).to_bytes(2, "big")
    def u32(self, value: int) -> None: self.buf += int(value).to_bytes(4, "big")
    def u64(self, value: int) -> None: self.buf += int(value).to_bytes(8, "big")
    def fixed(self, value: bytes, length: int) -> None:
        if len(value) != length: raise ValueError(f"expected {length} bytes")
        self.buf += value
    def blob16(self, value: bytes) -> None: _validate_blob(value, "blob"); self.u16(len(value)); self.buf += value
    def text(self, value: str) -> None:
        _validate_string(value, "string"); raw = value.encode("utf-8"); self.u16(len(raw)); self.buf += raw
    def optional_text(self, value: str | None) -> None:
        self.u8(0 if value is None else 1)
        if value is not None: self.text(value)
    def strings(self, values: tuple[str, ...]) -> None:
        _validate_string_list(values, "list"); self.u16(len(values))
        for value in values: self.text(value)
    def mapping(self, values: tuple[tuple[str, str], ...]) -> None:
        _validate_map(values, "map"); self.u16(len(values))
        for key, value in values: self.text(key); self.text(value)

class _Reader:
    def __init__(self, payload: bytes) -> None: self.payload = payload; self.pos = 0
    def _take(self, length: int) -> bytes:
        if length < 0 or self.pos + length > len(self.payload): raise ProtocolError("truncated payload")
        out = self.payload[self.pos:self.pos + length]; self.pos += length; return out
    def u8(self) -> int: return int.from_bytes(self._take(1), "big")
    def u16(self) -> int: return int.from_bytes(self._take(2), "big")
    def u32(self) -> int: return int.from_bytes(self._take(4), "big")
    def u64(self) -> int: return int.from_bytes(self._take(8), "big")
    def fixed(self, length: int) -> bytes: return self._take(length)
    def blob16(self) -> bytes:
        length = self.u16()
        if length > MAX_BLOB: raise ProtocolError("blob length exceeds maximum")
        return self._take(length)
    def text(self) -> str:
        length = self.u16()
        if length > MAX_SHORT_STRING: raise ProtocolError("string length exceeds maximum")
        raw = self._take(length)
        try: return raw.decode("utf-8")
        except UnicodeDecodeError as exc: raise ProtocolError("invalid UTF-8") from exc
    def optional_text(self) -> str | None:
        flag = self.u8()
        if flag == 0: return None
        if flag != 1: raise ProtocolError("invalid optional field marker")
        return self.text()
    def strings(self) -> tuple[str, ...]:
        count = self.u16()
        if count > MAX_CAPABILITIES: raise ProtocolError("too many string-list entries")
        return tuple(self.text() for _ in range(count))
    def mapping(self) -> tuple[tuple[str, str], ...]:
        count = self.u16()
        if count > MAX_MAP_ENTRIES: raise ProtocolError("too many map entries")
        return tuple((self.text(), self.text()) for _ in range(count))
    def finish(self) -> None:
        if self.pos != len(self.payload): raise ProtocolError("trailing payload bytes")

_KIND_BY_TYPE = {Hello:FrameKind.HELLO, HelloAck:FrameKind.HELLO_ACK, HelloReject:FrameKind.HELLO_REJECT, AuthBegin:FrameKind.AUTH_BEGIN, AuthChallenge:FrameKind.AUTH_CHALLENGE, AuthResponse:FrameKind.AUTH_RESPONSE, AuthOk:FrameKind.AUTH_OK, AuthFail:FrameKind.AUTH_FAIL, DialRequest:FrameKind.DIAL_REQUEST, DialAccepted:FrameKind.DIAL_ACCEPTED, DialFailed:FrameKind.DIAL_FAILED, CallProgress:FrameKind.CALL_PROGRESS, CallTerminated:FrameKind.CALL_TERMINATED, DataBytes:FrameKind.DATA_BYTES, FlowStatus:FrameKind.FLOW_STATUS, Ping:FrameKind.PING, Pong:FrameKind.PONG, HangupRequest:FrameKind.HANGUP_REQUEST, HangupAck:FrameKind.HANGUP_ACK}

def kind_for_message(message: Any) -> FrameKind:
    try: return _KIND_BY_TYPE[type(message)]
    except KeyError as exc: raise ValueError(f"unsupported message type {type(message).__name__}") from exc

def encode_payload(message: Any) -> bytes:
    w = _Writer()
    if isinstance(message, Hello): w.text(message.client_name); w.text(message.client_version); w.u16(message.protocol_min); w.u16(message.protocol_max); w.fixed(message.endpoint_id,32); w.strings(message.capabilities)
    elif isinstance(message, HelloAck): w.u16(message.selected_version); w.text(message.relay_id); w.u32(message.max_frame_payload); w.u32(message.heartbeat_seconds); w.strings(message.capabilities)
    elif isinstance(message, HelloReject): w.text(message.reason)
    elif isinstance(message, AuthBegin): pass
    elif isinstance(message, AuthChallenge): w.blob16(message.nonce); w.text(message.method)
    elif isinstance(message, AuthResponse): w.blob16(message.proof)
    elif isinstance(message, AuthOk): w.fixed(message.endpoint_id,32); w.mapping(message.policy)
    elif isinstance(message, AuthFail): w.text(message.reason)
    elif isinstance(message, DialRequest): w.text(message.target); w.u16(int(message.requested_mode)); w.u8(int(message.network_transport)); w.strings(message.client_capabilities); w.u32(message.dial_timeout_ms); w.mapping(message.caller_metadata)
    elif isinstance(message, DialAccepted): w.fixed(message.call_id,16); w.fixed(message.assigned_session_id,16); w.text(message.selected_gateway_id); w.u16(int(message.selected_mode))
    elif isinstance(message, DialFailed): w.fixed(message.call_id,16); w.u16(int(message.reason)); w.u8(1 if message.retryable else 0); w.optional_text(message.human_detail)
    elif isinstance(message, CallProgress): w.u16(int(message.phase)); w.optional_text(message.detail)
    elif isinstance(message, CallTerminated): w.text(message.reason); w.u8(int(message.source)); w.optional_text(message.diagnostic_code)
    elif isinstance(message, DataBytes): w.u64(message.stream_seq); w.buf += message.data
    elif isinstance(message, FlowStatus): w.u32(message.receive_window_bytes); w.u32(message.queued_bytes)
    elif isinstance(message, Ping): w.u64(message.nonce); w.u64(message.monotonic_hint)
    elif isinstance(message, Pong): w.u64(message.nonce)
    elif isinstance(message, HangupRequest): w.text(message.reason)
    elif isinstance(message, HangupAck): pass
    else: raise ValueError(f"unsupported message type {type(message).__name__}")
    if len(w.buf) > MAX_PAYLOAD: raise ValueError("encoded payload exceeds protocol maximum")
    return bytes(w.buf)

def _enum(enum_type, value: int, label: str):
    try: return enum_type(value)
    except ValueError as exc: raise ProtocolError(f"unknown {label} value {value}") from exc

def decode_payload(kind: FrameKind, payload: bytes) -> Any:
    r = _Reader(payload)
    if kind == FrameKind.HELLO: msg = Hello(r.text(), r.text(), r.u16(), r.u16(), r.fixed(32), r.strings())
    elif kind == FrameKind.HELLO_ACK: msg = HelloAck(r.u16(), r.text(), r.u32(), r.u32(), r.strings())
    elif kind == FrameKind.HELLO_REJECT: msg = HelloReject(r.text())
    elif kind == FrameKind.AUTH_BEGIN: msg = AuthBegin()
    elif kind == FrameKind.AUTH_CHALLENGE: msg = AuthChallenge(r.blob16(), r.text())
    elif kind == FrameKind.AUTH_RESPONSE: msg = AuthResponse(r.blob16())
    elif kind == FrameKind.AUTH_OK: msg = AuthOk(r.fixed(32), r.mapping())
    elif kind == FrameKind.AUTH_FAIL: msg = AuthFail(r.text())
    elif kind == FrameKind.DIAL_REQUEST: msg = DialRequest(r.text(), _enum(Mode,r.u16(),"mode"), _enum(NetworkTransport,r.u8(),"network transport"), r.strings(), r.u32(), r.mapping())
    elif kind == FrameKind.DIAL_ACCEPTED: msg = DialAccepted(r.fixed(16), r.fixed(16), r.text(), _enum(Mode,r.u16(),"mode"))
    elif kind == FrameKind.DIAL_FAILED:
        call_id=r.fixed(16); reason=_enum(DialFailure,r.u16(),"dial failure"); retry=r.u8()
        if retry not in (0,1): raise ProtocolError("invalid retryable flag")
        msg=DialFailed(call_id,reason,bool(retry),r.optional_text())
    elif kind == FrameKind.CALL_PROGRESS: msg=CallProgress(_enum(ProgressPhase,r.u16(),"progress phase"),r.optional_text())
    elif kind == FrameKind.CALL_TERMINATED: msg=CallTerminated(r.text(),_enum(TerminationSource,r.u8(),"termination source"),r.optional_text())
    elif kind == FrameKind.DATA_BYTES:
        seq=r.u64(); data=r._take(len(payload)-r.pos)
        try: msg=DataBytes(seq,data)
        except ValueError as exc: raise ProtocolError(str(exc)) from exc
    elif kind == FrameKind.FLOW_STATUS: msg=FlowStatus(r.u32(),r.u32())
    elif kind == FrameKind.PING: msg=Ping(r.u64(),r.u64())
    elif kind == FrameKind.PONG: msg=Pong(r.u64())
    elif kind == FrameKind.HANGUP_REQUEST: msg=HangupRequest(r.text())
    elif kind == FrameKind.HANGUP_ACK: msg=HangupAck()
    else: raise ProtocolError(f"no Beta 0.1 payload codec for frame kind {int(kind)}")
    r.finish(); return msg
