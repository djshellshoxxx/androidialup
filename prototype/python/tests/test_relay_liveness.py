import asyncio
import ssl
import tempfile
import time
import unittest

from androidialup_gateway.backend import BackendState
from androidialup_gateway.loopback import LoopbackBackend
from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import Frame, FrameKind, ZERO_ID
from androidialup_protocol.liveness import LinkFailure, TimeoutConfig
from androidialup_protocol.messages import (
    AuthBegin,
    AuthResponse,
    DataBytes,
    DialRequest,
    Hello,
    Mode,
    NetworkTransport,
    Ping,
    Pong,
    decode_payload,
    encode_payload,
    kind_for_message,
)
from androidialup_relay.auth import ChallengeResponseAuthenticator, InMemoryDeviceCredentialStore, compute_proof
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.session import RelaySession
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from tls_test_utils import generate_localhost_certificate

ENDPOINT_ID = b"L" * 32
CALL_ID = b"c" * 16
DEVICE_SECRET = b"relay-liveness-test-secret"

# Sub-second stand-ins for the S1 section 7 defaults (10 s / 30 s / 8 s).
FAST = TimeoutConfig(heartbeat_interval=0.05, heartbeat_failure=0.15, relay_auth=0.25, dial_ack=0.2)


def frame_for(message, *, call_id=ZERO_ID, session_id=ZERO_ID, request_id=1):
    return Frame(
        kind=kind_for_message(message),
        call_id=call_id,
        session_id=session_id,
        request_id=request_id,
        payload=encode_payload(message),
    )


class RelayLivenessTests(unittest.IsolatedAsyncioTestCase):
    timeouts = FAST

    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = generate_localhost_certificate(self.tmp.name, prefix="relay-liveness")
        self.client_context = create_client_ssl_context(cert)
        self.backends = []
        store = InMemoryDeviceCredentialStore({ENDPOINT_ID: DEVICE_SECRET})

        def session_factory():
            backend = LoopbackBackend()
            self.backends.append(backend)
            return RelaySession(
                backend=backend,
                authenticator=ChallengeResponseAuthenticator(store),
                heartbeat_seconds=self.timeouts.heartbeat_seconds_advertised,
            )

        self.server = RelayTcpServer(
            "127.0.0.1",
            0,
            create_server_ssl_context(cert, key),
            session_factory=session_factory,
            timeouts=self.timeouts,
        )
        await self.server.start()

    async def asyncTearDown(self):
        await self.server.close()
        self.tmp.cleanup()

    async def open_client(self):
        host, port = self.server.address
        reader, writer = await asyncio.open_connection(host, port, ssl=self.client_context, server_hostname="localhost")
        return AsyncFramedConnection(reader, writer)

    async def authenticate(self, connection):
        await connection.send_frame(frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ("BYTE_RELAY",)), request_id=1))
        hello_ack_frame = await connection.recv_frame()
        hello_ack = decode_payload(hello_ack_frame.kind, hello_ack_frame.payload)
        await connection.send_frame(frame_for(AuthBegin(), request_id=2))
        challenge_frame = await connection.recv_frame()
        challenge = decode_payload(challenge_frame.kind, challenge_frame.payload)
        proof = compute_proof(DEVICE_SECRET, challenge.nonce, ENDPOINT_ID, hello_ack.relay_id)
        await connection.send_frame(frame_for(AuthResponse(proof), request_id=3))
        auth = await connection.recv_frame()
        self.assertEqual(auth.kind, FrameKind.AUTH_OK)
        return hello_ack

    async def dial(self, connection):
        await connection.send_frame(
            frame_for(
                DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI, ("BYTE_RELAY",), 60000),
                call_id=CALL_ID,
                request_id=4,
            )
        )
        accepted = await connection.recv_frame()
        session_id = decode_payload(accepted.kind, accepted.payload).assigned_session_id
        for _ in range(3):  # DIALING, CONNECTED, FLOW_STATUS
            await connection.recv_frame()
        return session_id

    async def wait_for_close_record(self, timeout=2.0):
        deadline = time.monotonic() + timeout
        while not self.server.close_records:
            if time.monotonic() > deadline:
                self.fail("relay did not record a connection close")
            await asyncio.sleep(0.005)
        return self.server.close_records[0]

    async def assert_closed_by_relay(self, connection, timeout=2.0):
        # Orderly close gives EOF; an abort may surface as a reset instead.
        with self.assertRaises((ConnectionClosed, ConnectionError, ssl.SSLError)):
            while True:
                await asyncio.wait_for(connection.recv_frame(), timeout)

    async def test_hello_ack_advertises_configured_heartbeat(self):
        connection = await self.open_client()
        hello_ack = await self.authenticate(connection)
        self.assertEqual(hello_ack.heartbeat_seconds, 1)
        await connection.close()

    async def test_relay_pings_when_idle_and_pong_keeps_connection(self):
        connection = await self.open_client()
        await self.authenticate(connection)
        nonces = []
        for _ in range(3):
            started = time.monotonic()
            ping_frame = await asyncio.wait_for(connection.recv_frame(), 1.0)
            self.assertEqual(ping_frame.kind, FrameKind.PING)
            self.assertEqual((ping_frame.call_id, ping_frame.session_id, ping_frame.request_id), (ZERO_ID, ZERO_ID, 0))
            self.assertLess(time.monotonic() - started, 0.5)
            ping = decode_payload(ping_frame.kind, ping_frame.payload)
            nonces.append(ping.nonce)
            await connection.send_frame(frame_for(Pong(ping.nonce), request_id=0))
        self.assertEqual(len(set(nonces)), 3)
        self.assertEqual(self.server.close_records, [])
        await connection.close()

    async def test_no_pong_closes_with_heartbeat_timeout_and_releases_backend(self):
        connection = await self.open_client()
        await self.authenticate(connection)
        await self.dial(connection)
        self.assertEqual(self.backends[0].state, BackendState.CONNECTED)
        started = time.monotonic()
        await self.assert_closed_by_relay(connection)
        elapsed = time.monotonic() - started
        record = await self.wait_for_close_record()
        self.assertEqual(record, LinkFailure("NETWORK_LOST", "HEARTBEAT_TIMEOUT"))
        self.assertGreaterEqual(elapsed, self.timeouts.heartbeat_interval + self.timeouts.heartbeat_failure - 0.02)
        self.assertLess(elapsed, 1.0)
        self.assertNotEqual(self.backends[0].state, BackendState.CONNECTED)
        self.assertNotEqual(self.backends[0].state, BackendState.DIALING)
        await connection.close()

    async def test_wrong_nonce_pong_is_protocol_violation(self):
        connection = await self.open_client()
        await self.authenticate(connection)
        ping_frame = await asyncio.wait_for(connection.recv_frame(), 1.0)
        ping = decode_payload(ping_frame.kind, ping_frame.payload)
        await connection.send_frame(frame_for(Pong(ping.nonce ^ 0xFFFF), request_id=0))
        await self.assert_closed_by_relay(connection)
        self.assertEqual(await self.wait_for_close_record(), LinkFailure("PROTOCOL_VIOLATION", "PONG nonce does not match outstanding PING"))
        await connection.close()

    async def test_unsolicited_pong_is_protocol_violation(self):
        connection = await self.open_client()
        await self.authenticate(connection)
        await connection.send_frame(frame_for(Pong(7), request_id=0))
        await self.assert_closed_by_relay(connection)
        self.assertEqual((await self.wait_for_close_record()).reason, "PROTOCOL_VIOLATION")
        await connection.close()

    async def test_client_traffic_defers_relay_ping(self):
        # Wider interval so scheduler jitter under load cannot open a 50 ms gap.
        self.server.timeouts = TimeoutConfig(heartbeat_interval=0.3, heartbeat_failure=0.3, relay_auth=2.0)
        connection = await self.open_client()
        await self.authenticate(connection)
        session_id = await self.dial(connection)
        seq = 0
        kinds = []
        for _ in range(8):
            await connection.send_frame(frame_for(DataBytes(seq, b"x"), call_id=CALL_ID, session_id=session_id, request_id=0))
            seq += 1
            kinds.append((await connection.recv_frame()).kind)  # echoed DATA_BYTES
            kinds.append((await connection.recv_frame()).kind)  # FLOW_STATUS
            await asyncio.sleep(0.03)
        self.assertNotIn(FrameKind.PING, kinds)
        await connection.close()

    async def test_relay_answers_client_ping_after_auth(self):
        connection = await self.open_client()
        await self.authenticate(connection)
        await connection.send_frame(frame_for(Ping(42, 1), request_id=0))
        reply = await asyncio.wait_for(connection.recv_frame(), 1.0)
        self.assertEqual(decode_payload(reply.kind, reply.payload), Pong(42))
        await connection.close()

    async def test_silent_client_is_closed_at_auth_deadline_without_frames(self):
        connection = await self.open_client()
        started = time.monotonic()
        await self.assert_closed_by_relay(connection)
        elapsed = time.monotonic() - started
        self.assertGreaterEqual(elapsed, self.timeouts.relay_auth - 0.02)
        self.assertLess(elapsed, 1.0)
        self.assertEqual(await self.wait_for_close_record(), LinkFailure("AUTH_FAILURE", "AUTH_TIMEOUT"))
        await connection.close()

    async def test_stalled_auth_gets_auth_fail_then_close(self):
        connection = await self.open_client()
        await connection.send_frame(frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ()), request_id=1))
        await connection.recv_frame()
        await connection.send_frame(frame_for(AuthBegin(), request_id=2))
        await connection.recv_frame()  # AUTH_CHALLENGE, never answered
        fail = await asyncio.wait_for(connection.recv_frame(), 1.0)
        self.assertEqual(fail.kind, FrameKind.AUTH_FAIL)
        self.assertEqual(decode_payload(fail.kind, fail.payload).reason, "AUTH_TIMEOUT")
        await self.assert_closed_by_relay(connection)
        self.assertEqual(await self.wait_for_close_record(), LinkFailure("AUTH_FAILURE", "AUTH_TIMEOUT"))
        await connection.close()

    async def test_no_relay_ping_before_authentication(self):
        connection = await self.open_client()
        await connection.send_frame(frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ()), request_id=1))
        await connection.recv_frame()
        await connection.send_frame(frame_for(AuthBegin(), request_id=2))
        await connection.recv_frame()
        # Within the auth deadline the only frame that may arrive is AUTH_FAIL.
        frame = await asyncio.wait_for(connection.recv_frame(), 1.0)
        self.assertEqual(frame.kind, FrameKind.AUTH_FAIL)
        await connection.close()


if __name__ == "__main__":
    unittest.main()
