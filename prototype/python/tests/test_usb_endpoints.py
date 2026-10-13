"""Hardware-free contract tests for selecting Android AOA bulk endpoints."""
from __future__ import annotations

import unittest
from types import SimpleNamespace

from androidialup_host.usb_endpoints import (
    USB_ENDPOINT_IN,
    USB_TRANSFER_TYPE_BULK,
    USB_TRANSFER_TYPE_MASK,
    select_bulk_endpoints,
)


def descriptor(address: int, attributes: int = 2):
    return SimpleNamespace(bEndpointAddress=address, bmAttributes=attributes)


class UsbBulkEndpointSelectionTests(unittest.TestCase):
    def test_selects_first_bulk_in_and_out_in_interface_order(self):
        interrupt_in = descriptor(0x82, 3)
        in1 = descriptor(0x81)
        out1 = descriptor(0x01)
        in2 = descriptor(0x83)
        out2 = descriptor(0x02)
        self.assertEqual(USB_ENDPOINT_IN, 0x80)
        self.assertEqual(USB_TRANSFER_TYPE_MASK, 3)
        self.assertEqual(USB_TRANSFER_TYPE_BULK, 2)
        self.assertEqual((in1, out1),
                         select_bulk_endpoints([interrupt_in, out1, in1, in2, out2]))

    def test_skips_all_non_bulk_transfer_types(self):
        interface = [descriptor(0x81, 3), descriptor(0x01, 1),
                     descriptor(0x80, 0), descriptor(0x82, 2), descriptor(0x03, 2)]
        incoming, outgoing = select_bulk_endpoints(iter(interface))
        self.assertIs(interface[3], incoming)
        self.assertIs(interface[4], outgoing)

    def test_missing_input_or_output_uses_legacy_exception_text(self):
        with self.assertRaisesRegex(RuntimeError, "AOA interface does not expose both bulk IN and OUT endpoints"):
            select_bulk_endpoints([descriptor(0x81)])
        with self.assertRaisesRegex(RuntimeError, "AOA interface does not expose both bulk IN and OUT endpoints"):
            select_bulk_endpoints([descriptor(0x01)])
        with self.assertRaisesRegex(RuntimeError, "AOA interface does not expose both bulk IN and OUT endpoints"):
            select_bulk_endpoints([])

    def test_does_not_open_or_mutate_usb_devices(self):
        incoming, outgoing = descriptor(0x81), descriptor(0x01)
        interface = [incoming, outgoing]
        result = select_bulk_endpoints(interface)
        self.assertEqual((incoming, outgoing), result)
        self.assertEqual([incoming, outgoing], interface)


if __name__ == "__main__":
    unittest.main()
