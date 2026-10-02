from __future__ import annotations

import asyncio
import math
import secrets
import ssl
from contextlib import suppress
from typing import Protocol

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.byte_relay import ByteRelayReceiver, ByteRelaySender
from androidialup_protocol.frame import Frame, FrameKind, ProtocolError, ZERO_ID
from androidialup_protocol.liveness import (
    DEFAULT_TIMEOUTS,
    DETAIL_AUTH_TIMEOUT,
    DETAIL_CONNECT_TIMEOUT,
    DETAIL_DIAL_ACK_TIMEOUT,
    DETAIL_HEARTBEAT_TIMEOUT,
    DETAIL_TRANSPORT_CLOSED,
    DETAIL_TRANSPORT_ERROR,
    Clock,
    HeartbeatMonitor,
    LinkFailure,
    TimeoutConfig,
    monotonic,
)
from androidialup_protocol.messages import (
    AuthBegin,
    AuthChallenge,
    AuthFail,
    AuthResponse,
    CallProgress,
    CallTerminated,
    DataBytes,
    DialAccepted,
    DialFailed,
    DialFailure,
    DialRequest,
    FlowStatus,
    HangupRequest,
    Hello,
    HelloAck,
    Mode,
    NetworkTransport,
    Ping,
    Pong,
    ProgressPhase,
    decode_payload,
    encode_payload,
    kind_for_message,
)
from androidialup_relay.auth import DEVICE_HMAC_SHA256_V1, MIN_NONCE_LENGTH, compute_proof

from .at_engine import ResultCode


class ControllerCallbacks(Protocol):
    def on_dial_result(self, result: ResultCode, reason: str | None = None) -> None: ...
    def on_remote_data(self, data: bytes) -> None: ...
    def on_remote_hangup(self, reason: str = "REMOTE_HANGUP", detail: str | None = None) -> None: ...


class RelayLinkError(Exception):
    """The relay link could not be established; carries one S1 taxonomy reason."""

    def __init__(self, failure: LinkFailure, message: str | None = None) -> None:
        super().__init__(message or (failure.reason if failure.detail is None else f"{failure.reason}: {failure.detail}"))
        self.failure = failure


class RelayAuthenticationError(RelayLinkError):
    """The relay answered AUTH_FAIL (maps to the AUTH_FAILURE error class)."""

    def __init__(self, message: str = "relay rejected device credential", *, detail: str | None = None) -> None:
        super().__init__(LinkFailure("AUTH_FAILURE", detail), message)


# DIAL_FAILED enum -> (DTE result, S1 taxonomy reason).
_DIAL_FAILURE_MAP = {
    DialFailure.BUSY: (ResultCode.BUSY, "BACKEND_BUSY"),
    DialFailure.NO_DIALTONE: (ResultCode.NO_DIALTONE, "BACKEND_NO_DIALTONE"),
    DialFailure.NO_ANSWER: (ResultCode.NO_ANSWER, "BACKEND_NO_ANSWER"),
    DialFailure.NO_ROUTE: (ResultCode.NO_CARRIER, "NO_ROUTE"),
    DialFailure.GATEWAY_UNAVAILABLE: (ResultCode.NO_CARRIER, "BACKEND_UNAVAILABLE"),
    DialFailure.AUTHORIZATION_DENIED: (ResultCode.NO_CARRIER, "AUTH_FAILURE"),
    DialFailure.UNSUPPORTED_MODE: (ResultCode.NO_CARRIER, "LOCAL_CONFIG"),
    DialFailure.TIMEOUT: (ResultCode.NO_CARRIER, "BACKEND_NO_ANSWER"),
    DialFailure.INTERNAL_ERROR: (ResultCode.NO_CARRIER, "INTERNAL_ERROR"),
}


class RelaySessionPort:
    """SessionPort adapter that maps modem operations onto ADUP over TLS."""

    def __init__(
        self,
        host: str,
        port: int,
        ssl_context: ssl.SSLContext,
        *,
        server_hostname: str,
        endpoint_id: bytes,
        device_secret: bytes,
        network_transport: NetworkTransport = NetworkTransport.WIFI,
        timeouts: TimeoutConfig = DEFAULT_TIMEOUTS,
        clock: Clock = monotonic,
        heartbeat_interval: float | None = None,
    ) -> None:
        if len(endpoint_id) != 32:
            raise ValueError("endpoint_id must be exactly 32 bytes")
        if not device_secret:
            raise ValueError("device_secret must not be empty")
        # Kept private and never surfaced in diagnostics, str() or repr().
        self._device_secret = bytes(device_secret)
        self.host = host
        self.port = port
        self.ssl_context = ssl_context
        self.server_hostname = server_hostname
        self.endpoint_id = bytes(endpoint_id)
        self.network_transport = network_transport
        self.relay_id: str | None = None
        self.controller: ControllerCallbacks | None = None
        self.connection: AsyncFramedConnection | None = None
        self._reader_task: asyncio.Task | None = None
        self._tx_task: asyncio.Task | None = None
        self._tx_queue: asyncio.Queue[Frame | None] = asyncio.Queue(maxsize=256)
        self._request_id = 10
        self._call_id = ZERO_ID
        self._session_id = ZERO_ID
        self._sender = ByteRelaySender()
        self._receiver = ByteRelayReceiver()
        self._connected = False
        self._terminal_notified = False
        self._closed = False
        self.timeouts = timeouts
        self.clock = clock
        # None: use HELLO_ACK.heartbeat_seconds (S1 section 9 "negotiated interval").
        self._heartbeat_override = heartbeat_interval
        self.heartbeat_interval: float | None = None
        self._monitor: HeartbeatMonitor | None = None
        self._dial_deadline: float | None = None
        self._timers_changed = asyncio.Event()
        self._liveness_task: asyncio.Task | None = None
        #: First terminal reason of the relay link, once it has failed.
        self.link_failure: LinkFailure | None = None
        self.heartbeat_stats = {"pings_sent": 0, "pongs_received": 0, "pongs_sent": 0}

    def bind_controller(self, controller: ControllerCallbacks) -> None:
        self.controller = controller

    def _next_request_id(self) -> int:
        self._request_id = (self._request_id + 1) & 0xFFFFFFFF
        if self._request_id == 0:
            self._request_id = 1
        return self._request_id

    def _frame(self, message, *, request_id: int = 0, call_id: bytes | None = None, session_id: bytes | None = None) -> Frame:
        return Frame(
            kind=kind_for_message(message),
            call_id=self._call_id if call_id is None else call_id,
            session_id=self._session_id if session_id is None else session_id,
            request_id=request_id,
            payload=encode_payload(message),
        )

    async def start(self) -> None:
        if self.connection is not None:
            return
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(
                    self.host,
                    self.port,
                    ssl=self.ssl_context,
                    server_hostname=self.server_hostname,
                ),
                self.timeouts.connect,
            )
        except asyncio.TimeoutError as exc:
            raise RelayLinkError(LinkFailure("CONNECT_TIMEOUT", DETAIL_CONNECT_TIMEOUT)) from exc
        connection = AsyncFramedConnection(reader, writer)
        try:
            try:
                hello_ack = await asyncio.wait_for(self._handshake(connection), self.timeouts.relay_auth)
            except asyncio.TimeoutError as exc:
                connection.abort()  # silent relay: do not wait for a TLS close exchange
                raise RelayLinkError(LinkFailure("AUTH_FAILURE", DETAIL_AUTH_TIMEOUT)) from exc
        except BaseException:
            await connection.close(grace=self.timeouts.disconnect_grace)
            raise

        if self._heartbeat_override is not None:
            interval = float(self._heartbeat_override)
        elif hello_ack.heartbeat_seconds > 0:
            interval = float(hello_ack.heartbeat_seconds)
        else:
            interval = math.inf  # heartbeat disabled by relay; PINGs are still answered
        self.heartbeat_interval = interval
        now = self.clock()
        self._monitor = (
            HeartbeatMonitor(interval, self.timeouts.heartbeat_failure, now) if math.isfinite(interval) else None
        )
        self.connection = connection
        self.link_failure = None
        self._closed = False
        self._tx_task = asyncio.create_task(self._writer_loop())
        self._reader_task = asyncio.create_task(self._reader_loop())
        self._liveness_task = asyncio.create_task(self._liveness_loop())

    @staticmethod
    def _raise_if_auth_fail(frame: Frame) -> None:
        if frame.kind == FrameKind.AUTH_FAIL:
            detail = decode_payload(frame.kind, frame.payload)
            reason = detail.reason if isinstance(detail, AuthFail) else "unspecified"
            raise RelayAuthenticationError(
                f"relay rejected device credential: {reason}",
                detail=DETAIL_AUTH_TIMEOUT if reason == DETAIL_AUTH_TIMEOUT else None,
            )

    async def _handshake(self, connection: AsyncFramedConnection) -> HelloAck:
        await connection.send_frame(
            self._frame(
                Hello("AndroidDialup", "0.1", 1, 1, self.endpoint_id, ("BYTE_RELAY",)),
                request_id=1,
                call_id=ZERO_ID,
                session_id=ZERO_ID,
            )
        )
        hello_ack_frame = await connection.recv_frame()
        if hello_ack_frame.kind != FrameKind.HELLO_ACK:
            raise ProtocolError("relay did not return HELLO_ACK")
        hello_ack = decode_payload(hello_ack_frame.kind, hello_ack_frame.payload)
        if not isinstance(hello_ack, HelloAck):
            raise ProtocolError("invalid HELLO_ACK")
        self.relay_id = hello_ack.relay_id

        await connection.send_frame(
            self._frame(AuthBegin(), request_id=2, call_id=ZERO_ID, session_id=ZERO_ID)
        )
        challenge_frame = await connection.recv_frame()
        self._raise_if_auth_fail(challenge_frame)
        if challenge_frame.kind != FrameKind.AUTH_CHALLENGE:
            raise ProtocolError("relay did not return AUTH_CHALLENGE")
        challenge = decode_payload(challenge_frame.kind, challenge_frame.payload)
        if not isinstance(challenge, AuthChallenge):
            raise ProtocolError("invalid AUTH_CHALLENGE")
        if challenge.method != DEVICE_HMAC_SHA256_V1:
            raise ProtocolError(f"unsupported relay auth method {challenge.method!r}")
        if len(challenge.nonce) < MIN_NONCE_LENGTH:
            raise ProtocolError("relay auth nonce is too short")
        proof = compute_proof(self._device_secret, challenge.nonce, self.endpoint_id, hello_ack.relay_id)

        await connection.send_frame(
            self._frame(AuthResponse(proof), request_id=3, call_id=ZERO_ID, session_id=ZERO_ID)
        )
        auth = await connection.recv_frame()
        self._raise_if_auth_fail(auth)
        if auth.kind != FrameKind.AUTH_OK:
            raise ProtocolError("relay did not return AUTH_OK")
        return hello_ack

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._tx_queue.put_nowait(None)
        except asyncio.QueueFull:
            pass
        await self._stop_transport()

    async def _stop_transport(self, *, abort: bool = False) -> None:
        current = asyncio.current_task()
        tasks = [
            task
            for task in (self._reader_task, self._tx_task, self._liveness_task)
            if task is not None and task is not current
        ]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._reader_task = None
        self._tx_task = None
        self._liveness_task = None
        if self.connection is not None:
            if abort:
                self.connection.abort()
            else:
                with suppress(Exception):
                    await self.connection.close(grace=self.timeouts.disconnect_grace)
            self.connection = None

    def _link_failed(self, failure: LinkFailure) -> None:
        """The relay link is gone: one terminal reason, NO CARRIER for any call, close transport."""
        if self.link_failure is not None or self._closed:
            return
        self.link_failure = failure
        self._dial_deadline = None
        if self._call_id != ZERO_ID:
            self._notify_terminal(failure.reason, failure.detail)
        self._closed = True
        # The peer is presumed dead or misbehaving: no TLS close_notify exchange.
        asyncio.get_running_loop().create_task(self._stop_transport(abort=True))

    async def _liveness_loop(self) -> None:
        """Heartbeat and DIAL_REQUEST-acknowledgement deadlines (monotonic)."""
        while True:
            deadlines = []
            if self._monitor is not None:
                deadlines.append(self._monitor.next_deadline())
            if self._dial_deadline is not None:
                deadlines.append(self._dial_deadline)
            delay = None if not deadlines else max(0.0, min(deadlines) - self.clock())
            self._timers_changed.clear()
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(self._timers_changed.wait(), delay)
            now = self.clock()
            if self._dial_deadline is not None and now >= self._dial_deadline:
                self._link_failed(LinkFailure("RELAY_UNAVAILABLE", DETAIL_DIAL_ACK_TIMEOUT))
                return
            if self._monitor is None:
                continue
            action = self._monitor.poll(now)
            if action.kind == "TIMEOUT":
                self._link_failed(LinkFailure("NETWORK_LOST", DETAIL_HEARTBEAT_TIMEOUT))
                return
            if action.kind == "PING":
                self.heartbeat_stats["pings_sent"] += 1
                try:
                    self._enqueue(
                        Frame(
                            kind=FrameKind.PING,
                            payload=encode_payload(Ping(action.nonce, int(now * 1000) & 0xFFFFFFFFFFFFFFFF)),
                        )
                    )
                except BufferError:
                    # S1_SPEC_FREEZE section 6: control queue overflow is fatal.
                    self._link_failed(LinkFailure("QUEUE_OVERFLOW", "RELAY_TX_QUEUE"))
                    return

    def _enqueue(self, frame: Frame) -> None:
        if self.connection is None or self._closed:
            raise RuntimeError("relay connection is not started")
        try:
            self._tx_queue.put_nowait(frame)
        except asyncio.QueueFull as exc:
            raise BufferError("relay transmit queue is full") from exc

    async def _writer_loop(self) -> None:
        connection = self.connection
        assert connection is not None
        try:
            while True:
                frame = await self._tx_queue.get()
                if frame is None:
                    return
                await connection.send_frame(frame)
        except ConnectionClosed:
            self._link_failed(LinkFailure("NETWORK_LOST", DETAIL_TRANSPORT_CLOSED))
        except (ConnectionError, ssl.SSLError, OSError):
            self._link_failed(LinkFailure("NETWORK_LOST", DETAIL_TRANSPORT_ERROR))

    async def _reader_loop(self) -> None:
        connection = self.connection
        assert connection is not None
        try:
            while True:
                frame = await connection.recv_frame()
                self._handle_inbound(frame)
        except ProtocolError as exc:
            self._link_failed(LinkFailure("PROTOCOL_VIOLATION", str(exc) or None))
        except ConnectionClosed:
            self._link_failed(LinkFailure("NETWORK_LOST", DETAIL_TRANSPORT_CLOSED))
        except (ConnectionError, ssl.SSLError, asyncio.IncompleteReadError, OSError):
            self._link_failed(LinkFailure("NETWORK_LOST", DETAIL_TRANSPORT_ERROR))
        except Exception as exc:  # never let the reader die silently
            self._link_failed(LinkFailure("INTERNAL_ERROR", type(exc).__name__))

    def _handle_inbound(self, frame: Frame) -> None:
        now = self.clock()
        if self._monitor is not None:
            self._monitor.on_inbound(now)
        message = decode_payload(frame.kind, frame.payload)

        if isinstance(message, Ping):
            self.heartbeat_stats["pongs_sent"] += 1
            self._enqueue(
                Frame(
                    kind=FrameKind.PONG,
                    call_id=frame.call_id,
                    session_id=frame.session_id,
                    request_id=frame.request_id,
                    payload=encode_payload(Pong(message.nonce)),
                )
            )
            return

        if isinstance(message, Pong):
            if self._monitor is None:
                raise ProtocolError("unsolicited PONG")
            self._monitor.on_pong(message.nonce, now)
            self.heartbeat_stats["pongs_received"] += 1
            self._timers_changed.set()
            return

        if isinstance(message, DialAccepted):
            if frame.call_id != self._call_id:
                raise ProtocolError("DIAL_ACCEPTED call_id mismatch")
            self._session_id = message.assigned_session_id
            self._clear_dial_deadline()
            self._sender = ByteRelaySender()
            self._receiver = ByteRelayReceiver()
            return

        if isinstance(message, DialFailed):
            if frame.call_id != self._call_id:
                raise ProtocolError("DIAL_FAILED call_id mismatch")
            self._clear_dial_deadline()
            result, reason = _DIAL_FAILURE_MAP.get(message.reason, (ResultCode.NO_CARRIER, "INTERNAL_ERROR"))
            if self.controller is not None:
                self.controller.on_dial_result(result, reason)
            self._reset_call()
            return

        if isinstance(message, CallProgress):
            if message.phase == ProgressPhase.CONNECTED:
                self._connected = True
                if self.controller is not None:
                    self.controller.on_dial_result(ResultCode.CONNECT)
            return

        if isinstance(message, FlowStatus):
            self._sender.update_flow(message)
            for data_message in self._sender.drain():
                self._enqueue(self._frame(data_message))
            return

        if isinstance(message, DataBytes):
            if frame.call_id != self._call_id or frame.session_id != self._session_id:
                raise ProtocolError("DATA_BYTES call/session mismatch")
            data = self._receiver.accept(message)
            if self.controller is not None:
                self.controller.on_remote_data(data)
            self._enqueue(self._frame(FlowStatus(256 * 1024, 0)))
            return

        if isinstance(message, CallTerminated):
            self._notify_terminal(message.reason, message.diagnostic_code)
            return

    def _clear_dial_deadline(self) -> None:
        if self._dial_deadline is not None:
            self._dial_deadline = None
            self._timers_changed.set()

    def _notify_terminal(self, reason: str, detail: str | None = None) -> None:
        if self._terminal_notified:
            return
        self._terminal_notified = True
        self._dial_deadline = None
        if self.controller is not None:
            if detail is None:
                self.controller.on_remote_hangup(reason)
            else:
                self.controller.on_remote_hangup(reason, detail)
        self._reset_call(preserve_terminal=True)

    def _reset_call(self, *, preserve_terminal: bool = False) -> None:
        self._call_id = ZERO_ID
        self._session_id = ZERO_ID
        self._connected = False
        self._sender = ByteRelaySender()
        self._receiver = ByteRelayReceiver()
        if not preserve_terminal:
            self._terminal_notified = False

    def dial(self, target: str) -> None:
        if self._call_id != ZERO_ID:
            raise RuntimeError("a call is already active")
        if self.link_failure is not None:
            # The link already failed: the dial ends with that same reason, delivered
            # asynchronously so the controller is never re-entered from dial().
            failure = self.link_failure
            self._call_id = secrets.token_bytes(16)
            self._terminal_notified = False

            def fail() -> None:
                self._notify_terminal(failure.reason, failure.detail)
                self._reset_call()

            asyncio.get_running_loop().call_soon(fail)
            return
        self._call_id = secrets.token_bytes(16)
        self._session_id = ZERO_ID
        self._connected = False
        self._terminal_notified = False
        request = DialRequest(
            target,
            Mode.BYTE_RELAY,
            self.network_transport,
            ("BYTE_RELAY",),
            60000,
        )
        self._enqueue(
            self._frame(
                request,
                request_id=self._next_request_id(),
                call_id=self._call_id,
                session_id=ZERO_ID,
            )
        )
        self._dial_deadline = self.clock() + self.timeouts.dial_ack
        self._timers_changed.set()

    def write_data(self, data: bytes) -> None:
        if not self._connected or self._session_id == ZERO_ID:
            raise RuntimeError("call is not connected")
        for message in self._sender.build(bytes(data)):
            self._enqueue(self._frame(message))

    def hangup(self, reason: str) -> None:
        if self._call_id == ZERO_ID or self._session_id == ZERO_ID:
            return
        self._enqueue(
            self._frame(HangupRequest(reason), request_id=self._next_request_id())
        )

    def answer(self) -> None:
        raise NotImplementedError("incoming calls are not implemented in I1")
