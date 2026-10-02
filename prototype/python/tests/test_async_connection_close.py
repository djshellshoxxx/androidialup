import asyncio
import time
import unittest

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import Frame, FrameKind


class _Transport:
    def __init__(self):
        self.aborted = False

    def abort(self):
        self.aborted = True


class HangingWriter:
    """Writer whose close never completes (TLS peer that never answers close_notify)."""

    def __init__(self):
        self.transport = _Transport()
        self.closed = False

    def close(self):
        self.closed = True

    def is_closing(self):
        return self.closed

    async def wait_closed(self):
        await asyncio.Event().wait()


class AsyncConnectionCloseTests(unittest.IsolatedAsyncioTestCase):
    async def test_close_is_bounded_by_grace_then_aborts(self):
        writer = HangingWriter()
        connection = AsyncFramedConnection(asyncio.StreamReader(), writer)
        started = time.monotonic()
        await connection.close(grace=0.05)
        self.assertLess(time.monotonic() - started, 0.5)
        self.assertTrue(writer.closed)
        self.assertTrue(writer.transport.aborted)

    async def test_abort_is_immediate_and_idempotent(self):
        writer = HangingWriter()
        connection = AsyncFramedConnection(asyncio.StreamReader(), writer)
        connection.abort()
        connection.abort()
        self.assertTrue(writer.transport.aborted)
        await connection.close()  # already closed: returns at once
        with self.assertRaises(ConnectionClosed):
            await connection.send_frame(Frame(kind=FrameKind.PING))


if __name__ == "__main__":
    unittest.main()
