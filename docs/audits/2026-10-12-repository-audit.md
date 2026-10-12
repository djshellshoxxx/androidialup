# Androidialup repository audit and completion backlog — 2026-10-12

Baseline: `main` at `71ab245ee1ce274338eb40f4c4c317599d2e911b` (PR #9, 2026-10-06). Scope: source, specs, test inventory, CI history, branch/PR inventory and representative security/failure handling paths. This is a code-and-evidence review, **not** physical device, PSTN, DSP, or release certification.

## Branch and build baseline

- GitHub has six historical pull requests (#4–#9), none open. PRs #4, #6, #8, #9 were merged; #5 and #7 were closed after their intended changes were consolidated in #9.
- Six historical non-main branches remain. `codex/i2-aoa-service` is behind `main`. Others diverge because of stacked histories and squash merges. Do not merge them wholesale without patch-by-patch comparison.
- Latest `main` Android Core Tests CI run (2026-10-06) passed, including app APK compilation, pure-Java, Android adapter JVM, instrumentation **APK compilation**. Instrumentation APK compilation does not execute tests on a phone.
- The Python Prototype Tests workflow runs `python -m unittest discover -s tests -v`. Local hardware testing and interoperability with a real modem are not covered by either workflow.
- The source tree does **not** contain a Gradle wrapper; CI explicitly selects Gradle 9.6. For repeatability, a checked-in wrapper is recommended after checking Android Gradle Plugin compatibility.

## Capability and acceptance matrix

| Area | Repository evidence | Status | Exit needed |
|---|---|---|---|
| Research, protocols and architecture (R1/S1) | `docs/research`, `docs/spec`, S1 exit review | Specified | Revalidate claims when implementations change |
| Python AT/DTE + relay + loopback (I1) | `prototype/python/androidialup_modem`, `androidialup_relay`, `androidialup_protocol`, Python tests | Implemented/tested in simulation | Negative tests, hardening, cross-language live session |
| Java modem, session, network and app shell (I1) | `android/core-*`, `platform-android`, `app` | Implemented, CI-compiles and JVM-tests | Run phone acceptance on Wi-Fi and mobile data; capture diagnostics |
| USB Open Accessory and desktop bridge (I2) | `UsbAccessoryDteTransport`, `UsbAccessoryCoordinator`, `androidialup_host` | Software path exists | Gate I2-B physical phone/PC, unplug/replug, 64 KiB binary; POSIX PTY, optional Windows COM bridge |
| Privileged USB CDC-ACM device endpoint (I2) | `SerialGadgetProvider`, `PrivilegedCdcAcmDteTransport` | Abstraction only for device-specific serial provider | Choose supported test handset/image, implement real provider, Gate I2-C |
| Timed PCM/VBD/media and network impairment (I3) | S1 media/DSP spec; no corresponding implemented media module | Not implemented | Independent PCM vectors, jitter/loss/reorder/clock-drift measurements |
| V.21/Bell-103-class DSP (I4) | PHY research and contract only; no PHY implementation | Not implemented | Independent transmitter/receiver conformance vectors and reference interoperability |
| Real remote dial through hosted SIP/PSTN gateway (I5) | `S1_GATEWAY_BACKEND.md` describes planned `sip_trunk`; code has loopback backend only | Not implemented | SIP trunk gateway and real modems, result-code mapping, no-landline acceptance |
| V.22–V.90 and standards expansion (I6/R2) | Research documents | Research stage | Individual spec/license/vector gates per standard |
| Session migration between Wi-Fi and cellular (I7) | Project plan | Not implemented | Authenticated reconnect, session identity and loss/ordering tests |
| Cellular voice (X1) | Research and experimental matrix | Research stage, explicitly nongating | Scientific evidence gates G-X1-0 through G-X1-4; no working-mode claims |

## Confirmed defects corrected on audit branch

1. `prototype/python/androidialup_relay/session.py`: a backend exception during `HANGUP_REQUEST` escaped the relay call-state handler. Added a regression test in `tests/test_relay_session_failures.py`, and now send the normal `HANGUP_ACK` followed by one `BACKEND_UNAVAILABLE` termination while returning to the authenticated control state.
2. `README.md`: install instructions checked out an old merged branch; changed to `main`. Replaced the nonexistent `PROTOCOL_AND_MODULE_CONTRACTS.md` documentation reference with existing S1 files and clarified software-vs-hardware verification.

## Unresolved audit findings, prioritized

**P1 — bounded resources and remote robustness**

- Python `RelayTcpServer` tracks each completed client in an ever-growing `close_records` list, and has no explicit concurrent-session cap. Long-running internet-facing deployments risk unbounded diagnostics memory growth and client/task resource exhaustion. Add configurable limits, close-record ring, and authenticated/load/concurrency tests. Limit TLS handshake time as well.
- Java `TcpDteServer` allocates multiple threads and a scheduled executor per client without a global client bound; inbound read tasks use the scheduler's unbounded queue. Bound clients/inbound pending bytes and prove under a deliberately slow controller that excess input is rejected/disconnected rather than buffered indefinitely.
- Strengthen unauthenticated input, parser fuzz/property tests, size bounds and test clients that never complete TLS/HELLO/AUTH. Frame payload bounds exist, but server-level denial-of-service testing is incomplete.

**P2 — functionality and operating correctness**

- `RelaySession` currently only supports the loopback backend. `sip_trunk` and live remote modem operation are major missing product features, not minor wiring defects.
- `androidialup_host/cli.py` serves one local TCP client then exits; it does not expose a POSIX PTY or native Windows COM device. Build/validate a restart/reconnect story and host device-selection behavior.
- USB accessory success and bearer binding have only simulation/JVM and instrumentation-compile evidence. Real phone tests must exercise API-level and OEM behavior, permission timing, detach/reinsert and full binary streaming.
- DTE modem control signals (CTS/DCD/RTS/DTR where supported), `ATA` inbound handling, some dial progress and DTMF controls, and timeouts remain in the implementation-status backlog.
- The Android app declares `minSdk 26`, but the TLS 1.3 relay path requires Android API 29+. Document capability on 26–28 separately or align an explicit product floor. The earlier API 26 Java/desugaring and Keystore gaps were addressed in PR #9; older status notes are historical.
- Check Keystore corruption/migration behavior on actual devices, certificate rotation, credentials enrollment/revocation and TLS peer/server failure taxonomy before public beta.

**P3 — process and QA**

- Keep a checked-in Gradle wrapper, separate debug developer builds from publishable APKs, and add repeatable Android emulator instrumentation runs where feasible.
- Coverage reporting, static analysis, dependency/SBOM scanning, release signing and reproducible artefacts are not wired into present CI. Preserve the existing successful tests when adding checks.
- Reconcile stale `docs/implementation/*STATUS.md` entries with newer PR #9; do not treat older “remaining” items as current code defects without inspecting the main tree.

## Parallel implementation boundaries

1. **Relay hardening**: own `prototype/python/androidialup_relay` + matching tests. No overlapping writes with other workstreams.
2. **Android DTE concurrency and USB device tests**: own `android/platform-android/*dte*`, USB tests, physical test logs. Preserve the serialized modem owner.
3. **I3 media timing**: new media module with its own external vectors/simulation, no AT parser modification.
4. **I4 PHY/DSP**: new native DSP module and independent test vectors, no claims of real modem support without cross-check.
5. **I5 hosted SIP gateway**: own `androidialup_gateway` backend contract, mock SIP tests and separate integration test rig.
6. **QA/release**: own CI/workflows/build docs, verify test matrices, coordinate merges by green checks and fresh main rebases.

Keep distinct branches per area, publish tested PRs, and merge only after review and relevant automated checks. Tasks 3–5 may proceed in parallel behind stable contracts, but I5 interoperability depends on an actual supported gateway/PHY strategy and service access.

## Validation record

At audit time, no physical phone, USB gadget, modem, hosted SIP trunk, or live carrier bearer was available through these tools. Historical main CI is green; this audit branch's new Python regression test must pass its own new GitHub Actions workflow before merge. Do not represent this document as an end-to-end hardware certification or as completion of I3–I7.
