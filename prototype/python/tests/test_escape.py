import unittest

from androidialup_modem.escape import EscapeDetector


class EscapeDetectorTests(unittest.TestCase):
    def test_exact_pre_and_post_guard_produces_escape(self):
        detector = EscapeDetector(last_forwarded_ms=0)
        self.assertTrue(detector.feed(ord("+"), 1000, 43, 1000).held)
        self.assertTrue(detector.feed(ord("+"), 1100, 43, 1000).held)
        self.assertTrue(detector.feed(ord("+"), 1200, 43, 1000).held)
        self.assertFalse(detector.timer(2199).escaped)
        action = detector.timer(2200)
        self.assertTrue(action.escaped)
        self.assertEqual(action.forward, b"")

    def test_too_short_pre_guard_forwards_plus_as_data(self):
        detector = EscapeDetector(last_forwarded_ms=500)
        action = detector.feed(ord("+"), 1000, 43, 1000)
        self.assertEqual(action.forward, b"+")
        self.assertFalse(action.held)

    def test_intervening_byte_flushes_held_plus_bytes(self):
        detector = EscapeDetector(last_forwarded_ms=0)
        detector.feed(ord("+"), 1000, 43, 1000)
        detector.feed(ord("+"), 1100, 43, 1000)
        action = detector.feed(ord("A"), 1200, 43, 1000)
        self.assertEqual(action.forward, b"++A")
        self.assertFalse(action.escaped)

    def test_extra_fourth_plus_cancels_escape_and_forwards_all(self):
        detector = EscapeDetector(last_forwarded_ms=0)
        detector.feed(ord("+"), 1000, 43, 1000)
        detector.feed(ord("+"), 1100, 43, 1000)
        detector.feed(ord("+"), 1200, 43, 1000)
        action = detector.feed(ord("+"), 1300, 43, 1000)
        self.assertEqual(action.forward, b"++++")
        self.assertFalse(detector.timer(3000).escaped)

    def test_escape_detection_disabled_when_s2_over_127(self):
        detector = EscapeDetector(last_forwarded_ms=0)
        out = bytearray()
        for when in (1000, 1100, 1200):
            out.extend(detector.feed(ord("+"), when, 200, 1000).forward)
        self.assertEqual(bytes(out), b"+++")
        self.assertFalse(detector.timer(3000).escaped)

    def test_incomplete_candidate_flushes_after_guard_timeout(self):
        detector = EscapeDetector(last_forwarded_ms=0)
        detector.feed(ord("+"), 1000, 43, 1000)
        action = detector.timer(2001)
        self.assertEqual(action.forward, b"+")
        self.assertFalse(action.escaped)

    def test_binary_non_escape_bytes_are_forwarded_unchanged(self):
        detector = EscapeDetector(last_forwarded_ms=0)
        values = [0x00, 0x0D, 0xFF]
        out = bytearray()
        now = 10
        for value in values:
            action = detector.feed(value, now, 43, 1000)
            out.extend(action.forward)
            now += 10
        self.assertEqual(bytes(out), bytes(values))


if __name__ == "__main__":
    unittest.main()
