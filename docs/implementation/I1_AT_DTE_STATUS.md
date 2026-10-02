# I1 AT/DTE + Live Relay Status

Status: PASS for the Python transport/control reference slice.

## Implemented

The prototype now has a complete terminal-facing path:

```text
TCP terminal/DTE
  -> TcpDteServer
  -> ModemController
  -> AtLineParser / AtEngine / EscapeDetector
  -> RelaySessionPort
  -> TLS 1.3 ADUP relay
  -> RelaySession
  -> LoopbackBackend
  -> reverse path to terminal
```

Implemented behavior includes:

- V.250-style command parsing and result formatting for the Beta command set.
- COMMAND, DIALING, ONLINE_DATA, and ONLINE_COMMAND state ownership.
- DCD state transitions on connect/termination.
- `ATDloopback` to a real TLS relay session.
- BYTE_RELAY binary transport through the loopback backend.
- timed `+++` escape using S12 guard time.
- `ATO` return to online data.
- `ATH` clean local hangup.
- remote hangup/failure mapping.
- TCP DTE fragmentation/coalescing handling.
- one controller per TCP DTE client.
- monotonic timer servicing while the DTE socket is idle.
- bounded relay transmit queue.

## Backpressure bug found and fixed

The first terminal end-to-end test exposed a real integration defect: `ModemController` originally called `SessionPort.write_data()` once per transparent DTE byte while scanning for escape characters. A 64 KiB TCP read therefore generated tens of thousands of tiny relay operations and exhausted the bounded transmit-frame queue.

The fix keeps bytewise escape detection internally but aggregates all ordinary forwarded bytes from a DTE read before handing them to the mode/session adapter. Protocol-sized BYTE_RELAY chunking then happens at the existing ByteRelaySender boundary.

A regression test now verifies that a 64 KiB online DTE read remains byte-for-byte intact without degenerating into per-octet session writes.

## Escape timer correction

Integration also exposed an earlier EscapeDetector timer defect: an incomplete one- or two-plus candidate was being flushed immediately when the timer advanced. It now remains held until one complete S12 guard interval has elapsed after the most recently held `+`.

Tests pin the exact boundary behavior for one-plus, two-plus, and three-plus candidates.

## End-to-end acceptance exercised

The terminal integration test performs:

1. `AT` -> `OK`.
2. Set short test guard time through `ATS12=1`.
3. `ATDloopback` -> `CONNECT`.
4. Send and receive a deterministic 64 KiB binary payload unchanged.
5. Wait pre-guard, send `+++`, receive `OK`, enter ONLINE_COMMAND.
6. `ATO` -> `CONNECT`, return ONLINE_DATA.
7. Escape again and issue `ATH` -> `OK`.
8. Verify DCD low and modem state COMMAND.

The protocol layer separately retains the existing 1 MiB TLS BYTE_RELAY acceptance test.

## Verification

GitHub Actions workflow: `Prototype Tests`.

Known green checkpoint:

- commit `71a577e9e0b43ea23f3d45b97123de4554214526`
- workflow run `37051980686`
- result: SUCCESS

This run includes the terminal-to-relay end-to-end test plus all existing protocol, relay, TLS, gateway, AT parser/engine, escape, controller, and TCP DTE tests.

## Remaining I1 work

The Python reference control plane is now sufficient to guide the Android implementation. Remaining I1 work is primarily Android-specific:

- Android project/application shell.
- `AndroidNetworkManager` / `NetworkSelector` implementing S1 policy and scoring.
- explicit Wi-Fi/cellular `Network` socket binding.
- Android relay session adapter using the frozen ADUP v1 framing.
- Android TCP DTE developer transport wired to the reducer/state model.
- structured diagnostics/metrics snapshot plumbing.
- instrumentation on actual Android hardware for WIFI_ONLY and CELLULAR_ONLY bearer tests.

The hardware serial-modem gateway remains an I5 gate, not an I1 requirement.
