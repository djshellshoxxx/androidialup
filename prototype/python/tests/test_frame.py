import unittest

from androidialup_protocol.frame import (
    Frame,
    FrameKind,
    ProtocolError,
    decode_frame,
    encode_frame,
    FIXED_HEADER_LEN,
)


class FrameCodecTests(unittest.TestCase):
    def test_round_trip_preserves_normative_fields(self):
        frame = Frame(
            kind=FrameKind.HELLO,
            flags=0x1234,
            call_id=bytes(range(16)),
            session_id=bytes(range(16, 32)),
            request_id=0x10203040,
            payload=b"hello",
        )
        encoded = encode_frame(frame)
        decoded, consumed = decode_frame(encoded)
        self.assertEqual(consumed, len(encoded))
        self.assertEqual(decoded, frame)

    def test_header_is_network_byte_order(self):
        frame = Frame(kind=FrameKind.PING, flags=0x0102, request_id=0x01020304)
        encoded = encode_frame(frame)
        self.assertEqual(encoded[:4], b"ADUP")
        self.assertEqual(encoded[4], 1)
        self.assertEqual(encoded[6:8], b"\x01\x02")
        self.assertEqual(encoded[8:10], FIXED_HEADER_LEN.to_bytes(2, "big"))
        self.assertEqual(encoded[46:50], b"\x01\x02\x03\x04")

    def test_rejects_wrong_magic(self):
        encoded = bytearray(encode_frame(Frame(kind=FrameKind.PING)))
        encoded[:4] = b"NOPE"
        with self.assertRaisesRegex(ProtocolError, "magic"):
            decode_frame(bytes(encoded))

    def test_rejects_unsupported_version(self):
        encoded = bytearray(encode_frame(Frame(kind=FrameKind.PING)))
        encoded[4] = 2
        with self.assertRaisesRegex(ProtocolError, "version"):
            decode_frame(bytes(encoded))

    def test_rejects_nonzero_reserved(self):
        encoded = bytearray(encode_frame(Frame(kind=FrameKind.PING)))
        encoded[50:54] = (1).to_bytes(4, "big")
        with self.assertRaisesRegex(ProtocolError, "reserved"):
            decode_frame(bytes(encoded))

    def test_rejects_payload_over_negotiated_maximum(self):
        encoded = encode_frame(Frame(kind=FrameKind.DATA_BYTES, payload=b"12345"))
        with self.assertRaisesRegex(ProtocolError, "payload"):
            decode_frame(encoded, max_payload=4)

    def test_reports_incomplete_frame_without_overreading(self):
        encoded = encode_frame(Frame(kind=FrameKind.PING, payload=b"abc"))
        with self.assertRaisesRegex(ProtocolError, "incomplete"):
            decode_frame(encoded[:-1])


if __name__ == "__main__":
    unittest.main()
