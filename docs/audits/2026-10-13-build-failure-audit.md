# Androidialup deep bug and build-failure audit — 2026-10-13

Scope: public `djshellshoxxx/androidialup` repository, Android/Java control plane and Python prototype/host. Audited current code and CI configurations; reproduced findings with dedicated GitHub Actions jobs on Linux and **native Windows**, not on physical Android/USB hardware. Findings below distinguish confirmed failures from exposure or unverified requirements.

## Evidence and build gates

| Check | Result / evidence |
| --- | --- |
| Original `main`, `fbafcc6b` | Android Core Tests passed in Actions run 38352600357. Debug application and Android instrumentation APKs assemble; instrumentation tests are **not executed**. |
| USB/stream DTE fix | PR #21 merged as `a71fe6d5` after Android Core Tests run 38474657635 passed. |
| Additional native Windows regression suite | The first run 38474584822 **failed**, exposing 3 test failures and 2 errors. Full suite passes after fixes in run **38475518898**. |
| Added Android release and lint gate | Initially **failed**, revealing genuine Android lint errors masked by normal debug compile; the release APK had assembled before lint failed. Both lint targets and release assembly now pass in run **38475518898**. |
| Linux Python suite on audit branch | `Prototype Tests` run **38475518618** passed. |
| Android debug/JVM suite with UI fixes | `Android Core Tests` run 38475398890 passed (before latest Python-only changes). |
| Physical Android runtime/USB AOA | **Not tested**. |
| Real hosted SIP, PCM/VBD, softmodem DSP | **Not implemented**; build success is not functional interoperability. |

## Confirmed defects fixed

1. **P1 / real runtime: USB DTE memory and cleanup.** `StreamDteSession` had an outbound queue cap but unbounded incoming chunks queued on its modem executor; a stalled consumer could consume unbounded memory. It also skipped the stream-close notification and executor shutdown when backend hangup threw. PR #21 caps pending input at 1 MiB by default and guarantees lifecycle notification. Regression CI green.
2. **P1 / real protocol: normal backend failure could kill reusable TLS connection.** Native Windows failure-injection test exposed `NETWORK_LOST`; relay-side evidence showed an unexpected `FLOW_STATUS` after the previous call ended. `RelaySession` now ignores **only a validated flow-credit frame bearing the ID pair of its last terminated call** while idle. Unknown call IDs and other unexpected frame kinds remain protocol violations. Windows/Linux CI now green.
3. **P1 / builds: Java Android lint errors unseen by debug assembly.** `DialerActivity` and `NetworkActivity` used hard-coded numerical radio view IDs and arithmetic; lint rejects this and unchecked IDs could cause indexing errors. Replaced with generated view IDs and guarded ID mappings.
4. **P1 / builds: dynamic receiver export declaration lint.** The service correctly uses explicit export flags on Android 13+, but pre-13 calls were still flagged. Isolated the legacy registration in a version-gated method with a narrowly-scoped lint annotation.
5. **P1 / builds: library permission manifest.** Android library lint could not establish `ACCESS_NETWORK_STATE` for `AndroidNetworkManager`. Declaring the permission in the platform library manifest allows correct merger into the app and makes lint pass.
6. **P1 / native Windows tests: unavailable POSIX signals.** Windows lacks `SIGSTOP/SIGCONT`; limit process-suspend failure injection to POSIX while preserving cross-platform kill tests.
7. **P1 / native Windows tests: golden vector line endings.** Git checkout converts LF to CRLF under Windows; raw newline comparison reported stale generated vectors. Normalize at the text-reading boundary, leaving generated payload bytes unchanged.
8. **P1 / Windows host: reader teardown hang.** Shutdown of a read-half did not always wake a blocked socket receive on Windows when USB reached EOF. Replace indefinite read with a bounded `select.select` wait, with regression coverage.

## Remaining risks and plausible build-failure causes

| Priority | Evidence | Problem / next gate |
| --- | --- | --- |
| P1 | No `android/gradlew`, `android/gradle/wrapper/*` in recursive tree | Builds require correctly provisioned **external Gradle 9.6** and AGP **9.4.0**; `./gradlew` instructions cannot work. Add a genuine checked-in wrapper JAR + properties with verified checksums (do not invent a JAR or use an unaudited download). |
| P1 | `AndroidManifest` minSdk 26 vs `RelayTls` TLS 1.3 API 29+ | API 26–28 devices are installable but cannot connect to relay. Align release minimum SDK to API 29, or provide a supported alternative transport before claiming support for older Android. |
| P1 | No test runner actually invokes `androidTest` on hardware | USB accessory permission, enumeration, foreground service on API 34–36, network-bound sockets, Android Keystore and device-specific TLS remain unverified. Set up emulator/device matrix and **real AOA host** acceptance. |
| P1 | Java `RelayTlsTransport`, `DialerSession` use single-thread ScheduledThreadPoolExecutor with default unbounded task queue | Malformed or bursty clients may exhaust memory while owner is stalled; bound and stress-test inbound queue by bytes and callbacks (related #11). |
| P1 | `RelayTcpServer.max_clients` applies **after TLS**, despite 10s handshake timeout | Many simultaneous pre-handshake sockets may still exhaust server resources. Add pre-handshake admission/rate controls or a terminating proxy with capacity limits (related #11). |
| P1 | No functional SIP trunk, media DSP, VBD or real modem gateway yet | Developer app APK building does not mean a user can dial a real analog modem (#13–15). |
| P2 | Native Windows tests require `openssl` in PATH, POSIX-only process suspension skipped | New gate proves the Windows Python prototype; does not prove PyUSB/libusb, COM serial, or a Windows standalone installer. |
| P2 | Release `assembleRelease` is unsigned; artifact lacks tested install and publishing policy | Must provision signing securely, verify signed release installation/upgrade and publish a versioned APK. |
| P2 | `verify_api26_compat.py` scans only 4 libraries and a short forbidden-token list | It is not a complete API 26 runtime verifier; lint and on-device API 29+ tests remain necessary. |
| P2 | CI only Ubuntu Android and Windows Python; no macOS or physical-device gate | Add matrix where supported and avoid extrapolating from Linux-only tests. |
| P2 | Linux Python tests generate self-signed certificates with `openssl`; PC AOA backend optionally imports `pyusb` | Missing external OpenSSL/libusb dependencies or USB drivers will fail local tests/hardware runs even when no Java build errors occur. |

## New continuous build gate

`.github/workflows/cross-platform-build-audit.yml` runs Android release assembly, both Android lint targets and native Windows Python 3.12 full tests in addition to existing workflows. It uploads unsigned release APK/lint artifacts for inspection. The gate is reproducible in GitHub Actions and preserves the existing Java and Linux Python suites. It cannot substitute for on-device testing.

## Integration instructions

1. Require `Android Core Tests`, `Prototype Tests`, and **both jobs** of `Cross-platform Build Audit` to pass on the final PR revision.
2. Merge with the PR head SHA pinned, verify `main` CI after merge, and treat any further lint failure as a release blocker.
3. For the next audit iteration, prioritize actual Android service start/stop and AOA discovery on phones, bounded Java relay owner queues, and a checked-in Gradle wrapper. Track acceptance with reproducible logs and device/OS versions.

Neither green CI nor this report represents an end-to-end working modem: the SIP and modem media/PHY features remain future milestones.
