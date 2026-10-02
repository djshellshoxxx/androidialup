import unittest

from androidialup_protocol.frame import Frame, FrameKind, encode_frame
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
        with self.assertRaisesRegex(Exception, "buffer"):
            decoder.feed(b"x" * 65)


if __name__ == "__main__":
    unittest.main()
