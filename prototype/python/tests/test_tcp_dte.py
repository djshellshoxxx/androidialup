import asyncio
import unittest

from androidialup_modem.controller import ModemController
from androidialup_modem.tcp_dte import TcpDteServer


class NullSessionPort:
    def dial(self, target): pass
    def write_data(self, data): pass
    def hangup(self, reason): pass
    def answer(self): pass


class TcpDteServerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.controllers = []

        def factory(write_dte):
            controller = ModemController(NullSessionPort(), write_dte)
            controller.engine.profile.echo = False
            self.controllers.append(controller)
            return controller

        self.server = TcpDteServer("127.0.0.1", 0, factory)
        await self.server.start()
        self.port = self.server.bound_port

    async def asyncTearDown(self):
        await self.server.close()

    async def _connect(self):
        return await asyncio.open_connection("127.0.0.1", self.port)

    async def test_at_returns_ok(self):
        reader, writer = await self._connect()
        writer.write(b"AT\r")
        await writer.drain()
        self.assertEqual(await reader.readuntil(b"\n"), b"OK\r\n")
        writer.close(); await writer.wait_closed()

    async def test_fragmented_and_coalesced_commands(self):
        reader, writer = await self._connect()
        writer.write(b"A")
        await writer.drain()
        await asyncio.sleep(0)
        writer.write(b"T\rATI0\r")
        await writer.drain()
        self.assertEqual(await reader.readuntil(b"\n"), b"OK\r\n")
        self.assertEqual(await reader.readuntil(b"\n"), b"AndroidDialup\r\n")
        self.assertEqual(await reader.readuntil(b"\n"), b"OK\r\n")
        writer.close(); await writer.wait_closed()

    async def test_client_eof_invokes_controller_cleanup(self):
        reader, writer = await self._connect()
        self.assertEqual(len(self.controllers), 1)
        controller = self.controllers[0]
        writer.close(); await writer.wait_closed()
        await asyncio.sleep(0.05)
        self.assertFalse(controller.signals.dcd)

    async def test_simultaneous_clients_have_independent_controllers(self):
        r1, w1 = await self._connect()
        r2, w2 = await self._connect()
        w1.write(b"ATE0\rATI0\r")
        w2.write(b"ATE0\rATI1\r")
        await asyncio.gather(w1.drain(), w2.drain())
        out1 = await r1.readuntil(b"\n"); out1 += await r1.readuntil(b"\n"); out1 += await r1.readuntil(b"\n")
        out2 = await r2.readuntil(b"\n"); out2 += await r2.readuntil(b"\n"); out2 += await r2.readuntil(b"\n")
        self.assertIn(b"AndroidDialup", out1)
        self.assertIn(b"Beta 0.1", out2)
        self.assertEqual(len(self.controllers), 2)
        w1.close(); w2.close(); await asyncio.gather(w1.wait_closed(), w2.wait_closed())


if __name__ == "__main__":
    unittest.main()
