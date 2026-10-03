import unittest

from androidialup_host.aoa import (
    AOA_GET_PROTOCOL,
    AOA_SEND_IDENT,
    AOA_START,
    AccessoryIdentity,
    AoaProtocolError,
    enter_accessory_mode,
)


class FakeUsbDevice:
    def __init__(self, protocol=2):
        self.protocol = protocol
        self.calls = []

    def control_in(self, request, value, index, length):
        self.calls.append(("in", request, value, index, length))
        if request != AOA_GET_PROTOCOL:
            raise AssertionError("unexpected IN request")
        return int(self.protocol).to_bytes(2, "little")

    def control_out(self, request, value, index, data=b""):
        payload = bytes(data)
        self.calls.append(("out", request, value, index, payload))
        return len(payload)


class AoaHostProtocolTest(unittest.TestCase):
    def test_sends_identity_fields_in_protocol_order_then_starts(self):
        device = FakeUsbDevice(protocol=2)
        identity = AccessoryIdentity(
            manufacturer="CircuitDrift",
            model="AndroidDialup",
            description="AndroidDialup PC bridge",
            version="0.1",
            uri="https://github.com/djshellshoxxx/androidialup",
            serial="host-01",
        )

        protocol = enter_accessory_mode(device, identity)

        self.assertEqual(2, protocol)
        self.assertEqual(("in", AOA_GET_PROTOCOL, 0, 0, 2), device.calls[0])
        expected = [
            b"CircuitDrift\0",
            b"AndroidDialup\0",
            b"AndroidDialup PC bridge\0",
            b"0.1\0",
            b"https://github.com/djshellshoxxx/androidialup\0",
            b"host-01\0",
        ]
        self.assertEqual(
            [("out", AOA_SEND_IDENT, 0, index, value) for index, value in enumerate(expected)],
            device.calls[1:7],
        )
        self.assertEqual(("out", AOA_START, 0, 0, b""), device.calls[7])

    def test_rejects_protocol_zero(self):
        with self.assertRaisesRegex(AoaProtocolError, "unsupported AOA protocol"):
            enter_accessory_mode(FakeUsbDevice(protocol=0), AccessoryIdentity.default())

    def test_rejects_short_protocol_response(self):
        class ShortDevice(FakeUsbDevice):
            def control_in(self, request, value, index, length):
                return b"\x02"

        with self.assertRaisesRegex(AoaProtocolError, "2-byte"):
            enter_accessory_mode(ShortDevice(), AccessoryIdentity.default())

    def test_identity_fields_reject_embedded_nul(self):
        with self.assertRaises(ValueError):
            AccessoryIdentity(
                manufacturer="Circuit\0Drift",
                model="AndroidDialup",
                description="bridge",
                version="0.1",
                uri="https://example.invalid",
                serial="host",
            )


if __name__ == "__main__":
    unittest.main()
