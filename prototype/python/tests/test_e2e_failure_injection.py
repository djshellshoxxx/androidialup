"""S1_TEST_PLAN sections 8/9 failure injection, end to end over real TLS.

Topology for every case:

    DTE TCP client -> TcpDteServer -> ModemController -> RelaySessionPort
        -> TLS 1.3 -> RelayTcpServer (in-process or separate OS process)
        -> RelaySession -> LoopbackBackend

Each case establishes a call, moves binary data, injects one failure and
asserts: the exact S1 taxonomy reason (and detail), exactly one NO CARRIER on
the DTE stream, DCD low published before NO CARRIER, and exactly one return to
COMMAND.
"""

import asyncio
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest

from androidialup_gateway.backend import BackendState
from androidialup_modem.controller import ModemController, ModemState
from androidialup_modem.relay_port import RelaySessionPort
from androidialup_modem.tcp_dte import TcpDteServer
from androidialup_protocol.liveness import LinkFailure, TimeoutConfig
from androidialup_relay.auth import ChallengeResponseAuthenticator, InMemoryDeviceCredentialStore
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.session import RelaySession
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from test_relay_session_failures import CrashingBackend
from tls_test_utils import generate_localhost_certificate

ENDPOINT_ID = b"F" * 32
DEVICE_SECRET = bytes(range(32))
HELPER = Path(__file__).resolve().parent / "relay_process_helper.py"

# Client-side stand-ins for the S1 10 s / 30 s heartbeat defaults.
CLIENT_TIMEOUTS = TimeoutConfig(heartbeat_interval=0.05, heartbeat_failure=0.2, dial_ack=0.5)

# A lost TCP/TLS connection is always NETWORK_LOST. The detail says whether the
# client saw an orderly EOF (FIN / close_notify) or a reset: the kernel sends RST
# instead of FIN when unread client bytes (e.g. an in-flight PING) are queued at
# the closing end, so either detail is a correct report of the same failure.
TRANSPORT_LOSS = {LinkFailure("NETWORK_LOST", "TRANSPORT_CLOSED"), LinkFailure("NETWORK_LOST", "TRANSPORT_ERROR")}


class FailureInjectionHarness(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cert, self.key = generate_localhost_certificate(self.tmp.name, prefix="failure-injection")
        self.relay = None
        self.process = None
        self.backends = []
        self.session_port = None
        self.dte = None
        self.writer = None

    async def asyncTearDown(self):
        if self.writer is not None:
            self.writer.close()
            try:
                await self.writer.wait_closed()
            except (ConnectionError, BrokenPipeError):
                pass
        if self.dte is not None:
            await self.dte.close()
        if self.session_port is not None:
            await self.session_port.close()
        if self.relay is not None:
            await self.relay.close()
        if self.process is not None and self.process.returncode is None:
            self.process.send_signal(signal.SIGCONT)
            self.process.kill()
            await self.process.wait()
        self.tmp.cleanup()

    # -- relay variants ---------------------------------------------------

    async def start_inprocess_relay(self, backend_factory=CrashingBackend):
        store = InMemoryDeviceCredentialStore({ENDPOINT_ID: DEVICE_SECRET})

        def session_factory():
            backend = backend_factory()
            self.backends.append(backend)
            return RelaySession(backend=backend, authenticator=ChallengeResponseAuthenticator(store))

        self.relay = RelayTcpServer(
            "127.0.0.1",
            0,
            create_server_ssl_context(self.cert, self.key),
            session_factory=session_factory,
            backend_poll_interval=0.01,
        )
        await self.relay.start()
        return self.relay.address

    async def start_relay_process(self):
        self.process = await asyncio.create_subprocess_exec(
            sys.executable,
            str(HELPER),
            self.cert,
            self.key,
            ENDPOINT_ID.hex(),
            DEVICE_SECRET.hex(),
            stdout=asyncio.subprocess.PIPE,
        )
        line = await asyncio.wait_for(self.process.stdout.readline(), 10.0)
        self.assertTrue(line.startswith(b"PORT "), line)
        return "127.0.0.1", int(line.split()[1])

    # -- modem side -------------------------------------------------------

    async def start_modem(self, address):
        host, port = address
        self.session_port = RelaySessionPort(
            host,
            port,
            create_client_ssl_context(self.cert),
            server_hostname="localhost",
            endpoint_id=ENDPOINT_ID,
            device_secret=DEVICE_SECRET,
            timeouts=CLIENT_TIMEOUTS,
            heartbeat_interval=CLIENT_TIMEOUTS.heartbeat_interval,
        )
        await self.session_port.start()
        self.controller = None
        self.log = []  # ("dte", bytes) | ("snap", ModemSnapshot), in emission order

        def controller_factory(write_dte):
            def logged_write(data):
                self.log.append(("dte", bytes(data)))
                write_dte(data)

            controller = ModemController(
                self.session_port,
                logged_write,
                build_id="failure-injection",
                snapshot_listener=lambda snap: self.log.append(("snap", snap)),
            )
            controller.engine.profile.echo = False
            self.session_port.bind_controller(controller)
            self.controller = controller
            return controller

        self.dte = TcpDteServer("127.0.0.1", 0, controller_factory, timer_tick_ms=5)
        await self.dte.start()
        self.reader, self.writer = await asyncio.open_connection("127.0.0.1", self.dte.bound_port)

    async def line(self, timeout=2.0):
        return await asyncio.wait_for(self.reader.readuntil(b"\n"), timeout)

    async def establish_call_with_data(self):
        self.writer.write(b"ATDloopback\r")
        await self.writer.drain()
        self.assertEqual(await self.line(), b"CONNECT\r\n")
        self.assertEqual(self.controller.state, ModemState.ONLINE_DATA)
        self.assertTrue(self.controller.signals.dcd)
        payload = bytes(range(256)) * 64  # 16 KiB including NUL, CR, '+', 0xFF
        self.writer.write(payload)
        await self.writer.drain()
        self.assertEqual(await asyncio.wait_for(self.reader.readexactly(len(payload)), 5.0), payload)
        self.mark = len(self.log)

    async def assert_single_no_carrier(self, expected, *, max_seconds: float):
        """expected: one LinkFailure, or a set of acceptable ones (same reason)."""
        acceptable = expected if isinstance(expected, set) else {expected}
        started = time.monotonic()
        self.assertEqual(await self.line(timeout=max_seconds + 1.0), b"NO CARRIER\r\n")
        elapsed = time.monotonic() - started
        self.assertLess(elapsed, max_seconds)

        self.assertEqual(self.controller.state, ModemState.COMMAND)
        self.assertFalse(self.controller.signals.dcd)
        self.assertIn(LinkFailure(self.controller.terminal_reason, self.controller.terminal_detail), acceptable)

        # Wait well past every client deadline, then prove nothing else was queued
        # on the DTE stream: the very next line must answer a fresh AT.
        await asyncio.sleep(0.4)
        self.writer.write(b"AT\r")
        await self.writer.drain()
        self.assertEqual(await self.line(), b"OK\r\n")

        events = self.log[self.mark:]
        snaps = [item for kind, item in events if kind == "snap"]
        self.assertEqual([(s.state, s.signals.dcd) for s in snaps], [(ModemState.COMMAND, False)])
        no_carrier_at = [i for i, (kind, item) in enumerate(events) if kind == "dte" and b"NO CARRIER" in item]
        self.assertEqual(len(no_carrier_at), 1)
        snap_at = next(i for i, (kind, _) in enumerate(events) if kind == "snap")
        self.assertLess(snap_at, no_carrier_at[0], "DCD must drop before NO CARRIER")


class RelayFailureInjectionTests(FailureInjectionHarness):
    async def test_relay_process_killed_mid_call(self):
        await self.start_modem(await self.start_relay_process())
        await self.establish_call_with_data()
        self.process.kill()  # SIGKILL: kernel closes the socket, no TLS close_notify
        await self.process.wait()
        await self.assert_single_no_carrier(TRANSPORT_LOSS, max_seconds=1.0)
        self.assertIn(self.session_port.link_failure, TRANSPORT_LOSS)

    async def test_relay_stops_responding_heartbeat_timeout(self):
        await self.start_modem(await self.start_relay_process())
        await self.establish_call_with_data()
        self.process.send_signal(signal.SIGSTOP)  # hung relay: socket stays open, nothing answers
        started = time.monotonic()
        await self.assert_single_no_carrier(LinkFailure("NETWORK_LOST", "HEARTBEAT_TIMEOUT"), max_seconds=1.5)
        self.assertGreaterEqual(time.monotonic() - started, CLIENT_TIMEOUTS.heartbeat_failure)
        self.assertEqual(self.session_port.link_failure, LinkFailure("NETWORK_LOST", "HEARTBEAT_TIMEOUT"))

    async def test_tls_socket_closed_mid_call(self):
        await self.start_modem(await self.start_inprocess_relay())
        await self.establish_call_with_data()
        self.assertEqual(len(self.relay.connections), 1)
        relay_side = next(iter(self.relay.connections))
        await relay_side.close()  # TLS close_notify + FIN from the relay end
        await self.assert_single_no_carrier(TRANSPORT_LOSS, max_seconds=1.0)
        # Relay side: exactly one terminal record and the backend lease released.
        await self._wait(lambda: self.relay.close_records)
        self.assertEqual(len(self.relay.close_records), 1)
        self.assertEqual(self.relay.close_records[0].reason, "NETWORK_LOST")
        self.assertEqual(self.backends[0].state, BackendState.CLOSED)

    async def test_relay_task_killed_mid_call(self):
        await self.start_modem(await self.start_inprocess_relay())
        await self.establish_call_with_data()
        relay, self.relay = self.relay, None
        await relay.close()  # cancels the per-connection relay task
        await self.assert_single_no_carrier(TRANSPORT_LOSS, max_seconds=1.0)
        self.assertEqual(relay.close_records, [LinkFailure("RELAY_UNAVAILABLE", "RELAY_SHUTDOWN")])
        self.assertEqual(self.backends[0].state, BackendState.CLOSED)

    async def test_gateway_backend_failure_mid_call(self):
        await self.start_modem(await self.start_inprocess_relay())
        await self.establish_call_with_data()
        self.backends[0].dead = True  # gateway process death, detected by the relay's backend poll
        await self.assert_single_no_carrier(LinkFailure("BACKEND_UNAVAILABLE", "BACKEND_FAILED"), max_seconds=1.0)
        # The relay link itself survives a gateway failure (S1 section 10: socket reusable).
        self.assertIsNone(self.session_port.link_failure)
        self.assertEqual(self.relay.close_records, [])
        self.assertEqual(self.backends[0].state, BackendState.CLOSED)
        self.backends[0].dead = False  # gateway restarted
        self.writer.write(b"ATDloopback\r")
        await self.writer.drain()
        self.assertEqual(await self.line(), b"CONNECT\r\n")

    async def _wait(self, predicate, timeout=2.0):
        deadline = time.monotonic() + timeout
        while not predicate():
            if time.monotonic() > deadline:
                self.fail("timed out")
            await asyncio.sleep(0.005)


if __name__ == "__main__":
    unittest.main()
