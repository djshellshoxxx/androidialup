# I1 Android Relay Session Status

Status: GREEN checkpoint (pure Java, JVM tests). Android TLS transport (plan
Task 4) and the device harness (Task 6) are not part of this checkpoint.

Plan: `docs/superpowers/plans/2026-10-02-i1-android-relay-session.md`
(Tasks 1, 2, 3, 5 plus the modem bridge).

## What `android/core-session` now proves

All in `io.circuitdrift.androidialup.session`, single-threaded, no sockets,
no sleeping, monotonic time passed in by the host.

| Area | Class | Covered by |
|---|---|---|
| Beta auth method `device-credential-hmac-sha256-v1` (S1_WIRE_PROTOCOL 4.1) | `DeviceCredentialProof` | `DeviceCredentialProofTest` (frozen known-answer vector, transcript layout, UTF-8 relay_id vector cross-checked with Python, method/nonce rejection, no secret in `toString`) |
| Handshake, dial, call progress, termination, stale IDs | `RelaySessionMachine` | `RelaySessionMachineTest` |
| S1_SPEC_FREEZE section 7 deadlines via `onTimer(nowMs)` | `RelaySessionMachine` | `RelaySessionDeadlineTest` |
| Heartbeat PING/PONG, 30 s failure | `RelaySessionMachine` | `RelaySessionHeartbeatTest` |
| BYTE_RELAY sender/receiver, flow window, hangup races | `RelaySessionMachine` | `RelaySessionByteRelayTest` |
| Diagnostics snapshot (plan Task 5) | `RelaySessionSnapshot` | `RelaySessionSnapshotTest` |
| `SessionPort` adapter for `ModemController` | `ModemRelayBridge`, `FrameSink` | `ModemRelayBridgeTest`, `ModemRelayEndToEndTest` |

Deadlines and the reason each one reports (each fires at most once):

| Deadline (S1 section 7) | Armed | Cancelled by | On expiry |
|---|---|---|---|
| relay authentication 8 s | `onTlsConnected` | AUTH_OK | `TransportFailed(AUTH_FAILURE)` |
| DIAL_REQUEST acknowledgement 5 s | `dial` | DIAL_ACCEPTED / DIAL_FAILED | `TransportFailed(RELAY_UNAVAILABLE)` |
| backend dial setup 60 s | DIAL_ACCEPTED | CALL_PROGRESS(CONNECTED), DIAL_FAILED | `HANGUP_REQUEST` + `CallFailed(TIMEOUT)`, terminal reason `BACKEND_NO_ANSWER`, DTE `NO CARRIER` |
| clean disconnect grace 3 s | HANGUP_REQUEST sent or received | CALL_TERMINATED | `TransportFailed(RELAY_UNAVAILABLE)` |
| heartbeat failure 30 s (existing) | PING sent | matching PONG | `TransportFailed(HEARTBEAT_TIMEOUT)` |

`AUTH_FAIL` now yields `TransportFailed(AUTH_FAILURE, <relay reason>)` and
`HELLO_REJECT` yields `TransportFailed(PROTOCOL_VERSION_MISMATCH, ...)`
instead of a silent move to FAILED. `fail(reason)` lets the host report socket
loss (`NETWORK_LOST`, `CONNECT_TIMEOUT`, `TLS_FAILURE`, ...) exactly once.

End-to-end (in process, `ModemRelayEndToEndTest`): `ModemController` ->
`ModemRelayBridge` -> `RelaySessionMachine` -> `FrameCodec` bytes ->
`FrameStreamDecoder` -> fake relay with loopback backend, asserting the S1
section 16 ordering exactly: `AT`, `ATS12=1`, `ATDloopback` (triggers
`FrameSink.requestConnect`), `CONNECT`, 96 KiB binary both ways, relay PING,
timed `+++` -> `OK`, `ATH` -> `OK`, HANGUP_ACK, CALL_TERMINATED, second call
on the same TLS session; heartbeat loss -> `+ADIAG: HEARTBEAT_TIMEOUT`,
`NO CARRIER` exactly once; `BUSY` mapping.

## Verification

Run with Gradle 9.6 on JDK 21 in a scratch settings project that includes
`core-network`, `core-protocol`, `core-modem`, `core-session` (the Android
Gradle plugin cannot resolve in this environment); main and test sources also
compile with `javac --release 17`.

```text
:core-session:test   81 tests, 0 failures
:core-modem:test     77 tests, 0 failures
:core-protocol:test  18 tests, 0 failures
```

## Decisions

1. **AuthProofProvider signature.** It now receives
   `proofFor(String relayId, byte[] endpointId, AuthChallenge challenge)`;
   `relayId` is the HELLO_ACK value kept by the machine, so the transcript
   always binds the relay that actually answered (I1_RELAY_AUTH_STATUS
   "Exact proof definition"). Existing tests only changed their lambdas.
   HMAC uses the JCE standard algorithm name `HmacSHA256`, which every Java SE
   implementation must support (Java Security Standard Algorithm Names,
   https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#mac-algorithms).
   Method and short-nonce rejections throw `ProtocolException` so no
   AUTH_RESPONSE is sent (S1 4.1).
2. **Deadline reasons.** S1 section 12 has no timeout-specific names for
   these deadlines, so the closest existing names were used rather than new
   ones: authentication did not complete -> `AUTH_FAILURE` (the error class the
   Python `RelayAuthenticationError` maps to; the `detail` field tells timeout
   from rejection); relay silent on a request -> `RELAY_UNAVAILABLE`; backend
   never connected -> `BACKEND_NO_ANSWER`, carried as wire
   `DialFailure.TIMEOUT` (the gateway's own `onFailed(TIMEOUT)` in
   S1_GATEWAY_BACKEND section 7).
3. **Setup timeout is `NO CARRIER`, not `NO ANSWER`.** S1_AT_DTE section 10
   ties the 60 s default to S7 ("wait-for-carrier/dial timeout"). Per V.250 /
   Hayes practice, S7 expiry without carrier disconnects with `NO CARRIER`;
   `NO ANSWER` is reserved for the `@` quiet-answer dial modifier. Source:
   USRobotics 5631 result-code reference, "NO CARRIER ... no carrier is
   detected within the period of time determined by register S7"
   (https://support.usr.com/support/5631/5631-ug/resultcodes.htm, seen via
   web search; the page itself and the ITU-T V.250 text were not fetchable
   from this environment, so re-check against V.250 section 6.3.10 when
   available). `ModemRelayBridge.resultFor`
   therefore maps only BUSY / NO_DIALTONE / NO_ANSWER directly and everything
   else to `NO_CARRIER`, matching the Python `RelaySessionPort` mapping.
4. **Dial-ack timeout closes the transport.** Before DIAL_ACCEPTED there is no
   session ID, so no HANGUP_REQUEST can be addressed; a relay that ignores a
   request for 5 s on a live TLS stream is treated as unavailable. This also
   avoids a late DIAL_ACCEPTED resurrecting a cancelled call
   (S1_NETWORK_THREADING section 12).
5. **Local hangup before DIAL_ACCEPTED** is deferred: HANGUP_REQUEST goes out
   as soon as the session ID arrives; a DIAL_FAILED instead ends it silently.
   A host-initiated hangup produces no terminal action (the host already
   knows), so each call has exactly one terminal report (S1 section 12).
6. **FLOW_STATUS semantics.** The spec (S1 section 8) and the Python
   `ByteRelaySender.update_flow` both treat `receive_window_bytes` as absolute
   credit that replaces the sender's window; this is kept. The Python receivers
   (client and relay) advertise a fixed 256 KiB after every DATA_BYTES because
   they deliver synchronously. The Java machine keeps that as the default (and
   `ModemRelayBridge` delivers synchronously), and adds an opt-in
   `Options.withManualInboundConsumption(true)` for asynchronous hosts:
   FLOW_STATUS then advertises `256 KiB - unconsumed` with `queued_bytes =
   unconsumed`, and `onInboundConsumed(n)` re-advertises when the window
   reopens from zero or grows by at least one DATA_BYTES payload. Because
   credit is absolute, bytes in flight when FLOW_STATUS is generated are not
   deducted, so a receive buffer overrun is treated as local
   `QUEUE_OVERFLOW` (S1_SPEC_FREEZE section 6), not as a peer protocol
   violation.
7. **Queue overflow** (local pending > 256 KiB or unconsumed inbound >
   256 KiB) terminates the call with `QUEUE_OVERFLOW` via HANGUP_REQUEST,
   never drops bytes (S1_SPEC_FREEZE section 6). Previously `writeData` threw.
8. **Hangup races.** After HANGUP_REQUEST no more DATA_BYTES are sent and the
   local pending buffer is dropped (the Python relay resets the call on hangup
   and would reject them); inbound DATA_BYTES still in flight are sequence
   checked and discarded; a late CALL_PROGRESS(CONNECTED) is ignored. A
   relay-sent HANGUP_REQUEST (S1 section 10, "either side") is answered with
   HANGUP_ACK. HANGUP_ACK must echo our HANGUP_REQUEST `request_id`.
9. **Receiver error text** mirrors Python: "gap" vs "duplicate/overlap".
10. **Snapshot IDs** are the first 4 bytes in hex only; no endpoint ID,
    nonce, secret or proof is ever stored in or reachable from a snapshot.
11. **Clean disconnect grace (3 s)** was added although not requested, because
    without it a relay that never sends CALL_TERMINATED leaves the machine in
    HANGING_UP and blocks every later dial.
12. **Bridge connection model.** A dial with no live transport calls
    `FrameSink.requestConnect()` once and is held until AUTH_OK; a dial while
    the previous call is still hanging up is held until CALL_TERMINATED. The
    bridge never opens sockets; a failed machine is replaced on the next
    `onTlsConnected()`.

## Remaining

- `HEARTBEAT_TIMEOUT` (pre-existing reason) is not an S1 section 12 name;
  S1_TEST_PLAN section 8 suggests `NETWORK_LOST`/transport diagnostic. Left as
  is pending a spec amendment, since the existing heartbeat test freezes it.
- `ModemController.onDialFailed(ResultCode)` cannot carry the taxonomy reason,
  so `+ADIAG:` for dial failures shows the result name (e.g. `BUSY`), while
  the bridge snapshot holds `BACKEND_BUSY`. Needs a core-modem API change.
- `ATA` reaches `SessionPort.answer()` with no incoming-call support; the
  bridge does nothing and core-modem emits no result line.
- TCP/TLS connect deadline (10 s) belongs to the Android transport adapter
  (plan Task 4), which must call `onTransportClosed("CONNECT_TIMEOUT")`.
- Android Keystore storage of the device secret and credential enrolment.
- DTE-side backpressure (CTS / `FLOW_CONTROL_TIMEOUT`) and manual inbound
  consumption wiring for an asynchronous DTE writer.
- Integration against the real Python relay over TLS and on-device bearer
  binding (plan Tasks 4 and 6).
