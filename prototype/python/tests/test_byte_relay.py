import unittest

from androidialup_protocol.byte_relay import (
    DEFAULT_RECEIVE_WINDOW,
    MAX_LOCAL_PENDING,
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

    def test_zero_window_buffers_without_consuming_sequence(self):
        sender = ByteRelaySender()
        sender.update_flow(FlowStatus(0, DEFAULT_RECEIVE_WINDOW))
        self.assertEqual(sender.build(b"abc"), [])
        self.assertEqual(sender.pending_bytes, 3)
        self.assertEqual(sender.next_stream_seq, 0)

    def test_small_window_sends_prefix_and_retains_remainder(self):
        sender = ByteRelaySender()
        sender.update_flow(FlowStatus(3, 0))
        messages = sender.build(b"abcdef")
        self.assertEqual(messages, [DataBytes(0, b"abc")])
        self.assertEqual(sender.pending_bytes, 3)
        self.assertEqual(sender.next_stream_seq, 3)
        self.assertEqual(sender.available_window, 0)

    def test_flow_update_reopens_and_drains_pending_bytes(self):
        sender = ByteRelaySender()
        sender.update_flow(FlowStatus(0, 10))
        self.assertEqual(sender.build(b"abc"), [])
        sender.update_flow(FlowStatus(10, 0))
        messages = sender.drain()
        self.assertEqual(messages, [DataBytes(0, b"abc")])
        self.assertEqual(sender.pending_bytes, 0)
        self.assertEqual(sender.available_window, 7)

    def test_pending_storage_is_bounded(self):
        sender = ByteRelaySender()
        sender.update_flow(FlowStatus(0, 0))
        sender.build(b"x" * MAX_LOCAL_PENDING)
        with self.assertRaisesRegex(FlowControlBlocked, "pending limit"):
            sender.build(b"y")
        self.assertEqual(sender.pending_bytes, MAX_LOCAL_PENDING)


if __name__ == "__main__":
    unittest.main()
