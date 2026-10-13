# Androidialup modularization roadmap

Updated: 2026-10-13. Status: incremental, compatibility-first. Scope is the Android/JVM application and Python reference/host implementation. **Do not interpret this as authorization for wholesale rewrites or changed external behavior.**

## Current system boundaries

| Area | Files/packages | Responsibility | Boundary status |
| --- | --- | --- | --- |
| Android protocol | `android/core-protocol` | ADUP framing, validation, message serialization | Cohesive; preserve wire bytes, frame ordering and vector tests. |
| Android modem | `android/core-modem` | V.250 AT commands, escape handling, DTE modem state | Cohesive; state machine and timing are high risk. |
| Android networks | `android/core-network` | Selection logic and diagnostics | Pure selection already separate from `platform-android`; protect choices and policy. |
| Android session | `android/core-session` | Session/authentication/relay state | Preserve protocol transitions and failure taxonomy. |
| Android platform | `android/platform-android` | USB/TCP DTE, network sockets, TLS; Android API boundaries | Hardware/transport adapters already separated. Reduce oversized adapters cautiously. |
| Android application | `android/app` | Foreground service and developer UI | `ModemService` coordinates networking, DTE, USB, preferences and TLS; **higher risk** to split due to lifecycle and thread ownership. |
| Python protocol | `prototype/python/androidialup_protocol` | ADUP transport, codecs, flow control and liveness | Mostly cohesive; use Java/Python golden vectors as contract. |
| Python relay/gateway | `androidialup_relay`, `androidialup_gateway` | Relay server/session, backend lifecycle | Keep server admission and backend failures isolated from frame semantics. |
| Python modem | `androidialup_modem` | AT command and modem DTE | Preserve parity with JVM modem and error taxonomy. |
| Python PC host | `androidialup_host` | AOA control, PyUSB, host bridge and CLI | Low-risk extraction started: `accessory_discovery.py`. |

## Approved incremental sequence

1. **First safe extraction (in progress):** move AOA device discovery, optional initial VID:PID switch and re-enumeration polling from `androidialup_host.cli` to `androidialup_host.accessory_discovery`. Keep `cli._obtain_accessory` and `cli._wait_for_accessory` aliases, existing argument names, diagnostics, timeout semantics and ownership. Prove with fake USB tests, entire Python suite on Linux + Windows, and untouched Android workflow checks.
2. **PC host next:** split CLI option validation from socket/server lifecycle, retaining `run(argv)`, parser flags, stderr messages and exact exit codes. Add executable CLI tests that exercise defaults, USB unavailable, malformed IDs and socket/bridge failure. Do not add real USB driver dependencies to the normal test suite.
3. **Android service:** first characterize service create/start/destroy, USB permission callback, network policy saves and DTE port restarts with instrumented tests. Then extract preference/notification or lifecycle coordination **as one independent PR per boundary**. Retain `ModemService` as the compatibility façade.
4. **Android UI:** isolate input parsing and view models from `DialerActivity` and `NetworkActivity` only once UI state behavior is covered. Avoid changing public extras, permissions and exported components.
5. **Session and relay:** only after coverage for delayed frames, dial/hangup idempotence, authentication failures, flow control, queue bounds and cancellations. Extract state transitions through documented narrow interfaces; freeze golden vectors and taxonomy as acceptance criteria.
6. **Soft modem / SIP and audio features:** add codec, media transport, PHY/DSP, gateway adapters and routing as separately compiled/tested modules from the first implementation. The protocol and audio/data planes must remain independently testable and avoid allocation/locking in real-time callbacks.

## Refactor acceptance checklist

- The change declares what **moves**, what stays public, and which behaviors cannot change.
- Focused characterization tests cover real input/output and at least one error path *before* extraction.
- Old entry points continue working (or a versioned migration is separately approved).
- No new cyclic dependency, hidden singleton, unbounded queue or expanded permission.
- Test suite and build workflow pass on the final PR revision, including Linux and native Windows Python, Android debug/test, release assembly and lint where relevant.
- API 29+ TLS reality, physical AOA/USB and Android foreground-service acceptance remain independently tracked; passing JVM tests is not hardware validation.
- Documentation and module table updated and follow-up refactors use fresh independent PRs.

## Deferred items requiring evidence

- `ModemService` foreground startup and USB receiver ownership: physical API29/34/36 test matrix.
- Java `RelayTlsTransport` owner task queue bounds and TCP DTE concurrency: backpressure/stress baselines.
- Android-to-Python golden vectors and call state transitions: parity testing before any shared protocol abstraction.
- USB hotplug and Windows driver integration: real-device enumeration and reconnect tests.
- Gradle build reproducibility: checked-in verified wrapper and release-signing/install pipeline.

In particular, **do not conflate modularity with completing missing product capabilities** (SIP, VBD, PHY). Those require separate feature specifications and end-to-end acceptance.
