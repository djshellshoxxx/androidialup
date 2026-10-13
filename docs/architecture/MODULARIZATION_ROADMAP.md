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
| Python PC host | `androidialup_host` | AOA discovery, PyUSB adapter, host bridge, TCP listener and CLI | Discovery extracted into `accessory_discovery.py`; `tcp_listener.py` now owns one-client TCP lifetime with `cli.run()` as composition root. |

## Approved incremental sequence

1. **Complete, PR #24:** USB accessory discovery, initial VID:PID AOA mode-switch and re-enumeration polling now live in `androidialup_host.accessory_discovery`. `cli._obtain_accessory` and `cli._wait_for_accessory` remain as compatibility aliases. Linux and cross-platform CI passed.
2. **In progress, this PR:** `androidialup_host.tcp_listener.serve_one_client(host, port, transport, bridge)` owns exactly one TCP socket, accepted-client lifetime and bridge-failure reporting. `cli.run(argv)` remains the composition root and supplies the existing `bridge_socket_to_transport` implementation. CLI option parsing/validation, USB initialization, stderr output and exit codes remain intact. Fake-socket tests characterize success, bind failure, bridge failure, close/cleanup and the existing CLI entry point on Linux and Windows. Future extraction of pure CLI argument validation is optional only if complexity warrants its own module.
3. **Android service:** first characterize service create/start/destroy, USB permission callback, network policy saves and DTE port restarts with instrumented tests. Then extract preference/notification or lifecycle coordination **as one independent PR per boundary**. Retain `ModemService` as the compatibility façade.
4. **Android UI:** isolate input parsing and view models from `DialerActivity` and `NetworkActivity` only once UI state behavior is covered. Avoid changing public extras, permissions and exported components.
5. **Session and relay:** only after coverage for delayed frames, dial/hangup idempotence, authentication failures, flow control, queue bounds and cancellations. Extract state transitions through documented narrow interfaces; freeze golden vectors and taxonomy as acceptance criteria.
6. **Soft modem / SIP and audio features:** add codec, media transport, PHY/DSP, gateway adapters and routing as separately compiled/tested modules from the first implementation. The protocol and audio/data planes must remain independently testable and avoid allocation/locking in real-time callbacks.

## PC listener module interface and invariants

- **Provider:** `androidialup_host.tcp_listener.serve_one_client(host, port, transport, bridge)`.
- **Consumer:** `androidialup_host.cli.run(argv)` passes the configured endpoint, already-open USB transport and existing bridge function.
- **Lifecycle ownership:** The listener owns and closes its TCP listening/accepted sockets. The caller owns USB transport. It serves **one** accepted client, matching prior behavior; reconnection/multiple clients require a separate feature and spec.
- **Error contract:** Return `0` for completed bridge; return `3` and print the original diagnostic on bridge `OSError`/`RuntimeError`; propagate socket bind/listen/accept failures. Do not change those contracts during this refactor.
- **Dependency direction:** CLI (options and composition) → TCP listener (socket lifetime) → injected bridge function (byte forwarding). The listener does not know PyUSB, protocol frames or Android state and can be tested without USB hardware.
- **Tests:** `prototype/python/tests/test_host_cli.py` checks unchanged CLI behavior; `test_host_listener.py` checks the extracted interface, socket cleanup and error paths. Run full Linux and native Windows Python, Android lint/release build and Android core tests as applicable.

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
