import asyncio
import unittest

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import Frame, FrameKind, ProtocolError, encode_frame


class AsyncFramedConnectionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.servers = []

    async def asyncTearDown(self):
        for server in self.servers:
            server.close()
            await server.wait_closed()

    async def start_server(self, handler):
        server = await asyncio.start_server(handler, "127.0.0.1", 0)
        self.servers.append(server)
        return server.sockets[0].getsockname()[:2]

    async def test_recv_frame_handles_fragmented_tcp_bytes(self):
        frame = Frame(kind=FrameKind.PING, request_id=9, payload=b"abcdef")
        encoded = encode_frame(frame)

        async def handler(reader, writer):
            for part in (encoded[:3], encoded[3:17], encoded[17:-1], encoded[-1:]):
                writer.write(part)
                await writer.drain()
            writer.close()
            await writer.wait_closed()

        host, port = await self.start_server(handler)
        reader, writer = await asyncio.open_connection(host, port)
        connection = AsyncFramedConnection(reader, writer)
        self.assertEqual(await connection.recv_frame(), frame)
        await connection.close()

    async def test_recv_frame_preserves_multiple_frames_from_one_read(self):
        a = Frame(kind=FrameKind.PING, request_id=1)
        b = Frame(kind=FrameKind.PONG, request_id=2)

        async def handler(reader, writer):
            writer.write(encode_frame(a) + encode_frame(b))
            await writer.drain()
            await asyncio.sleep(0.01)
            writer.close()
            await writer.wait_closed()

        host, port = await self.start_server(handler)
        reader, writer = await asyncio.open_connection(host, port)
        connection = AsyncFramedConnection(reader, writer)
        self.assertEqual(await connection.recv_frame(), a)
        self.assertEqual(await connection.recv_frame(), b)
        await connection.close()

    async def test_concurrent_sends_remain_frame_aligned(self):
        received = []
        complete = asyncio.Event()

        async def handler(reader, writer):
            conn = AsyncFramedConnection(reader, writer)
            received.append(await conn.recv_frame())
            received.append(await conn.recv_frame())
            complete.set()
            await conn.close()

        host, port = await self.start_server(handler)
        reader, writer = await asyncio.open_connection(host, port)
        connection = AsyncFramedConnection(reader, writer)
        a = Frame(kind=FrameKind.PING, request_id=1, payload=b"a" * 1000)
        b = Frame(kind=FrameKind.PONG, request_id=2, payload=b"b" * 1000)
        await asyncio.gather(connection.send_frame(a), connection.send_frame(b))
        await asyncio.wait_for(complete.wait(), 1)
        self.assertCountEqual(received, [a, b])
        await connection.close()

    async def test_eof_raises_connection_closed(self):
        async def handler(reader, writer):
            writer.close()
            await writer.wait_closed()

        host, port = await self.start_server(handler)
        reader, writer = await asyncio.open_connection(host, port)
        connection = AsyncFramedConnection(reader, writer)
        with self.assertRaises(ConnectionClosed):
            await connection.recv_frame()
        await connection.close()

    async def test_malformed_frame_propagates_protocol_error(self):
        async def handler(reader, writer):
            writer.write(b"NOPE" + b"\x00" * 50)
            await writer.drain()
            writer.close()
            await writer.wait_closed()

        host, port = await self.start_server(handler)
        reader, writer = await asyncio.open_connection(host, port)
        connection = AsyncFramedConnection(reader, writer)
        with self.assertRaises(ProtocolError):
            await connection.recv_frame()
        await connection.close()


if __name__ == "__main__":
    unittest.main()
