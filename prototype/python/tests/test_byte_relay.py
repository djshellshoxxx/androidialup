import unittest

from androidialup_protocol.byte_relay import (
    DEFAULT_RECEIVE_WINDOW,
    ByteRelayReceiver,
    ByteRelaySender,
    FlowControlBlocked,
)
from androidialup_protocol.frame import ProtocolError
from androidialup_protocol.messages import DataBytes, FlowStatus


class ByteRelayTests(unittest.TestCase):
    def test_receiver_accepts_contiguous_byte_offsets(self):
        receiver = ByteRelayReceiver()
        self.assertEqual(receiver.accept(DataBytes(0, b"abc")), b"abc")
        self.assertEqual(receiver.accept(DataBytes(3, b"defg")), b"defg")
        self.assertEqual(receiver.next_expected_stream_seq, 7)

    def test_receiver_rejects_gap(self):
        receiver = ByteRelayReceiver()
        with self.assertRaisesRegex(ProtocolError, "gap"):
            receiver.accept(DataBytes(5, b"x"))

    def test_receiver_rejects_duplicate_or_overlap(self):
        receiver = ByteRelayReceiver()
        receiver.accept(DataBytes(0, b"abcd"))
        with self.assertRaisesRegex(ProtocolError, "duplicate/overlap"):
            receiver.accept(DataBytes(2, b"xx"))

    def test_sender_chunks_at_32768_bytes(self):
        sender = ByteRelaySender()
        data = bytes(range(256)) * 300
        messages = sender.build(data)
        self.assertEqual([len(m.data) for m in messages], [32768, 32768, len(data) - 65536])
        self.assertEqual([m.stream_seq for m in messages], [0, 32768, 65536])
        self.assertEqual(b"".join(m.data for m in messages), data)

    def test_default_peer_window_is_256_kib(self):
        sender = ByteRelaySender()
        self.assertEqual(sender.available_window, DEFAULT_RECEIVE_WINDOW)

    def test_zero_window_stops_production_without_consuming_sequence(self):
        sender = ByteRelaySender()
        sender.update_flow(FlowStatus(0, DEFAULT_RECEIVE_WINDOW))
        with self.assertRaises(FlowControlBlocked):
            sender.build(b"abc")
        self.assertEqual(sender.next_stream_seq, 0)

    def test_submission_larger_than_window_is_rejected_atomically(self):
        sender = ByteRelaySender()
        sender.update_flow(FlowStatus(3, 0))
        with self.assertRaises(FlowControlBlocked):
            sender.build(b"abcd")
        self.assertEqual(sender.next_stream_seq, 0)
        self.assertEqual(sender.available_window, 3)

    def test_flow_update_reopens_sender(self):
        sender = ByteRelaySender()
        sender.update_flow(FlowStatus(0, 10))
        with self.assertRaises(FlowControlBlocked):
            sender.build(b"abc")
        sender.update_flow(FlowStatus(10, 0))
        messages = sender.build(b"abc")
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0], DataBytes(0, b"abc"))
        self.assertEqual(sender.available_window, 7)


if __name__ == "__main__":
    unittest.main()
