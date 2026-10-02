from __future__ import annotations

import asyncio
import secrets
import ssl
from typing import Protocol

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.byte_relay import ByteRelayReceiver, ByteRelaySender
from androidialup_protocol.frame import Frame, FrameKind, ProtocolError, ZERO_ID
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
    ProgressPhase,
    decode_payload,
    encode_payload,
    kind_for_message,
)
from androidialup_relay.auth import DEVICE_HMAC_SHA256_V1, MIN_NONCE_LENGTH, compute_proof

from .at_engine import ResultCode


class ControllerCallbacks(Protocol):
    def on_dial_result(self, result: ResultCode) -> None: ...
    def on_remote_data(self, data: bytes) -> None: ...
    def on_remote_hangup(self, reason: str = "REMOTE_HANGUP") -> None: ...


class RelayAuthenticationError(Exception):
    """The relay answered AUTH_FAIL (maps to the AUTH_FAILURE error class)."""


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
        reader, writer = await asyncio.open_connection(
            self.host,
            self.port,
            ssl=self.ssl_context,
            server_hostname=self.server_hostname,
        )
        connection = AsyncFramedConnection(reader, writer)
        try:
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
            if auth.kind == FrameKind.AUTH_FAIL:
                detail = decode_payload(auth.kind, auth.payload)
                reason = detail.reason if isinstance(detail, AuthFail) else "unspecified"
                raise RelayAuthenticationError(f"relay rejected device credential: {reason}")
            if auth.kind != FrameKind.AUTH_OK:
                raise ProtocolError("relay did not return AUTH_OK")
        except Exception:
            await connection.close()
            raise

        self.connection = connection
        self._closed = False
        self._tx_task = asyncio.create_task(self._writer_loop())
        self._reader_task = asyncio.create_task(self._reader_loop())

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._tx_queue.put_nowait(None)
        except asyncio.QueueFull:
            pass
        tasks = [task for task in (self._reader_task, self._tx_task) if task is not None]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._reader_task = None
        self._tx_task = None
        if self.connection is not None:
            await self.connection.close()
            self.connection = None

    def _enqueue(self, frame: Frame) -> None:
        if self.connection is None or self._closed:
            raise RuntimeError("relay connection is not started")
        try:
            self._tx_queue.put_nowait(frame)
        except asyncio.QueueFull as exc:
            raise BufferError("relay transmit queue is full") from exc

    async def _writer_loop(self) -> None:
        assert self.connection is not None
        while True:
            frame = await self._tx_queue.get()
            if frame is None:
                return
            await self.connection.send_frame(frame)

    async def _reader_loop(self) -> None:
        assert self.connection is not None
        try:
            while True:
                frame = await self.connection.recv_frame()
                self._handle_inbound(frame)
        except (ConnectionClosed, ConnectionError, ssl.SSLError, ProtocolError):
            if self.controller is not None and (self._connected or self._call_id != ZERO_ID):
                self._notify_terminal("NETWORK_LOST")

    def _handle_inbound(self, frame: Frame) -> None:
        message = decode_payload(frame.kind, frame.payload)

        if isinstance(message, DialAccepted):
            if frame.call_id != self._call_id:
                raise ProtocolError("DIAL_ACCEPTED call_id mismatch")
            self._session_id = message.assigned_session_id
            self._sender = ByteRelaySender()
            self._receiver = ByteRelayReceiver()
            return

        if isinstance(message, DialFailed):
            mapping = {
                DialFailure.BUSY: ResultCode.BUSY,
                DialFailure.NO_DIALTONE: ResultCode.NO_DIALTONE,
                DialFailure.NO_ANSWER: ResultCode.NO_ANSWER,
            }
            if self.controller is not None:
                self.controller.on_dial_result(mapping.get(message.reason, ResultCode.NO_CARRIER))
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
            self._notify_terminal(message.reason)
            return

    def _notify_terminal(self, reason: str) -> None:
        if self._terminal_notified:
            return
        self._terminal_notified = True
        if self.controller is not None:
            self.controller.on_remote_hangup(reason)
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
