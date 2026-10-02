import unittest

from androidialup_gateway.loopback import LoopbackBackend
from androidialup_protocol.frame import Frame, FrameKind, ProtocolError, ZERO_ID
from androidialup_protocol.messages import (
    AuthBegin, AuthChallenge, AuthOk, AuthResponse, CallProgress, CallTerminated,
    DataBytes, DialAccepted, DialRequest, FlowStatus, HangupAck, HangupRequest,
    Hello, HelloAck, Mode, NetworkTransport, Ping, Pong, ProgressPhase,
    decode_payload, encode_payload, kind_for_message,
)
from androidialup_relay.session import RelaySession, RelaySessionState

CALL_ID = b"C" * 16
SESSION_ID = b"S" * 16
ENDPOINT_ID = b"E" * 32


def make_frame(message, *, call_id=ZERO_ID, session_id=ZERO_ID, request_id=1):
    return Frame(
        kind=kind_for_message(message),
        call_id=call_id,
        session_id=session_id,
        request_id=request_id,
        payload=encode_payload(message),
    )


def authenticated_session():
    session = RelaySession(session_id_factory=lambda: SESSION_ID)
    hello = session.handle_frame(make_frame(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ("BYTE_RELAY",))))
    assert isinstance(decode_payload(hello[0].kind, hello[0].payload), HelloAck)
    challenge = session.handle_frame(make_frame(AuthBegin(), request_id=2))
    assert isinstance(decode_payload(challenge[0].kind, challenge[0].payload), AuthChallenge)
    auth = session.handle_frame(make_frame(AuthResponse(b"test-proof"), request_id=3))
    assert isinstance(decode_payload(auth[0].kind, auth[0].payload), AuthOk)
    return session


def dial_loopback(session):
    request = DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI, ("BYTE_RELAY",), 60000)
    frames = session.handle_frame(make_frame(request, call_id=CALL_ID, request_id=4))
    return request, frames


class RelaySessionTests(unittest.TestCase):
    def test_hello_auth_dial_required_order(self):
        session = RelaySession(session_id_factory=lambda: SESSION_ID)
        with self.assertRaisesRegex(ProtocolError, "HELLO"):
            session.handle_frame(make_frame(AuthBegin()))
        session.handle_frame(make_frame(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ())))
        with self.assertRaisesRegex(ProtocolError, "AUTH_BEGIN"):
            session.handle_frame(make_frame(AuthResponse(b"test-proof")))

    def test_dial_accepts_and_progresses_to_connected(self):
        session = authenticated_session()
        _, frames = dial_loopback(session)
        messages = [decode_payload(f.kind, f.payload) for f in frames]
        self.assertIsInstance(messages[0], DialAccepted)
        self.assertEqual(messages[0].assigned_session_id, SESSION_ID)
        phases = [m.phase for m in messages if isinstance(m, CallProgress)]
        self.assertEqual(phases, [ProgressPhase.DIALING, ProgressPhase.CONNECTED])
        self.assertEqual(session.state, RelaySessionState.CONNECTED)
        self.assertTrue(any(isinstance(m, FlowStatus) for m in messages))

    def test_data_echo_preserves_binary_and_sequence(self):
        session = authenticated_session(); dial_loopback(session)
        payload = b"\x00abc\xff"
        frames = session.handle_frame(make_frame(DataBytes(0, payload), call_id=CALL_ID, session_id=SESSION_ID, request_id=0))
        messages = [decode_payload(f.kind, f.payload) for f in frames]
        echoed = [m for m in messages if isinstance(m, DataBytes)]
        self.assertEqual(echoed, [DataBytes(0, payload)])

    def test_call_and_session_id_mismatch_is_rejected(self):
        session = authenticated_session(); dial_loopback(session)
        with self.assertRaisesRegex(ProtocolError, "call_id"):
            session.handle_frame(make_frame(DataBytes(0, b"x"), call_id=b"X" * 16, session_id=SESSION_ID))
        with self.assertRaisesRegex(ProtocolError, "session_id"):
            session.handle_frame(make_frame(DataBytes(0, b"x"), call_id=CALL_ID, session_id=b"X" * 16))

    def test_data_sequence_gap_is_rejected(self):
        session = authenticated_session(); dial_loopback(session)
        with self.assertRaisesRegex(ProtocolError, "gap"):
            session.handle_frame(make_frame(DataBytes(1, b"x"), call_id=CALL_ID, session_id=SESSION_ID))

    def test_flow_status_can_close_and_reopen_echo_window(self):
        session = authenticated_session(); dial_loopback(session)
        session.handle_frame(make_frame(FlowStatus(0, 1), call_id=CALL_ID, session_id=SESSION_ID))
        with self.assertRaisesRegex(ProtocolError, "flow window"):
            session.handle_frame(make_frame(DataBytes(0, b"x"), call_id=CALL_ID, session_id=SESSION_ID))
        session.handle_frame(make_frame(FlowStatus(10, 0), call_id=CALL_ID, session_id=SESSION_ID))
        frames = session.handle_frame(make_frame(DataBytes(1, b"y"), call_id=CALL_ID, session_id=SESSION_ID))
        self.assertTrue(any(isinstance(decode_payload(f.kind, f.payload), DataBytes) for f in frames))

    def test_ping_gets_pong(self):
        session = authenticated_session()
        frames = session.handle_frame(make_frame(Ping(77, 1234), request_id=55))
        self.assertEqual(decode_payload(frames[0].kind, frames[0].payload), Pong(77))
        self.assertEqual(frames[0].request_id, 55)

    def test_hangup_ack_and_terminal_are_emitted_once(self):
        session = authenticated_session(); dial_loopback(session)
        frames = session.handle_frame(make_frame(HangupRequest("user"), call_id=CALL_ID, session_id=SESSION_ID, request_id=9))
        messages = [decode_payload(f.kind, f.payload) for f in frames]
        self.assertEqual(sum(isinstance(m, HangupAck) for m in messages), 1)
        self.assertEqual(sum(isinstance(m, CallTerminated) for m in messages), 1)
        self.assertEqual(session.state, RelaySessionState.AUTHENTICATED)

    def test_duplicate_active_dial_returns_existing_acceptance_without_redial(self):
        backend = LoopbackBackend()
        session = RelaySession(backend=backend, session_id_factory=lambda: SESSION_ID)
        session.handle_frame(make_frame(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ())))
        session.handle_frame(make_frame(AuthBegin(), request_id=2))
        session.handle_frame(make_frame(AuthResponse(b"test-proof"), request_id=3))
        request, first = dial_loopback(session)
        duplicate = session.handle_frame(make_frame(request, call_id=CALL_ID, session_id=ZERO_ID, request_id=4))
        self.assertEqual(len(duplicate), 1)
        self.assertIsInstance(decode_payload(duplicate[0].kind, duplicate[0].payload), DialAccepted)
        self.assertEqual(session.state, RelaySessionState.CONNECTED)


if __name__ == "__main__":
    unittest.main()
