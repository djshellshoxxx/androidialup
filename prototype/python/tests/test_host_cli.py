import argparse
import io
import unittest
from contextlib import redirect_stderr
from unittest.mock import MagicMock, patch

from androidialup_host.cli import _obtain_accessory, build_parser, parse_usb_id, run


class FakeCore:
    def __init__(self, accessory=None):
        self.accessory = accessory
        self.calls = []

    def find(self, **kwargs):
        self.calls.append(kwargs)
        if kwargs.get("idVendor") == 0x18D1 and kwargs.get("idProduct") == 0x2D00:
            return self.accessory
        return None


class HostCliTest(unittest.TestCase):
    def test_parse_usb_id_accepts_hex_vid_pid(self):
        self.assertEqual((0x18D1, 0x4EE7), parse_usb_id("18d1:4ee7"))

    def test_parse_usb_id_rejects_invalid_value(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            parse_usb_id("not-a-device")

    def test_run_retains_listener_setup_bridge_handoff_and_output(self):
        """Characterize the existing CLI lifecycle before moving socket ownership."""
        server = MagicMock()
        server.__enter__.return_value = server
        client = MagicMock()
        client.__enter__.return_value = client
        server.getsockname.return_value = ("127.0.0.1", 3456)
        server.accept.return_value = (client, ("127.0.0.1", 5678))
        usb_core = object()
        accessory = object()
        transport = object()
        errors = io.StringIO()
        with patch("androidialup_host.cli.load_pyusb", return_value=(usb_core, None)), \
             patch("androidialup_host.cli._obtain_accessory", return_value=accessory) as obtain, \
             patch("androidialup_host.cli.open_bulk_transport", return_value=transport) as open_transport, \
             patch("socket.socket", return_value=server) as factory, \
             patch("androidialup_host.cli.bridge_socket_to_transport") as bridge, \
             redirect_stderr(errors):
            result = run(["--listen-host", "127.0.0.1", "--listen-port", "3456",
                          "--usb-timeout-ms", "450"])
        self.assertEqual(0, result)
        obtain.assert_called_once()
        self.assertIs(usb_core, obtain.call_args.args[0])
        self.assertEqual(450, open_transport.call_args.kwargs["timeout_ms"])
        factory.assert_called_once()
        server.bind.assert_called_once_with(("127.0.0.1", 3456))
        server.listen.assert_called_once_with(1)
        bridge.assert_called_once_with(client, transport)
        client.__exit__.assert_called_once()
        server.__exit__.assert_called_once()
        self.assertIn("AndroidDialup USB DTE listening on 127.0.0.1:3456", errors.getvalue())
        self.assertIn("DTE client connected from 127.0.0.1:5678", errors.getvalue())

    def test_run_retains_bridge_failure_exit_code_and_cleanup(self):
        server = MagicMock()
        server.__enter__.return_value = server
        client = MagicMock()
        client.__enter__.return_value = client
        server.getsockname.return_value = ("127.0.0.1", 2323)
        server.accept.return_value = (client, ("127.0.0.1", 9000))
        errors = io.StringIO()
        with patch("androidialup_host.cli.load_pyusb", return_value=(object(), None)), \
             patch("androidialup_host.cli._obtain_accessory", return_value=object()), \
             patch("androidialup_host.cli.open_bulk_transport", return_value=object()), \
             patch("socket.socket", return_value=server), \
             patch("androidialup_host.cli.bridge_socket_to_transport", side_effect=OSError("USB cable removed")), \
             redirect_stderr(errors):
            result = run([])
        self.assertEqual(3, result)
        self.assertIn("bridge ended: USB cable removed", errors.getvalue())
        client.__exit__.assert_called_once()
        server.__exit__.assert_called_once()

    def test_run_rejects_bad_listen_port_before_loading_usb(self):
        with patch("androidialup_host.cli.load_pyusb") as load:
            with self.assertRaisesRegex(SystemExit, "--listen-port must be 1..65535"):
                run(["--listen-port", "0"])
        load.assert_not_called()

    def test_existing_accessory_does_not_require_initial_device_argument(self):
        accessory = object()
        core = FakeCore(accessory)
        args = build_parser().parse_args([])

        self.assertIs(accessory, _obtain_accessory(core, args))


if __name__ == "__main__":
    unittest.main()
