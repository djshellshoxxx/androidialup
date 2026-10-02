import random
import unittest

from androidialup_protocol.byte_relay import ByteRelayReceiver
from androidialup_protocol.frame import Frame, ZERO_ID
from androidialup_protocol.messages import (
    AuthBegin,
    AuthResponse,
    CallProgress,
    CallTerminated,
    DataBytes,
    DialAccepted,
    DialRequest,
    FlowStatus,
    HangupAck,
    HangupRequest,
    Hello,
    Mode,
    NetworkTransport,
    Ping,
    Pong,
    ProgressPhase,
    decode_payload,
    encode_payload,
    kind_for_message,
)
from androidialup_relay.session import RelaySession, RelaySessionState

CALL_ID = bytes.fromhex("00112233445566778899aabbccddeeff")
SESSION_ID = bytes.fromhex("ffeeddccbbaa99887766554433221100")
ENDPOINT_ID = bytes(range(32))


def frame_for(message, *, call_id=ZERO_ID, session_id=ZERO_ID, request_id=1):
    return Frame(
        kind=kind_for_message(message),
        call_id=call_id,
        session_id=session_id,
        request_id=request_id,
        payload=encode_payload(message),
    )


def decoded(frames):
    return [decode_payload(frame.kind, frame.payload) for frame in frames]


class EndToEndLoopbackTests(unittest.TestCase):
    def test_one_megabyte_binary_loopback_flow_control_heartbeat_and_hangup(self):
        relay = RelaySession(session_id_factory=lambda: SESSION_ID)

        hello = relay.handle_frame(
            frame_for(Hello("AndroidDialup", "0.1", 1, 1, ENDPOINT_ID, ("BYTE_RELAY",)), request_id=1)
        )
        self.assertEqual(len(hello), 1)

        relay.handle_frame(frame_for(AuthBegin(), request_id=2))
        relay.handle_frame(frame_for(AuthResponse(b"test-proof"), request_id=3))

        dial = DialRequest(
            "loopback",
            Mode.BYTE_RELAY,
            NetworkTransport.WIFI,
            ("BYTE_RELAY",),
            60000,
        )
        dial_frames = relay.handle_frame(frame_for(dial, call_id=CALL_ID, request_id=4))
        dial_messages = decoded(dial_frames)
        accepted = next(message for message in dial_messages if isinstance(message, DialAccepted))
        self.assertEqual(accepted.assigned_session_id, SESSION_ID)
        self.assertIn(
            ProgressPhase.CONNECTED,
            [message.phase for message in dial_messages if isinstance(message, CallProgress)],
        )
        self.assertEqual(relay.state, RelaySessionState.CONNECTED)

        rng = random.Random(0xAD01A1)
        source = rng.randbytes(1024 * 1024)
        echoed = bytearray()
        client_receiver = ByteRelayReceiver()
        tx_offset = 0
        chunk_size = 32768

        while tx_offset < len(source):
            chunk = source[tx_offset : tx_offset + chunk_size]
            response_frames = relay.handle_frame(
                frame_for(
                    DataBytes(tx_offset, chunk),
                    call_id=CALL_ID,
                    session_id=SESSION_ID,
                    request_id=0,
                )
            )
            for message in decoded(response_frames):
                if isinstance(message, DataBytes):
                    echoed.extend(client_receiver.accept(message))
            # Client has consumed all echoed data, so advertise the full window again.
            relay.handle_frame(
                frame_for(
                    FlowStatus(256 * 1024, 0),
                    call_id=CALL_ID,
                    session_id=SESSION_ID,
                    request_id=0,
                )
            )
            tx_offset += len(chunk)

        self.assertEqual(bytes(echoed), source)
        self.assertEqual(client_receiver.next_expected_stream_seq, len(source))

        # Force outbound pause, accept bytes into the bounded sender, then reopen.
        relay.handle_frame(
            frame_for(FlowStatus(0, 0), call_id=CALL_ID, session_id=SESSION_ID, request_id=0)
        )
        paused_payload = b"paused-window-\x00-\xff"
        paused_response = relay.handle_frame(
            frame_for(
                DataBytes(len(source), paused_payload),
                call_id=CALL_ID,
                session_id=SESSION_ID,
                request_id=0,
            )
        )
        self.assertFalse(any(isinstance(message, DataBytes) for message in decoded(paused_response)))

        reopened = relay.handle_frame(
            frame_for(FlowStatus(256 * 1024, 0), call_id=CALL_ID, session_id=SESSION_ID, request_id=0)
        )
        reopened_data = [message for message in decoded(reopened) if isinstance(message, DataBytes)]
        self.assertEqual(reopened_data, [DataBytes(len(source), paused_payload)])
        echoed.extend(client_receiver.accept(reopened_data[0]))
        self.assertEqual(bytes(echoed[-len(paused_payload) :]), paused_payload)

        heartbeat = relay.handle_frame(
            frame_for(Ping(0x12345678, 999), call_id=CALL_ID, session_id=SESSION_ID, request_id=10)
        )
        self.assertEqual(decoded(heartbeat), [Pong(0x12345678)])

        hangup = relay.handle_frame(
            frame_for(HangupRequest("user"), call_id=CALL_ID, session_id=SESSION_ID, request_id=11)
        )
        hangup_messages = decoded(hangup)
        self.assertEqual(sum(isinstance(message, HangupAck) for message in hangup_messages), 1)
        self.assertEqual(sum(isinstance(message, CallTerminated) for message in hangup_messages), 1)
        self.assertEqual(relay.state, RelaySessionState.AUTHENTICATED)


if __name__ == "__main__":
    unittest.main()
