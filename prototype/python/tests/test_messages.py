import unittest

from androidialup_protocol.frame import FrameKind, ProtocolError
from androidialup_protocol.messages import (
    AuthBegin, AuthChallenge, AuthFail, AuthOk, AuthResponse,
    CallProgress, CallTerminated, DataBytes, DialAccepted, DialFailed,
    DialFailure, DialRequest, FlowStatus, HangupAck, HangupRequest,
    Hello, HelloAck, HelloReject, Mode, NetworkTransport, Ping, Pong,
    ProgressPhase, TerminationSource, decode_payload, encode_payload,
)

class MessageCodecTests(unittest.TestCase):
    def round_trip(self, kind, message):
        self.assertEqual(decode_payload(kind, encode_payload(message)), message)

    def test_hello_and_ack_round_trip(self):
        self.round_trip(FrameKind.HELLO, Hello("AndroidDialup", "0.1", 1, 1, b"E" * 32, ("BYTE_RELAY", "PCM_VBD_EXPERIMENTAL")))
        self.round_trip(FrameKind.HELLO_ACK, HelloAck(1, "relay-01", 1024 * 1024, 10, ("BYTE_RELAY",)))
        self.round_trip(FrameKind.HELLO_REJECT, HelloReject("unsupported"))

    def test_auth_round_trip(self):
        self.round_trip(FrameKind.AUTH_BEGIN, AuthBegin())
        self.round_trip(FrameKind.AUTH_CHALLENGE, AuthChallenge(b"N" * 32, "device-credential"))
        self.round_trip(FrameKind.AUTH_RESPONSE, AuthResponse(b"P" * 64))
        self.round_trip(FrameKind.AUTH_OK, AuthOk(b"E" * 32, (("dial", "allowed"),)))
        self.round_trip(FrameKind.AUTH_FAIL, AuthFail("bad proof"))

    def test_dial_round_trip(self):
        self.round_trip(FrameKind.DIAL_REQUEST, DialRequest("loopback", Mode.BYTE_RELAY, NetworkTransport.WIFI, ("BYTE_RELAY",), 60000, (("test", "1"),)))
        self.round_trip(FrameKind.DIAL_ACCEPTED, DialAccepted(b"C" * 16, b"S" * 16, "gw-loopback", Mode.BYTE_RELAY))
        self.round_trip(FrameKind.DIAL_FAILED, DialFailed(b"C" * 16, DialFailure.BUSY, False, "busy"))

    def test_progress_and_termination_round_trip(self):
        self.round_trip(FrameKind.CALL_PROGRESS, CallProgress(ProgressPhase.CONNECTED, "loopback"))
        self.round_trip(FrameKind.CALL_TERMINATED, CallTerminated("LOCAL_HANGUP", TerminationSource.LOCAL, "OK"))

    def test_data_flow_heartbeat_and_hangup_round_trip(self):
        self.round_trip(FrameKind.DATA_BYTES, DataBytes(123, b"abc\x00\xff"))
        self.round_trip(FrameKind.FLOW_STATUS, FlowStatus(262144, 7))
        self.round_trip(FrameKind.PING, Ping(99, 123456))
        self.round_trip(FrameKind.PONG, Pong(99))
        self.round_trip(FrameKind.HANGUP_REQUEST, HangupRequest("user"))
        self.round_trip(FrameKind.HANGUP_ACK, HangupAck())

    def test_rejects_trailing_bytes(self):
        payload = encode_payload(Pong(7)) + b"x"
        with self.assertRaisesRegex(ProtocolError, "trailing"):
            decode_payload(FrameKind.PONG, payload)

    def test_rejects_truncated_length_prefixed_string(self):
        with self.assertRaisesRegex(ProtocolError, "truncated"):
            decode_payload(FrameKind.HELLO_REJECT, b"\x00\x05ab")

    def test_rejects_invalid_utf8(self):
        with self.assertRaisesRegex(ProtocolError, "UTF-8"):
            decode_payload(FrameKind.HELLO_REJECT, b"\x00\x01\xff")

    def test_rejects_empty_and_oversized_data_bytes(self):
        with self.assertRaises(ValueError):
            DataBytes(0, b"")
        with self.assertRaises(ValueError):
            DataBytes(0, b"x" * 32769)
        with self.assertRaisesRegex(ProtocolError, "1..32768"):
            decode_payload(FrameKind.DATA_BYTES, (0).to_bytes(8, "big"))

    def test_rejects_target_over_256_utf8_bytes(self):
        with self.assertRaisesRegex(ValueError, "256"):
            DialRequest("é" * 129, Mode.BYTE_RELAY, NetworkTransport.WIFI, (), 60000)

if __name__ == "__main__":
    unittest.main()
