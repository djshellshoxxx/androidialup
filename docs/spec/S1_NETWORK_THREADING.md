# S1 Android Network, Threading, and Memory Specification

Status: normative for Beta 0.1.

## 1. Network policy

Supported policies:

```text
AUTOMATIC
WIFI_ONLY
CELLULAR_ONLY
PREFER_WIFI
PREFER_CELLULAR
```

Only networks with INTERNET capability are candidates. Beta requires validated Internet for relay dialing unless an explicit developer override is enabled.

## 2. Candidate normalization

```text
NetworkCandidate {
  id: opaque stable-for-lifetime handle
  transport: WIFI | CELLULAR | ETHERNET | OTHER
  internet: bool
  validated: bool
  metered: bool
  roaming: bool
  downstream_kbps_hint: int?
  upstream_kbps_hint: int?
  signal_strength_hint: int?
  last_capabilities_monotonic_ms: u64
  relay_probe_rtt_ms: float?
  relay_probe_loss: float?
  relay_probe_age_ms: u64?
}
```

Android bandwidth and signal values are hints only. Once relay probes exist, measured path behavior dominates selection.

## 3. Selection scoring

Beta deterministic scoring:

```text
if !internet: ineligible
if !validated and !developer_override: ineligible
if policy excludes transport: ineligible

base = 1000
if relay_probe_rtt known: base -= min(rtt_ms, 800)
else: base -= 250

if relay_probe_loss known: base -= 1000 * clamp(loss_fraction,0,0.5)
if metered: base -= 25
if roaming: base -= 50

policy preference bonus:
  PREFER_WIFI and WIFI: +150
  PREFER_CELLULAR and CELLULAR: +150
  AUTOMATIC: no transport bonus
```

Tie breaker order: higher score, then lower measured RTT, then Wi-Fi, then cellular, then lower opaque handle ordering only for deterministic testing.

## 4. Hysteresis

Selected idle network is not changed unless competitor score is at least 100 points higher or selected network becomes ineligible.

During an active Beta call, selection changes do not migrate the socket. A better-network event is diagnostic only.

If active network is lost, the active call fails with NETWORK_LOST in Beta 0.1. Future reconnect/migration hooks may attempt recovery but are disabled by default.

## 5. Android API ownership

Exactly one `AndroidNetworkManager` owns ConnectivityManager callbacks.

It registers requests sufficient to observe Wi-Fi and cellular Internet networks, normalizes callbacks into immutable candidate snapshots, and posts serialized `NETWORK_*` events.

No ModeAdapter, AT parser, DTE implementation, or UI component may invoke ConnectivityManager directly.

## 6. Socket creation

TCP:

```text
openBoundTcp(network, address, timeout):
  socket = network.socketFactory.createSocket()
  configure keepalive/nodelay as required
  socket.connect(address, timeout)
  return socket
```

UDP/native fd:

```text
openBoundUdp(network, remote):
  fd = socket(... SOCK_DGRAM ...)
  network.bindSocket(fd)
  connect(fd, remote)
  return fd
```

Process-wide network binding is prohibited for normal Beta session traffic.

DNS resolution for relay endpoints SHALL occur using the selected `Network` where Android API support permits; implementations must not accidentally resolve through a different default network and then assume the result proves reachability.

## 7. Thread model

Required logical executors:

```text
UI/Main
StateReducer
DteIo
SessionIo
NetworkCallbacks
PacketRx
PacketTx
Metrics
NativeAudioCallback (future I3)
DspWorker (future)
```

These may map to coroutines/thread pools, but ownership rules remain.

## 8. StateReducer

Single writer for authoritative state.

Rules:

- never performs blocking socket/file/DTE I/O;
- never waits for futures;
- performs bounded deterministic computation only;
- emits effects into effect executors;
- publishes immutable snapshots.

## 9. I/O ownership

Each stream/socket has exactly one read owner and one write serialization owner.

`SessionIo` owns reliable relay stream parsing. Writes from other modules enter a bounded outbound queue and are serialized by one writer.

DTE reads occur on `DteIo`; DTE writes are serialized by the transport implementation.

No two threads write raw bytes to the same framed stream independently.

## 10. Buffer ownership

Beta JVM/control-plane rule:

- immutable `ByteArray` or immutable frame object crosses module/event boundaries;
- mutable pooled buffers stay within one I/O subsystem until returned;
- a buffer cannot be returned to a pool while referenced by a queued event.

Native audio rule:

- preallocated rings only in realtime callback;
- no heap allocation in callback;
- single-producer/single-consumer rings where possible;
- ownership transfer is index-based rather than pointer sharing with unbounded lifetime.

## 11. Backpressure

DTE -> relay:

```text
if relay tx queue < high_water:
  CTS=1
else:
  CTS=0 if supported
  begin stall timer

if queue drains below low_water:
  CTS=1
  cancel stall timer

if stall timer expires:
  terminate FLOW_CONTROL_TIMEOUT
```

Defaults:

```text
relay tx max 256 KiB
high water 192 KiB
low water 128 KiB
```

Relay -> DTE uses symmetric bounded behavior but cannot assume hardware flow control exists; a persistently blocked DTE terminates the call rather than discarding BYTE_RELAY bytes.

## 12. Cancellation

Every async dial/connect operation has a cancellation token tied to current `call_id`.

When hangup/reset occurs:

1. reducer increments/changes call generation;
2. outstanding effects are cancelled where APIs support it;
3. late completion events carrying stale call ID/generation are ignored and counted.

This prevents a delayed successful socket connection from resurrecting a cancelled call.

## 13. Timer service

One monotonic timer service posts `TIMER_EXPIRED(timer_id, call_id, generation)` events.

Timers are logical records, not sleeping reducer threads.

Cancellation removes logical timer registration; stale expirations are ignored by generation check.

## 14. Lifecycle

Foreground service is required while DTE service or active call is enabled, subject to Android platform requirements.

Process death loses active calls in Beta. On restart:

- stale local call state is not reconstructed as connected;
- relay session from prior process instance is treated abandoned;
- DCD starts low;
- diagnostics may record prior abnormal termination if persisted safely.

## 15. Metrics snapshots

Metrics collection reads atomics/counters or immutable queue stats. It never locks StateReducer or realtime paths for prolonged periods.

Snapshot interval default: 1 second during active calls, 5 seconds idle.

## 16. Structured logs

Each log record:

```text
timestamp_monotonic
timestamp_wall_optional
severity
component
event_code
call_id optional
session_id optional
fields bounded map
```

Sensitive values are redacted at producer boundary.

High-frequency packet/audio logs use counters/sampling, not one disk log per packet.

## 17. Concurrency tests

Tests SHALL inject:

- network loss simultaneous with remote CONNECT;
- user hangup during TLS connect;
- DTE disconnect during DIALING;
- stale dial completion after cancellation;
- queue high-water/low-water transitions;
- relay close while outbound writes are queued;
- rapid Wi-Fi/cellular capability callbacks;
- duplicate/lost timer events.

Correctness criterion: deterministic terminal state, no resurrection of stale calls, no unbounded queue growth, and exactly one terminal DTE result where applicable.
