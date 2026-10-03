# I1 Android Platform, App Shell and Developer Dialer Status

Status: IMPLEMENTED, JVM-tested where Android-free; Android-specific code has only been
compiled locally against a Robolectric `android-all` API 36 jar, so CI
(`.github/workflows/android-core-tests.yml`) is the first real AGP compile. Physical-device
acceptance is still open.

Covers: relay-session plan Task 4 (network-bound TLS transport), network-control plan Task 5
(device acceptance harness), Codex handoff items A (app shell), B (TCP DTE) and C
(instrumentation tests), and the developer dialer of `docs/spec/S1_DIALER_GUI.md`.

## What now exists

### `android/platform-android`

| Class | Android APIs | Purpose |
|---|---|---|
| `dte.TcpDteServer` | none | Java port of `tcp_dte.py`. Loopback (or explicit LAN) listener, one `ModemController` per client. Per client: one read owner, one modem thread (the only thread touching the controller: DTE input, 10 ms idle tick via `onTimeAdvanced`, session callbacks, `onDteDisconnected`), one bounded serialized writer. A DTE that stops reading is disconnected instead of dropping modem output. |
| `relay.RelayTlsTransport` | none | One ADUP control connection. `relay-owner` single-thread executor is the only caller of `RelaySessionMachine`; `relay-io` runs the connector, then is the single reader feeding `FrameStreamDecoder`; `relay-write` drains a bounded frame queue (`QUEUE_OVERFLOW`, never drops); monotonic tick calls `onTimer`. Terminates exactly once with one S1 taxonomy reason; late socket completions after close are closed and ignored. Uses only the existing public machine API and ignores unknown future `Action` types. |
| `relay.NetworkBoundRelayConnector` | `Network` | `BoundNetworkSockets.openTcp(selectedNetwork, ...)` (DNS via `Network.getAllByName`, socket via `Network.getSocketFactory()`), then TLS over exactly that socket. |
| `relay.RelayTls` | none | `SSLSocketFactory.createSocket(socket, host, port, true)`, TLSv1.3 only, `setEndpointIdentificationAlgorithm("HTTPS")`, SNI for host names, explicit `HostnameVerifier` check, negotiated-protocol check, configurable trust store (platform CAs or supplied PEM/DER). |
| `relay.DeviceCredentialAuth` | none | `device-credential-hmac-sha256-v1` proof provider; captures `relay_id` from HELLO_ACK via the transport's inbound observer (the machine's `AuthProofProvider` does not receive it). Matches the Python reference vector. |
| `relay.RelayModemSessionPort` | none | core-modem `SessionPort` over the transport: one transport per `ATD` on the network selected at dial time, events posted back to the modem thread, generation counter against stale-call resurrection, DIAL_FAILED mapping, per-call DIALING timeout -> `NO ANSWER`, advisory CALL_PROGRESS observer. |
| `dialer.*` | none | `DialerSession` (in-process DTE driving a real `ModemController`), `DestinationInput` (validated with the modem's `AtLineParser`), `DialMethod`, `CallLog`/`CallLogRecord` (section 5 record, NDJSON), `TestList` (section 3). |
| `src/androidTest/.../SelectedNetworkSocketDeviceTest` | many | Device-only acceptance, see below. |

### `android/app`

- `ModemService`: `connectedDevice` foreground service owning the single
  `AndroidNetworkManager`, the loopback `TcpDteServer` (default port 2323), the
  `DialerSession`, and per call a `RelayTlsTransport` built from the saved relay settings.
- `DialerActivity` (launcher): S1_DIALER_GUI section 6 wiring; one destination,
  AUTO/TONE/PULSE, per-call timeout, Dial / Cancel (`ATH` in DIALING) / Hang up (guarded
  `+++` then `ATH`), status rendered only from `ModemController.snapshot()` and the modem's
  own result lines, confirmed test list (individual entries, attestation recorded in the log
  header, one explicit tap per call, no auto-advance), call-progress log view behind
  `CallLog.Listener`, NDJSON export via the share sheet.
- `NetworkActivity`: policy picker (AUTOMATIC / WIFI_ONLY / CELLULAR_ONLY / PREFER_WIFI /
  PREFER_CELLULAR), developer override, live `NetworkDiagnosticsSnapshot`, relay host/port,
  endpoint id, device secret, optional private CA PEM, TCP DTE port.
- Plain framework views built in code (no layouts/resources, no AndroidX, no Compose).

### Dialer spec constraints (S1_DIALER_GUI sections 1 and 3)

- There is no range input, no sweep control and no API that iterates destinations:
  `DialerSession.dial` places exactly one call and rejects a dial while a call is in progress;
  there is no queue, redial or "next".
- `TestList` accepts at most 10 individual entries, rejects range-like syntax (`..`, `~`,
  en/em dash, brackets, braces) and any entry that fails dial validation, and
  `entryForDial` throws until the attestation was acknowledged (written to the log header).
- Log records carry the redacted target (all but the last four characters replaced by `x`),
  never credentials, proofs, nonces or payload bytes. `tones`, `dtmf_detected` and
  `negotiated` stay empty over BYTE_RELAY (no backend reports them yet).

## Verification

Local (this sandbox cannot resolve the Android Gradle plugin; `dl.google.com` is blocked):

- `platform-android` JVM tests (JUnit 4), compiled with `javac --release 17` against the
  core-* sources and run with JUnitCore: **45 tests, all passing**, repeated runs stable.
  - `TcpDteServerTest`: fragmentation, coalescing, one controller per client, idle-timer
    escape completion, DTE disconnect -> `DTE_DISCONNECTED` hangup, server close.
  - `RelayTlsTest`: real loopback TLS with throwaway self-signed test keys: TLSv1.3 +
    byte round trip, IP-literal SAN, hostname mismatch, untrusted CA, TLS 1.2-only relay,
    verifier veto all fail with `TLS_FAILURE` and close the TCP socket.
  - `RelayTlsTransportTest`: handshake/dial/200 KB echo/hangup with every listener event on
    `relay-owner`; AUTH_FAIL -> `AUTH_FAILURE`; relay death -> `NETWORK_LOST` exactly once;
    `DNS_FAILURE` mapping; late socket after close; heartbeat PING from the timer tick and
    `HEARTBEAT_TIMEOUT`; answered heartbeats keep the link up.
  - `DteToRelayEndToEndTest`: Python I1 terminal acceptance ported (ATDloopback, 64 KiB
    binary echo, `+++`, `ATO`, `ATH`), relay loss -> `+ADIAG: NETWORK_LOST` + NO CARRIER,
    BUSY, no network -> NO CARRIER, progress phases, per-call timeout -> NO ANSWER.
  - `DialerSessionTest`, `DialerInputTest`, `DeviceCredentialAuthTest`.
- All `platform-android` main sources, the `app` sources and the `androidTest` sources
  compile with `javac --release 17` against Robolectric `android-all-16-robolectric-13921718`
  (API 36 framework classes; that jar also contains hidden APIs, so every Android API used
  was additionally checked against its developer.android.com reference page).

Not compiled locally (first compile is CI): everything that needs AGP, i.e. manifest merge,
resource processing, dexing/desugaring of the core-* classes into the APK, and the
`androidx.test:runner` dependency resolution. Files whose Android usage is only checked by
the `android-all` compile:
`android/app/src/main/java/io/circuitdrift/androidialup/app/*.java`,
`android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle`,
`android/platform-android/build.gradle`,
`android/platform-android/src/main/java/.../relay/NetworkBoundRelayConnector.java`,
`android/platform-android/src/androidTest/**`.

CI command now also runs `:platform-android:assembleDebugAndroidTest :app:assembleDebug`.

## Device acceptance harness

`SelectedNetworkSocketDeviceTest` (run with `gradle :platform-android:connectedDebugAndroidTest`
on a phone with Wi-Fi and mobile data):

- requests a validated Wi-Fi and a validated cellular network (`requestNetwork` with timeout,
  which also keeps cellular up while Wi-Fi is the default), opens TCP through
  `BoundNetworkSockets` and asserts the socket's local address is one of that network's
  `LinkProperties` addresses, or its interface is the network's interface (or the `v4-`
  CLAT interface stacked on it);
- runs `NetworkBoundRelayConnector` against a public TLS 1.3 host and asserts TLSv1.3 on the
  selected network, and that the same certificate is rejected for another host name on the
  device's Conscrypt;
- starts `AndroidNetworkManager` with WIFI_ONLY and CELLULAR_ONLY and asserts the selected
  network has that transport and that sockets go through it.

A missing bearer is reported as a skipped test, not a pass. Instrumentation arguments
`probeHost`/`probePort`/`tlsHost`/`tlsPort` override the defaults.

## Decisions

1. **TLS 1.3 vs minSdk 26: fail closed, keep minSdk 26.** Android's `SSLSocket` supports and
   enables TLSv1.3 only from API 29 (protocol table on the
   [SSLSocket reference](https://developer.android.com/reference/javax/net/ssl/SSLSocket)).
   The spec requires TLS 1.3 minimum (S1_SPEC_FREEZE section 11), so there is no TLS 1.2
   fallback and no bundled TLS provider (would be a new third-party dependency). On API
   26-28 the TCP DTE, AT engine and network diagnostics work, but every relay connect fails
   with `TLS_FAILURE` ("TLSv1.3 is not supported by this platform"). The check is by
   `getSupportedProtocols()`, not `SDK_INT`. Revisit by raising `minSdk` to 29 if API 26-28
   support has no value.
2. **Hostname verification is enforced twice.** `SSLParameters.setEndpointIdentificationAlgorithm`
   and `setServerNames` exist from API 24
   ([SSLParameters](https://developer.android.com/reference/javax/net/ssl/SSLParameters)), but
   the Android security guide states "SSLSocket does not perform hostname verification" and
   tells apps to call `getDefaultHostnameVerifier()` and check its boolean result
   ([Security with network protocols](https://developer.android.com/privacy-and-security/security-ssl)).
   `RelayTls.wrap` sets `HTTPS` endpoint identification and then also requires
   `HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)` on Android. On the
   JVM tests the verifier is optional because JSSE enforces endpoint identification (the JDK's
   default `HostnameVerifier` always returns false, so it cannot be used there).
3. **Sockets only via the selected Network.** `Network.getSocketFactory()` "Returns a
   SocketFactory bound to this network ... if this Network ever disconnects, this factory and
   any Socket it produced ... will cease to work"
   ([Network](https://developer.android.com/reference/android/net/Network)). The existing
   `BoundNetworkSockets.openTcp` (DNS via `Network.getAllByName`) is reused unchanged; TLS is
   layered with `createSocket(socket, host, port, true)` so the TLS socket never opens its own
   connection. No process-wide binding.
4. **Foreground service type `connectedDevice`.** The modem serves an external computer over
   a network link (I1 TCP, I2 USB). `connectedDevice` covers interactions with external
   devices over Bluetooth, NFC, IR, USB or network connection, requires
   `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and its runtime prerequisite is satisfied by the
   normal permission `CHANGE_NETWORK_STATE`; it has no time limit
   ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)).
   `dataSync` was rejected because Android 15 limits it to 6 hours per 24 hours
   ([FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout));
   `specialUse` needs Play review justification and is unnecessary. `startForeground(id,
   notification, type)` is API 29+
   ([Service](https://developer.android.com/reference/android/app/Service)); API 26-28 use the
   two-argument overload. The service returns `START_NOT_STICKY` (process death loses calls,
   S1_NETWORK_THREADING section 14).
5. **Permissions, exactly:** `INTERNET`; `ACCESS_NETWORK_STATE` (network callbacks);
   `CHANGE_NETWORK_STATE` (FGS prerequisite above, and `requestNetwork`);
   `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`; `POST_NOTIFICATIONS`
   (requested at runtime on API 33+ so the ongoing notification is visible). The androidTest
   manifest declares `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`.
6. **Instrumentation tests compile in CI without an emulator.** `assembleDebugAndroidTest`
   only builds the test APK; running needs `connectedAndroidTest` with an attached device
   ([Test from the command line](https://developer.android.com/studio/test/command-line)).
   Runner: `androidx.test:runner:1.7.0`, the current stable release
   ([androidx.test releases](https://developer.android.com/jetpack/androidx/releases/test)),
   Apache-2.0, androidTest-only. Arguments via `InstrumentationRegistry.getArguments()`
   ([reference](https://developer.android.com/reference/androidx/test/platform/app/InstrumentationRegistry)).
   Bearers are brought up with `requestNetwork(request, callback, timeoutMs)` (API 26,
   [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager))
   and evidence uses `LinkProperties.getLinkAddresses()`
   ([LinkProperties](https://developer.android.com/reference/android/net/LinkProperties)).
7. **AGP 9 application module.** Same Groovy DSL style as the existing library module. The
   app applies `com.android.application` without a version because the root build already
   puts AGP 9.4.0 on the classpath (adding a version there would be rejected by Gradle; the
   root `build.gradle` is outside this change's ownership). AGP 9 enforces unique namespaces
   (`io.circuitdrift.androidialup.app` vs `.platform`) and defaults `android.useAndroidx=true`
   ([AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)).
8. **Threading.** Owners are `ScheduledThreadPoolExecutor(1)` instances rather than
   `HandlerThread` so the same classes run in JVM tests; delayed tasks are dropped on
   shutdown. Monotonic time: `SystemClock.elapsedRealtime` for the relay machine and call log,
   `System.nanoTime` for the escape timer (the controller takes nanoseconds).
9. **Per-call connection.** Each `ATD` opens a fresh control connection on the network
   selected at that moment, and the connection closes when the call ends. This keeps "no
   active-call migration" trivially true and avoids stale handshake state; a persistent
   authenticated control session is a later optimisation.
10. **Auth proof needs `relay_id`.** `RelaySessionMachine.AuthProofProvider` gets only the
    `AUTH_CHALLENGE`, while the Beta method binds the HELLO_ACK `relay_id`. Rather than edit
    core-session, the transport accepts an inbound-frame observer and `DeviceCredentialAuth`
    captures `relay_id` from it on the same owner thread. If core-session later passes the
    relay id to the provider, the observer can be dropped.
11. **Avoid post-API-26 Java library methods in platform code.** `String.strip()` and
    `ByteArrayOutputStream.writeBytes` are API 33 and `List.copyOf` API 31 on Android
    ([String](https://developer.android.com/reference/java/lang/String),
    [ByteArrayOutputStream](https://developer.android.com/reference/java/io/ByteArrayOutputStream),
    [List](https://developer.android.com/reference/java/util/List)), so platform-android uses
    pre-26 equivalents rather than relying on D8 backports, which could not be verified here.

## Remaining work

- **Physical-device acceptance** (the gate): run `SelectedNetworkSocketDeviceTest` on a phone
  with Wi-Fi + mobile data; then dial the Python TLS relay from the app over WIFI_ONLY and
  CELLULAR_ONLY (relay-session plan Task 6) and capture evidence.
- **core-* classes on API 26-32 (owner: core modules).** `core-modem` (`ModemController`
  `writeBytes`, `AtLineParser` `strip`), `core-protocol` (`PayloadCodec` `writeBytes`) and
  `core-session` (`RelaySessionMachine` `writeBytes`) call Java 11 methods that Android only
  has from API 33. Unless D8 backports them they throw `NoSuchMethodError` below API 33.
  Verify with Android lint (`NewApi`) or on an API 26-32 device, or replace them.
- Device secret is kept in app-private SharedPreferences (developer build); move it to
  Android Keystore-backed storage (S1_SPEC_FREEZE section 11).
- Relay-session plan Task 5 diagnostics (session counters, heartbeat age) are only partly
  surfaced (`RelayTlsTransport.Snapshot`); not yet shown in the UI.
- DTE flow control (CTS high/low water, `FLOW_CONTROL_TIMEOUT`) is not implemented; the
  transport fails with `QUEUE_OVERFLOW` instead of stalling.
- `+ACPROG`/`+ADTMF`, `AT+DTMF`, tones, DTMF detection and negotiated rate are not wired
  (no backend reports them); the log fields stay empty.
- Dialer per-call timeout is enforced in `RelayModemSessionPort`; the DIAL_REQUEST still
  carries core-session's fixed 60000 ms `dial_timeout_ms`.
