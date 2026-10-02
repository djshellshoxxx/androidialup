# Experiment Plan

These experiments are required before stronger interoperability claims are made. Every run must capture software version/commit, device model, Android build, network type, codec/path information when known, timestamps, raw counters and representative audio/packet captures where legally/technically possible.

## EXP-001: Android network selection

Goal: verify deterministic selection and socket binding for Wi-Fi and cellular data.

Procedure:
1. Connect device to working Wi-Fi and cellular data simultaneously.
2. Enumerate candidates with `ConnectivityManager`.
3. Open one relay probe socket explicitly bound to Wi-Fi and another explicitly bound to cellular.
4. Record public source IP as observed by relay, RTT, loss and Android network handle.
5. Repeat policies `WIFI_ONLY`, `CELLULAR_ONLY`, `PREFER_WIFI`, `PREFER_CELLULAR`, `AUTOMATIC`.
6. Disable/re-enable each bearer and confirm callbacks/state transitions.

Pass: policy always selects only eligible bearer; packet source observed by relay matches intended bearer; no process-global binding is required.

## EXP-002: CGNAT relay viability

Goal: validate client-initiated relay sessions on real cellular networks.

Procedure:
1. Establish outbound authenticated control session over cellular.
2. Establish media/data channel.
3. Hold for 60 minutes with keepalives.
4. Force idle periods of 30 s, 60 s, 120 s, 5 min.
5. Record NAT mapping changes, reconnects and keepalive requirements.

Pass: session can be established without inbound port forwarding and can recover cleanly from mapping loss.

## EXP-003: Wi-Fi/cellular path characterization

For each bearer record over at least 30 minutes:
- RTT distribution
- one-way jitter where clocks permit
- packet loss
- burst-loss distribution
- reordering
- uplink/downlink throughput headroom
- path changes

Use results to parameterize `NetworkSimulator` profiles.

## EXP-004: PCM packetization/jitter-buffer sweep

Packetization: 10, 20, 30, 40 ms.

Network conditions:
- jitter: 0–100 ms distributions
- random loss: 0–10%
- burst loss using Gilbert-Elliott model
- reordering: 0–5 packets
- clock mismatch: ±200 ppm initially

Metrics:
- audio discontinuities
- late packet rate
- buffer delay
- FSK bit-error rate using independent vectors

Select Beta defaults from measured BER, not voice-quality metrics.

## EXP-005: Clock drift / ASRC control

Feed a continuous known modem waveform through sender/receiver clocks offset by known ppm values. Sweep ±10, ±25, ±50, ±100, ±200 ppm. Confirm buffer occupancy remains bounded and demodulator BER does not materially worsen from correction modulation.

## EXP-006: External Bell/V.21 vectors

Generate receive vectors with at least two independent implementations/hardware sources where possible.

Candidate sources:
- minimodem for Bell-class FSK
- SpanDSP-generated V.21
- captured hardware modem audio

Tests:
- clean AWGN-free
- amplitude sweep
- frequency offset
- sample-rate error
- band limiting
- additive noise
- phase discontinuity
- packetized PCM impairment

No encoder/decoder pair is considered validated using only its own generated vectors.

## EXP-007: Hardware modem gateway

Linux gateway + USB/serial hardware modem.

Validate:
- dial command forwarding
- DCD/DSR/CTS semantics
- connect result propagation
- transparent data
- hangup/escape
- remote busy/no-answer mapping

This is the quickest route to proving Android can behave like a modern network-attached DTE modem while a physical modem terminates the PSTN side.

## EXP-008: VBD through controlled SIP/G.711 path

Build a lab path with speech processing disabled where possible:

`modem/DSP -> analog or PCM edge -> SIP/G.711 -> gateway -> modem/DSP`

Record:
- codec exactly used
- ptime
- jitter buffer
- PLC behavior
- echo cancellation
- silence suppression/VAD
- transcoding

Test low-rate modem families before faster modes.

## EXP-009: Cellular voice privilege feasibility

Device classes:
1. stock non-rooted Android
2. rooted Android
3. AOSP/custom system image with privileged app
4. device with external audio coupling if available

For each determine:
- can uplink audio be captured?
- can downlink audio be captured?
- can synthesized audio be injected to uplink?
- actual sample format/rate
- processing that cannot be disabled
- repeatability across calls

Stock Android is expected to fail privileged call-audio access; document observed behavior rather than trying to bypass the platform architecture through unsupported assumptions.

## EXP-010: Legacy modem over cellular voice

Only after EXP-009 supplies a valid audio path.

Modes to test independently:
- Bell 103
- V.21
- V.22
- V.22bis
- V.32
- V.32bis
- V.34

For each record:
- handshake completion rate
- negotiated speed
- retrains
- BER/BLER where measurable
- carrier/network/codec
- AMR/EVS mode where observable

Failure is an acceptable result. Do not extrapolate one carrier/device result to all cellular voice networks.

## EXP-011: Codec-aware cellular voice modem

Separate from V-series compatibility. Reproduce or benchmark against published speech-like/chirp-based data-over-voice approaches in a simulator first, then on real call paths if privileged audio access exists.

Output must be labeled as a project-specific data-over-voice mode, never as V.21/V.34 compatibility.

## EXP-012: Handover prototype

Post-Beta:
- establish logical session on Wi-Fi
- switch to cellular
- compare application-level reconnect, custom UDP session rebinding and QUIC migration
- measure interruption and state preservation

The modem/DTE session must not depend on source IP identity.