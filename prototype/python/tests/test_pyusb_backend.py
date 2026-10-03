import unittest

from androidialup_host.pyusb_backend import (
    ACCESSORY_PRODUCT_IDS,
    GOOGLE_VENDOR_ID,
    PyUsbControlDevice,
    find_accessory_device,
    open_bulk_transport,
)


class FakeEndpoint:
    def __init__(self, address, attributes=2):
        self.bEndpointAddress = address
        self.bmAttributes = attributes
        self.writes = []
        self.read_data = b""

    def write(self, data, timeout=None):
        self.writes.append((bytes(data), timeout))
        return len(data)

    def read(self, size, timeout=None):
        return self.read_data[:size]


class FakeInterface(list):
    pass


class FakeConfiguration:
    def __init__(self, interface):
        self.interface = interface

    def __getitem__(self, key):
        if key != (0, 0):
            raise KeyError(key)
        return self.interface


class FakeDevice:
    def __init__(self, interface=None):
        self.controls = []
        self.configuration = FakeConfiguration(interface or FakeInterface())
        self.set_configuration_calls = 0

    def ctrl_transfer(self, request_type, request, value, index, payload, timeout=None):
        self.controls.append((request_type, request, value, index, payload, timeout))
        if request_type == 0xC0:
            return bytes([2, 0])
        return len(payload) if hasattr(payload, "__len__") else 0

    def set_configuration(self):
        self.set_configuration_calls += 1

    def get_active_configuration(self):
        return self.configuration


class FakeCore:
    def __init__(self, devices):
        self.devices = devices
        self.calls = []

    def find(self, **kwargs):
        self.calls.append(kwargs)
        return self.devices.get((kwargs.get("idVendor"), kwargs.get("idProduct")))


class PyUsbBackendTest(unittest.TestCase):
    def test_control_device_maps_aoa_vendor_requests(self):
        raw = FakeDevice()
        device = PyUsbControlDevice(raw, timeout_ms=1234)

        self.assertEqual(b"\x02\x00", device.control_in(51, 0, 0, 2))
        self.assertEqual(4, device.control_out(52, 0, 1, b"abc\0"))
        self.assertEqual(
            [
                (0xC0, 51, 0, 0, 2, 1234),
                (0x40, 52, 0, 1, b"abc\0", 1234),
            ],
            raw.controls,
        )

    def test_find_accessory_checks_google_accessory_product_ids(self):
        target = object()
        core = FakeCore({(GOOGLE_VENDOR_ID, ACCESSORY_PRODUCT_IDS[1]): target})

        found = find_accessory_device(core)

        self.assertIs(target, found)
        self.assertEqual(
            [
                {"idVendor": GOOGLE_VENDOR_ID, "idProduct": ACCESSORY_PRODUCT_IDS[0]},
                {"idVendor": GOOGLE_VENDOR_ID, "idProduct": ACCESSORY_PRODUCT_IDS[1]},
            ],
            core.calls,
        )

    def test_bulk_transport_uses_first_interface_bulk_in_and_out(self):
        out_ep = FakeEndpoint(0x01)
        in_ep = FakeEndpoint(0x81)
        interrupt_ep = FakeEndpoint(0x82, attributes=3)
        device = FakeDevice(FakeInterface([interrupt_ep, out_ep, in_ep]))

        transport = open_bulk_transport(device, timeout_ms=500)
        self.assertEqual(1, device.set_configuration_calls)

        self.assertEqual(3, transport.write(b"abc"))
        self.assertEqual([(b"abc", 500)], out_ep.writes)
        in_ep.read_data = b"xyz"
        self.assertEqual(b"xyz", transport.read(64))

    def test_missing_bulk_pair_is_rejected(self):
        device = FakeDevice(FakeInterface([FakeEndpoint(0x81)]))
        with self.assertRaisesRegex(RuntimeError, "bulk IN and OUT"):
            open_bulk_transport(device)


if __name__ == "__main__":
    unittest.main()
