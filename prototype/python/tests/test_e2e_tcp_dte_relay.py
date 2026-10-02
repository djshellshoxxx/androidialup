import asyncio
import random
import tempfile
import unittest

from androidialup_modem.controller import ModemController, ModemState
from androidialup_modem.relay_port import RelaySessionPort
from androidialup_modem.tcp_dte import TcpDteServer
from androidialup_relay.auth import InMemoryDeviceCredentialStore
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from tls_test_utils import generate_localhost_certificate


ENDPOINT_ID = b"T" * 32
DEVICE_SECRET = bytes.fromhex("5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e")


class TcpDteRelayEndToEndTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = generate_localhost_certificate(self.tmp.name, prefix="terminal-e2e")
        self.relay = RelayTcpServer(
            "127.0.0.1",
            0,
            create_server_ssl_context(cert, key),
            credential_store=InMemoryDeviceCredentialStore({ENDPOINT_ID: DEVICE_SECRET}),
        )
        await self.relay.start()
        relay_host, relay_port = self.relay.address

        self.session_port = RelaySessionPort(
            relay_host,
            relay_port,
            create_client_ssl_context(cert),
            server_hostname="localhost",
            endpoint_id=ENDPOINT_ID,
            device_secret=DEVICE_SECRET,
        )
        await self.session_port.start()
        self.controller = None

        def controller_factory(write_dte):
            controller = ModemController(self.session_port, write_dte, build_id="e2e-test")
            controller.engine.profile.echo = False
            self.session_port.bind_controller(controller)
            self.controller = controller
            return controller

        self.dte = TcpDteServer("127.0.0.1", 0, controller_factory, timer_tick_ms=5)
        await self.dte.start()
        self.reader, self.writer = await asyncio.open_connection("127.0.0.1", self.dte.bound_port)

    async def asyncTearDown(self):
        self.writer.close()
        await self.writer.wait_closed()
        await self.dte.close()
        await self.session_port.close()
        await self.relay.close()
        self.tmp.cleanup()

    async def command(self, raw: bytes, expected_lines: int = 1):
        self.writer.write(raw)
        await self.writer.drain()
        return [await asyncio.wait_for(self.reader.readuntil(b"\n"), 2.0) for _ in range(expected_lines)]

    async def test_terminal_dial_binary_escape_resume_and_hangup(self):
        self.assertEqual(await self.command(b"AT\r"), [b"OK\r\n"])
        self.assertEqual(await self.command(b"ATS12=1\r"), [b"OK\r\n"])

        self.writer.write(b"ATDloopback\r")
        await self.writer.drain()
        self.assertEqual(await asyncio.wait_for(self.reader.readuntil(b"\n"), 2.0), b"CONNECT\r\n")
        self.assertIsNotNone(self.controller)
        self.assertEqual(self.controller.state, ModemState.ONLINE_DATA)
        self.assertTrue(self.controller.signals.dcd)

        rng = random.Random(0xAD1A1)
        payload = rng.randbytes(64 * 1024)
        self.writer.write(payload)
        await self.writer.drain()
        echoed = await asyncio.wait_for(self.reader.readexactly(len(payload)), 5.0)
        self.assertEqual(echoed, payload)

        # S12=1 means a 20 ms guard interval. Wait for pre-guard, send exactly
        # three escape characters, and let TcpDteServer's monotonic timer satisfy
        # the post-guard without test-only controller access.
        await asyncio.sleep(0.03)
        self.writer.write(b"+++")
        await self.writer.drain()
        self.assertEqual(await asyncio.wait_for(self.reader.readuntil(b"\n"), 1.0), b"OK\r\n")
        self.assertEqual(self.controller.state, ModemState.ONLINE_COMMAND)
        self.assertTrue(self.controller.signals.dcd)

        self.assertEqual(await self.command(b"ATO\r"), [b"CONNECT\r\n"])
        self.assertEqual(self.controller.state, ModemState.ONLINE_DATA)

        await asyncio.sleep(0.03)
        self.writer.write(b"+++")
        await self.writer.drain()
        self.assertEqual(await asyncio.wait_for(self.reader.readuntil(b"\n"), 1.0), b"OK\r\n")
        self.assertEqual(self.controller.state, ModemState.ONLINE_COMMAND)

        self.assertEqual(await self.command(b"ATH\r"), [b"OK\r\n"])
        self.assertEqual(self.controller.state, ModemState.COMMAND)
        self.assertFalse(self.controller.signals.dcd)


if __name__ == "__main__":
    unittest.main()
