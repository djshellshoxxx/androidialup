from __future__ import annotations

import asyncio
import ssl
from collections.abc import Callable
from contextlib import suppress

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import Frame, FrameKind, ProtocolError
from androidialup_protocol.liveness import (
    DEFAULT_TIMEOUTS,
    DETAIL_AUTH_TIMEOUT,
    DETAIL_HEARTBEAT_TIMEOUT,
    DETAIL_TRANSPORT_CLOSED,
    DETAIL_TRANSPORT_ERROR,
    Clock,
    HeartbeatMonitor,
    LinkFailure,
    TimeoutConfig,
    monotonic,
)
from androidialup_protocol.messages import AuthFail, Ping, decode_payload, encode_payload

from .auth import ChallengeResponseAuthenticator, DeviceCredentialStore, InMemoryDeviceCredentialStore
from .session import RelaySession, RelaySessionState


class RelayTcpServer:
    def __init__(
        self,
        host: str,
        port: int,
        ssl_context: ssl.SSLContext,
        *,
        session_factory: Callable[[], RelaySession] | None = None,
        credential_store: DeviceCredentialStore | None = None,
        timeouts: TimeoutConfig = DEFAULT_TIMEOUTS,
        clock: Clock = monotonic,
        backend_poll_interval: float = 0.05,
    ) -> None:
        if session_factory is not None and credential_store is not None:
            raise ValueError("pass either session_factory or credential_store, not both")
        self.host = host
        self.port = port
        self.ssl_context = ssl_context
        if session_factory is None:
            # One authenticator (and therefore one pending nonce) per client session.
            store = credential_store if credential_store is not None else InMemoryDeviceCredentialStore()
            session_factory = lambda: RelaySession(  # noqa: E731
                authenticator=ChallengeResponseAuthenticator(store),
                heartbeat_seconds=timeouts.heartbeat_seconds_advertised,
            )
        self.session_factory = session_factory
        self.timeouts = timeouts
        self.clock = clock
        self.backend_poll_interval = backend_poll_interval
        #: One S1 terminal reason per finished client connection, in close order.
        self.close_records: list[LinkFailure] = []
        #: Live client connections (diagnostics and failure injection).
        self.connections: set[AsyncFramedConnection] = set()
        self._server: asyncio.AbstractServer | None = None
        self._client_tasks: set[asyncio.Task] = set()

    @property
    def address(self) -> tuple[str, int]:
        if self._server is None or not self._server.sockets:
            raise RuntimeError("relay server is not started")
        host, port = self._server.sockets[0].getsockname()[:2]
        return str(host), int(port)

    async def start(self) -> None:
        if self._server is not None:
            return
        self._server = await asyncio.start_server(
            self._handle_client,
            self.host,
            self.port,
            ssl=self.ssl_context,
        )

    async def close(self) -> None:
        server = self._server
        self._server = None
        if server is not None:
            server.close()
        # Cancel client tasks before wait_closed(): since Python 3.12
        # Server.wait_closed() also waits for every accepted connection.
        tasks = [task for task in self._client_tasks if task is not asyncio.current_task()]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._client_tasks.clear()
        if server is not None:
            await server.wait_closed()

    async def _handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        if task is not None:
            self._client_tasks.add(task)
        connection = AsyncFramedConnection(reader, writer)
        self.connections.add(connection)
        session = self.session_factory()
        failure: LinkFailure | None = None
        try:
            failure = await self._serve(connection, session)
        except asyncio.CancelledError:
            failure = LinkFailure("RELAY_UNAVAILABLE", "RELAY_SHUTDOWN")
            raise
        finally:
            if failure is None:
                failure = LinkFailure("INTERNAL_ERROR")
            session.abort(failure)
            self.close_records.append(failure)
            self.connections.discard(connection)
            if failure.detail in {DETAIL_HEARTBEAT_TIMEOUT, DETAIL_TRANSPORT_ERROR}:
                connection.abort()  # peer unresponsive or transport broken
            else:
                await connection.close(grace=self.timeouts.disconnect_grace)
            if task is not None:
                self._client_tasks.discard(task)

    async def _serve(self, connection: AsyncFramedConnection, session: RelaySession) -> LinkFailure:
        """Run one client connection; return its single terminal reason."""
        timeouts = self.timeouts
        auth_deadline = self.clock() + timeouts.relay_auth
        monitor: HeartbeatMonitor | None = None
        recv_task: asyncio.Task | None = None
        try:
            while True:
                now = self.clock()
                if session.authenticated:
                    if monitor is None:
                        monitor = HeartbeatMonitor(timeouts.heartbeat_interval, timeouts.heartbeat_failure, now)
                    deadline = monitor.next_deadline()
                    if session.call_active:
                        deadline = min(deadline, now + self.backend_poll_interval)
                else:
                    deadline = auth_deadline

                if recv_task is None:
                    recv_task = asyncio.ensure_future(connection.recv_frame())
                done, _ = await asyncio.wait({recv_task}, timeout=max(0.0, deadline - now))
                now = self.clock()

                if not done:
                    if not session.authenticated:
                        if now >= auth_deadline:
                            if session.state in {RelaySessionState.HELLO_DONE, RelaySessionState.AUTH_CHALLENGE_SENT}:
                                # S1 section 4: on failure send AUTH_FAIL, flush, close.
                                await connection.send_frame(
                                    Frame(kind=FrameKind.AUTH_FAIL, payload=encode_payload(AuthFail(DETAIL_AUTH_TIMEOUT)))
                                )
                            return LinkFailure("AUTH_FAILURE", DETAIL_AUTH_TIMEOUT)
                        continue
                    assert monitor is not None
                    action = monitor.poll(now)
                    if action.kind == "TIMEOUT":
                        return LinkFailure("NETWORK_LOST", DETAIL_HEARTBEAT_TIMEOUT)
                    if action.kind == "PING":
                        await connection.send_frame(
                            Frame(
                                kind=FrameKind.PING,
                                payload=encode_payload(Ping(action.nonce, int(now * 1000) & 0xFFFFFFFFFFFFFFFF)),
                            )
                        )
                    for response in session.poll():
                        await connection.send_frame(response)
                    continue

                frame = recv_task.result()
                recv_task = None
                if monitor is not None:
                    monitor.on_inbound(now)
                if frame.kind == FrameKind.PONG:
                    pong = decode_payload(frame.kind, frame.payload)
                    if monitor is None:
                        raise ProtocolError("unsolicited PONG")
                    monitor.on_pong(pong.nonce, now)
                    continue
                responses = session.handle_frame(frame)
                for response in responses:
                    await connection.send_frame(response)
                for response in session.poll():
                    await connection.send_frame(response)
                if session.state == RelaySessionState.FAILED:
                    # AUTH_FAIL (or any terminal failure) has been flushed by
                    # send_frame's drain; close without waiting for more input.
                    return LinkFailure("AUTH_FAILURE")
        except ProtocolError as exc:
            return LinkFailure("PROTOCOL_VIOLATION", str(exc) or None)
        except ConnectionClosed:
            return LinkFailure("NETWORK_LOST", DETAIL_TRANSPORT_CLOSED)
        except (ConnectionError, ssl.SSLError, asyncio.IncompleteReadError, OSError):
            return LinkFailure("NETWORK_LOST", DETAIL_TRANSPORT_ERROR)
        finally:
            if recv_task is not None and not recv_task.done():
                recv_task.cancel()
                with suppress(asyncio.CancelledError, Exception):
                    await recv_task
