import asyncio
import hashlib
import hmac
import tempfile
import unittest

from androidialup_modem.relay_port import RelayAuthenticationError, RelaySessionPort
from androidialup_protocol.async_connection import AsyncFramedConnection, ConnectionClosed
from androidialup_protocol.frame import Frame, ProtocolError, ZERO_ID
from androidialup_protocol.messages import (
    AuthBegin,
    AuthChallenge,
    AuthFail,
    AuthOk,
    AuthResponse,
    Hello,
    HelloAck,
    decode_payload,
    encode_payload,
    kind_for_message,
)
from androidialup_relay.auth import (
    DEVICE_HMAC_SHA256_V1,
    TRANSCRIPT_PREFIX,
    ChallengeResponseAuthenticator,
    InMemoryDeviceCredentialStore,
    build_transcript,
    compute_proof,
)
from androidialup_relay.server import RelayTcpServer
from androidialup_relay.session import RelaySession, RelaySessionState
from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context
from tls_test_utils import generate_localhost_certificate

ENDPOINT_ID = bytes(range(32))
OTHER_ENDPOINT_ID = bytes(reversed(range(32)))
SECRET = bytes.fromhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
WRONG_SECRET = bytes.fromhex("ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100")
NONCE = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")
RELAY_ID = "relay-prototype"

# Known-answer vector (frozen; the Java port must reproduce it exactly).
KAT_EXPECTED_TRANSCRIPT_HEX = (
    "414455502d415554482d7631"  # "ADUP-AUTH-v1"
    "000f" "72656c61792d70726f746f74797065"  # u16 len + "relay-prototype"
    "0020" "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"  # u16 len + nonce
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"  # endpoint_id (32 raw bytes)
)
KAT_EXPECTED_PROOF_HEX = "2d68e358e8f25193c1470e207683c93ac0521796480d323fd0682abac59d71a3"


def make_frame(message, *, request_id=1):
    return Frame(
        kind=kind_for_message(message),
        call_id=ZERO_ID,
        session_id=ZERO_ID,
        request_id=request_id,
        payload=encode_payload(message),
    )


def decoded(frames):
    return [decode_payload(f.kind, f.payload) for f in frames]


def session_with(store, *, nonce_source=None, relay_id=RELAY_ID):
    authenticator = ChallengeResponseAuthenticator(store, nonce_source=nonce_source)
    return RelaySession(relay_id=relay_id, authenticator=authenticator)


def run_to_challenge(session, endpoint_id=ENDPOINT_ID):
    (hello_ack,) = decoded(
        session.handle_frame(make_frame(Hello("AndroidDialup", "0.1", 1, 1, endpoint_id, ("BYTE_RELAY",))))
    )
    assert isinstance(hello_ack, HelloAck)
    (challenge,) = decoded(session.handle_frame(make_frame(AuthBegin(), request_id=2)))
    assert isinstance(challenge, AuthChallenge)
    return hello_ack, challenge


class ComputeProofTests(unittest.TestCase):
    def test_transcript_is_length_prefixed_and_unambiguous(self):
        transcript = build_transcript(NONCE, ENDPOINT_ID, RELAY_ID)
        relay_utf8 = RELAY_ID.encode("utf-8")
        expected = (
            TRANSCRIPT_PREFIX
            + len(relay_utf8).to_bytes(2, "big")
            + relay_utf8
            + len(NONCE).to_bytes(2, "big")
            + NONCE
            + ENDPOINT_ID
        )
        self.assertEqual(TRANSCRIPT_PREFIX, b"ADUP-AUTH-v1")
        self.assertEqual(transcript, expected)

    def test_proof_is_hmac_sha256_over_transcript(self):
        proof = compute_proof(SECRET, NONCE, ENDPOINT_ID, RELAY_ID)
        self.assertEqual(len(proof), 32)
        self.assertEqual(
            proof,
            hmac.new(SECRET, build_transcript(NONCE, ENDPOINT_ID, RELAY_ID), hashlib.sha256).digest(),
        )

    def test_known_answer_vector(self):
        self.assertEqual(build_transcript(NONCE, ENDPOINT_ID, RELAY_ID).hex(), KAT_EXPECTED_TRANSCRIPT_HEX)
        self.assertEqual(compute_proof(SECRET, NONCE, ENDPOINT_ID, RELAY_ID).hex(), KAT_EXPECTED_PROOF_HEX)

    def test_proof_is_bound_to_relay_id_nonce_endpoint_and_secret(self):
        base = compute_proof(SECRET, NONCE, ENDPOINT_ID, RELAY_ID)
        self.assertNotEqual(base, compute_proof(SECRET, NONCE, ENDPOINT_ID, "relay-other"))
        self.assertNotEqual(base, compute_proof(SECRET, bytes(32), ENDPOINT_ID, RELAY_ID))
        self.assertNotEqual(base, compute_proof(SECRET, NONCE, OTHER_ENDPOINT_ID, RELAY_ID))
        self.assertNotEqual(base, compute_proof(WRONG_SECRET, NONCE, ENDPOINT_ID, RELAY_ID))

    def test_input_validation(self):
        with self.assertRaises(ValueError):
            compute_proof(SECRET, b"short-nonce", ENDPOINT_ID, RELAY_ID)
        with self.assertRaises(ValueError):
            compute_proof(SECRET, NONCE, b"E" * 31, RELAY_ID)
        with self.assertRaises(ValueError):
            compute_proof(b"", NONCE, ENDPOINT_ID, RELAY_ID)
        with self.assertRaises(ValueError):
            compute_proof(SECRET, bytes(65536), ENDPOINT_ID, RELAY_ID)

    def test_method_identifier(self):
        self.assertEqual(DEVICE_HMAC_SHA256_V1, "device-credential-hmac-sha256-v1")


class InMemoryStoreTests(unittest.TestCase):
    def test_lookup_and_unknown(self):
        store = InMemoryDeviceCredentialStore({ENDPOINT_ID: SECRET})
        self.assertEqual(store.secret_for(ENDPOINT_ID), SECRET)
        self.assertIsNone(store.secret_for(OTHER_ENDPOINT_ID))

    def test_rejects_malformed_entries(self):
        with self.assertRaises(ValueError):
            InMemoryDeviceCredentialStore({b"short": SECRET})
        with self.assertRaises(ValueError):
            InMemoryDeviceCredentialStore({ENDPOINT_ID: b""})

    def test_repr_does_not_leak_secret(self):
        store = InMemoryDeviceCredentialStore({ENDPOINT_ID: SECRET})
        self.assertNotIn(SECRET.hex(), repr(store) + str(store))


class RelaySessionAuthTests(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryDeviceCredentialStore({ENDPOINT_ID: SECRET})

    def test_challenge_carries_fresh_random_nonce_and_method(self):
        a = session_with(self.store)
        b = session_with(self.store)
        _, challenge_a = run_to_challenge(a)
        _, challenge_b = run_to_challenge(b)
        self.assertEqual(challenge_a.method, DEVICE_HMAC_SHA256_V1)
        self.assertEqual(len(challenge_a.nonce), 32)
        self.assertNotEqual(challenge_a.nonce, challenge_b.nonce)

    def test_correct_proof_yields_auth_ok(self):
        session = session_with(self.store, nonce_source=lambda: NONCE)
        hello_ack, challenge = run_to_challenge(session)
        self.assertEqual(challenge.nonce, NONCE)
        proof = compute_proof(SECRET, challenge.nonce, ENDPOINT_ID, hello_ack.relay_id)
        (ok,) = decoded(session.handle_frame(make_frame(AuthResponse(proof), request_id=3)))
        self.assertIsInstance(ok, AuthOk)
        self.assertEqual(ok.endpoint_id, ENDPOINT_ID)
        self.assertEqual(session.state, RelaySessionState.AUTHENTICATED)

    def _assert_auth_fail(self, session, proof):
        frames = session.handle_frame(make_frame(AuthResponse(proof), request_id=3))
        (fail,) = decoded(frames)
        self.assertIsInstance(fail, AuthFail)
        self.assertEqual(fail.reason, "authentication failed")
        self.assertEqual(frames[0].request_id, 3)
        self.assertEqual(session.state, RelaySessionState.FAILED)
        with self.assertRaisesRegex(ProtocolError, "failed"):
            session.handle_frame(make_frame(AuthBegin(), request_id=4))
        return fail

    def test_wrong_secret_is_rejected(self):
        session = session_with(self.store)
        hello_ack, challenge = run_to_challenge(session)
        self._assert_auth_fail(
            session, compute_proof(WRONG_SECRET, challenge.nonce, ENDPOINT_ID, hello_ack.relay_id)
        )

    def test_unknown_endpoint_is_rejected_with_same_reason(self):
        session = session_with(self.store)
        hello_ack, challenge = run_to_challenge(session, endpoint_id=OTHER_ENDPOINT_ID)
        fail = self._assert_auth_fail(
            session, compute_proof(SECRET, challenge.nonce, OTHER_ENDPOINT_ID, hello_ack.relay_id)
        )
        self.assertEqual(fail.reason, "authentication failed")

    def test_default_session_has_no_credentials(self):
        session = RelaySession()
        hello_ack, challenge = run_to_challenge(session)
        self._assert_auth_fail(session, compute_proof(SECRET, challenge.nonce, ENDPOINT_ID, hello_ack.relay_id))

    def test_replayed_proof_fails_against_new_session(self):
        first = session_with(self.store)
        hello_ack, challenge = run_to_challenge(first)
        proof = compute_proof(SECRET, challenge.nonce, ENDPOINT_ID, hello_ack.relay_id)
        (ok,) = decoded(first.handle_frame(make_frame(AuthResponse(proof), request_id=3)))
        self.assertIsInstance(ok, AuthOk)
        second = session_with(self.store)
        run_to_challenge(second)
        self._assert_auth_fail(second, proof)

    def test_proof_for_other_relay_is_rejected(self):
        session = session_with(self.store, nonce_source=lambda: NONCE, relay_id="relay-a")
        run_to_challenge(session)
        self._assert_auth_fail(session, compute_proof(SECRET, NONCE, ENDPOINT_ID, "relay-b"))

    def test_proof_for_other_endpoint_is_rejected(self):
        store = InMemoryDeviceCredentialStore({ENDPOINT_ID: SECRET, OTHER_ENDPOINT_ID: SECRET})
        session = session_with(store, nonce_source=lambda: NONCE)
        run_to_challenge(session)
        self._assert_auth_fail(session, compute_proof(SECRET, NONCE, OTHER_ENDPOINT_ID, RELAY_ID))

    def test_authenticator_nonce_is_single_use(self):
        authenticator = ChallengeResponseAuthenticator(self.store, nonce_source=lambda: NONCE)
        challenge = authenticator.begin(ENDPOINT_ID, RELAY_ID)
        proof = compute_proof(SECRET, challenge.nonce, ENDPOINT_ID, RELAY_ID)
        self.assertTrue(authenticator.verify(proof))
        self.assertFalse(authenticator.verify(proof))
        self.assertFalse(authenticator.verify(proof))

    def test_authenticator_verify_before_begin_fails(self):
        authenticator = ChallengeResponseAuthenticator(self.store)
        self.assertFalse(authenticator.verify(compute_proof(SECRET, NONCE, ENDPOINT_ID, RELAY_ID)))

    def test_authenticator_rejects_short_nonce_source(self):
        authenticator = ChallengeResponseAuthenticator(self.store, nonce_source=lambda: b"too-short")
        with self.assertRaises(ValueError):
            authenticator.begin(ENDPOINT_ID, RELAY_ID)

    def test_wrong_length_proof_is_rejected_not_raised(self):
        session = session_with(self.store)
        run_to_challenge(session)
        self._assert_auth_fail(session, b"\x01")

    def test_repr_does_not_leak_secret_or_proof(self):
        authenticator = ChallengeResponseAuthenticator(self.store, nonce_source=lambda: NONCE)
        challenge = authenticator.begin(ENDPOINT_ID, RELAY_ID)
        proof = compute_proof(SECRET, challenge.nonce, ENDPOINT_ID, RELAY_ID)
        text = repr(authenticator) + str(authenticator)
        self.assertNotIn(SECRET.hex(), text)
        self.assertNotIn(proof.hex(), text)
        self.assertNotIn(str(SECRET), text)


class FakeChallengeServer:
    """Minimal relay impersonator so the client's method/nonce checks can be exercised."""

    def __init__(self, challenge_factory, after_response=None):
        self.challenge_factory = challenge_factory
        self.after_response = after_response
        self.received_proofs = []

    async def handle(self, reader, writer):
        connection = AsyncFramedConnection(reader, writer)
        try:
            hello = await connection.recv_frame()
            await connection.send_frame(
                make_frame(HelloAck(1, RELAY_ID, 1024 * 1024, 10, ("BYTE_RELAY",)), request_id=hello.request_id)
            )
            begin = await connection.recv_frame()
            await connection.send_frame(make_frame(self.challenge_factory(), request_id=begin.request_id))
            response = await connection.recv_frame()
            self.received_proofs.append(decode_payload(response.kind, response.payload).proof)
            if self.after_response is not None:
                await connection.send_frame(make_frame(self.after_response, request_id=response.request_id))
        except Exception:
            pass
        finally:
            await connection.close()


class RelaySessionPortAuthTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = generate_localhost_certificate(self.tmp.name, prefix="relay-auth")
        self.server_context = create_server_ssl_context(cert, key)
        self.client_context = create_client_ssl_context(cert)
        self.store = InMemoryDeviceCredentialStore({ENDPOINT_ID: SECRET})

    async def asyncTearDown(self):
        self.tmp.cleanup()

    def make_port(self, host, port, *, endpoint_id=ENDPOINT_ID, secret=SECRET):
        return RelaySessionPort(
            host,
            port,
            self.client_context,
            server_hostname="localhost",
            endpoint_id=endpoint_id,
            device_secret=secret,
        )

    async def test_real_credential_authenticates_over_tls(self):
        server = RelayTcpServer("127.0.0.1", 0, self.server_context, credential_store=self.store)
        await server.start()
        try:
            port = self.make_port(*server.address)
            await port.start()
            self.assertIsNotNone(port.connection)
            await port.close()
        finally:
            await server.close()

    async def test_wrong_secret_surfaces_auth_failure(self):
        server = RelayTcpServer("127.0.0.1", 0, self.server_context, credential_store=self.store)
        await server.start()
        try:
            port = self.make_port(*server.address, secret=WRONG_SECRET)
            with self.assertRaises(RelayAuthenticationError) as ctx:
                await port.start()
            self.assertNotIn(WRONG_SECRET.hex(), str(ctx.exception) + repr(ctx.exception))
            self.assertIsNone(port.connection)
        finally:
            await server.close()

    async def test_unknown_endpoint_surfaces_auth_failure(self):
        server = RelayTcpServer("127.0.0.1", 0, self.server_context, credential_store=self.store)
        await server.start()
        try:
            port = self.make_port(*server.address, endpoint_id=OTHER_ENDPOINT_ID)
            with self.assertRaises(RelayAuthenticationError):
                await port.start()
        finally:
            await server.close()

    async def test_server_without_credentials_rejects_everyone(self):
        server = RelayTcpServer("127.0.0.1", 0, self.server_context)
        await server.start()
        try:
            with self.assertRaises(RelayAuthenticationError):
                await self.make_port(*server.address).start()
        finally:
            await server.close()

    async def test_server_closes_connection_after_flushing_auth_fail(self):
        server = RelayTcpServer("127.0.0.1", 0, self.server_context, credential_store=self.store)
        await server.start()
        try:
            host, port = server.address
            reader, writer = await asyncio.open_connection(host, port, ssl=self.client_context, server_hostname="localhost")
            connection = AsyncFramedConnection(reader, writer)
            await connection.send_frame(make_frame(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ("BYTE_RELAY",))))
            await connection.recv_frame()
            await connection.send_frame(make_frame(AuthBegin(), request_id=2))
            challenge_frame = await connection.recv_frame()
            challenge = decode_payload(challenge_frame.kind, challenge_frame.payload)
            bad_proof = compute_proof(WRONG_SECRET, challenge.nonce, ENDPOINT_ID, RELAY_ID)
            await connection.send_frame(make_frame(AuthResponse(bad_proof), request_id=3))
            fail_frame = await asyncio.wait_for(connection.recv_frame(), 2.0)
            self.assertIsInstance(decode_payload(fail_frame.kind, fail_frame.payload), AuthFail)
            # Relay must close without waiting for any further client frame.
            with self.assertRaises((ConnectionClosed, ConnectionError, asyncio.IncompleteReadError)):
                await asyncio.wait_for(connection.recv_frame(), 2.0)
            await connection.close()
        finally:
            await server.close()

    async def test_server_rejects_both_store_and_factory(self):
        with self.assertRaises(ValueError):
            RelayTcpServer(
                "127.0.0.1", 0, self.server_context, credential_store=self.store, session_factory=RelaySession
            )

    async def _run_fake(self, fake):
        server = await asyncio.start_server(fake.handle, "127.0.0.1", 0, ssl=self.server_context)
        host, port = server.sockets[0].getsockname()[:2]
        return server, host, port

    async def test_unsupported_method_raises_protocol_error_without_sending_proof(self):
        fake = FakeChallengeServer(lambda: AuthChallenge(NONCE, "device-credential-other"))
        server, host, port = await self._run_fake(fake)
        try:
            with self.assertRaisesRegex(ProtocolError, "method"):
                await self.make_port(host, port).start()
            await asyncio.sleep(0.05)
            self.assertEqual(fake.received_proofs, [])
        finally:
            server.close()
            await server.wait_closed()

    async def test_short_nonce_is_rejected_by_client(self):
        fake = FakeChallengeServer(lambda: AuthChallenge(b"short", DEVICE_HMAC_SHA256_V1))
        server, host, port = await self._run_fake(fake)
        try:
            with self.assertRaisesRegex(ProtocolError, "nonce"):
                await self.make_port(host, port).start()
            await asyncio.sleep(0.05)
            self.assertEqual(fake.received_proofs, [])
        finally:
            server.close()
            await server.wait_closed()

    async def test_client_proof_matches_reference_function(self):
        fake = FakeChallengeServer(
            lambda: AuthChallenge(NONCE, DEVICE_HMAC_SHA256_V1), after_response=AuthOk(ENDPOINT_ID, ())
        )
        server, host, port = await self._run_fake(fake)
        try:
            port_obj = self.make_port(host, port)
            await port_obj.start()
            await port_obj.close()
            self.assertEqual(fake.received_proofs, [compute_proof(SECRET, NONCE, ENDPOINT_ID, RELAY_ID)])
        finally:
            server.close()
            await server.wait_closed()

    async def test_port_requires_secret_and_hides_it(self):
        with self.assertRaises(ValueError):
            RelaySessionPort(
                "127.0.0.1", 1, self.client_context, server_hostname="localhost", endpoint_id=ENDPOINT_ID, device_secret=b""
            )
        port = self.make_port("127.0.0.1", 1)
        self.assertNotIn(SECRET.hex(), repr(port) + str(port))
        self.assertNotIn(str(SECRET), repr(port) + str(port))
        self.assertFalse(hasattr(port, "device_secret"))


if __name__ == "__main__":
    unittest.main()
