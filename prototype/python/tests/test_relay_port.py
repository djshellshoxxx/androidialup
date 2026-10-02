import asyncio
import tempfile
import unittest

from androidialup_modem.at_engine import ResultCode
from androidialup_modem.relay_port import RelaySessionPort
from androidialup_relay.auth import InMemoryDeviceCredentialStore
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from tls_test_utils import generate_localhost_certificate


class FakeController:
    def __init__(self):
        self.dial_results = []
        self.remote_data = []
        self.remote_hangups = []

    def on_dial_result(self, result):
        self.dial_results.append(result)

    def on_remote_data(self, data):
        self.remote_data.append(bytes(data))

    def on_remote_hangup(self, reason="REMOTE_HANGUP"):
        self.remote_hangups.append(reason)


ENDPOINT_ID = b"R" * 32
DEVICE_SECRET = b"relay-port-test-secret"


class RelaySessionPortTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = generate_localhost_certificate(self.tmp.name, prefix="relay-port")
        self.server = RelayTcpServer(
            "127.0.0.1",
            0,
            create_server_ssl_context(cert, key),
            credential_store=InMemoryDeviceCredentialStore({ENDPOINT_ID: DEVICE_SECRET}),
        )
        await self.server.start()
        host, port = self.server.address
        self.controller = FakeController()
        self.port = RelaySessionPort(
            host,
            port,
            create_client_ssl_context(cert),
            server_hostname="localhost",
            endpoint_id=ENDPOINT_ID,
            device_secret=DEVICE_SECRET,
        )
        self.port.bind_controller(self.controller)
        await self.port.start()

    async def asyncTearDown(self):
        await self.port.close()
        await self.server.close()
        self.tmp.cleanup()

    async def _wait_for(self, predicate, timeout=2.0):
        deadline = asyncio.get_running_loop().time() + timeout
        while not predicate():
            if asyncio.get_running_loop().time() >= deadline:
                self.fail("timed out waiting for relay adapter event")
            await asyncio.sleep(0.005)

    async def test_dial_data_echo_and_hangup(self):
        self.port.dial("loopback")
        await self._wait_for(lambda: ResultCode.CONNECT in self.controller.dial_results)

        payload = b"\x00hello\xff"
        self.port.write_data(payload)
        await self._wait_for(lambda: b"".join(self.controller.remote_data) == payload)

        self.port.hangup("LOCAL_HANGUP")
        await self._wait_for(lambda: len(self.controller.remote_hangups) == 1)
        self.assertEqual(self.controller.remote_hangups, ["LOCAL_HANGUP"])

    async def test_busy_maps_to_busy_result(self):
        self.port.dial("busy")
        await self._wait_for(lambda: bool(self.controller.dial_results))
        self.assertEqual(self.controller.dial_results, [ResultCode.BUSY])


if __name__ == "__main__":
    unittest.main()
