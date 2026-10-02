from __future__ import annotations

import asyncio
import time
from collections.abc import Callable

from .controller import ModemController


class TcpDteServer:
    def __init__(
        self,
        host: str,
        port: int,
        controller_factory: Callable[[Callable[[bytes], None]], ModemController],
    ) -> None:
        self.host = host
        self.port = port
        self.controller_factory = controller_factory
        self._server: asyncio.AbstractServer | None = None
        self._clients: set[asyncio.Task] = set()

    @property
    def bound_port(self) -> int:
        if self._server is None or not self._server.sockets:
            raise RuntimeError("server is not started")
        return int(self._server.sockets[0].getsockname()[1])

    async def start(self) -> None:
        if self._server is not None:
            return
        self._server = await asyncio.start_server(self._handle_client, self.host, self.port)

    async def close(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
        tasks = list(self._clients)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        task = asyncio.current_task()
        if task is not None:
            self._clients.add(task)

        def write_dte(data: bytes) -> None:
            if not writer.is_closing():
                writer.write(bytes(data))

        controller = self.controller_factory(write_dte)
        try:
            while True:
                data = await reader.read(65536)
                if not data:
                    break
                now_ms = time.monotonic_ns() // 1_000_000
                controller.feed_dte(data, now_ms)
                await writer.drain()
        finally:
            controller.on_dte_disconnect()
            if not writer.is_closing():
                writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionError, BrokenPipeError):
                pass
            if task is not None:
                self._clients.discard(task)
