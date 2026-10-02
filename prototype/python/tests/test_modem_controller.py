import unittest

from androidialup_modem.controller import ModemController, ModemState
from androidialup_modem.at_engine import ResultCode


class FakeSessionPort:
    def __init__(self):
        self.dials = []
        self.writes = []
        self.hangups = []
        self.answers = 0

    def dial(self, target):
        self.dials.append(target)

    def write_data(self, data):
        self.writes.append(bytes(data))

    def hangup(self, reason):
        self.hangups.append(reason)

    def answer(self):
        self.answers += 1


class ModemControllerTests(unittest.TestCase):
    def setUp(self):
        self.port = FakeSessionPort()
        self.out = []
        self.controller = ModemController(self.port, self.out.append)
        # Echo behavior is covered at the AT/profile layer; reducer tests focus on state/results.
        self.controller.engine.profile.echo = False

    def connect(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.controller.on_dial_result(ResultCode.CONNECT)
        self.out.clear()
        self.port.writes.clear()

    def test_at_dial_connect_and_binary_data(self):
        self.controller.feed_dte(b"AT\r", 0)
        self.assertIn(b"OK\r\n", self.out)
        self.out.clear()

        self.controller.feed_dte(b"ATDloopback\r", 10)
        self.assertEqual(self.controller.state, ModemState.DIALING)
        self.assertEqual(self.port.dials, ["loopback"])
        self.assertEqual(self.out, [])

        self.controller.on_dial_result(ResultCode.CONNECT)
        self.assertEqual(self.controller.state, ModemState.ONLINE_DATA)
        self.assertTrue(self.controller.signals.dcd)
        self.assertIn(b"CONNECT\r\n", self.out)

        self.out.clear()
        payload = b"\x00abc\xff\r"
        self.controller.feed_dte(payload, 100)
        self.assertEqual(b"".join(self.port.writes), payload)
        self.assertEqual(self.out, [])

    def test_large_online_read_is_forwarded_in_bounded_chunks_not_per_byte(self):
        self.connect()
        payload = (bytes(range(256)) * 256)  # 64 KiB, representative TCP read.
        self.controller.feed_dte(payload, 100)
        self.assertEqual(b"".join(self.port.writes), payload)
        self.assertLessEqual(len(self.port.writes), 4)

    def test_failed_dial_maps_to_terminal_result_once(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.controller.on_dial_result(ResultCode.BUSY)
        self.controller.on_dial_result(ResultCode.NO_CARRIER)
        self.assertEqual(self.state_result_lines(), [b"BUSY\r\n"])
        self.assertEqual(self.controller.state, ModemState.COMMAND)
        self.assertFalse(self.controller.signals.dcd)

    def test_escape_ato_and_ath(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.controller.on_dial_result(ResultCode.CONNECT)
        self.out.clear()

        self.controller.feed_dte(b"+", 1000)
        self.controller.feed_dte(b"+", 1001)
        self.controller.feed_dte(b"+", 1002)
        self.controller.on_timer(2002)
        self.assertEqual(self.controller.state, ModemState.ONLINE_COMMAND)
        self.assertEqual(self.out, [b"OK\r\n"])
        self.assertEqual(self.port.writes, [])

        self.out.clear()
        self.controller.feed_dte(b"ATO\r", 2100)
        self.assertEqual(self.controller.state, ModemState.ONLINE_DATA)
        self.assertEqual(self.out, [b"CONNECT\r\n"])

        self.out.clear()
        self.controller.feed_dte(b"+", 3100)
        self.controller.feed_dte(b"+", 3101)
        self.controller.feed_dte(b"+", 3102)
        self.controller.on_timer(4102)
        self.out.clear()
        self.controller.feed_dte(b"ATH\r", 4200)
        self.assertEqual(self.port.hangups, ["LOCAL_HANGUP"])
        self.assertEqual(self.controller.state, ModemState.COMMAND)
        self.assertFalse(self.controller.signals.dcd)
        self.assertEqual(self.out, [b"OK\r\n"])

    def test_remote_data_and_remote_hangup(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.controller.on_dial_result(ResultCode.CONNECT)
        self.out.clear()
        self.controller.on_remote_data(b"xyz\x00\xff")
        self.assertEqual(self.out, [b"xyz\x00\xff"])
        self.out.clear()
        self.controller.on_remote_hangup("REMOTE_HANGUP")
        self.assertEqual(self.controller.state, ModemState.COMMAND)
        self.assertFalse(self.controller.signals.dcd)
        self.assertEqual(self.out, [b"NO CARRIER\r\n"])

    def test_dte_disconnect_hangs_up_active_call(self):
        self.controller.feed_dte(b"ATDloopback\r", 0)
        self.controller.on_dial_result(ResultCode.CONNECT)
        self.controller.on_dte_disconnect()
        self.assertFalse(self.controller.signals.dcd)
        self.assertEqual(self.port.hangups, ["DTE_DISCONNECTED"])
        self.assertEqual(self.controller.state, ModemState.COMMAND)

    def state_result_lines(self):
        return [item for item in self.out if item.endswith(b"\r\n")]


if __name__ == "__main__":
    unittest.main()
