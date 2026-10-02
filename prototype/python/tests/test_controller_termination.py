import unittest

from androidialup_modem.at_engine import ResultCode
from androidialup_modem.controller import ModemController, ModemState

from test_modem_controller import FakeSessionPort


class ControllerTerminationTests(unittest.TestCase):
    def setUp(self):
        self.port = FakeSessionPort()
        self.events = []  # interleaved ("dte", bytes) / ("snap", snapshot)
        self.controller = ModemController(
            self.port,
            lambda data: self.events.append(("dte", bytes(data))),
            snapshot_listener=lambda snap: self.events.append(("snap", snap)),
        )
        self.controller.engine.profile.echo = False

    def connect(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.controller.on_dial_result(ResultCode.CONNECT)
        self.events.clear()

    def snapshots(self):
        return [item for kind, item in self.events if kind == "snap"]

    def dte(self):
        return b"".join(item for kind, item in self.events if kind == "dte")

    def test_transport_failure_online_gives_one_no_carrier_dcd_first(self):
        self.connect()
        self.controller.on_remote_hangup("NETWORK_LOST", "HEARTBEAT_TIMEOUT")
        self.controller.on_remote_hangup("NETWORK_LOST", "TRANSPORT_CLOSED")
        self.assertEqual(self.dte(), b"NO CARRIER\r\n")
        snaps = self.snapshots()
        self.assertEqual(len(snaps), 1)
        self.assertEqual(snaps[0].state, ModemState.COMMAND)
        self.assertFalse(snaps[0].signals.dcd)
        self.assertEqual(snaps[0].terminal_reason, "NETWORK_LOST")
        # DCD low is published before NO CARRIER reaches the DTE (S1_AT_DTE section 15).
        self.assertEqual(self.events[0][0], "snap")
        self.assertEqual(self.controller.terminal_reason, "NETWORK_LOST")
        self.assertEqual(self.controller.terminal_detail, "HEARTBEAT_TIMEOUT")

    def test_transport_failure_in_online_command(self):
        self.controller.engine.profile.s_registers[12] = 1
        self.connect()
        self.controller.feed_dte(b"+++", 100)
        self.controller.on_timer(200)
        self.assertEqual(self.controller.state, ModemState.ONLINE_COMMAND)
        self.events.clear()
        self.controller.on_remote_hangup("NETWORK_LOST", "TRANSPORT_CLOSED")
        self.assertEqual(self.dte(), b"NO CARRIER\r\n")
        self.assertEqual([s.state for s in self.snapshots()], [ModemState.COMMAND])

    def test_failure_while_dialing_gives_no_carrier_with_reason(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.events.clear()
        self.controller.on_remote_hangup("RELAY_UNAVAILABLE", "DIAL_ACK_TIMEOUT")
        self.controller.on_dial_result(ResultCode.CONNECT)  # stale, must not resurrect
        self.assertEqual(self.dte(), b"NO CARRIER\r\n")
        self.assertEqual(self.controller.state, ModemState.COMMAND)
        self.assertEqual(self.controller.terminal_reason, "RELAY_UNAVAILABLE")
        self.assertEqual(self.controller.terminal_detail, "DIAL_ACK_TIMEOUT")

    def test_dial_failure_keeps_taxonomy_reason(self):
        self.controller.feed_dte(b"ATDbusy\r", 0)
        self.controller.on_dial_result(ResultCode.BUSY, "BACKEND_BUSY")
        self.assertEqual(self.controller.terminal_reason, "BACKEND_BUSY")
        self.assertEqual(self.dte(), b"BUSY\r\n")

    def test_dial_failure_without_reason_keeps_legacy_name(self):
        self.controller.feed_dte(b"ATDbusy\r", 0)
        self.controller.on_dial_result(ResultCode.BUSY)
        self.assertEqual(self.controller.terminal_reason, "BUSY")

    def test_snapshots_published_for_dial_and_connect(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.controller.on_dial_result(ResultCode.CONNECT)
        self.assertEqual(
            [(s.state, s.signals.dcd) for s in self.snapshots()],
            [(ModemState.DIALING, False), (ModemState.ONLINE_DATA, True)],
        )

    def test_failure_when_idle_is_ignored(self):
        self.controller.on_remote_hangup("NETWORK_LOST", "HEARTBEAT_TIMEOUT")
        self.assertEqual(self.events, [])
        self.assertIsNone(self.controller.terminal_reason)


if __name__ == "__main__":
    unittest.main()
