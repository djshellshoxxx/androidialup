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

Only after privileged/device path exists:
- characterize call audio API/routing
- identify AMR/EVS/transcoding behavior
- run legacy modem survivability matrix
- prototype codec-aware project-specific data-over-voice mode if worthwhile

Cellular voice failure must not block the packet-data product.

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