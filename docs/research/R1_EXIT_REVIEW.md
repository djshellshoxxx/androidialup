# Phase R1 Exit Review

Date: 2026-10-02

## Exit criterion

Phase R1 is complete when every architecture-changing question has either:

1. an evidence-backed engineering decision, or
2. a named experiment that can resolve it without redesigning the Beta architecture.

## Architecture-changing questions

### 1. Can Wi-Fi and LTE/5G packet data use one Android architecture?

**Decision: YES.**

Use Android `ConnectivityManager`/`NetworkRequest`/`NetworkCallback` and bind relay sockets to the selected `Network`. Treat Wi-Fi and cellular packet service as equivalent IP bearers underneath a transport/session layer.

Beta modes remain `AUTOMATIC`, `WIFI_ONLY`, `CELLULAR_ONLY`, `PREFER_WIFI`, `PREFER_CELLULAR`.

### 2. Should cellular voice be the primary bearer?

**Decision: NO.**

Normal third-party Android apps cannot directly capture cellular call uplink/downlink audio because the relevant audio sources require privileged/system permission. Modern cellular speech codecs, VAD/DTX, packet-loss concealment, AEC, AGC and noise suppression also make transparent V-series waveform transport unreliable without device/carrier-specific evidence.

Cellular voice remains an isolated experimental privileged/custom-ROM track.

### 3. Should Beta transport raw modem audio or modem data?

**Decision: MULTI-MODE, IP-FIRST.**

Beta first proves control/data using private `BYTE_RELAY`. Timed PCM/VBD is a separate experimental mode. Standards-compatible V.152 and V.150.1 are later transport profiles behind the same session abstraction.

### 4. Is V.150.1 just a reliable byte tunnel?

**Decision: NO.**

V.150.1 is modem relay with explicit gateway procedures and SPRT transport behavior. The private Beta byte relay must not claim V.150.1 compliance.

### 5. Is V.152 ordinary VoIP?

**Decision: NO.**

V.152 VBD needs waveform-preserving media behavior: suitable codec, constant latency treatment, VAD/CNG disabled and avoidance of speech processing that damages modem waveforms. G.711 is the baseline interoperability codec.

### 6. What is the fastest real working Beta gateway?

**Decision: PURPOSE-BUILT LINUX RELAY + HARDWARE MODEM.**

A small Linux daemon controlling a real USB/RS-232 modem is the shortest path to end-to-end dial-up behavior while isolating Android networking from modem-DSP risk.

FreeSWITCH + SpanDSP is the strongest softmodem lab/reference backend. Asterisk + SpanDSP is a useful G.711/T.38/V.21/fax interoperability lab, not the primary generic data-modem DTE backend.

### 7. Should the project immediately implement V.34/V.90?

**Decision: NO.**

Build and validate the layering in increasing complexity:

```text
AT/DTE + relay
    -> V.21/Bell-103-class PHY
    -> V.8
    -> V.22/V.22bis
    -> V.42
    -> V.42bis
    -> reusable high-speed DSP foundation
    -> V.32/V.32bis
    -> V.34
    -> V.90 topology validation + implementation
    -> V.92
```

### 8. Can genuine V.90 be achieved with two ordinary analogue modems?

**Decision: NO.**

V.90 assumes a digital modem/network side and an analogue subscriber modem side. A digital V.90 server topology is a separate lab requirement.

Named experiment: `EXP-V90-TOPOLOGY-001`.

### 9. What DSP should be written locally versus reused?

**Decision:**

- V.21/Bell-103-class FSK: BUILD OUR OWN, independently validate.
- V.22bis: likely BUILD/ADAPT after direct comparison with SpanDSP.
- V.32bis/V.34: do not commit to rewrite until SpanDSP maturity/interoperability is measured.
- V.42/V.42bis: maintain clean replaceable interfaces; evaluate SpanDSP linkage/port versus independent implementation.
- RTP/SIP/QUIC/network crypto: use mature libraries/protocol implementations rather than custom cryptography or full protocol reimplementation.

### 10. How will DSP be validated independently?

**Decision: PROJECT-OWNED COMMERCIAL-HARDWARE CAPTURE CORPUS + EXTERNAL SOFTWARE ORACLES.**

Do not validate encoder/decoder solely against itself. Create project-owned captures from commercial modems and use independent software such as minimodem/SpanDSP where technically applicable. Third-party WAVs with unclear provenance are reference-only.

### 11. What should Android audio use?

**Decision: OBOE/AAUDIO-CLASS NATIVE AUDIO PATH.**

The realtime callback performs no allocation, blocking I/O, file logging or network calls. Device sample rate is decoupled from canonical modem DSP rates through bounded rate/clock adaptation.

### 12. Is seamless Wi-Fi/cellular handover required for Beta?

**Decision: NO.**

Session identity will be independent of IP address so QUIC migration/application-level reconnection can be added later without redesign. Beta only needs deterministic bearer selection and clean reconnect/failure behavior.

## Research coverage achieved

R1 now contains focused material for:

- standards matrix;
- Android networking/audio constraints;
- Wi-Fi vs cellular data vs cellular voice;
- V.150.1 and V.152;
- V.21 and V.22bis implementation constants;
- V.42/V.42bis layering;
- V.32bis startup/training;
- V.34 architecture;
- V.90 topology;
- historical/vendor documentation leads;
- textbooks/university material;
- SpanDSP/minimodem/open-source reuse analysis;
- FreeSWITCH and Asterisk gateway comparison;
- test-vector/capture provenance;
- staged high-speed modem roadmap.

## Remaining research that does not block S1

These items remain valuable but no longer change the Beta 0.1 architecture:

1. page-by-page extraction of additional Rockwell/Conexant/Lucent/Agere manuals;
2. acquisition of physical modem hardware and project-owned captures;
3. exact interoperability limits of SpanDSP V.32bis/V.34 implementations;
4. construction of a digital V.90 server lab;
5. device-specific privileged Android cellular-call-audio experiments;
6. cellular voice legacy-modem survivability measurements;
7. final licensing decision for the AndroidDialup project itself.

These continue as experiments/research tasks in later phases.

## R1 exit decision

**PASS.**

No unresolved research question requires changing the core Beta architecture. Phase S1 can now freeze module boundaries, APIs, wire formats, state machines, threading/memory rules, AT command behavior and concrete Beta 0.1 acceptance tests.

Research does not stop after R1; it becomes targeted validation attached to the relevant implementation milestone.
