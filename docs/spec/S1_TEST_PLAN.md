# S1 Beta 0.1 Acceptance and Test Plan

Status: normative acceptance matrix for Phase I1 and later Beta modules.

## 1. Test layers

```text
UNIT
PROTOCOL
STATE_MACHINE
NETWORK
GATEWAY
END_TO_END
IMPAIRMENT
HARDWARE_INTEROP
```

No module is accepted solely because it round-trips against itself.

## 2. AT parser tests

Required cases:

- `AT` -> OK
- mixed-case commands
- E/Q/V state changes
- syntax validation atomicity
- line overflow recovery
- unknown command -> ERROR
- valid and invalid S-register accesses
- text/numeric result mapping
- quiet mode suppressing results
- dial success/failure mapping
- online-command transitions
- exact escape pre/post guard timing boundaries
- binary online data containing 0x00, 0x0D, 0x2B, 0xFF

## 3. State-machine tests

Every valid transition must have at least one test. Invalid transitions must remain deterministic.

Race scenarios:

```text
hangup while connecting
network lost while CONNECT event is in flight
DTE disconnect during dial
remote hangup simultaneous with local hangup
auth failure after cancellation
late gateway CONNECT after call generation changed
```

Assertions:

- one terminal state;
- at most one terminal DTE result;
- DCD ends low;
- backend lease released;
- stale events do not resurrect call.

## 4. Wire protocol tests

Golden binary vectors SHALL be stored in `protocol/test_vectors/`.

Required vectors:

```text
HELLO
HELLO_ACK
AUTH challenge/response envelope
DIAL_REQUEST
DIAL_ACCEPTED
DIAL_FAILED
CALL_PROGRESS
DATA_BYTES empty-invalid
DATA_BYTES normal
FLOW_STATUS
HANGUP_REQUEST
CALL_TERMINATED
```

Negative vectors:

- wrong magic;
- unsupported version;
- header shorter than fixed header;
- payload > maximum;
- truncated frame;
- reserved bits set;
- duplicate/overlapping BYTE_RELAY sequence;
- invalid session/call ID for current state.

Fuzz parser with arbitrary length-prefixed input under bounded memory monitoring.

## 5. NetworkSelector tests

Use fake candidates and deterministic probe values.

Verify all five policies, validation filtering, metered/roaming penalty, hysteresis, tie breakers, selected-network loss, and no automatic migration during an active Beta call.

Android instrumentation tests verify sockets are actually bound to the selected `Network` rather than the process default.

## 6. Queue/backpressure tests

Fill queues to:

```text
low_water - 1
low_water
high_water
max - 1
max
attempt max + 1
```

Verify CTS behavior, stall timer, recovery, and FLOW_CONTROL_TIMEOUT.

No BYTE_RELAY payload may be silently discarded.

## 7. Loopback end-to-end acceptance

Topology:

```text
PC test client -> Android DTE TCP -> relay -> gatewayd loopback -> relay -> Android -> PC
```

Acceptance:

1. `AT` returns OK.
2. `ATDloopback` progresses to CONNECT.
3. Send 1 MiB pseudorandom binary payload.
4. Receive byte-for-byte identical payload.
5. No sequence gaps/duplicates.
6. Queue limits not exceeded.
7. Escape to ONLINE_COMMAND with timed `+++`.
8. `ATO` resumes data without reconnect.
9. `ATH` cleanly terminates and DCD drops.

Run on Wi-Fi and cellular packet data separately.

## 8. Network failure acceptance

During 1 MiB loopback transfer:

- disable selected Wi-Fi while WIFI_ONLY active;
- disable mobile data while CELLULAR_ONLY active;
- remove Internet validation via controlled test network;
- kill relay connection;

Beta expected behavior: active call terminates predictably with NO CARRIER and NETWORK_LOST/transport diagnostic. No hidden bearer migration is expected.

## 9. Relay/gateway failure tests

Inject:

- gateway unavailable;
- backend busy;
- backend no dialtone;
- backend no answer;
- backend remote hangup;
- gateway process death;
- TLS close mid-call;
- heartbeat timeout;
- serial write stall.

Verify exact internal reason and DTE-compatible result mapping.

## 10. Hardware modem acceptance

Topology:

```text
Android -> relay -> gatewayd -> USB/RS232 hardware modem -> controlled remote modem
```

Minimum evidence:

- gateway initializes modem;
- real dial attempt issued;
- CONNECT is sourced from hardware modem result, not fabricated;
- arbitrary binary DTE data crosses established modem call in both directions;
- hardware flow control measured where available;
- DTR or configured hangup strategy reliably terminates carrier;
- at least one retry after failure succeeds without restarting gatewayd.

Record vendor/model/firmware and remote peer.

## 11. PCM experimental tests

Before real network testing, use deterministic WAV/PCM source.

Verify:

- sequence extension/reordering;
- 20 ms packet timestamp increments of 160;
- late packet counting;
- bounded jitter-buffer depth;
- no speech PLC enabled;
- clock correction responds to controlled sender ppm error without unbounded drift.

Impairment matrix begins with:

```text
loss: 0, 0.1, 0.5, 1, 2 percent
reorder: 0, 0.1, 0.5 percent
one-way jitter: 0, 5, 10, 20, 40 ms
clock error: -100, -50, 0, +50, +100 ppm
```

Actual modem waveform survivability is measured later with independent external vectors.

## 12. Diagnostics acceptance

For every active call diagnostic snapshot must provide:

```text
call/session IDs in safe form
modem state
session state
selected bearer
relay RTT
queue depths/high water
DCD/DSR/CTS/RI
bytes rx/tx
terminal reason when ended
```

PCM mode additionally exposes loss, late, reorder, jitter depth and clock correction ppm.

No credential secret may appear in captured logs.

## 13. Resource tests

Run 1000 sequential loopback calls.

Acceptance:

- no monotonic growth in open sockets/threads beyond expected caches;
- backend leases return to zero;
- queue objects are released;
- no stale call IDs remain active;
- memory reaches stable bounded range.

## 14. Soak test

BYTE_RELAY loopback call for 8 hours with bidirectional pseudorandom stream and periodic idle gaps.

Track:

```text
bytes
errors
RTT
queue high-water
memory
thread count
heartbeat failures
DTE result anomalies
```

Zero byte corruption is required.

## 15. I1 exit criteria

Phase I1 passes only when all are true:

1. Android connects to relay using explicitly selected Wi-Fi.
2. Android connects using explicitly selected cellular packet data.
3. `ATD` creates an end-to-end loopback call.
4. BYTE_RELAY transports arbitrary binary data bidirectionally.
5. escape/ATO/ATH behavior matches S1.
6. queue/backpressure tests pass.
7. forced network/relay failures terminate deterministically.
8. structured diagnostics identify terminal reason.
9. no unbounded resource growth in sequential-call test.

Hardware-modem dial is the later I5 exit gate, not required to claim I1 complete.
