# I1 Protocol / Relay / Loopback Status

Status: GREEN checkpoint

## Implemented

The first executable I1 slice is now implemented in `prototype/python`:

- ADUP protocol-v1 fixed framing and incremental stream decoder.
- Strict typed payload codecs for HELLO, authentication envelope, dial lifecycle, call progress/termination, BYTE_RELAY, flow control, heartbeat, and hangup.
- BYTE_RELAY byte-offset sequencing with duplicate/overlap/gap detection.
- 32 KiB DATA_BYTES chunking.
- Bounded 256 KiB pending output buffer.
- FLOW_STATUS pause/reopen semantics with no silent accepted-byte loss.
- Deterministic Linux-gateway loopback backend with ECHO, SINK, PATTERN, simulated BUSY, NO_ANSWER, and NO_DIALTONE.
- Deterministic relay session ordering: HELLO -> AUTH -> DIAL -> CONNECTED -> DATA -> HANGUP.
- Call/session-ID validation and idempotent duplicate active dial handling.
- PING/PONG heartbeat handling.
- End-to-end in-process loopback acceptance test.
- GitHub Actions test workflow for the Python prototype.

## Verified acceptance behavior

GitHub Actions run `37047722273` on commit `64721a9aa25bfbb3cad00614910bd69dc97032b9` completed successfully.

Command:

```text
python -m unittest discover -s tests -v
```

Result:

```text
Ran 47 tests in 0.013s
OK
```

The integration test transferred a deterministic 1 MiB binary payload through the relay/gateway loopback path and verified exact byte equality. It additionally forced a zero receive window, confirmed echoed data was retained rather than dropped, reopened the window, verified the retained bytes emerged at the correct stream offset, exercised PING/PONG, and performed HANGUP_ACK + one CALL_TERMINATED transition.

## Current topology proven

```text
protocol test client
    -> Frame/message codec
    -> RelaySession
    -> LoopbackBackend
    -> RelaySession
    -> protocol test client
```

This proves the protocol/state/backend core without network sockets.

## Important implementation ruling

FLOW_STATUS zero-window behavior is implemented as bounded buffering rather than failure. Accepted gateway output may remain queued up to the 256 KiB local pending limit. When the peer advertises new receive capacity, queued bytes drain at their original BYTE_RELAY offsets. Exceeding the bounded pending limit remains a hard flow-control failure; bytes are never silently discarded.

## Not yet claimed

This checkpoint does not yet prove the complete I1 phase. Remaining I1 work includes:

- actual TLS 1.3 relay listener/service around `RelaySession`;
- real stream fragmentation/coalescing through sockets;
- client-side relay connection implementation;
- TCP DTE endpoint;
- AT parser/modem state reducer;
- Android project shell and NetworkSelector;
- Android per-`Network` socket binding;
- Wi-Fi and cellular packet-data device tests;
- structured I1 diagnostics/metrics around live sockets;
- forced live network-loss and relay-death acceptance tests.

The serial hardware modem backend remains Phase I5 and is not required for I1 completion.
