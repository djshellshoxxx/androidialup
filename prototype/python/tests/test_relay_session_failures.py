import unittest

from androidialup_gateway.backend import BackendState
from androidialup_gateway.loopback import LoopbackBackend
from androidialup_protocol.liveness import LinkFailure
from androidialup_protocol.messages import (
    CallTerminated,
    DataBytes,
    DialFailed,
    DialFailure,
    TerminationSource,
    decode_payload,
)
from androidialup_relay.session import RelaySession, RelaySessionState

from test_relay_session import CALL_ID, SESSION_ID, authenticated_session, dial_loopback, make_frame


class CrashingBackend(LoopbackBackend):
    """Loopback backend whose gateway process can be 'killed' mid-call."""

    def __init__(self):
        super().__init__()
        self.dead = False
        self.dial_raises = False

    def dial(self, target, options=None):
        if self.dial_raises:
            raise OSError("gateway unreachable")
        super().dial(target, options)

    def write(self, data):
        if self.dead:
            raise ConnectionResetError("gateway process died")
        return super().write(data)

    def poll_events(self, *, now=None):
        if self.dead:
            raise ConnectionResetError("gateway process died")
        return super().poll_events(now=now)


def session_with(backend):
    session = authenticated_session()
    session.backend = backend
    return session


def decoded(frames):
    return [decode_payload(frame.kind, frame.payload) for frame in frames]


class RelaySessionFailureTests(unittest.TestCase):
    def test_backend_death_on_poll_terminates_call_once_and_keeps_session(self):
        backend = CrashingBackend()
        session = session_with(backend)
        dial_loopback(session)
        self.assertEqual(session.state, RelaySessionState.CONNECTED)
        backend.dead = True
        messages = decoded(session.poll())
        self.assertEqual(messages, [CallTerminated("BACKEND_UNAVAILABLE", TerminationSource.GATEWAY, "BACKEND_FAILED")])
        self.assertEqual(session.last_call_failure, LinkFailure("BACKEND_UNAVAILABLE", "BACKEND_FAILED"))
        self.assertEqual(session.state, RelaySessionState.AUTHENTICATED)
        backend.dead = False
        self.assertEqual(session.poll(), [])

    def test_backend_death_on_write_terminates_call(self):
        backend = CrashingBackend()
        session = session_with(backend)
        dial_loopback(session)
        backend.dead = True
        frames = session.handle_frame(make_frame(DataBytes(0, b"abc"), call_id=CALL_ID, session_id=SESSION_ID, request_id=0))
        self.assertEqual(decoded(frames), [CallTerminated("BACKEND_UNAVAILABLE", TerminationSource.GATEWAY, "BACKEND_FAILED")])
        self.assertEqual(session.state, RelaySessionState.AUTHENTICATED)

    def test_backend_dial_exception_maps_to_gateway_unavailable(self):
        backend = CrashingBackend()
        backend.dial_raises = True
        session = session_with(backend)
        _, frames = dial_loopback(session)
        messages = decoded(frames)
        self.assertEqual(len(messages), 1)
        self.assertIsInstance(messages[0], DialFailed)
        self.assertEqual(messages[0].reason, DialFailure.GATEWAY_UNAVAILABLE)
        self.assertEqual(session.state, RelaySessionState.AUTHENTICATED)

    def test_abort_releases_backend_lease(self):
        backend = LoopbackBackend()
        session = session_with(backend)
        dial_loopback(session)
        self.assertEqual(backend.state, BackendState.CONNECTED)
        session.abort(LinkFailure("NETWORK_LOST", "HEARTBEAT_TIMEOUT"))
        self.assertEqual(backend.state, BackendState.CLOSED)
        self.assertEqual(session.last_call_failure, LinkFailure("NETWORK_LOST", "HEARTBEAT_TIMEOUT"))
        self.assertEqual(session.state, RelaySessionState.FAILED)

    def test_abort_without_call_is_harmless(self):
        session = authenticated_session()
        session.abort(LinkFailure("NETWORK_LOST", "TRANSPORT_CLOSED"))
        self.assertIsNone(session.last_call_failure)
        self.assertEqual(session.state, RelaySessionState.FAILED)

    def test_heartbeat_seconds_is_configurable(self):
        session = RelaySession(heartbeat_seconds=3)
        from androidialup_protocol.messages import Hello
        frames = session.handle_frame(make_frame(Hello("AndroidDialup", "0.1", 1, 1, b"E" * 32, ())))
        self.assertEqual(decode_payload(frames[0].kind, frames[0].payload).heartbeat_seconds, 3)


if __name__ == "__main__":
    unittest.main()
