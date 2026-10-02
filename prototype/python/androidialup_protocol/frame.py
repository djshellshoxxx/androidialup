from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
import struct

MAGIC = b"ADUP"
PROTOCOL_VERSION = 1
MAX_PAYLOAD = 1024 * 1024
ZERO_ID = b"\x00" * 16
_HEADER = struct.Struct("!4sBBHHI16s16sII")
FIXED_HEADER_LEN = _HEADER.size


class ProtocolError(ValueError):
    pass


class FrameKind(IntEnum):
    HELLO = 1
    HELLO_ACK = 2
    HELLO_REJECT = 3
    AUTH_BEGIN = 10
    AUTH_CHALLENGE = 11
    AUTH_RESPONSE = 12
    AUTH_OK = 13
    AUTH_FAIL = 14
    DIAL_REQUEST = 20
    DIAL_ACCEPTED = 21
    DIAL_FAILED = 22
    CALL_PROGRESS = 23
    CALL_TERMINATED = 24
    DATA_BYTES = 30
    FLOW_STATUS = 31
    PING = 40
    PONG = 41
    HANGUP_REQUEST = 50
    HANGUP_ACK = 51
    INCOMING_CALL = 60
    ANSWER_REQUEST = 61
    ANSWER_ACCEPTED = 62
    ANSWER_FAILED = 63


@dataclass(frozen=True, slots=True)
class Frame:
    kind: FrameKind
    flags: int = 0
    call_id: bytes = ZERO_ID
    session_id: bytes = ZERO_ID
    request_id: int = 0
    payload: bytes = b""

    def __post_init__(self) -> None:
        if len(self.call_id) != 16 or len(self.session_id) != 16:
            raise ValueError("call_id and session_id must be exactly 16 bytes")
        if not 0 <= self.flags <= 0xFFFF:
            raise ValueError("flags must fit u16")
        if not 0 <= self.request_id <= 0xFFFFFFFF:
            raise ValueError("request_id must fit u32")
        if len(self.payload) > MAX_PAYLOAD:
            raise ValueError("payload exceeds protocol maximum")


def encode_frame(frame: Frame) -> bytes:
    payload = bytes(frame.payload)
    header = _HEADER.pack(
        MAGIC,
        PROTOCOL_VERSION,
        int(frame.kind),
        frame.flags,
        FIXED_HEADER_LEN,
        len(payload),
        frame.call_id,
        frame.session_id,
        frame.request_id,
        0,
    )
    return header + payload


def decode_frame(data: bytes, *, max_payload: int = MAX_PAYLOAD) -> tuple[Frame, int]:
    if len(data) < FIXED_HEADER_LEN:
        raise ProtocolError("incomplete fixed header")

    (
        magic,
        version,
        kind_raw,
        flags,
        header_len,
        payload_len,
        call_id,
        session_id,
        request_id,
        reserved,
    ) = _HEADER.unpack_from(data)

    if magic != MAGIC:
        raise ProtocolError("invalid magic")
    if version != PROTOCOL_VERSION:
        raise ProtocolError(f"unsupported version {version}")
    if header_len < FIXED_HEADER_LEN:
        raise ProtocolError("header shorter than fixed header")
    if header_len != FIXED_HEADER_LEN:
        raise ProtocolError("header extensions are not supported in Beta 0.1")
    if reserved != 0:
        raise ProtocolError("reserved field must be zero")
    if payload_len > min(max_payload, MAX_PAYLOAD):
        raise ProtocolError("payload exceeds negotiated maximum")

    total = header_len + payload_len
    if len(data) < total:
        raise ProtocolError("incomplete frame payload")

    try:
        kind = FrameKind(kind_raw)
    except ValueError as exc:
        raise ProtocolError(f"unknown frame kind {kind_raw}") from exc

    frame = Frame(
        kind=kind,
        flags=flags,
        call_id=call_id,
        session_id=session_id,
        request_id=request_id,
        payload=bytes(data[header_len:total]),
    )
    return frame, total
