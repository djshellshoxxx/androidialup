import unittest

from androidialup_protocol.frame import ProtocolError
from androidialup_protocol.liveness import (
    DEFAULT_TIMEOUTS,
    HeartbeatAction,
    HeartbeatMonitor,
    LinkFailure,
    ManualClock,
    TimeoutConfig,
)


class TimeoutConfigTests(unittest.TestCase):
    def test_defaults_match_s1_spec_freeze_section_7(self):
        self.assertEqual(DEFAULT_TIMEOUTS.connect, 10.0)
        self.assertEqual(DEFAULT_TIMEOUTS.relay_auth, 8.0)
        self.assertEqual(DEFAULT_TIMEOUTS.dial_ack, 5.0)
        self.assertEqual(DEFAULT_TIMEOUTS.backend_dial_setup, 60.0)
        self.assertEqual(DEFAULT_TIMEOUTS.heartbeat_interval, 10.0)
        self.assertEqual(DEFAULT_TIMEOUTS.heartbeat_failure, 30.0)
        self.assertEqual(DEFAULT_TIMEOUTS.disconnect_grace, 3.0)

    def test_heartbeat_seconds_advertised_is_whole_seconds_and_at_least_one(self):
        self.assertEqual(DEFAULT_TIMEOUTS.heartbeat_seconds_advertised, 10)
        self.assertEqual(TimeoutConfig(heartbeat_interval=0.05).heartbeat_seconds_advertised, 1)

    def test_non_positive_values_rejected(self):
        with self.assertRaises(ValueError):
            TimeoutConfig(heartbeat_failure=0)
        with self.assertRaises(ValueError):
            TimeoutConfig(relay_auth=-1)


class ManualClockTests(unittest.TestCase):
    def test_only_moves_forward(self):
        clock = ManualClock(5.0)
        self.assertEqual(clock(), 5.0)
        clock.advance(1.5)
        self.assertEqual(clock(), 6.5)
        with self.assertRaises(ValueError):
            clock.advance(-0.1)


class LinkFailureTests(unittest.TestCase):
    def test_reason_must_be_in_s1_taxonomy(self):
        self.assertEqual(LinkFailure("NETWORK_LOST", "HEARTBEAT_TIMEOUT").reason, "NETWORK_LOST")
        with self.assertRaises(ValueError):
            LinkFailure("HEARTBEAT_TIMEOUT")


class HeartbeatMonitorTests(unittest.TestCase):
    def setUp(self):
        self.clock = ManualClock(100.0)
        self.monitor = HeartbeatMonitor(interval=10.0, failure=30.0, now=self.clock())

    def test_no_ping_before_interval_of_inbound_silence(self):
        self.clock.advance(9.999)
        self.assertEqual(self.monitor.poll(self.clock()), HeartbeatAction.none())

    def test_ping_after_interval_then_timeout_after_failure_interval(self):
        self.clock.advance(10.0)
        action = self.monitor.poll(self.clock())
        self.assertEqual(action.kind, "PING")
        self.assertTrue(self.monitor.outstanding)
        # Exactly one PING outstanding: no second PING while waiting.
        self.clock.advance(29.999)
        self.assertEqual(self.monitor.poll(self.clock()), HeartbeatAction.none())
        self.clock.advance(0.001)
        self.assertEqual(self.monitor.poll(self.clock()).kind, "TIMEOUT")
        # Timeout is reported exactly once.
        self.clock.advance(100)
        self.assertEqual(self.monitor.poll(self.clock()), HeartbeatAction.none())

    def test_inbound_activity_defers_ping(self):
        self.clock.advance(8)
        self.monitor.on_inbound(self.clock())
        self.clock.advance(8)
        self.assertEqual(self.monitor.poll(self.clock()), HeartbeatAction.none())
        self.clock.advance(2)
        self.assertEqual(self.monitor.poll(self.clock()).kind, "PING")

    def test_other_inbound_frames_do_not_answer_an_outstanding_ping(self):
        self.clock.advance(10)
        self.monitor.poll(self.clock())
        for _ in range(4):
            self.clock.advance(10)
            self.monitor.on_inbound(self.clock())
        self.assertEqual(self.monitor.poll(self.clock()).kind, "TIMEOUT")

    def test_matching_pong_clears_outstanding_and_restarts_idle(self):
        self.clock.advance(10)
        nonce = self.monitor.poll(self.clock()).nonce
        self.clock.advance(1)
        self.monitor.on_pong(nonce, self.clock())
        self.assertFalse(self.monitor.outstanding)
        self.clock.advance(9.5)
        self.assertEqual(self.monitor.poll(self.clock()), HeartbeatAction.none())
        self.clock.advance(0.5)
        second = self.monitor.poll(self.clock())
        self.assertEqual(second.kind, "PING")
        self.assertNotEqual(second.nonce, nonce)

    def test_wrong_nonce_pong_is_protocol_violation(self):
        self.clock.advance(10)
        nonce = self.monitor.poll(self.clock()).nonce
        with self.assertRaises(ProtocolError):
            self.monitor.on_pong(nonce ^ 1, self.clock())

    def test_unsolicited_pong_is_protocol_violation(self):
        with self.assertRaises(ProtocolError):
            self.monitor.on_pong(1, self.clock())

    def test_next_deadline_tracks_ping_and_failure(self):
        self.assertEqual(self.monitor.next_deadline(), 110.0)
        self.clock.advance(10)
        self.monitor.poll(self.clock())
        self.assertEqual(self.monitor.next_deadline(), 140.0)

    def test_nonce_factory_injectable(self):
        monitor = HeartbeatMonitor(interval=1, failure=1, now=0.0, nonce_factory=lambda: 0xDEADBEEF)
        self.assertEqual(monitor.poll(1.0).nonce, 0xDEADBEEF)

    def test_invalid_intervals_rejected(self):
        with self.assertRaises(ValueError):
            HeartbeatMonitor(interval=0, failure=1, now=0.0)
        with self.assertRaises(ValueError):
            HeartbeatMonitor(interval=1, failure=0, now=0.0)


if __name__ == "__main__":
    unittest.main()
