#!/usr/bin/env python3
"""Deterministically generate the ADUP v1 cross-language golden vectors.

Output format (``tests/vectors/adup_v1_vectors.txt``), one vector per line::

    name<TAB>kind_int<TAB>hex

* Lines starting with ``#`` and blank lines are comments and must be ignored.
* ``kind_int`` is the numeric ``FrameKind`` of the payload.
* ``hex`` is the lower-case hex of the encoded payload, or, when ``name``
  starts with ``frame.``, of a complete encoded ADUP frame carrying that
  payload.

No JSON, so a consumer in any language only needs ``split`` and a hex decoder.

Usage::

    python3 tools/gen_adup_vectors.py            # rewrite the committed file
    python3 tools/gen_adup_vectors.py --check    # exit 1 if the file is stale
    python3 tools/gen_adup_vectors.py --stdout   # print instead of writing
"""
from __future__ import annotations

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_PY_ROOT = os.path.dirname(_HERE)
if _PY_ROOT not in sys.path:
    sys.path.insert(0, _PY_ROOT)

from androidialup_protocol.frame import Frame, FrameKind, encode_frame  # noqa: E402
from androidialup_protocol.messages import (  # noqa: E402
    MAX_BLOB, MAX_CAPABILITIES, MAX_DATA_BYTES, MAX_MAP_ENTRIES, MAX_SHORT_STRING,
    AuthBegin, AuthChallenge, AuthFail, AuthOk, AuthResponse,
    CallProgress, CallTerminated, DataBytes, DialAccepted, DialFailed,
    DialFailure, DialRequest, FlowStatus, HangupAck, HangupRequest,
    Hello, HelloAck, HelloReject, Mode, NetworkTransport, Ping, Pong,
    ProgressPhase, TerminationSource, encode_payload, kind_for_message,
)

VECTORS_PATH = os.path.join(_PY_ROOT, "tests", "vectors", "adup_v1_vectors.txt")

UTF8_SAMPLE = "é€\U0001F600 日本語"  # é € 😀 日本語


def _pattern_bytes(length: int, seed: int) -> bytes:
    """Deterministic non-trivial byte pattern (no PRNG, so no version drift)."""
    return bytes(((i * 7 + seed) & 0xFF) for i in range(length))


def payload_vectors() -> list[tuple[str, object]]:
    """Return (name, message) pairs in stable order."""
    e32 = b"E" * 32
    c16 = b"C" * 16
    s16 = b"S" * 16
    many_caps = tuple(f"cap{i:03d}" for i in range(MAX_CAPABILITIES))
    many_entries = tuple((f"k{i:03d}", f"v{i:03d}") for i in range(MAX_MAP_ENTRIES))
    max_string = "x" * MAX_SHORT_STRING
    return [
        # HELLO
        ("hello.basic", Hello("AndroidDialup", "0.1", 1, 1, e32, ("BYTE_RELAY", "PCM_VBD_EXPERIMENTAL"))),
        ("hello.empty_capabilities", Hello("", "", 0, 0xFFFF, bytes(32), ())),
        ("hello.utf8", Hello(UTF8_SAMPLE, "vé", 1, 2, _pattern_bytes(32, 1), (UTF8_SAMPLE,))),
        ("hello.max_capabilities", Hello("n", "v", 1, 1, e32, many_caps)),
        ("hello.max_string", Hello(max_string, "v", 1, 1, e32, ())),
        # HELLO_ACK
        ("hello_ack.basic", HelloAck(1, "relay-01", 1024 * 1024, 10, ("BYTE_RELAY",))),
        ("hello_ack.min_payload", HelloAck(0, "", 1, 0, ())),
        ("hello_ack.max_u32_heartbeat", HelloAck(0xFFFF, "r", 65536, 0xFFFFFFFF, ("a", "b", "c"))),
        ("hello_ack.max_capabilities", HelloAck(1, "relay", 4096, 30, many_caps)),
        # HELLO_REJECT
        ("hello_reject.basic", HelloReject("unsupported")),
        ("hello_reject.empty", HelloReject("")),
        ("hello_reject.utf8", HelloReject(UTF8_SAMPLE)),
        # AUTH_*
        ("auth_begin.empty", AuthBegin()),
        ("auth_challenge.basic", AuthChallenge(b"N" * 32, "device-credential")),
        ("auth_challenge.empty_nonce", AuthChallenge(b"", "")),
        ("auth_challenge.pattern", AuthChallenge(_pattern_bytes(300, 3), "méthode")),
        ("auth_response.basic", AuthResponse(b"P" * 64)),
        ("auth_response.empty", AuthResponse(b"")),
        ("auth_response.max_blob", AuthResponse(_pattern_bytes(MAX_BLOB, 5))),
        ("auth_ok.basic", AuthOk(e32, (("dial", "allowed"),))),
        ("auth_ok.empty_policy", AuthOk(bytes(32), ())),
        ("auth_ok.utf8_policy", AuthOk(_pattern_bytes(32, 9), ((UTF8_SAMPLE, ""), ("", UTF8_SAMPLE)))),
        ("auth_ok.max_policy", AuthOk(e32, many_entries)),
        ("auth_fail.basic", AuthFail("bad proof")),
        ("auth_fail.utf8", AuthFail(UTF8_SAMPLE)),
        # DIAL_REQUEST
        ("dial_request.basic", DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI, ("BYTE_RELAY",), 60000, (("test", "1"),))),
        ("dial_request.minimal", DialRequest("1", Mode.BYTE_RELAY, NetworkTransport.OTHER, (), 0)),
        ("dial_request.pcm_cellular", DialRequest("+15551234567", Mode.PCM_VBD_EXPERIMENTAL, NetworkTransport.CELLULAR, ("BYTE_RELAY", "PCM_VBD_EXPERIMENTAL"), 0xFFFFFFFF, (("a", "1"), ("b", "2")))),
        ("dial_request.reserved_modes_ethernet", DialRequest("x", Mode.V152_RTP_RESERVED, NetworkTransport.ETHERNET, (), 1)),
        ("dial_request.reserved_sprt", DialRequest("x", Mode.V1501_SPRT_RESERVED, NetworkTransport.WIFI, (), 2)),
        ("dial_request.utf8_target_max", DialRequest("é" * 128, Mode.BYTE_RELAY, NetworkTransport.WIFI, (UTF8_SAMPLE,), 5, ((UTF8_SAMPLE, UTF8_SAMPLE),))),
        ("dial_request.max_lists", DialRequest("t" * 256, Mode.BYTE_RELAY, NetworkTransport.WIFI, many_caps, 1, many_entries)),
        # DIAL_ACCEPTED
        ("dial_accepted.basic", DialAccepted(c16, s16, "gw-loopback", Mode.BYTE_RELAY)),
        ("dial_accepted.pattern_pcm", DialAccepted(_pattern_bytes(16, 11), _pattern_bytes(16, 13), "", Mode.PCM_VBD_EXPERIMENTAL)),
        # DIAL_FAILED
        ("dial_failed.with_detail", DialFailed(c16, DialFailure.BUSY, False, "busy")),
        ("dial_failed.no_detail", DialFailed(c16, DialFailure.TIMEOUT, True, None)),
        ("dial_failed.empty_detail", DialFailed(bytes(16), DialFailure.INTERNAL_ERROR, True, "")),
        ("dial_failed.utf8_detail", DialFailed(_pattern_bytes(16, 17), DialFailure.AUTHORIZATION_DENIED, False, UTF8_SAMPLE)),
    ] + [
        (f"dial_failed.reason_{reason.name.lower()}", DialFailed(c16, reason, bool(reason.value % 2), None))
        for reason in DialFailure
    ] + [
        # CALL_PROGRESS
        ("call_progress.with_detail", CallProgress(ProgressPhase.CONNECTED, "loopback")),
        ("call_progress.no_detail", CallProgress(ProgressPhase.RINGBACK, None)),
        ("call_progress.utf8_detail", CallProgress(ProgressPhase.DIALING, UTF8_SAMPLE)),
    ] + [
        (f"call_progress.phase_{phase.name.lower()}", CallProgress(phase, None))
        for phase in ProgressPhase
    ] + [
        # CALL_TERMINATED
        ("call_terminated.with_code", CallTerminated("LOCAL_HANGUP", TerminationSource.LOCAL, "OK")),
        ("call_terminated.no_code", CallTerminated("REMOTE_HANGUP", TerminationSource.REMOTE, None)),
        ("call_terminated.utf8", CallTerminated(UTF8_SAMPLE, TerminationSource.GATEWAY, UTF8_SAMPLE)),
        ("call_terminated.relay_empty", CallTerminated("", TerminationSource.RELAY, "")),
        # DATA_BYTES
        ("data_bytes.basic", DataBytes(123, b"abc\x00\xff")),
        ("data_bytes.single_byte", DataBytes(0, b"\x00")),
        ("data_bytes.max", DataBytes(0xFFFFFFFFFFFFFFFF, _pattern_bytes(MAX_DATA_BYTES, 19))),
        ("data_bytes.high_bit_seq", DataBytes(0x8000000000000000, b"\x80")),
        # FLOW_STATUS
        ("flow_status.basic", FlowStatus(262144, 7)),
        ("flow_status.zero", FlowStatus(0, 0)),
        ("flow_status.max_u32", FlowStatus(0xFFFFFFFF, 0xFFFFFFFF)),
        # PING / PONG
        ("ping.basic", Ping(99, 123456)),
        ("ping.zero", Ping(0, 0)),
        ("ping.max_u64", Ping(0xFFFFFFFFFFFFFFFF, 0x8000000000000000)),
        ("pong.basic", Pong(99)),
        ("pong.max_u64", Pong(0xFFFFFFFFFFFFFFFF)),
        # HANGUP
        ("hangup_request.basic", HangupRequest("user")),
        ("hangup_request.utf8", HangupRequest(UTF8_SAMPLE)),
        ("hangup_ack.empty", HangupAck()),
    ]


def frame_vectors() -> list[tuple[str, Frame]]:
    """Complete frames exercising header fields together with typed payloads."""
    c16 = b"C" * 16
    s16 = b"S" * 16
    return [
        ("frame.hello", Frame(kind=FrameKind.HELLO, request_id=1,
                              payload=encode_payload(Hello("AndroidDialup", "0.1", 1, 1, b"E" * 32, ("BYTE_RELAY",))))),
        ("frame.dial_accepted", Frame(kind=FrameKind.DIAL_ACCEPTED, flags=0x0001, call_id=c16, session_id=s16,
                                      request_id=0x01020304,
                                      payload=encode_payload(DialAccepted(c16, s16, "gw-loopback", Mode.BYTE_RELAY)))),
        ("frame.data_bytes", Frame(kind=FrameKind.DATA_BYTES, flags=0xFFFF, call_id=c16, session_id=s16,
                                   request_id=0xFFFFFFFF,
                                   payload=encode_payload(DataBytes(42, _pattern_bytes(1500, 23))))),
        ("frame.auth_begin_empty_payload", Frame(kind=FrameKind.AUTH_BEGIN, request_id=7)),
        ("frame.ping_unsolicited", Frame(kind=FrameKind.PING, payload=encode_payload(Ping(1, 2)))),
    ]


def render() -> str:
    lines = [
        "# ADUP v1 cross-language golden vectors. GENERATED by prototype/python/tools/gen_adup_vectors.py; do not edit.",
        "# name<TAB>kind_int<TAB>hex  (names starting with 'frame.' hold a complete ADUP frame)",
    ]
    seen: set[str] = set()
    for name, message in payload_vectors():
        if name in seen:
            raise ValueError(f"duplicate vector name {name}")
        seen.add(name)
        kind = kind_for_message(message)
        lines.append(f"{name}\t{int(kind)}\t{encode_payload(message).hex()}")
    for name, frame in frame_vectors():
        if name in seen:
            raise ValueError(f"duplicate vector name {name}")
        seen.add(name)
        lines.append(f"{name}\t{int(frame.kind)}\t{encode_frame(frame).hex()}")
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    text = render()
    if "--stdout" in argv:
        sys.stdout.write(text)
        return 0
    if "--check" in argv:
        try:
            with open(VECTORS_PATH, "r", encoding="utf-8", newline="") as fh:
                current = fh.read()
        except FileNotFoundError:
            current = ""
        if current != text:
            sys.stderr.write(f"{VECTORS_PATH} is stale; rerun tools/gen_adup_vectors.py\n")
            return 1
        return 0
    os.makedirs(os.path.dirname(VECTORS_PATH), exist_ok=True)
    with open(VECTORS_PATH, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
