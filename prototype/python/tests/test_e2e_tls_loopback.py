import asyncio
import random
import ssl
import tempfile
import unittest

from androidialup_protocol.async_connection import AsyncFramedConnection
from androidialup_protocol.byte_relay import ByteRelayReceiver
from androidialup_protocol.frame import Frame, ZERO_ID
from androidialup_protocol.messages import (
    AuthBegin,
    AuthOk,
    AuthResponse,
    CallTerminated,
    DataBytes,
    DialAccepted,
    DialRequest,
    FlowStatus,
    HangupAck,
    HangupRequest,
    Hello,
    Mode,
    NetworkTransport,
    Ping,
    Pong,
    ProgressPhase,
    decode_payload,
    encode_payload,
    kind_for_message,
)
from androidialup_relay.auth import DEVICE_HMAC_SHA256_V1, InMemoryDeviceCredentialStore, compute_proof
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from tls_test_utils import generate_localhost_certificate

CALL_ID = bytes.fromhex("102132435465768798a9bacbdcedfe0f")
ENDPOINT_ID = bytes(reversed(range(32)))
DEVICE_SECRET = bytes.fromhex("7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a")


def frame_for(message, *, call_id=ZERO_ID, session_id=ZERO_ID, request_id=1):
    return Frame(
        kind=kind_for_message(message),
        call_id=call_id,
        session_id=session_id,
        request_id=request_id,
        payload=encode_payload(message),
    )


class TlsEndToEndLoopbackTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = generate_localhost_certificate(self.tmp.name, prefix="trusted")
        self.cert = cert
        self.server = RelayTcpServer(
            "127.0.0.1",
            0,
            create_server_ssl_context(cert, key),
            credential_store=InMemoryDeviceCredentialStore({ENDPOINT_ID: DEVICE_SECRET}),
        )
        await self.server.start()

    async def asyncTearDown(self):
        await self.server.close()
        self.tmp.cleanup()

    async def open_connection(self, context=None):
        host, port = self.server.address
        context = context or create_client_ssl_context(self.cert)
        reader, writer = await asyncio.open_connection(host, port, ssl=context, server_hostname="localhost")
        return AsyncFramedConnection(reader, writer), writer

    async def test_tls13_one_megabyte_binary_transfer_heartbeat_and_hangup(self):
        connection, writer = await self.open_connection()
        ssl_object = writer.get_extra_info("ssl_object")
        self.assertIsNotNone(ssl_object)
        self.assertEqual(ssl_object.version(), "TLSv1.3")

        await connection.send_frame(frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ("BYTE_RELAY",)), request_id=1))
        hello_ack_frame = await connection.recv_frame()
        hello_ack = decode_payload(hello_ack_frame.kind, hello_ack_frame.payload)
        await connection.send_frame(frame_for(AuthBegin(), request_id=2))
        challenge_frame = await connection.recv_frame()
        challenge = decode_payload(challenge_frame.kind, challenge_frame.payload)
        self.assertEqual(challenge.method, DEVICE_HMAC_SHA256_V1)
        proof = compute_proof(DEVICE_SECRET, challenge.nonce, ENDPOINT_ID, hello_ack.relay_id)
        await connection.send_frame(frame_for(AuthResponse(proof), request_id=3))
        auth_frame = await connection.recv_frame()
        self.assertIsInstance(decode_payload(auth_frame.kind, auth_frame.payload), AuthOk)
        await connection.send_frame(
            frame_for(
                DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI, ("BYTE_RELAY",), 60000),
                call_id=CALL_ID,
                request_id=4,
            )
        )
        accepted_frame = await connection.recv_frame()
        accepted = decode_payload(accepted_frame.kind, accepted_frame.payload)
        self.assertIsInstance(accepted, DialAccepted)
        session_id = accepted.assigned_session_id
        p1f = await connection.recv_frame()
        p1 = decode_payload(p1f.kind, p1f.payload)
        p2f = await connection.recv_frame()
        p2 = decode_payload(p2f.kind, p2f.payload)
        flowf = await connection.recv_frame()
        flow = decode_payload(flowf.kind, flowf.payload)
        self.assertEqual([p1.phase, p2.phase], [ProgressPhase.DIALING, ProgressPhase.CONNECTED])
        self.assertIsInstance(flow, FlowStatus)

        rng = random.Random(0x7151A1)
        source = rng.randbytes(1024 * 1024)
        receiver = ByteRelayReceiver()
        echoed = bytearray()
        offset = 0
        while offset < len(source):
            chunk = source[offset : offset + 32768]
            await connection.send_frame(
                frame_for(DataBytes(offset, chunk), call_id=CALL_ID, session_id=session_id, request_id=0)
            )
            found_data = None
            for _ in range(2):
                response = await connection.recv_frame()
                message = decode_payload(response.kind, response.payload)
                if isinstance(message, DataBytes):
                    found_data = message
            self.assertIsNotNone(found_data)
            echoed.extend(receiver.accept(found_data))
            await connection.send_frame(
                frame_for(FlowStatus(256 * 1024, 0), call_id=CALL_ID, session_id=session_id, request_id=0)
            )
            offset += len(chunk)

        self.assertEqual(bytes(echoed), source)

        await connection.send_frame(
            frame_for(Ping(987654321, 123), call_id=CALL_ID, session_id=session_id, request_id=99)
        )
        pong_frame = await connection.recv_frame()
        self.assertEqual(decode_payload(pong_frame.kind, pong_frame.payload), Pong(987654321))

        await connection.send_frame(
            frame_for(HangupRequest("user"), call_id=CALL_ID, session_id=session_id, request_id=100)
        )
        end_messages = []
        for _ in range(2):
            end_frame = await connection.recv_frame()
            end_messages.append(decode_payload(end_frame.kind, end_frame.payload))
        self.assertEqual(sum(isinstance(message, HangupAck) for message in end_messages), 1)
        self.assertEqual(sum(isinstance(message, CallTerminated) for message in end_messages), 1)
        await connection.close()

    async def test_untrusted_server_certificate_fails_handshake(self):
        wrong_dir = tempfile.TemporaryDirectory()
        try:
            wrong_cert, _ = generate_localhost_certificate(wrong_dir.name, prefix="wrong")
            wrong_context = create_client_ssl_context(wrong_cert)
            host, port = self.server.address
            with self.assertRaises(ssl.SSLCertVerificationError):
                await asyncio.open_connection(host, port, ssl=wrong_context, server_hostname="localhost")
        finally:
            wrong_dir.cleanup()


if __name__ == "__main__":
    unittest.main()
