# Phase S1 — Beta 0.1 Specification Freeze

Status: normative for Beta 0.1 implementation unless superseded by a later S1 amendment.

## 1. Scope

Phase S1 converts the Phase R1 research into exact module boundaries and observable behavior. Beta 0.1 is a packet-data Android modem endpoint with modem-like DTE semantics. It does not require V.34/V.90 DSP or privileged cellular-call audio to succeed.

## 2. Normative implementation target

Beta 0.1 SHALL include:

- Android application control plane in Kotlin/Java.
- Native C++ library boundary reserved for DSP/audio work.
- DTE transport abstraction with TCP first; USB transport interface defined now, implementation may follow in I2.
- V.250-style AT parser and result-code engine.
- Serialized modem/session state machine.
- Android network selection across Wi-Fi and cellular packet data.
- TLS-protected relay control and BYTE_RELAY data path.
- Linux `androidialup-gatewayd` reference gateway with loopback backend and serial-modem backend contract.
- Explicit diagnostics and bounded queues.
- PCM_VBD_EXPERIMENTAL protocol contract, even if full media implementation is completed in I3.

## 3. Required source tree boundaries

Recommended tree:

```text
android/
  app/
  core/
    at/
    dte/
    network/
    session/
    diagnostics/
  native/
    include/
    dsp/
    audio/
relay/
  server/
gateway/
  gatewayd/
  backends/
    loopback/
    serial_modem/
protocol/
  schema/
  test_vectors/
tests/
  protocol/
  at/
  integration/
docs/spec/
```

The Android UI SHALL NOT own modem/session state. UI observes immutable snapshots from the control plane.

## 4. Stable component interfaces

### 4.1 DTE

```text
interface DteTransport {
    start(listener: DteListener): Result
    stop(reason)
    write(bytes): WriteResult
    setSignals(signals: DteSignals)
    capabilities(): DteCapabilities
}

DteSignals {
    dcd: bool
    dsr: bool
    cts: bool
    ri: bool
}
```

### 4.2 Session

```text
interface SessionController {
    dial(request: DialRequest)
    answer()
    hangup(reason)
    writeData(bytes)
    escapeToOnlineCommand()
    resumeOnlineData()
}
```

### 4.3 Network

```text
interface NetworkSelector {
    setPolicy(NetworkPolicy)
    snapshot(): NetworkSnapshot
    selectedNetwork(): SelectedNetwork?
    openTcp(remote): BoundStream
    openUdp(remote): BoundDatagram
}
```

### 4.4 Gateway backend

```text
interface GatewayBackend {
    open(context): BackendHandle
    dial(target, options): BackendDialResult
    answer(): BackendResult
    write(bytes): BackendWriteResult
    setControl(signals): BackendResult
    pollEvents(deadline): list<BackendEvent>
    hangup(reason)
    close()
}
```

## 5. Serialized event model

Only `ModemReducer` mutates authoritative modem/session state.

Event sources enqueue immutable events:

```text
DTE_BYTES
DTE_DISCONNECTED
AT_COMMAND
ESCAPE_GUARD_EXPIRED
NETWORK_SELECTED
NETWORK_LOST
RELAY_CONNECTED
RELAY_AUTH_FAILED
DIAL_ACCEPTED
DIAL_FAILED
REMOTE_RING
REMOTE_CONNECT
REMOTE_HANGUP
BACKEND_STATUS
TIMER_EXPIRED
USER_HANGUP
```

Reducer pseudocode:

```text
loop:
    event = eventQueue.take()
    old = state
    new, effects = reduce(old, event)
    state = new
    publishSnapshot(new)
    for effect in effects:
        effectExecutor.submit(effect)
```

Effects may perform I/O but SHALL NOT mutate state directly. Their completion returns as a new event.

## 6. Queue limits

Beta defaults:

```text
eventQueue           4096 events
DTE inbound bytes    64 KiB
DTE outbound bytes   64 KiB
relay tx bytes       256 KiB
relay rx bytes       256 KiB
media tx packets     256 packets
media rx packets     512 packets
log ring             4096 records
```

All queues SHALL expose depth/high-water counters. No queue may grow without bound.

Overflow policy:

- control/event queue overflow: fatal session error.
- DTE TX/RX overflow: deassert CTS if supported; if still overflowing, terminate with `FLOW_CONTROL_TIMEOUT`.
- BYTE_RELAY queue overflow: terminate call, do not drop arbitrary user bytes.
- PCM media overflow: drop according to media policy and increment counters; control path remains alive.

## 7. Timeouts

All deadlines use monotonic time.

Beta defaults:

```text
TCP/TLS connect                  10 s
relay authentication             8 s
DIAL_REQUEST acknowledgement     5 s
backend dial setup              60 s
incoming ring expiry           120 s
control heartbeat interval      10 s
control heartbeat failure       30 s
DTE flow-control stall          30 s
clean disconnect grace           3 s
reconnect experiment timeout    15 s
```

Values are configurable, but protocol defaults SHALL be documented in diagnostics.

## 8. State hierarchy

Top-level modem state:

```text
OFFLINE
COMMAND
DIALING
RINGING
ONLINE_DATA
ONLINE_COMMAND
DISCONNECTING
FAILED
```

Transport/session substate:

```text
NO_SESSION
CONNECTING
AUTHENTICATING
IDLE
DIAL_REQUESTED
BACKEND_CONNECTING
ESTABLISHED
RECONNECTING
CLOSING
```

Illegal transitions SHALL be rejected deterministically and logged with old state, event, and reason.

## 9. Result-code mapping

Minimum numeric/text results:

```text
0 OK
1 CONNECT
2 RING
3 NO CARRIER
4 ERROR
6 NO DIALTONE
7 BUSY
8 NO ANSWER
```

Text vs numeric output is controlled by the standard `V` command. Quiet mode follows `Q` semantics.

Project diagnostic reasons are retained internally and exposed through `AT+DIAG?`; they SHALL NOT replace conventional modem result codes on the normal DTE stream.

## 10. Beta operating modes

```text
AUTO
BYTE_RELAY
PCM_VBD_EXPERIMENTAL
```

`AUTO` in Beta selects BYTE_RELAY unless both relay and gateway advertise PCM experimental support and the dial target explicitly requests it.

Future reserved values:

```text
V152_RTP
V1501_SPRT
LOCAL_SOFTMODEM
CELLULAR_VOICE_EXPERIMENTAL
```

Unknown mode identifiers must be rejected rather than silently downgraded.

## 11. Security baseline

- TLS 1.3 minimum for relay control and BYTE_RELAY.
- Certificate validation required.
- Device credential stored using Android Keystore-backed storage where available.
- Session IDs are 128 random bits minimum.
- Call IDs are independently generated 128 random bits.
- No credentials, bearer tokens, or plaintext secrets in diagnostics.
- Gateway serial device access runs under a dedicated unprivileged service account where OS support permits.

## 12. Error taxonomy

```text
LOCAL_CONFIG
NO_ELIGIBLE_NETWORK
DNS_FAILURE
CONNECT_TIMEOUT
TLS_FAILURE
AUTH_FAILURE
PROTOCOL_VERSION_MISMATCH
PROTOCOL_VIOLATION
RELAY_UNAVAILABLE
NO_ROUTE
BACKEND_UNAVAILABLE
BACKEND_NO_DIALTONE
BACKEND_BUSY
BACKEND_NO_ANSWER
REMOTE_HANGUP
NETWORK_LOST
FLOW_CONTROL_TIMEOUT
QUEUE_OVERFLOW
DTE_DISCONNECTED
INTERNAL_ERROR
```

Every disconnect SHALL have exactly one terminal reason code plus optional structured detail.

## 13. Acceptance gate for S1

S1 is frozen when:

1. wire protocol fields and message ordering are normative;
2. AT/DTE behavior is deterministic;
3. Android networking and socket ownership are explicit;
4. gateway API and backend behavior are explicit;
5. threading, queue, timeout, and error rules are explicit;
6. test cases can be written without inventing behavior.
