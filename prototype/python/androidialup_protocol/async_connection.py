from __future__ import annotations

import asyncio
from collections import deque
from contextlib import suppress

from .frame import MAX_PAYLOAD, Frame, encode_frame
from .stream import FrameStreamDecoder


#: S1_SPEC_FREEZE section 7 clean disconnect grace, seconds.
DEFAULT_CLOSE_GRACE = 3.0


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

    async def close(self, *, grace: float | None = DEFAULT_CLOSE_GRACE) -> None:
        """Orderly close (TLS close_notify), bounded by ``grace`` seconds.

        A dead or hung peer never completes the TLS shutdown; after the grace
        period (S1_SPEC_FREEZE section 7 "clean disconnect grace", 3 s) the
        transport is aborted instead of waiting for the platform's own timeout.
        """
        if self._closed:
            return
        self._closed = True
        self.writer.close()
        try:
            if grace is None:
                await self.writer.wait_closed()
            else:
                await asyncio.wait_for(self.writer.wait_closed(), grace)
        except asyncio.TimeoutError:
            self._abort_transport()
        except Exception:
            pass

    def abort(self) -> None:
        """Drop the connection at once without a TLS close_notify exchange."""
        self._closed = True
        self._abort_transport()

    def _abort_transport(self) -> None:
        transport = getattr(self.writer, "transport", None)
        if transport is not None:
            with suppress(Exception):
                transport.abort()
