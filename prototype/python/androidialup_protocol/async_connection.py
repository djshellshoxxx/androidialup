from __future__ import annotations

import asyncio
from collections import deque
from contextlib import suppress

from .frame import MAX_PAYLOAD, Frame, encode_frame
from .stream import FrameStreamDecoder


class ConnectionClosed(EOFError):
    pass


class AsyncFramedConnection:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, *, max_payload: int = MAX_PAYLOAD) -> None:
        self.reader = reader
        self.writer = writer
        self.decoder = FrameStreamDecoder(max_payload=max_payload)
        self._ready: deque[Frame] = deque()
        self._write_lock = asyncio.Lock()
        self._closed = False

    async def send_frame(self, frame: Frame) -> None:
        if self._closed:
            raise ConnectionClosed("connection is closed")
        encoded = encode_frame(frame)
        async with self._write_lock:
            self.writer.write(encoded)
            await self.writer.drain()

    async def recv_frame(self) -> Frame:
        if self._ready:
            return self._ready.popleft()
        while True:
            chunk = await self.reader.read(65536)
            if not chunk:
                raise ConnectionClosed("peer closed the connection")
            frames = self.decoder.feed(chunk)
            if frames:
                self._ready.extend(frames)
                return self._ready.popleft()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self.writer.close()
        with suppress(Exception):
            await self.writer.wait_closed()
