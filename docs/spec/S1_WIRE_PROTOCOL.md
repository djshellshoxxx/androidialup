# S1 Wire Protocol — Beta 0.1

Status: normative for Beta 0.1.

## 1. Transport split

Beta uses two logical channels:

1. `CONTROL` — TLS 1.3 reliable stream.
2. `DATA` — carried on the same reliable stream for BYTE_RELAY in Beta 0.1.

PCM experimental media uses a separate UDP socket with authenticated framing. It is not required for the I1 BYTE_RELAY milestone.

## 2. Framing on reliable stream

Each frame:

```text
magic        4 bytes  "ADUP"
version      u8       = 1
kind         u8
flags        u16      network byte order
header_len   u16
payload_len  u32
call_id      16 bytes; all zero before call creation where allowed
session_id   16 bytes; all zero before assigned
request_id   u32      0 for unsolicited events
reserved     u32      = 0
header_ext   variable, header_len includes fixed header + extensions
payload      payload_len bytes
```

Maximum payload length in Beta: 1 MiB. Frames larger than the negotiated maximum are protocol violations.

All integer fields use network byte order.

## 3. Version negotiation

Immediately after TLS establishment the Android client sends `HELLO`.

```text
HELLO payload:
client_name: utf8
client_version: utf8
protocol_min: u16
protocol_max: u16
endpoint_id: 32-byte fingerprint or assigned ID
capability_count: u16
capabilities[]: utf8 identifiers
```

Relay replies with either:

```text
HELLO_ACK
  selected_version
  relay_id
  max_frame_payload
  heartbeat_seconds
  capabilities[]
```

or `HELLO_REJECT` with reason.

No other application frame may be sent before `HELLO_ACK`.

## 4. Authentication

After `HELLO_ACK`:

```text
AUTH_BEGIN(request_id)
AUTH_CHALLENGE(request_id, nonce, method)
AUTH_RESPONSE(request_id, proof)
AUTH_OK(request_id, endpoint_id, policy)
```

Beta method SHALL support a per-device credential over TLS. Exact credential provisioning may evolve, but authentication success is independent from TLS server authentication.

On failure relay sends `AUTH_FAIL` then closes after flushing that frame.

## 5. Call creation

Android generates `call_id` before dialing.

`DIAL_REQUEST` payload:

```text
target: utf8
requested_mode: enum
network_transport: WIFI | CELLULAR | ETHERNET | OTHER
client_capabilities[]
options:
  dial_timeout_ms
  caller_metadata (optional bounded map)
```

Relay responses:

```text
DIAL_ACCEPTED
  call_id
  assigned_session_id
  selected_gateway_id
  selected_mode

DIAL_FAILED
  call_id
  reason enum
  retryable bool
  human_detail optional utf8
```

`DIAL_ACCEPTED` means relay/gateway accepted the attempt, not that the remote modem answered.

## 6. Call progress events

Unsolicited events use `request_id = 0`.

```text
CALL_PROGRESS
  phase:
    ROUTING
    GATEWAY_CONNECTING
    DIALING
    RINGBACK
    NEGOTIATING
    CONNECTED
  detail optional

CALL_TERMINATED
  reason
  source: LOCAL | RELAY | GATEWAY | REMOTE
  diagnostic_code optional
```

The Android AT layer maps these to standard DTE result codes.

## 7. BYTE_RELAY

After `CALL_PROGRESS(CONNECTED)` and selected mode BYTE_RELAY, data bytes may flow.

Frame kind `DATA_BYTES`:

```text
stream_seq: u64
payload: 1..32768 octets
```

Rules:

- `stream_seq` is the zero-based byte offset of the first payload byte in that direction.
- Each direction has its own sequence space.
- Reliable TLS stream transport should make sequence gaps impossible during normal operation; the field exists for diagnostics, replay detection, and future migration/reconnect work.
- Duplicate or overlapping data is a protocol error in Beta.
- Receiver tracks `next_expected_stream_seq`.

No application-level ACK is required in Beta BYTE_RELAY.

## 8. Flow control

Receiver periodically emits:

```text
FLOW_STATUS
  receive_window_bytes: u32
  queued_bytes: u32
```

Sender SHALL stop producing new DATA_BYTES when peer-advertised receive window reaches zero. Existing transport buffers are bounded locally.

Initial Beta receive window: 256 KiB unless negotiated otherwise.

## 9. Heartbeat

Relay sends `PING(nonce, monotonic_hint)` no less frequently than the negotiated heartbeat interval while idle. Peer answers `PONG(nonce)`.

Either peer may send a PING.

No PONG within the S1 failure interval causes a transport failure event; Android maps an active call to NO CARRIER unless a future reconnect policy successfully restores it.

## 10. Hangup

Either side may send:

```text
HANGUP_REQUEST
  reason
```

Peer replies:

```text
HANGUP_ACK
```

Relay then emits `CALL_TERMINATED` if not already emitted. Socket remains reusable for later calls unless transport-level failure requires close.

## 11. Incoming-call reservation

Protocol reserves:

```text
INCOMING_CALL
ANSWER_REQUEST
ANSWER_ACCEPTED
ANSWER_FAILED
```

These fields are specified now but incoming-call functionality is optional in I1.

## 12. PCM_VBD_EXPERIMENTAL datagram

UDP datagram fixed header:

```text
magic        4 bytes "ADUM"
version      u8 = 1
kind         u8 = PCM16_8K
flags        u16
session_id   16 bytes
ssrc         u32
seq          u16
timestamp    u32
payload_len  u16
payload      N bytes
AEAD tag     algorithm-defined
```

PCM Beta canonical packetization:

- signed 16-bit PCM
- mono
- 8000 samples/s media clock
- 160 samples / 20 ms per normal packet
- timestamp increments by 160 for each normal packet
- packet payload is little-endian PCM internally for the private protocol

This private format SHALL NOT be labeled RTP or V.152.

## 13. PCM loss/reorder semantics

- sequence numbers extend to a local 64-bit sequence space.
- packets arriving after their playout deadline are dropped and counted late.
- duplicate sequence numbers are dropped.
- gaps are reported to the jitter buffer.
- no speech PLC is used by default.

## 14. Protocol enums

### Mode

```text
1 BYTE_RELAY
2 PCM_VBD_EXPERIMENTAL
100 V152_RTP_RESERVED
101 V1501_SPRT_RESERVED
```

### Dial failure

```text
1 NO_ROUTE
2 GATEWAY_UNAVAILABLE
3 BUSY
4 NO_DIALTONE
5 NO_ANSWER
6 AUTHORIZATION_DENIED
7 UNSUPPORTED_MODE
8 TIMEOUT
9 INTERNAL_ERROR
```

Unknown enum values must be preserved in diagnostics but treated as unsupported.

## 15. Parser requirements

Parsers SHALL:

- reject wrong magic/version;
- reject lengths exceeding negotiated maximum;
- reject header underflow/overflow;
- reject nonzero reserved fields unless a negotiated extension allows them;
- never allocate directly from untrusted payload length without checking limits;
- treat malformed frames as protocol violations and close the affected connection.

## 16. Canonical ordering for dial

```text
TLS CONNECT
HELLO -> HELLO_ACK
AUTH_BEGIN -> CHALLENGE -> RESPONSE -> AUTH_OK
DIAL_REQUEST
DIAL_ACCEPTED
CALL_PROGRESS(...)
CALL_PROGRESS(CONNECTED)
DATA_BYTES <-> DATA_BYTES
HANGUP_REQUEST
HANGUP_ACK
CALL_TERMINATED
```

Any implementation test may assert this sequence exactly, allowing additional progress events but not reordering the required ones.
