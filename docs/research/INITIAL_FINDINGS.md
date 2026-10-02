# Initial Research Findings

Status: research tranche 1. These are architecture-shaping findings, not the final literature review.

## 1. The project should not be designed as a single waveform path

The evidence already separates the problem into three materially different channels:

1. **IP packet transport over Wi-Fi.** Android can explicitly request, inspect and bind sockets to networks. This is a normal supported application capability.
2. **IP packet transport over LTE/5G data.** From the application layer this can use the same session protocol as Wi-Fi, with differences handled by the network-selection and path-quality layers.
3. **Cellular voice.** This is not simply another IP bearer available to a normal Android application. Direct call uplink/downlink access requires privileged/system capabilities, and the underlying carrier path may contain speech codecs, VAD/DTX, PLC, echo control, AGC and transcoding that are hostile to legacy modem waveforms.

Therefore Beta 0.1 should use a transport-independent modem/session core and treat direct cellular voice as a separate experimental backend.

## 2. Primary Beta transport: Wi-Fi and cellular packet data

Android's `ConnectivityManager`, `NetworkRequest`, `NetworkCapabilities`, `NetworkCallback` and `Network` APIs are sufficient to implement the required `AUTOMATIC`, `WIFI_ONLY`, `CELLULAR_ONLY`, `PREFER_WIFI` and `PREFER_CELLULAR` policies without root access.

Implementation rule: bind individual session sockets to the selected `Network` instead of globally binding the Android process unless a specific subsystem requires process-wide binding. This avoids unrelated traffic accidentally following the modem session's bearer.

Both Wi-Fi and cellular packet data should feed the same `RelayTransport` interface. No modem-state code should contain checks for `wifi` versus `cellular`; those choices belong below the session layer.

## 3. CGNAT makes client-initiated relay infrastructure the sane default

Mobile networks frequently place clients behind NAT/CGNAT. STUN can characterize mappings but is not itself a traversal solution. ICE can attempt direct connectivity, while TURN provides a relay when a direct path cannot be established.

Beta 0.1 does not need full ICE/TURN if both Android and gateway initiate outbound connections to a project relay. A project relay with authenticated session IDs is the fastest deterministic architecture and naturally works through CGNAT. The protocol should nevertheless keep endpoint identity separate from IP addresses so ICE/TURN or direct peer paths can be added later.

## 4. QUIC is attractive for later handover, not a Beta requirement

RFC 9000 explicitly supports connection migration after handshake through connection IDs and path validation. That maps well to a phone moving between Wi-Fi and cellular networks.

Beta 0.1 should first prove correctness using a simpler control connection plus UDP/RTP-like media path or a custom reliable framed stream. The application session ID must survive underlying transport reconnection so a later QUIC transport can be substituted without redesigning the modem state machine.

## 5. V.150.1 and V.152 solve different problems

### V.150.1 direction

V.150.1 is modem relay: gateways identify/modulate/demodulate modem signals and exchange modem information/data across IP rather than continuously shipping a fragile analog waveform. This is conceptually the best long-term solution when both ends are AndroidDialup-aware or terminate in controllable gateways.

Benefits:

- avoids transporting every PCM sample across jittery networks;
- avoids many impairments caused by packet loss and sample-clock drift;
- permits legacy analog/V-series signaling to exist only at gateway edges;
- maps naturally to a remote hardware modem or software DSP gateway.

Cost: full standards compliance is substantial, and mature reusable V.150.1 implementations are not yet identified in this research tranche.

### V.152 direction

V.152 retains voice-band data through an IP voice network. This is the appropriate reference when AndroidDialup deliberately transports modem-like audio/PCM across a packet network. It is relevant to G.711/RTP, jitter handling, data-signal detection, echo-control handling and disabling speech-oriented processing.

For Beta, implement a **VBD-inspired PCM mode** first, not claim V.152 compliance until the standard's required procedures are fully implemented and interoperability tested.

## 6. Cellular voice cannot be a normal third-party Android feature

Android documents that `VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK` capture require `CAPTURE_AUDIO_OUTPUT`, a permission reserved for system components. Consequently, an ordinary Play-distributed app cannot depend on direct access to the cellular call audio stream.

Viable research paths are:

- a system/privileged app on a custom AOSP image;
- rooted/device-specific audio routing where technically possible;
- vendor/modem-specific interfaces;
- external analog/USB audio hardware physically coupled to the voice path;
- abandon legacy modem waveform compatibility and use a codec-aware acoustic/data-over-voice scheme through microphone/speaker or privileged call routing.

The project should support this work behind a `CellularVoiceBackend` interface but should not make it part of the minimum Beta success criterion.

## 7. Legacy modem signals and cellular speech-vocoder data are separate modem families

Published GSM/AMR data-over-voice work generally designs waveforms specifically to survive speech codecs, often using speech-like symbols or other codec-aware signaling. Modern work similarly uses purpose-built framed modulation, FEC and retransmission.

This evidence supports two explicit experimental goals:

- **Legacy compatibility experiment:** determine which low-rate V-series/Bell modes survive particular cellular voice paths, without assuming success.
- **Codec-aware cellular voice modem:** if desired, build a separate low-rate data mode optimized for AMR/EVS-style speech channels. This mode would not be a V.21/V.22/V.34 modem and must be labeled separately.

## 8. Android audio engine choice

For local software modem DSP, USB/analog coupling, test generation and loopback, use native C++ with Oboe. Google recommends Oboe/AAudio for new low-latency audio work and recommends callback-based, low-latency operation at the device's native sample rate, commonly 48 kHz.

Internal DSP should use 48 kHz floating-point PCM for Beta unless a module has a strong reason to operate at a lower canonical rate. Standards-specific DSP may decimate/interpolate internally, but only one sample-rate conversion stage should be present at each boundary where practical.

The realtime callback must not allocate memory, block on locks, perform network I/O, write files, or execute the AT parser. It moves samples through lock-free/preallocated buffers only.

## 9. Open-source reuse direction

### minimodem

Use as **REFERENCE ONLY / external test oracle** initially. Its Bell/FSK behavior and WAV/live-audio support are valuable, but its GPL-3.0-or-later license would propagate obligations if incorporated directly into a distributed combined work. It is well suited to generating and decoding independent FSK regression vectors during development.

### SpanDSP

Use as **candidate LINK/PORT/REFERENCE**, pending authoritative license/provenance review of the exact upstream version. The codebase contains many directly relevant telephony DSP elements including V.21 FSK, fax tones, HDLC, T.30 and faster fax modems. Its architecture can save years if licensing and Android portability are acceptable.

### SoftModem

Use as **REFERENCE / test-fixture inspiration**. Its BSD licensing is friendly and its Bell-202-like audio-jack design is useful for simple physical validation, but it is not a PSTN dial-up stack.

## 10. Beta 0.1 success definition should be narrower than "56K modem"

A 56K V.90/V.92 implementation is not an appropriate first milestone. V.90/V.92 depend on asymmetric PCM modem architecture and network conditions that are different from merely generating an audio waveform. A robust project should prove the control, transport, DTE, channel simulation and low-rate modem pipeline first.

Proposed Beta 0.1 success criteria:

1. Android exposes a functional AT-command modem endpoint over TCP and at least one local computer-facing transport (USB serial is preferred).
2. `ATD` establishes an authenticated logical session over selected Wi-Fi or cellular packet data.
3. Session survives ordinary NAT/CGNAT through a relay service.
4. Transparent byte mode works end-to-end through the relay to a gateway/remote endpoint.
5. PCM/VBD mode can transport a low-rate externally generated modem waveform between Android and gateway under controlled packet impairment.
6. Bell 103 or V.21-class FSK can be generated and decoded locally using independent external vectors.
7. The system reports measured latency, jitter, packet loss, buffer depth, audio clock error and carrier/DSP status.
8. No claim of V.34/V.90/V.92 interoperability is made until physical hardware tests pass.

## 11. Recommended architecture decision

Choose **E. MULTI-MODE** with a strict layering model:

`DTE <-> AT/Modem State <-> Logical Session <-> Mode Adapter <-> Transport <-> Gateway Backend`

Mode adapters:

- `BYTE_RELAY` — clean digital byte stream, fastest path to a functional modem-like product.
- `MODEM_RELAY` — future V.150.1-aligned relay.
- `PCM_VBD` — timed voice-band PCM transport for waveform/DSP experiments.
- `REMOTE_HARDWARE_MODEM` — gateway drives a real USB/serial modem.
- `LOCAL_SOFTMODEM` — Android generates/decodes modem audio through Oboe/physical audio I/O.
- `CELLULAR_VOICE_EXPERIMENT` — privileged/custom-hardware track only.

This allows useful functionality early without preventing standards-compliant modem work later.