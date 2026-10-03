from __future__ import annotations

from enum import Enum
import secrets
from typing import Callable

from androidialup_gateway.backend import BackendEventType
from androidialup_gateway.loopback import LoopbackBackend
from androidialup_protocol.byte_relay import DEFAULT_RECEIVE_WINDOW, ByteRelayReceiver, ByteRelaySender, FlowControlBlocked
from androidialup_protocol.frame import Frame, FrameKind, ProtocolError, ZERO_ID
from androidialup_protocol.liveness import DETAIL_BACKEND_FAILED, LinkFailure
from androidialup_protocol.messages import (
    AuthFail,
    AuthOk,
    AuthResponse,
    CallProgress,
    CallTerminated,
    DialAccepted,
    DialFailed,
    DialFailure,
    DialRequest,
    FlowStatus,
    HangupAck,
    Hello,
    HelloAck,
    Mode,
    Pong,
    ProgressPhase,
    TerminationSource,
    decode_payload,
    encode_payload,
    kind_for_message,
)

from .auth import AUTH_FAILED_REASON, ChallengeResponseAuthenticator


class RelaySessionState(str, Enum):
    NEW = "NEW"
    HELLO_DONE = "HELLO_DONE"
    AUTH_CHALLENGE_SENT = "AUTH_CHALLENGE_SENT"
    AUTHENTICATED = "AUTHENTICATED"
    DIALING = "DIALING"
    CONNECTED = "CONNECTED"
    FAILED = "FAILED"


class RelaySession:
    def __init__(
        self,
        *,
        backend: LoopbackBackend | None = None,
        relay_id: str = "relay-prototype",
        gateway_id: str = "gw-loopback",
        authenticator: ChallengeResponseAuthenticator | None = None,
        session_id_factory: Callable[[], bytes] | None = None,
        heartbeat_seconds: int = 10,
    ) -> None:
        self.state = RelaySessionState.NEW
        self.backend = backend or LoopbackBackend()
        self.relay_id = relay_id
        self.gateway_id = gateway_id
        # Default authenticator has an empty credential store: nobody authenticates
        # unless a store is explicitly provisioned.
        self.authenticator = authenticator or ChallengeResponseAuthenticator()
        self.session_id_factory = session_id_factory or (lambda: secrets.token_bytes(16))
        self.heartbeat_seconds = int(heartbeat_seconds)
        #: Terminal reason of the most recent call that ended abnormally (diagnostics).
        self.last_call_failure: LinkFailure | None = None
        self.endpoint_id: bytes | None = None
        self.call_id = ZERO_ID
        self.session_id = ZERO_ID
        self.selected_mode = Mode.BYTE_RELAY
        self._inbound = ByteRelayReceiver()
        self._outbound = ByteRelaySender()
        self._dial_accepted_frame: Frame | None = None
        self._terminal_sent = False

    def _frame(self, message, *, request_id: int = 0, call_id: bytes | None = None, session_id: bytes | None = None) -> Frame:
        return Frame(
            kind=kind_for_message(message),
            call_id=self.call_id if call_id is None else call_id,
            session_id=self.session_id if session_id is None else session_id,
            request_id=request_id,
            payload=encode_payload(message),
        )

    def _require_ids(self, frame: Frame) -> None:
        if frame.call_id != self.call_id:
            raise ProtocolError("call_id does not match active call")
        if frame.session_id != self.session_id:
            raise ProtocolError("session_id does not match active call")

    def handle_frame(self, frame: Frame) -> list[Frame]:
        if frame.kind == FrameKind.PING and self.state != RelaySessionState.NEW:
            ping = decode_payload(frame.kind, frame.payload)
            return [
                self._frame(
                    Pong(ping.nonce),
                    request_id=frame.request_id,
                    call_id=frame.call_id,
                    session_id=frame.session_id,
                )
            ]

        if self.state == RelaySessionState.NEW:
            if frame.kind != FrameKind.HELLO:
                raise ProtocolError("HELLO required before other application frames")
            hello = decode_payload(frame.kind, frame.payload)
            if not isinstance(hello, Hello):
                raise ProtocolError("invalid HELLO")
            if not (hello.protocol_min <= 1 <= hello.protocol_max):
                raise ProtocolError("protocol version 1 not offered")
            self.endpoint_id = hello.endpoint_id
            self.state = RelaySessionState.HELLO_DONE
            return [
                self._frame(
                    HelloAck(1, self.relay_id, 1024 * 1024, self.heartbeat_seconds, ("BYTE_RELAY",)),
                    request_id=frame.request_id,
                    call_id=ZERO_ID,
                    session_id=ZERO_ID,
                )
            ]

        if self.state == RelaySessionState.HELLO_DONE:
            if frame.kind != FrameKind.AUTH_BEGIN:
                raise ProtocolError("AUTH_BEGIN required after HELLO_ACK")
            decode_payload(frame.kind, frame.payload)
            assert self.endpoint_id is not None
            challenge = self.authenticator.begin(self.endpoint_id, self.relay_id)
            self.state = RelaySessionState.AUTH_CHALLENGE_SENT
            return [
                self._frame(
                    challenge,
                    request_id=frame.request_id,
                    call_id=ZERO_ID,
                    session_id=ZERO_ID,
                )
            ]

        if self.state == RelaySessionState.AUTH_CHALLENGE_SENT:
            if frame.kind != FrameKind.AUTH_RESPONSE:
                raise ProtocolError("AUTH_RESPONSE required after AUTH_CHALLENGE")
            response = decode_payload(frame.kind, frame.payload)
            if not isinstance(response, AuthResponse):
                raise ProtocolError("invalid AUTH_RESPONSE")
            if not self.authenticator.verify(response.proof):
                self.state = RelaySessionState.FAILED
                return [
                    self._frame(
                        AuthFail(AUTH_FAILED_REASON),
                        request_id=frame.request_id,
                        call_id=ZERO_ID,
                        session_id=ZERO_ID,
                    )
                ]
            self.state = RelaySessionState.AUTHENTICATED
            assert self.endpoint_id is not None
            return [
                self._frame(
                    AuthOk(self.endpoint_id, (("dial", "allowed"),)),
                    request_id=frame.request_id,
                    call_id=ZERO_ID,
                    session_id=ZERO_ID,
                )
            ]

        if self.state == RelaySessionState.AUTHENTICATED:
            if frame.kind != FrameKind.DIAL_REQUEST:
                raise ProtocolError("DIAL_REQUEST required while authenticated and idle")
            if frame.call_id == ZERO_ID:
                raise ProtocolError("DIAL_REQUEST requires nonzero call_id")
            if frame.session_id != ZERO_ID:
                raise ProtocolError("DIAL_REQUEST session_id must be zero before assignment")
            request = decode_payload(frame.kind, frame.payload)
            if not isinstance(request, DialRequest):
                raise ProtocolError("invalid DIAL_REQUEST")
            if request.requested_mode != Mode.BYTE_RELAY:
                return [
                    self._frame(
                        DialFailed(frame.call_id, DialFailure.UNSUPPORTED_MODE, False, "prototype supports BYTE_RELAY only"),
                        request_id=frame.request_id,
                        call_id=frame.call_id,
                        session_id=ZERO_ID,
                    )
                ]
            self.call_id = frame.call_id
            self.session_id = self.session_id_factory()
            if len(self.session_id) != 16 or self.session_id == ZERO_ID:
                raise RuntimeError("session_id_factory must return nonzero 16-byte IDs")
            self._inbound = ByteRelayReceiver()
            self._outbound = ByteRelaySender()
            self._terminal_sent = False
            try:
                self.backend.open()
                self.backend.dial(request.target, {"dial_timeout_ms": request.dial_timeout_ms})
            except Exception:
                # Gateway could not take the call at all: S1 GATEWAY_UNAVAILABLE.
                failed = self._frame(
                    DialFailed(self.call_id, DialFailure.GATEWAY_UNAVAILABLE, True, DETAIL_BACKEND_FAILED),
                    request_id=frame.request_id,
                    session_id=ZERO_ID,
                )
                self.last_call_failure = LinkFailure("BACKEND_UNAVAILABLE", DETAIL_BACKEND_FAILED)
                self._release_backend()
                self._reset_call()
                return [failed]
            self.state = RelaySessionState.DIALING
            accepted = self._frame(
                DialAccepted(self.call_id, self.session_id, self.gateway_id, Mode.BYTE_RELAY),
                request_id=frame.request_id,
            )
            self._dial_accepted_frame = accepted
            return [accepted, *self.poll()]

        if self.state in {RelaySessionState.DIALING, RelaySessionState.CONNECTED}:
            if frame.kind == FrameKind.DIAL_REQUEST:
                if frame.call_id != self.call_id:
                    raise ProtocolError("call_id does not match active call")
                if frame.session_id != ZERO_ID:
                    raise ProtocolError("duplicate DIAL_REQUEST session_id must remain zero")
                decode_payload(frame.kind, frame.payload)
                if self._dial_accepted_frame is None:
                    raise ProtocolError("duplicate dial has no prior status")
                return [self._dial_accepted_frame]

            self._require_ids(frame)

            if frame.kind == FrameKind.FLOW_STATUS:
                status = decode_payload(frame.kind, frame.payload)
                self._outbound.update_flow(status)
                return [self._frame(message) for message in self._outbound.drain()]

            if frame.kind == FrameKind.HANGUP_REQUEST:
                decode_payload(frame.kind, frame.payload)
                ack = self._frame(HangupAck(), request_id=frame.request_id)
                self.backend.hangup("LOCAL_HANGUP")
                return [ack, *self.poll()]

            if frame.kind == FrameKind.DATA_BYTES:
                if self.state != RelaySessionState.CONNECTED:
                    raise ProtocolError("DATA_BYTES not allowed before CONNECTED")
                message = decode_payload(frame.kind, frame.payload)
                data = self._inbound.accept(message)
                try:
                    self.backend.write(data)
                except Exception:
                    return self._backend_failed()
                out = self.poll()
                out.append(self._frame(FlowStatus(DEFAULT_RECEIVE_WINDOW, 0)))
                return out

            raise ProtocolError(f"frame kind {frame.kind.name} not allowed in active call")

        if self.state == RelaySessionState.FAILED:
            raise ProtocolError("session is failed")
        raise ProtocolError(f"unhandled relay state {self.state}")

    @property
    def call_active(self) -> bool:
        return self.state in {RelaySessionState.DIALING, RelaySessionState.CONNECTED}

    @property
    def authenticated(self) -> bool:
        return self.state in {RelaySessionState.AUTHENTICATED, RelaySessionState.DIALING, RelaySessionState.CONNECTED}

    def _release_backend(self) -> None:
        try:
            self.backend.close()
        except Exception:
            pass

    def _backend_failed(self) -> list[Frame]:
        """The gateway/backend died mid-call: terminate the call, keep the control session."""
        failure = LinkFailure("BACKEND_UNAVAILABLE", DETAIL_BACKEND_FAILED)
        out: list[Frame] = []
        if not self._terminal_sent:
            out.append(self._frame(CallTerminated(failure.reason, TerminationSource.GATEWAY, failure.detail)))
            self._terminal_sent = True
        self.last_call_failure = failure
        self._release_backend()
        self._reset_call(keep_terminal=True)
        return out

    def abort(self, failure: LinkFailure) -> None:
        """The control connection is gone: release any backend lease and fail the session."""
        if self.call_active:
            self.last_call_failure = failure
            try:
                self.backend.hangup(failure.reason)
            except Exception:
                pass
        self._release_backend()
        self._reset_call()
        self.state = RelaySessionState.FAILED

    def poll(self) -> list[Frame]:
        if not self.call_active:
            return []
        try:
            events = self.backend.poll_events()
        except Exception:
            return self._backend_failed()
        out: list[Frame] = []
        for event in events:
            if event.type == BackendEventType.PROGRESS:
                out.append(self._frame(CallProgress(ProgressPhase.DIALING, event.detail)))
            elif event.type == BackendEventType.CONNECTED:
                self.state = RelaySessionState.CONNECTED
                out.append(self._frame(CallProgress(ProgressPhase.CONNECTED, event.detail)))
                out.append(self._frame(FlowStatus(DEFAULT_RECEIVE_WINDOW, 0)))
            elif event.type == BackendEventType.DATA:
                try:
                    messages = self._outbound.build(event.data)
                except FlowControlBlocked as exc:
                    raise ProtocolError("BYTE_RELAY outbound pending limit exceeded") from exc
                out.extend(self._frame(message) for message in messages)
            elif event.type == BackendEventType.FAILED:
                mapping = {
                    "BUSY": DialFailure.BUSY,
                    "NO_ANSWER": DialFailure.NO_ANSWER,
                    "NO_DIALTONE": DialFailure.NO_DIALTONE,
                }
                reason = mapping.get(event.detail or "", DialFailure.INTERNAL_ERROR)
                out.append(self._frame(DialFailed(self.call_id, reason, False, event.detail)))
                self._reset_call()
            elif event.type == BackendEventType.HANGUP:
                if not self._terminal_sent:
                    out.append(
                        self._frame(
                            CallTerminated(event.detail or "REMOTE_HANGUP", TerminationSource.GATEWAY, None)
                        )
                    )
                    self._terminal_sent = True
                self._reset_call(keep_terminal=True)
        return out

    def _reset_call(self, *, keep_terminal: bool = False) -> None:
        self.state = RelaySessionState.AUTHENTICATED
        self.call_id = ZERO_ID
        self.session_id = ZERO_ID
        self._inbound = ByteRelayReceiver()
        self._outbound = ByteRelaySender()
        self._dial_accepted_frame = None
        if not keep_terminal:
            self._terminal_sent = False
