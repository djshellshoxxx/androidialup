# Codex Handoff — 2026-10-02

This file tells Codex what Claude is already building on branch
`claude/gifted-shannon-q2sjwr` so no duplicate work is done. Paste the
"Prompt for Codex" section into Codex verbatim.

## Prompt for Codex

You are working on the AndroidDialup repo together with Claude. Claude is
concurrently delivering the following four tracks on branch
`claude/gifted-shannon-q2sjwr` (PR to follow). Do NOT implement, refactor or
edit anything in these areas; treat the listed paths as owned by Claude until
that PR merges:

1. **Cross-language golden vectors only.** Main already carries Codex's
   Java `Messages`/`PayloadCodec`/`FrameStreamDecoder`, and Claude's duplicate
   codec was dropped in favour of it. Claude keeps
   `prototype/python/tools/gen_adup_vectors.py`,
   `prototype/python/tests/vectors/**`,
   `prototype/python/tests/test_protocol_vectors.py` and
   `android/core-protocol/.../PayloadCodecVectorsTest.java`, which runs every
   shared vector through Codex's codec. Do not change payload byte layout
   without regenerating the vectors.
2. **Pure Java AT/V.250 parser, result-code engine, escape detector and modem
   state reducer.** Paths: `android/core-modem/**` (module already in
   `settings.gradle` and CI). Result: `AtLineParser`, `ModemProfile`,
   `AtEngine`, `EscapeDetector`, `ModemController` plus a `SessionPort`
   interface the Android relay adapter will implement.
3. **Real per-device challenge/response authentication in the Python relay.**
   Paths: `prototype/python/androidialup_relay/**` (new `auth.py`),
   auth parts of `prototype/python/androidialup_modem/relay_port.py`,
   `docs/implementation/I1_RELAY_AUTH_STATUS.md`, and section 4 of
   `docs/spec/S1_WIRE_PROTOCOL.md`. Method string
   `device-credential-hmac-sha256-v1`, HMAC-SHA256 proof bound to nonce,
   endpoint_id and relay_id.
4. **Cellular voice codec research and spec integration.** Paths:
   `docs/research/CELLULAR_VOICE_CODECS_R2.md`,
   `docs/research/CELLULAR_VOICE_EXPERIMENTS_R2.md`, new rows in
   `docs/research/SOURCE_INVENTORY.md`, cellular-voice sub-sections in
   `docs/spec/S1_MEDIA_DSP_CONTRACT.md`, `BETA_0_1_ARCHITECTURE.md`,
   `S1_SPEC_FREEZE.md`, `S1_TEST_PLAN.md`, and Phase X1 of
   `docs/PROJECT_PLAN.md`.

Owned by Codex (Claude will not touch): `android/core-session/**` and
`android/core-protocol/src/main/**`.

Also owned by Claude's branch: `.gitignore`, `android/settings.gradle`,
`.github/workflows/android-core-tests.yml`.

### Open work you (Codex) should take instead

Pick from these; they touch none of the paths above and are the remaining
Phase I1 items from `docs/implementation/I1_AT_DTE_STATUS.md`:

A. **Android application shell** — new `android/app` module: foreground
   service, developer screen showing `NetworkDiagnosticsSnapshot`, policy
   picker (WIFI_ONLY / CELLULAR_ONLY / AUTOMATIC ...), and hooks for the
   device acceptance harness (Task 5 of
   `docs/superpowers/plans/2026-10-02-i1-android-network-control.md`).
   Add it to `settings.gradle` and CI only after Claude's PR merges, or in a
   separate commit that only appends lines.
B. **Android TCP DTE developer transport** in `android/platform-android`
   (`TcpDteServer` equivalent of `prototype/python/androidialup_modem/tcp_dte.py`):
   one controller per client, fragmentation/coalescing, idle timer servicing.
   Define it against a small `DteByteSink`/`DteByteSource` interface so it can
   be wired to `core-modem`'s `ModemController` once that lands.
C. **Instrumentation tests** for `BoundNetworkSockets` and
   `AndroidNetworkManager` on real devices (`androidTest` source set), proving
   relay sockets use the selected Android `Network`.
D. **Python relay liveness** — heartbeat scheduling and PONG-timeout failure
   mapping in `prototype/python/androidialup_protocol/async_connection.py`
   and forced network-loss / relay-death acceptance tests in new test files.
   Do not edit `session.py`, `server.py` or `relay_port.py` (auth work in
   flight); add a new module if you need server-side timers.
E. **Status docs** — `docs/implementation/I1_ANDROID_NETWORK_CONTROL_STATUS.md`
   describing what `core-network` and `platform-android` already prove.

Conventions: test-driven, pure JVM logic stays Android-free, Java 17 source
level, JUnit 5 in `core-*` modules and JUnit 4 in `platform-android`, Python
3.12 unittest, no new third-party dependencies without a license note. Local
note: the Android Gradle plugin needs `dl.google.com`; the pure `core-*`
modules can be tested with a scratch JVM-only `settings.gradle` that points
`projectDir` at the module.

When you finish a track, add a status doc under `docs/implementation/` and
state which paths you touched so Claude can avoid them next round.
