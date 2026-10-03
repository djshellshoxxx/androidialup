import argparse
import unittest

from androidialup_host.cli import _obtain_accessory, build_parser, parse_usb_id


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

    def test_existing_accessory_does_not_require_initial_device_argument(self):
        accessory = object()
        core = FakeCore(accessory)
        args = build_parser().parse_args([])

        self.assertIs(accessory, _obtain_accessory(core, args))


if __name__ == "__main__":
    unittest.main()
