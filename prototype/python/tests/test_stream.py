import unittest

from androidialup_protocol.frame import FIXED_HEADER_LEN, Frame, FrameKind, ProtocolError, encode_frame
from androidialup_protocol.stream import FrameStreamDecoder


class FrameStreamDecoderTests(unittest.TestCase):
    def test_partial_frame_waits_for_more_bytes(self):
        encoded = encode_frame(Frame(kind=FrameKind.PING, payload=b"abc"))
        decoder = FrameStreamDecoder()
        self.assertEqual(decoder.feed(encoded[:10]), [])
        frames = decoder.feed(encoded[10:])
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].payload, b"abc")

    def test_multiple_frames_in_one_read(self):
        a = Frame(kind=FrameKind.PING, request_id=1)
        b = Frame(kind=FrameKind.PONG, request_id=2)
        decoder = FrameStreamDecoder()
        frames = decoder.feed(encode_frame(a) + encode_frame(b))
        self.assertEqual(frames, [a, b])

    def test_byte_at_a_time(self):
        frame = Frame(kind=FrameKind.DATA_BYTES, payload=b"abcdef")
        decoder = FrameStreamDecoder()
        out = []
        for byte in encode_frame(frame):
            out.extend(decoder.feed(bytes([byte])))
        self.assertEqual(out, [frame])

    def test_buffer_limit_is_bounded(self):
        decoder = FrameStreamDecoder(max_buffer=64)
        # Malformed streams must fail, regardless of the chunk size.
        with self.assertRaises(ProtocolError):
            decoder.feed(b"x" * 65)

    def test_batch_of_valid_frames_may_exceed_buffer_collectively(self):
        frames = [Frame(kind=FrameKind.PING, request_id=i) for i in range(4)]
        decoder = FrameStreamDecoder(max_buffer=64)
        self.assertEqual(decoder.feed(b"".join(encode_frame(f) for f in frames)), frames)
        self.assertEqual(decoder.buffered_bytes, 0)

    def test_partial_frame_followed_by_large_batch(self):
        frames = [Frame(kind=FrameKind.PING, request_id=i) for i in range(3)]
        encoded = b"".join(encode_frame(f) for f in frames)
        decoder = FrameStreamDecoder(max_buffer=64)
        self.assertEqual(decoder.feed(encoded[:20]), [])
        self.assertEqual(decoder.feed(encoded[20:]), frames)
        self.assertEqual(decoder.buffered_bytes, 0)

    def test_single_frame_larger_than_buffer_is_rejected(self):
        decoder = FrameStreamDecoder(max_buffer=64)
        packet = encode_frame(Frame(kind=FrameKind.PING, payload=b"0" * 11))
        with self.assertRaisesRegex(ProtocolError, "frame exceeds stream buffer"):
            decoder.feed(packet)

    def test_buffer_must_fit_fixed_header(self):
        with self.assertRaisesRegex(ValueError, "fixed header"):
            FrameStreamDecoder(max_buffer=FIXED_HEADER_LEN - 1)


if __name__ == "__main__":
    unittest.main()
