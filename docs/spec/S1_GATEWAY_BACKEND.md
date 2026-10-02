# S1 Linux Gateway and Backend Contract

Status: normative for Beta 0.1.

## 1. Gateway role

`androidialup-gatewayd` terminates relay sessions and translates logical modem calls into backend operations. Beta 0.1 requires two backends:

1. `loopback` — deterministic test backend.
2. `serial_modem` — real hardware modem over tty/USB serial.

PBX/SpanDSP backends are later modules and SHALL fit the same backend interface.

## 2. Process model

One gateway daemon process may host multiple backend instances subject to configured limits.

Logical components:

```text
RelayConnectionManager
CallRegistry
BackendRegistry
GatewayCallWorker per active call
SerialPortWorker per serial modem device
Metrics
StructuredLogger
```

No backend may directly parse Android AT commands. AT semantics belong to Android; gateway receives normalized call/data operations.

## 3. Backend lifecycle interface

```text
open(config, callbacks) -> handle
dial(handle, target, options) -> async
answer(handle) -> async
write(handle, bytes) -> result
setSignals(handle, signals) -> result
hangup(handle, reason) -> async
close(handle)
```

Callbacks:

```text
onProgress(phase, detail)
onConnected(rate?, protocol?)
onData(bytes)
onSignals(signals)
onFailed(reason, detail)
onHangup(reason)
```

Each backend instance has exactly one active call in Beta unless explicitly documented otherwise.

## 4. Loopback backend

Purpose: prove complete Android -> relay -> gateway -> relay -> Android byte path without telephony hardware.

Modes:

```text
ECHO       every received byte returned unchanged
SINK       accepts bytes, returns none
PATTERN    emits deterministic configured byte pattern
DELAYED    ECHO with configured bounded delay
```

Loopback can simulate dial progress, BUSY, NO_ANSWER, NO_DIALTONE, remote hangup, and slow reader for flow-control tests.

## 4a. DTMF and call-progress backend interface

Backends that drive a real audio/telephony path (serial modem, and later
PBX/SpanDSP) extend the lifecycle interface:

```text
sendDtmf(handle, digits, timing) -> async   // digits 0-9 A-D * # , ; timing = tone_ms,gap_ms
```

Additional callbacks (optional; a backend that cannot detect a given event
simply never fires it):

```text
onTone(tone, detail?)   // DIAL_TONE|RINGBACK|BUSY|REORDER|SIT|VOICE|CARRIER
onDtmf(digit)           // inbound DTMF digit detected in the call audio
```

The gateway forwards `sendDtmf` from the Android `AT+DTMF` command and relays
`onTone`/`onDtmf` to Android as the `+ACPROG`/`+ADTMF` informational events
(`S1_AT_DTE.md`). Tone classification maps to the dial-failure reasons already
defined in `S1_WIRE_PROTOCOL.md`: a classified BUSY tone yields `DIAL_FAILED
BUSY`, absence of dial tone yields `NO_DIALTONE`, ringback with no answer
before `connect_timeout_ms` yields `NO_ANSWER`. Detection quality is backend-
and hardware-dependent and is not guaranteed in Beta; detection is advisory
and never changes the single terminal outcome of a call.

For the serial modem backend, DTMF generation and call-progress/busy detection
rely on the modem's own capabilities (for example `ATDT` with embedded digits,
`ATX<n>` call-progress result levels, and busy detection); the gateway maps the
modem result codes to the callbacks above.

The `loopback` backend MAY simulate `onTone` and `onDtmf` for tests but
generates no audio. No backend performs unattended multi-number dialing; the
gateway dials exactly the one target per call it is given.

## 5. Serial modem configuration

```text
SerialModemConfig {
  device_path
  baud_rate default 115200
  data_bits default 8
  parity default NONE
  stop_bits default 1
  hardware_flow_control default RTS_CTS when supported
  init_commands[]
  dial_prefix default "ATDT"
  command_timeout_ms default 3000
  connect_timeout_ms default 60000
  hangup_strategy
}
```

Device path is administrator-configured; client cannot submit arbitrary filesystem paths.

## 6. Serial modem initialization

On backend open:

```text
open tty nonexclusive/exclusive per platform capability
configure raw serial mode
flush stale input/output
assert required control signals
for cmd in init_commands:
  send cmd + CR
  parse lines until OK/ERROR/timeout
if any required init command fails:
  mark backend UNAVAILABLE
```

Recommended initial command set is hardware/profile dependent and configured rather than hardcoded.

## 7. Dial procedure

```text
dial(target):
  require backend READY
  validate target against gateway policy
  state = DIALING
  flush stale line parser state
  send dial_prefix + target + CR

  loop until timeout:
    line = readResultLine()
    normalize line
    if line begins CONNECT:
      parse optional rate/protocol metadata
      state = CONNECTED
      callback onConnected(...)
      switch serial parser to DATA mode
      return
    if BUSY:
      state = READY
      callback onFailed(BUSY)
      return
    if NO DIALTONE:
      state = READY
      callback onFailed(NO_DIALTONE)
      return
    if NO ANSWER:
      state = READY
      callback onFailed(NO_ANSWER)
      return
    if NO CARRIER:
      state = READY
      callback onFailed(NO_CARRIER)
      return
    else:
      callback onProgress(MODEM_TEXT, bounded line) only in diagnostics

  hangup/cancel safely
  state = READY
  callback onFailed(TIMEOUT)
```

Gateway sends call progress to relay but never fabricates CONNECT before hardware modem reports connection.

## 8. Connected serial data

After CONNECT, all received serial octets are data except where the hardware modem itself changes state and emits a recognized terminal result after carrier loss.

The gateway SHALL NOT implement its own `+++` escape for client data during ordinary operation; doing so could consume legitimate user bytes. Hangup uses a controlled strategy outside the transparent relay path.

## 9. Hangup strategies

Preferred order when hardware supports it:

1. drop DTR according to modem configuration and wait for carrier drop;
2. if configured/required, use guarded escape + ATH under exclusive gateway control;
3. final fallback close/reopen serial device only when explicitly enabled.

Client data is stopped before gateway emits a hangup sequence.

Each modem profile records its tested hangup strategy.

## 10. Carrier/result detection after CONNECT

If DCD modem-control signal is available, it is authoritative for carrier state with a small configurable debounce.

If DCD is unavailable, backend may use modem result lines after a gateway-controlled transition out of transparent data mode; it SHALL NOT scan arbitrary online user data for strings like `NO CARRIER`.

## 11. Hardware flow control

If tty supports RTS/CTS, enable it by default.

Relay -> serial data writes are bounded. If OS/serial output remains blocked beyond configured timeout, call fails with BACKEND_FLOW_CONTROL_TIMEOUT.

Serial -> relay reads are bounded by relay flow-control window. If peer cannot accept data, backend must stop/read-throttle where safe or terminate rather than accumulate unbounded RAM.

## 12. Gateway call registry

```text
GatewayCall {
  call_id
  session_id
  endpoint_id
  backend_id
  state
  created_time
  connected_time?
  bytes_tx
  bytes_rx
  terminal_reason?
}
```

Call IDs are unique among active/recent calls. Duplicate active DIAL_REQUEST for same call ID is idempotently rejected/answered with existing status; it must not start a second physical dial.

## 13. Resource locking

A serial modem backend is leased atomically to one call.

If no compatible modem is free, DIAL fails with GATEWAY_UNAVAILABLE rather than queueing indefinitely.

Lease is released only after backend cleanup reaches READY or UNAVAILABLE.

## 14. Crash recovery

On gateway start:

- no prior call is assumed connected;
- serial modem devices are reinitialized;
- DTR is driven to configured idle state;
- relay is informed that old sessions are gone through normal reconnect/registration behavior.

A gateway process crash therefore causes active Android calls to receive terminal transport failure/NO CARRIER.

## 15. Gateway security

- outbound authenticated TLS connection or mutually authenticated relay channel preferred;
- no public unauthenticated control listener required for Beta;
- backend identifiers and allowed dial patterns configured locally;
- target validation occurs server-side even if Android validates first;
- serial device paths, init strings, credentials, and relay secrets never accepted from arbitrary DTE input.

## 16. Gateway diagnostics

Per backend expose:

```text
backend_id
type
availability
leased_call_id?
device path redacted/administrator-visible
open/init status
DCD/CTS/DSR/RI when available
configured serial rate
bytes rx/tx
last modem result
last error
```

Per call expose state/progress/terminal reason and counters.

## 17. Serial hardware interoperability record

Every tested physical modem gets a profile:

```text
vendor
model
firmware
USB/serial adapter if used
initialization string
DTR semantics
hardware flow-control behavior
CONNECT result examples
known modes/rates
tested remote peer
known quirks
```

Behavioral workarounds belong in named modem profiles, not generic parser hacks.
