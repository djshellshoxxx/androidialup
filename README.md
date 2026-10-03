# AndroidDialup

AndroidDialup is an experimental Android-based dial-up modem platform intended to expose a legacy modem-style DTE interface (AT commands plus serial-style data) while allowing the remote connection to travel over modern IP networks and, where practical, legacy/voice-band paths.

The project is research-first. Beta 0.1 will be built from a sourced engineering specification rather than from ad-hoc implementation decisions.

## Project goals

- Present a modem-like endpoint to computers using USB serial, Bluetooth serial where available, or TCP.
- Support AT-command control and modem call-state semantics.
- Use Wi-Fi and LTE/5G packet data as interchangeable IP transports.
- Evaluate modem relay, voice-band-data transport, remote-modem gateways, and full software modem DSP.
- Preserve a path toward real legacy modem interoperability.
- Investigate cellular voice only where Android platform privileges, device hardware, carrier behavior, and vocoder behavior make it technically meaningful.
- Build reproducible channel/network simulators and externally sourced regression vectors before claiming interoperability.

## Working conclusion for Beta 0.1

The initial architecture is **multi-mode with an IP-first core**:

1. **Primary:** Android DTE/AT endpoint + authenticated relay session over Wi-Fi or cellular packet data.
2. **Secondary:** voice-band-data/audio transport over IP using RTP/UDP-style timing, with a gateway able to terminate into a hardware modem, analog interface, or software DSP.
3. **Experimental:** direct cellular voice-channel modem operation on privileged/custom Android builds or hardware where access to call audio is possible.

The core protocol will be transport-independent so later work can add V.150.1-style modem relay, V.152 voice-band data, QUIC migration, direct modem DSP, or remote hardware modem backends without replacing the DTE/AT layer.

## Repository map

- `docs/MASTER_RESEARCH_BRIEF.md` — authoritative research/specification mandate supplied for the project.
- `docs/research/SOURCE_INVENTORY.md` — controlled research database with confidence classification.
- `docs/research/INITIAL_FINDINGS.md` — early synthesis and feasibility conclusions.
- `docs/spec/BETA_0_1_ARCHITECTURE.md` — implementation architecture for the first beta.
- `docs/spec/PROTOCOL_AND_MODULE_CONTRACTS.md` — wire framing and subsystem boundaries.
- `docs/experiments/EXPERIMENT_PLAN.md` — experiments required before stronger interoperability claims.
- `docs/PROJECT_PLAN.md` — staged implementation and validation plan.

## Evidence policy

Important design decisions should prefer, in order: official standards, original vendor/chipset documentation, textbooks, peer-reviewed/academic material, mature open-source implementations, university material, experienced-practitioner documentation, and finally anecdotal reports. Lower-confidence findings are explicitly marked.

## Scope status

This repository currently contains the research/specification foundation. Coding should begin only for modules whose API contracts and validation criteria have been written down.
## Building and installing the Android app

> **Current state.** The Android app builds and runs, but it is an early
> developer build. It needs a reachable relay (the planned hosted SIP-trunk
> gateway) before it can place a real call, so a fresh install will open and
> show its screens but will not connect to anything yet. Treat a first install
> as confirming the app runs on your hardware, not as a working modem.
>
> Your phone must run **Android 10 (API 29) or newer**: the relay connection
> requires TLS 1.3, which older Android releases do not provide.

### 1. Get the code

Install [Git](https://git-scm.com/downloads) and
[Android Studio](https://developer.android.com/studio) (it bundles the Android
SDK and a compatible JDK). Then clone this branch:

```bash
git clone https://github.com/djshellshoxxx/androidialup.git
cd androidialup
git checkout claude/gifted-shannon-q2sjwr
```

### 2. Build the APK

Easiest, with Android Studio:

1. Open Android Studio, choose **Open**, and select the `android` folder
   inside the repo (not the repo root).
2. Let the Gradle sync finish (it downloads the Android build plugin the first
   time, so it needs internet).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. The result is `android/app/build/outputs/apk/debug/app-debug.apk`.

Command line, if you already have the Android SDK configured:

```bash
cd android
gradle :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install on the phone

Over USB with [adb](https://developer.android.com/tools/adb) (enable Developer
Options and USB debugging on the phone first):

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

Without a cable: copy `app-debug.apk` to the phone, tap it in a file manager,
and allow installing from this source when prompted.

### What the app does today

It opens to a dialer screen and a network screen (showing the selected
Wi-Fi/cellular network and policy). The dialer and the computer-facing TCP DTE
port work locally; dialing a remote modem requires the relay/gateway, which is
tracked as Phase I5 in `docs/PROJECT_PLAN.md`. Reaching a legacy modem is done
through a hosted SIP trunk over IP — see
`docs/spec/S1_GATEWAY_BACKEND.md` (section 4b) — not an analog line, and never
over a cellular voice call (see `docs/research/CELLULAR_VOICE_CODECS_R2.md`).

### Verifying the code

- Python reference prototype: `cd prototype/python && python3 -m unittest discover -s tests`
- Pure-Java modules (`core-network`, `core-protocol`, `core-modem`, `core-session`)
  and the Android app are built and tested by the **Android Core Tests** GitHub
  Actions workflow.

### Troubleshooting

**Build: "Minimum supported Gradle version is …" or a plugin/AGP version error.**
Use the bundled Gradle in Android Studio rather than a system Gradle. CI builds
with Gradle 9.6 and the Android Gradle plugin pinned in `android/build.gradle`;
a much older or newer command-line Gradle can mismatch. In Android Studio,
**File → Sync Project with Gradle Files**.

**Build: "Failed to find Android SDK" / "SDK location not found".**
Open the `android` folder in Android Studio once so it writes
`android/local.properties`, or set `ANDROID_HOME` to your SDK path. Install the
**Android 36** SDK platform and build-tools via the SDK Manager.

**Build: "Could not resolve com.android.application" or other downloads fail.**
The first build fetches the Android plugin and dependencies from the internet.
Check connectivity and any proxy; behind a firewall, configure Gradle's proxy
in `~/.gradle/gradle.properties`.

**Build: `JAVA_HOME`/Java version errors.**
The modules target Java 17. Android Studio's bundled JDK works; for the command
line, use a JDK 17+ and point `JAVA_HOME` at it.

**Install: `adb: command not found`.**
`adb` ships in the SDK platform-tools. Add `…/Android/sdk/platform-tools` to
your `PATH`, or run it by full path.

**Install: `adb` shows no device / "unauthorized".**
Enable **Developer Options** (tap Build Number seven times in Settings → About)
and **USB debugging**, replug the cable, and accept the "Allow USB debugging"
prompt on the phone. `adb devices` should list it as `device`, not
`unauthorized`.

**Install: `INSTALL_FAILED_OLDER_SDK` or the app won't open.**
The phone is older than Android 10 (API 29). That release is the minimum
because the relay needs TLS 1.3.

**Install: "App not installed" when tapping the APK.**
Allow installing from the file manager/browser in
**Settings → Apps → Special access → Install unknown apps**. If an older build
is present with a different signature, uninstall it first.

**Run: the app opens but dialing does nothing / "NO CARRIER".**
Expected for now. There is no relay to dial yet; see *What the app does today*
above. The network screen should still show your Wi-Fi/cellular selection.

**Run: relay connection fails on Android 10–12.**
Some core utilities were made safe for older Android, but if you hit a crash on
API 26–32, prefer Android 13+ for now and please open an issue with the logcat.
