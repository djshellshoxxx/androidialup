import asyncio
import ssl
import tempfile
import unittest

from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import Frame, FrameKind, ProtocolError, ZERO_ID, encode_frame
from androidialup_protocol.messages import (
    AuthBegin,
    AuthResponse,
    DataBytes,
    DialRequest,
    Hello,
    Mode,
    NetworkTransport,
    decode_payload,
    encode_payload,
    kind_for_message,
)
from androidialup_relay.auth import InMemoryDeviceCredentialStore, compute_proof
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from tls_test_utils import generate_localhost_certificate

ENDPOINT_ID = b"E" * 32
CALL_ID = b"C" * 16
DEVICE_SECRET = b"relay-server-test-secret"


def frame_for(message, *, call_id=ZERO_ID, session_id=ZERO_ID, request_id=1):
    return Frame(
        kind=kind_for_message(message),
        call_id=call_id,
        session_id=session_id,
        request_id=request_id,
        payload=encode_payload(message),
    )


class RelayServerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = generate_localhost_certificate(self.tmp.name)
        self.server_context = create_server_ssl_context(cert, key)
        self.client_context = create_client_ssl_context(cert)
        self.server = RelayTcpServer(
            "127.0.0.1",
            0,
            self.server_context,
            credential_store=InMemoryDeviceCredentialStore({ENDPOINT_ID: DEVICE_SECRET}),
        )
        await self.server.start()

    async def asyncTearDown(self):
        await self.server.close()
        self.tmp.cleanup()

    async def open_client(self):
        host, port = self.server.address
        reader, writer = await asyncio.open_connection(
            host,
            port,
            ssl=self.client_context,
            server_hostname="localhost",
        )
        return AsyncFramedConnection(reader, writer)

    async def handshake_and_dial(self, connection, *, call_id=CALL_ID):
        await connection.send_frame(frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ("BYTE_RELAY",)), request_id=1))
        hello_ack_frame = await connection.recv_frame()
        hello_ack = decode_payload(hello_ack_frame.kind, hello_ack_frame.payload)
        await connection.send_frame(frame_for(AuthBegin(), request_id=2))
        challenge_frame = await connection.recv_frame()
        challenge = decode_payload(challenge_frame.kind, challenge_frame.payload)
        proof = compute_proof(DEVICE_SECRET, challenge.nonce, ENDPOINT_ID, hello_ack.relay_id)
        await connection.send_frame(frame_for(AuthResponse(proof), request_id=3))
        auth_frame = await connection.recv_frame()
        self.assertEqual(auth_frame.kind, FrameKind.AUTH_OK)
        await connection.send_frame(
            frame_for(
                DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI, ("BYTE_RELAY",), 60000),
                call_id=call_id,
                request_id=4,
            )
        )
        accepted = await connection.recv_frame()
        session_id = decode_payload(accepted.kind, accepted.payload).assigned_session_id
        # DIALING, CONNECTED, initial FLOW_STATUS
        await connection.recv_frame()
        await connection.recv_frame()
        await connection.recv_frame()
        return session_id

    async def test_real_tls_hello_auth_dial_and_echo(self):
        connection = await self.open_client()
        session_id = await self.handshake_and_dial(connection)
        payload = b"\x00tls-echo\xff"
        await connection.send_frame(frame_for(DataBytes(0, payload), call_id=CALL_ID, session_id=session_id, request_id=0))
        messages = []
        for _ in range(2):
            frame = await connection.recv_frame()
            messages.append(decode_payload(frame.kind, frame.payload))
        self.assertIn(DataBytes(0, payload), messages)
        await connection.close()

    async def test_fragmented_hello_is_reassembled_over_tls(self):
        host, port = self.server.address
        reader, writer = await asyncio.open_connection(host, port, ssl=self.client_context, server_hostname="localhost")
        hello = frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ()), request_id=1)
        encoded = encode_frame(hello)
        for part in (encoded[:2], encoded[2:11], encoded[11:37], encoded[37:]):
            writer.write(part)
            await writer.drain()
        connection = AsyncFramedConnection(reader, writer)
        response = await connection.recv_frame()
        self.assertEqual(response.request_id, 1)
        await connection.close()

    async def test_two_clients_have_independent_sessions(self):
        a = await self.open_client()
        b = await self.open_client()
        session_a, session_b = await asyncio.gather(
            self.handshake_and_dial(a, call_id=b"A" * 16),
            self.handshake_and_dial(b, call_id=b"B" * 16),
        )
        self.assertNotEqual(session_a, session_b)
        await a.close(); await b.close()

    async def test_malformed_client_isolated_from_next_valid_client(self):
        host, port = self.server.address
        reader, writer = await asyncio.open_connection(host, port, ssl=self.client_context, server_hostname="localhost")
        writer.write(b"NOPE" + b"\x00" * 50)
        await writer.drain()
        await asyncio.sleep(0.05)
        self.assertTrue(writer.is_closing() or reader.at_eof() or await asyncio.wait_for(reader.read(1), 1) == b"")
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass

        valid = await self.open_client()
        await valid.send_frame(frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ()), request_id=9))
        response = await valid.recv_frame()
        self.assertEqual(response.request_id, 9)
        await valid.close()

    async def test_clean_client_eof_does_not_stop_listener(self):
        first = await self.open_client()
        await first.close()
        second = await self.open_client()
        await second.send_frame(frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ()), request_id=7))
        self.assertEqual((await second.recv_frame()).request_id, 7)
        await second.close()


if __name__ == "__main__":
    unittest.main()
