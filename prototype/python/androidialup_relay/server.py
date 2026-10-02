from __future__ import annotations

import asyncio
import ssl
from collections.abc import Callable

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import ProtocolError

from .session import RelaySession


class RelayTcpServer:
    def __init__(
        self,
        host: str,
        port: int,
        ssl_context: ssl.SSLContext,
        *,
        session_factory: Callable[[], RelaySession] | None = None,
    ) -> None:
        self.host = host
        self.port = port
        self.ssl_context = ssl_context
        self.session_factory = session_factory or RelaySession
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
        except (ConnectionClosed, ProtocolError, ConnectionError, ssl.SSLError, asyncio.IncompleteReadError):
            pass
        finally:
            await connection.close()
            if task is not None:
                self._client_tasks.discard(task)
