# Phase R1 Open-Source Implementation Findings

This document records source-level findings that affect design or testing. No third-party code should be imported into AndroidDialup until the exact version and license path are approved.

## 1. SpanDSP

Repository inspected: `https://github.com/freeswitch/spandsp`

Observed version banner: SpanDSP 3.1.1.

License findings from repository README/source:
- core library: GNU LGPL 2.1
- test suite and some support programs: GNU GPL 2
- individual source files must still be checked before reuse because test/support licensing differs from the library

Relevant source files found:
- `src/fsk.c` / `src/spandsp/fsk.h` / `src/spandsp/private/fsk.h`: generic FSK TX/RX primitives
- `src/v8.c` / `src/spandsp/private/v8.h`: V.8 negotiation using V.21 FSK plus ANSam/connect-tone logic
- `src/data_modems.c`: integrates AT interpreter, V.8, Bell 103, V.21, V.22bis, conditionally V.32bis and V.34, V.42 and V.42bis
- `src/modem_connect_tones.c`: modem/fax tone detection
- `src/t31.c`: T.31 fax-modem control path and V.21 receive integration
- `src/t38_gateway.c`: T.38 gateway implementation
- `src/v42*`: V.42 implementation
- `src/v42bis*`: V.42bis implementation
- `src/v22bis*`, `src/v32bis*`, `src/v34*`: higher-rate data modem implementations where enabled

Important architecture finding:
`src/data_modems.c` is not just a collection of isolated DSP functions. It already demonstrates a layered data-modem architecture combining V.8 negotiation, a DTE-facing AT interpreter, async framing, physical modem selections and V.42/V.42bis. This is highly valuable as a reference architecture for module boundaries and test expectations.

Reuse classification:
- FSK/DSP primitives: **REFERENCE FIRST; LINK/PORT POSSIBLE AFTER LGPL compliance plan**
- V.8 state machine: **REFERENCE FIRST**; compare directly against V.8 standard before reuse
- V.42/V.42bis: **REFERENCE FIRST; LINK MAY BE ATTRACTIVE** if Android NDK integration and license obligations are acceptable
- T.30/T.38: **LINK/PORT CANDIDATE FOR LATER FAX TRACK**
- SpanDSP test assets: **USE AS INDEPENDENT TEST ORACLE WHERE LICENSE ALLOWS**, but do not validate AndroidDialup only against SpanDSP if SpanDSP code is also used internally

## 2. minimodem

Repository: `https://github.com/kamalmostafa/minimodem`

License verified from `COPYING`: GNU GPL v3 or later.

Capabilities/role:
- software audio FSK modem
- Bell-style and RTTY modes
- useful independent command-line encoder/decoder for generating/decoding PCM/WAV fixtures

Reuse classification:
- embedded production library/code: **REFERENCE ONLY** unless the project deliberately adopts compatible GPL distribution terms
- executable test oracle in CI/lab environment: **STRONG CANDIDATE**
- waveform generation and interoperability comparison: **STRONG CANDIDATE**

Reason: the GPL license creates broader distribution obligations than we should accept accidentally, while the standalone executable remains very useful for black-box interoperability testing.

## 3. cryan209/v90modem

Repository discovered during Phase R1: `https://github.com/cryan209/v90modem`

This is unusually relevant because the repository root visibly contains:
- `.tmp_v32bis_startup.wav`
- `USR_V90_VEverything_44000k23600k.wav`
- `clock_recovery.c/.h`
- `call_init_tone_probe.c/.h`
- `data_interface.c/.h`
- an `ITU Docs/` directory
- many additional modem implementation artifacts

Current assessment:
- **potentially high-value test-asset and implementation-research source**
- **license not yet found/verified** in the initial inspection, therefore all code and waveforms are **REFERENCE ONLY / DO NOT COPY** until provenance and licensing are established
- recorded USRobotics V.90/V.Everything waveform is potentially valuable because externally captured real-modem audio is exactly the kind of non-self-generated regression input required by the project brief

Required follow-up:
1. establish author/project provenance and license
2. inspect WAV metadata and accompanying documentation to determine how recordings were made
3. verify whether test captures may legally be redistributed
4. compare algorithms and constants against primary ITU documents rather than treating the repo as authoritative
5. inspect commit history to determine maturity and whether the project is an original implementation, generated work, a fork, or assembled from other sources

## 4. Testing rule established by this archaeology

For every PHY implemented by AndroidDialup, maintain at least three test classes:

1. **normative vector tests** derived from public standard-defined sequences where redistribution is lawful
2. **independent software interoperability** using an implementation not sharing AndroidDialup code
3. **hardware/captured-path tests** using real modem recordings or live modem pairs

An encoder and decoder built from the same source tree passing each other is necessary but not sufficient evidence.

## 5. Initial open-source reuse plan

| Subsystem | Phase R1 classification | Reason |
|---|---|---|
| Android network selection | BUILD OUR OWN | Small, Android-specific; official APIs define behavior. |
| Relay/session control | BUILD OUR OWN | Project-specific identity/auth/handover requirements. |
| RTP | LINK EXISTING LIBRARY or small standards implementation | Mature infrastructure; avoid custom packet/media stack where a suitable Android/native library fits. |
| Jitter buffer | BUILD/ADAPT | Modem waveforms need deterministic observability and clock-drift handling unlike ordinary speech concealment. |
| V.152 state/negotiation | BUILD/ADAPT | Standards behavior is manageable but must integrate project diagnostics and relay modes. |
| SPRT/V.150.1 | BUILD/PORT after further archaeology | Niche protocol; mature reusable implementation not yet identified. |
| Bell 103/V.21 FSK | BUILD OUR OWN Beta primitive + independent SpanDSP/minimodem oracle | Small enough for auditable implementation; critical foundation for later state machines. |
| V.8 | BUILD OUR OWN or ADAPT after standard comparison | SpanDSP provides a strong reference; state machine must be traceable to the standard. |
| V.22/V.22bis | ADAPT/LINK candidate | More complex DSP; SpanDSP source exists. |
| V.32/V.32bis | LINK/PORT preferred if licensing/quality acceptable | Echo-cancelled QAM complexity makes clean-room rewrite expensive. |
| V.34 | LINK/PORT/REFERENCE preferred | High implementation complexity; do not reinvent unless existing code proves unsuitable. |
| V.90/V.92 | REMOTE/HARDWARE GATEWAY first | System-level PCM modem architecture and interoperability are much harder than lower V-series standards. |
| V.42 | LINK/PORT candidate | Mature protocol implementation exists in SpanDSP. |
| V.42bis | LINK/PORT candidate | Mature protocol implementation exists in SpanDSP. |
| T.30/T.38 | LINK SpanDSP-class implementation later | Mature fax stacks already exist and are outside Beta core. |

## Next archaeology targets

- Asterisk modem/fax passthrough media settings, jitter-buffer behavior and T.38 transitions
- FreeSWITCH modem/fax passthrough and SpanDSP integration
- Dire Wolf/soundmodem/fldigi timing recovery and test fixtures
- historical Linmodem/slmodem architecture and AT/DTE integration
- any public V.150.1/SPRT implementation
- open PPP implementation suitable for exercising transparent binary sessions
