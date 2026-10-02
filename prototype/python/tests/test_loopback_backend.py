import unittest

from androidialup_gateway.backend import BackendEventType, BackendState
from androidialup_gateway.loopback import LoopbackBackend, LoopbackMode


class LoopbackBackendTests(unittest.TestCase):
    def test_open_dial_progress_and_connect(self):
        backend = LoopbackBackend()
        backend.open()
        backend.dial("loopback")
        events = backend.poll_events()
        self.assertEqual([e.type for e in events], [BackendEventType.PROGRESS, BackendEventType.CONNECTED])
        self.assertEqual(backend.state, BackendState.CONNECTED)

    def test_echo_is_binary_clean(self):
        backend = LoopbackBackend(mode=LoopbackMode.ECHO)
        backend.open(); backend.dial("loopback"); backend.poll_events()
        payload = b"\x00abc\xff\r\n+"
        self.assertEqual(backend.write(payload), len(payload))
        events = backend.poll_events()
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].type, BackendEventType.DATA)
        self.assertEqual(events[0].data, payload)

    def test_sink_returns_no_data(self):
        backend = LoopbackBackend(mode=LoopbackMode.SINK)
        backend.open(); backend.dial("loopback"); backend.poll_events()
        backend.write(b"abc")
        self.assertEqual(backend.poll_events(), [])

    def test_failure_targets_are_deterministic(self):
        expected = {
            "busy": "BUSY",
            "noanswer": "NO_ANSWER",
            "nodialtone": "NO_DIALTONE",
        }
        for target, reason in expected.items():
            with self.subTest(target=target):
                backend = LoopbackBackend(); backend.open(); backend.dial(target)
                events = backend.poll_events()
                self.assertEqual(events[-1].type, BackendEventType.FAILED)
                self.assertEqual(events[-1].detail, reason)
                self.assertEqual(backend.state, BackendState.READY)

    def test_pattern_mode_emits_configured_pattern(self):
        backend = LoopbackBackend(mode=LoopbackMode.PATTERN, pattern=b"XYZ")
        backend.open(); backend.dial("loopback"); backend.poll_events()
        backend.write(b"ignored")
        self.assertEqual(backend.poll_events()[0].data, b"XYZ")

    def test_hangup_is_idempotent(self):
        backend = LoopbackBackend(); backend.open(); backend.dial("loopback"); backend.poll_events()
        backend.hangup("LOCAL_HANGUP")
        backend.hangup("LOCAL_HANGUP")
        events = backend.poll_events()
        self.assertEqual([e.type for e in events], [BackendEventType.HANGUP])
        self.assertEqual(backend.state, BackendState.READY)

    def test_write_requires_connected_state(self):
        backend = LoopbackBackend(); backend.open()
        with self.assertRaises(RuntimeError):
            backend.write(b"abc")


if __name__ == "__main__":
    unittest.main()
