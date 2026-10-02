from __future__ import annotations

import asyncio
import ssl
from collections.abc import Callable

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import ProtocolError

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
    ) -> None:
        if session_factory is not None and credential_store is not None:
            raise ValueError("pass either session_factory or credential_store, not both")
        self.host = host
        self.port = port
        self.ssl_context = ssl_context
        if session_factory is None:
            # One authenticator (and therefore one pending nonce) per client session.
            store = credential_store if credential_store is not None else InMemoryDeviceCredentialStore()
            session_factory = lambda: RelaySession(authenticator=ChallengeResponseAuthenticator(store))  # noqa: E731
        self.session_factory = session_factory
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
            await server.wait_closed()
        tasks = [task for task in self._client_tasks if task is not asyncio.current_task()]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._client_tasks.clear()

    async def _handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        if task is not None:
            self._client_tasks.add(task)
        connection = AsyncFramedConnection(reader, writer)
        session = self.session_factory()
        try:
            while True:
                frame = await connection.recv_frame()
                responses = session.handle_frame(frame)
                for response in responses:
                    await connection.send_frame(response)
                for response in session.poll():
                    await connection.send_frame(response)
                if session.state == RelaySessionState.FAILED:
                    # AUTH_FAIL (or any terminal failure) has been flushed by
                    # send_frame's drain; close without waiting for more input.
                    break
        except (ConnectionClosed, ProtocolError, ConnectionError, ssl.SSLError, asyncio.IncompleteReadError):
            pass
        finally:
            await connection.close()
            if task is not None:
                self._client_tasks.discard(task)
