"""Hardware-free contract tests for AOA device discovery and mode switching.

The CLI must use this module rather than owning USB discovery, while preserving
its pre-existing command-line behavior and the optional PyUSB import boundary.
"""
from __future__ import annotations

import argparse
import unittest
from unittest.mock import patch

from androidialup_host.accessory_discovery import obtain_accessory, wait_for_accessory
from androidialup_host.cli import _obtain_accessory


class FakeCore:
    def __init__(self, *, initial=None, accessory=None):
        self.initial = initial
        self.accessory = accessory
        self.calls = []

    def find(self, **criteria):
        self.calls.append(criteria)
        if criteria.get("idVendor") == 0x18D1 and criteria.get("idProduct") in (0x2D00, 0x2D01):
            return self.accessory
        if criteria == {"idVendor": 0x1234, "idProduct": 0x5678}:
            return self.initial
        return None


def args(*, device=None, serial="host-01", timeout=0.01):
    return argparse.Namespace(device=device, serial=serial, reenumeration_timeout=timeout)


class AccessoryDiscoveryTests(unittest.TestCase):
    def test_current_accessory_skips_mode_switch_and_initial_vid_pid(self):
        found = object()
        core = FakeCore(accessory=found)
        with patch("androidialup_host.accessory_discovery.enter_accessory_mode") as switch:
            self.assertIs(obtain_accessory(core, args()), found)
            switch.assert_not_called()
        self.assertIs(_obtain_accessory(core, args()), found)

    def test_missing_initial_id_preserves_error_message(self):
        with self.assertRaisesRegex(RuntimeError, "pass --device VID:PID"):
            obtain_accessory(FakeCore(), args())

    def test_missing_initial_device_preserves_vid_pid_diagnostics(self):
        with self.assertRaisesRegex(RuntimeError, "1234:5678"):
            obtain_accessory(FakeCore(), args(device=(0x1234, 0x5678)))

    def test_switch_negotiates_identity_and_discovers_reenumerated_accessory(self):
        first = object()
        next_device = object()
        core = FakeCore(initial=first)
        with patch("androidialup_host.accessory_discovery.enter_accessory_mode", return_value=2) as switch:
            with patch("androidialup_host.accessory_discovery.find_accessory_device") as find:
                # Existing device check, then re-enumeration check.
                find.side_effect = [None, next_device]
                found = obtain_accessory(core, args(device=(0x1234, 0x5678), serial="demo", timeout=0.1))
            self.assertIs(found, next_device)
            switch.assert_called_once()
            passed_identity = switch.call_args.args[1]
            self.assertEqual("demo", passed_identity.serial)
            self.assertEqual("AndroidDialup", passed_identity.model)

    def test_reenumeration_timeout_does_not_hang(self):
        core = FakeCore()
        with self.assertRaisesRegex(RuntimeError, "before timeout"):
            with patch("androidialup_host.accessory_discovery.enter_accessory_mode", return_value=2):
                with patch("androidialup_host.accessory_discovery.find_accessory_device", return_value=None):
                    obtain_accessory(FakeCore(initial=object()), args(device=(0x1234, 0x5678), timeout=0.01))

    def test_zero_wait_for_accessory_returns_none_without_polling(self):
        with patch("androidialup_host.accessory_discovery.find_accessory_device") as find:
            self.assertIsNone(wait_for_accessory(FakeCore(), 0))
            find.assert_not_called()


if __name__ == "__main__":
    unittest.main()
