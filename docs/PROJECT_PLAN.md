# Project Plan

## Phase R1 — research foundation

Deliverables:
- complete primary-standard inventory
- textbook/PDF inventory with chapter/page notes
- historical modem documentation inventory
- open-source archaeology and license matrix
- cellular voice evidence review
- V.150.1/V.152 deep dives
- initial reusable test-asset catalog

Exit criterion: architecture-changing questions have either an evidence-backed decision or a named experiment.

## Phase S1 — Beta 0.1 specification freeze

Deliverables:
- module/API contracts
- wire protocol
- threading/memory model
- AT/V.250 command matrix
- DTE transports
- Android NetworkSelector pseudocode and tests
- relay/gateway protocol
- PCM_VBD framing
- jitter/clock-recovery algorithms
- FSK physical-layer specification
- diagnostics schema
- security model

Exit criterion: separate coding agents can implement modules without inventing boundaries or packet/state formats.

## Phase I1 — transport/control prototype

Implement:
- Android shell app
- network selector
- relay authentication/session service
- TCP DTE
- AT parser/state machine
- BYTE_RELAY
- Linux loopback gateway
- metrics/logging

Exit criterion: `ATD` over Wi-Fi and cellular data creates a connection through relay and transports arbitrary binary data in both directions.

## Phase I2 — computer-facing modem endpoint

Implement preferred USB DTE transport and modem control signals where Android hardware/API permits. Add Bluetooth SPP only where platform/device support is suitable.

Exit criterion: a PC terminal application can use AndroidDialup as a modem-style endpoint without a custom Android debugging workflow.

## Phase I3 — timed media/VBD prototype

Implement:
- UDP/RTP-like media transport
- packet timestamps/sequence numbers
- jitter/reorder buffer
- sample clock correction
- network simulator
- 8 kHz PCM path
- Linux PCM gateway

Exit criterion: externally generated modem WAV/PCM survives defined network impairment profiles with measured results.

## Phase I4 — local softmodem foundation

Implement/port after license decision:
- NCO
- filters
- power/carrier detector
- FSK TX/RX
- async framing
- independent vector tests

Exit criterion: Bell-103/V.21-class target chosen from primary standard decodes externally generated vectors and interoperates with at least one independent implementation or hardware path.

## Phase I5 — remote hardware modem

Implement Linux serial modem backend and DTE/result-code mapping.

Exit criterion: Android DTE can dial a real remote modem through the relay/gateway architecture using packet data as the Android bearer.

## Phase R2/I6 — standards expansion

Research/implement progressively:
- V.8
- V.22/V.22bis
- V.32/V.32bis
- V.42/LAPM
- V.42bis
- V.34
- fax/T.30/T.38 as separate feature track
- V.150.1 modem relay
- V.152 compliance profile

Each standard gets its own conformance/interoperability matrix.

## Phase X1 — cellular voice research

Cellular voice failure must not block the packet-data product. X1 runs in parallel with, and
never gates, I1–I7.

Evidence and plan: `docs/research/CELLULAR_VOICE_CODECS_R2.md` (deep dive) and
`docs/research/CELLULAR_VOICE_EXPERIMENTS_R2.md` (experiment matrix S-01..S-08, E-01..E-16).
Constraints: S1_MEDIA_DSP_CONTRACT §13.1–§13.3 and §16. Rows: S1_TEST_PLAN §16.

Working model: the cellular voice call is a lossy, frame-structured byte-bearer candidate,
not an analogue line. The project uses modem relay at the phone. Mode hierarchy: packet data,
then codec-aware data-over-voice (CVDM), then IMS RTT signalling, then legacy waveform
passthrough (negative control only, never shipped).

Stages and gates:

1. **X1a — simulator** (may start once the I3 network simulator exists; needs no phone).
   Build the codec-in-the-loop chain (AMR-NB/AMR-WB via Apache-2.0 open implementations,
   GSM-FR and EVS reference code as test oracles pending licence review), DTX/VAD,
   frame-aligned loss, JBM, mode switching, tandem and terminal voice-processing models.
   Run S-01..S-03 and S-06.
   **Gate G-X1-0:** codec stages bit-exact against the 3GPP conformance sequences; DTX, loss
   and JBM reproduce the reference; CTM meets TS 26.231 in simulation.
2. **X1b — unprivileged live rig.** Bluetooth HFP hands-free host (BlueZ/oFono/PipeWire)
   plus far-end terminations. No privileged Android build is needed at this stage. Run E-01,
   E-02, E-03.
   **Gate G-X1-1:** repeatable codec-labelled calls; SCO stage quantified; CTM/TTY positive
   control passes on at least one path where supported.
3. **X1c — controls.** Run S-05, E-04, E-05 against their pre-registered failure
   predictions, plus E-13 (CSD survey).
   **Gate G-X1-2:** results published. No statement about legacy modes over cellular voice is
   made before this gate.
4. **X1d — CVDM go/no-go.** Run S-04 (calibrated by E-11), S-07, S-08, then E-06..E-09,
   E-11, E-14, and E-12 (RTT, in parallel).
   **Gate G-X1-3:** byte-exact CVDM in simulation for each codec family and live on at
   least one path, with independent-receiver agreement. Otherwise the track closes with a
   documented negative result.
5. **X1e — robustness and optional privileged cross-check.** Run E-15 and E-10, plus E-16
   if a privileged image exists.
   **Gate G-X1-4 (claim gate):** required before any document, UI or release note says a
   mode works over a cellular voice call.

Only after G-X1-3 may a later S-amendment define the `CELLULAR_VOICE_EXPERIMENTAL` wire mode.
The amendment must still keep it experimental and labelled (rule 6).

## Phase I7 — mobility

Prototype QUIC/application session migration between Wi-Fi and cellular. Preserve logical call identity across path changes.

## Engineering rules

1. Claims of standards compliance require primary-standard checklist plus interoperability evidence.
2. No DSP block is validated solely by round-tripping against itself.
3. Realtime audio callback performs no allocation, blocking I/O, file logging or network calls.
4. All queues are bounded and observable.
5. Session identity is independent of IP address.
6. Experimental modes are labeled experimental in protocol negotiation and diagnostics.
7. Third-party code enters the tree only after exact-version license review.