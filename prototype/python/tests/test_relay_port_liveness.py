import asyncio
import tempfile
import time
import unittest

from androidialup_modem.at_engine import ResultCode
from androidialup_modem.relay_port import RelayLinkError, RelaySessionPort
from androidialup_protocol.frame import FrameKind
from androidialup_protocol.liveness import LinkFailure, TimeoutConfig
from androidialup_protocol.frame import ZERO_ID
from androidialup_protocol.messages import AuthFail, Pong, decode_payload
from androidialup_relay.auth import ChallengeResponseAuthenticator, InMemoryDeviceCredentialStore
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.session import RelaySession, RelaySessionState
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from tls_test_utils import generate_localhost_certificate

ENDPOINT_ID = b"P" * 32
DEVICE_SECRET = b"relay-port-liveness-secret"
STORE = InMemoryDeviceCredentialStore({ENDPOINT_ID: DEVICE_SECRET})

FAST = TimeoutConfig(heartbeat_interval=0.05, heartbeat_failure=0.15, relay_auth=0.25, dial_ack=0.2, connect=0.25)
# Relay that never pings within a test's lifetime (S1 defaults).
QUIET = TimeoutConfig()


class FakeController:
    def __init__(self):
        self.dial_results = []
        self.remote_data = []
        self.remote_hangups = []

    def on_dial_result(self, result, reason=None):
        self.dial_results.append((result, reason))

    def on_remote_data(self, data):
        self.remote_data.append(bytes(data))

    def on_remote_hangup(self, reason="REMOTE_HANGUP", detail=None):
        self.remote_hangups.append((reason, detail))


class PingDeafSession(RelaySession):
    """Relay that stops answering heartbeats (hung relay process)."""

    def handle_frame(self, frame):
        if frame.kind == FrameKind.PING:
            return []
        return super().handle_frame(frame)


class WrongNoncePongSession(RelaySession):
    def handle_frame(self, frame):
        if frame.kind == FrameKind.PING:
            ping = decode_payload(frame.kind, frame.payload)
            return [self._frame(Pong(ping.nonce ^ 1), call_id=frame.call_id, session_id=frame.session_id)]
        return super().handle_frame(frame)


class DialSwallowingSession(RelaySession):
    """Relay that never acknowledges DIAL_REQUEST."""

    def handle_frame(self, frame):
        if frame.kind == FrameKind.DIAL_REQUEST:
            return []
        return super().handle_frame(frame)


class AuthTimeoutAtChallengeSession(RelaySession):
    """Relay whose auth deadline expired: AUTH_FAIL(AUTH_TIMEOUT) instead of AUTH_CHALLENGE."""

    def handle_frame(self, frame):
        if frame.kind == FrameKind.AUTH_BEGIN:
            self.state = RelaySessionState.FAILED
            return [self._frame(AuthFail("AUTH_TIMEOUT"), call_id=ZERO_ID, session_id=ZERO_ID)]
        return super().handle_frame(frame)


class RelayPortLivenessTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cert, self.key = generate_localhost_certificate(self.tmp.name, prefix="port-liveness")
        self.server = None
        self.port = None
        self.extra_servers = []

    async def asyncTearDown(self):
        if self.port is not None:
            await self.port.close()
        if self.server is not None:
            await self.server.close()
        for server in self.extra_servers:
            server.close()
            await server.wait_closed()
        self.tmp.cleanup()

    async def start_relay(self, *, timeouts=QUIET, session_cls=RelaySession):
        def factory():
            return session_cls(
                authenticator=ChallengeResponseAuthenticator(STORE),
                heartbeat_seconds=timeouts.heartbeat_seconds_advertised,
            )

        self.server = RelayTcpServer(
            "127.0.0.1", 0, create_server_ssl_context(self.cert, self.key), session_factory=factory, timeouts=timeouts
        )
        await self.server.start()
        return self.server.address

    def make_port(self, address, *, timeouts=FAST, heartbeat_interval=None):
        host, port = address
        self.controller = FakeController()
        self.port = RelaySessionPort(
            host,
            port,
            create_client_ssl_context(self.cert),
            server_hostname="localhost",
            endpoint_id=ENDPOINT_ID,
            device_secret=DEVICE_SECRET,
            timeouts=timeouts,
            heartbeat_interval=heartbeat_interval,
        )
        self.port.bind_controller(self.controller)
        return self.port

    async def wait_for(self, predicate, timeout=2.0):
        deadline = time.monotonic() + timeout
        while not predicate():
            if time.monotonic() > deadline:
                self.fail("timed out waiting for condition")
            await asyncio.sleep(0.005)

    async def connect_call(self):
        self.port.dial("loopback")
        await self.wait_for(lambda: (ResultCode.CONNECT, None) in self.controller.dial_results)

    async def test_client_answers_relay_pings(self):
        address = await self.start_relay(timeouts=FAST)
        port = self.make_port(address, heartbeat_interval=60.0)
        await port.start()
        await self.connect_call()
        await asyncio.sleep(0.6)  # many relay heartbeat cycles at 50 ms / 150 ms
        self.assertGreaterEqual(port.heartbeat_stats["pongs_sent"], 3)
        self.assertEqual(port.heartbeat_stats["pings_sent"], 0)
        self.assertEqual(self.server.close_records, [])
        self.assertIsNone(port.link_failure)
        self.assertEqual(self.controller.remote_hangups, [])

    async def test_client_pings_when_idle_and_relay_pong_keeps_link(self):
        address = await self.start_relay(timeouts=QUIET)
        port = self.make_port(address, heartbeat_interval=0.05)
        await port.start()
        await asyncio.sleep(0.6)
        self.assertGreaterEqual(port.heartbeat_stats["pings_sent"], 3)
        self.assertGreaterEqual(port.heartbeat_stats["pongs_received"], 3)
        self.assertIsNone(port.link_failure)

    async def test_negotiated_interval_used_by_default(self):
        address = await self.start_relay(timeouts=TimeoutConfig(heartbeat_interval=7))
        port = self.make_port(address)
        await port.start()
        self.assertEqual(port.heartbeat_interval, 7.0)

    async def test_missing_pong_mid_call_is_heartbeat_timeout_no_carrier(self):
        address = await self.start_relay(session_cls=PingDeafSession)
        port = self.make_port(address, heartbeat_interval=0.05)
        await port.start()
        await self.connect_call()
        started = time.monotonic()
        await self.wait_for(lambda: self.controller.remote_hangups)
        elapsed = time.monotonic() - started
        await asyncio.sleep(0.1)
        self.assertEqual(self.controller.remote_hangups, [("NETWORK_LOST", "HEARTBEAT_TIMEOUT")])
        self.assertEqual(port.link_failure, LinkFailure("NETWORK_LOST", "HEARTBEAT_TIMEOUT"))
        self.assertGreaterEqual(elapsed, FAST.heartbeat_failure - 0.02)
        self.assertLess(elapsed, 1.0)
        # Client closed the transport: the relay sees the connection end.
        await self.wait_for(lambda: self.server.close_records)
        self.assertEqual(self.server.close_records[0].reason, "NETWORK_LOST")

    async def test_wrong_nonce_pong_is_protocol_violation(self):
        address = await self.start_relay(session_cls=WrongNoncePongSession)
        port = self.make_port(address, heartbeat_interval=0.05)
        await port.start()
        await self.connect_call()
        await self.wait_for(lambda: self.controller.remote_hangups)
        self.assertEqual(self.controller.remote_hangups[0][0], "PROTOCOL_VIOLATION")
        self.assertEqual(port.link_failure.reason, "PROTOCOL_VIOLATION")

    async def test_idle_link_failure_does_not_notify_controller_but_fails_next_dial(self):
        address = await self.start_relay(session_cls=PingDeafSession)
        port = self.make_port(address, heartbeat_interval=0.05)
        await port.start()
        await self.wait_for(lambda: port.link_failure is not None)
        self.assertEqual(self.controller.remote_hangups, [])
        port.dial("loopback")
        await self.wait_for(lambda: self.controller.remote_hangups)
        self.assertEqual(self.controller.remote_hangups, [("NETWORK_LOST", "HEARTBEAT_TIMEOUT")])

    async def test_dial_ack_timeout_is_relay_unavailable(self):
        address = await self.start_relay(session_cls=DialSwallowingSession)
        port = self.make_port(address, heartbeat_interval=60.0)
        await port.start()
        started = time.monotonic()
        port.dial("loopback")
        await self.wait_for(lambda: self.controller.remote_hangups)
        elapsed = time.monotonic() - started
        self.assertEqual(self.controller.remote_hangups, [("RELAY_UNAVAILABLE", "DIAL_ACK_TIMEOUT")])
        self.assertGreaterEqual(elapsed, FAST.dial_ack - 0.02)
        self.assertLess(elapsed, 1.0)
        self.assertEqual(self.controller.dial_results, [])

    async def test_dial_ack_deadline_cleared_by_dial_accepted(self):
        address = await self.start_relay()
        port = self.make_port(address, heartbeat_interval=60.0)
        await port.start()
        await self.connect_call()
        await asyncio.sleep(FAST.dial_ack * 2)
        self.assertEqual(self.controller.remote_hangups, [])
        self.assertIsNone(port.link_failure)

    async def test_auth_deadline_when_relay_never_answers(self):
        import ssl as _ssl

        async def silent(reader, writer):
            try:
                await reader.read()  # never answers HELLO
            except (ConnectionError, _ssl.SSLError):
                pass
            finally:
                writer.close()

        server = await asyncio.start_server(silent, "127.0.0.1", 0, ssl=create_server_ssl_context(self.cert, self.key))
        self.extra_servers.append(server)
        port = self.make_port(server.sockets[0].getsockname()[:2])
        started = time.monotonic()
        with self.assertRaises(RelayLinkError) as caught:
            await port.start()
        elapsed = time.monotonic() - started
        self.assertEqual(caught.exception.failure, LinkFailure("AUTH_FAILURE", "AUTH_TIMEOUT"))
        self.assertGreaterEqual(elapsed, FAST.relay_auth - 0.02)
        self.assertLess(elapsed, 1.5)

    async def test_connect_deadline_when_tls_never_completes(self):
        async def mute(reader, writer):
            await reader.read()
            writer.close()

        server = await asyncio.start_server(mute, "127.0.0.1", 0)  # plain TCP: TLS handshake stalls
        self.extra_servers.append(server)
        port = self.make_port(server.sockets[0].getsockname()[:2])
        with self.assertRaises(RelayLinkError) as caught:
            await port.start()
        self.assertEqual(caught.exception.failure, LinkFailure("CONNECT_TIMEOUT", "CONNECT_TIMEOUT"))

    async def test_auth_fail_instead_of_challenge_maps_to_auth_failure(self):
        from androidialup_modem.relay_port import RelayAuthenticationError

        address = await self.start_relay(session_cls=AuthTimeoutAtChallengeSession)
        port = self.make_port(address)
        with self.assertRaises(RelayAuthenticationError) as caught:
            await port.start()
        self.assertEqual(caught.exception.failure, LinkFailure("AUTH_FAILURE", "AUTH_TIMEOUT"))


if __name__ == "__main__":
    unittest.main()
