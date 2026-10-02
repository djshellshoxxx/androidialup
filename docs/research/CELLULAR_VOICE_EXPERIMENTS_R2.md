# Phase X1 Experiment Matrix: Data Over Cellular Voice

Status: Phase X1 plan. The deep dive is `CELLULAR_VOICE_CODECS_R2.md`, cited below as "DD §n". The
mechanism codes M1–M14 and design rules D1–D13 are defined in DD §4. Sources are `CEL-nnn`
(and earlier `PAP-/STD-/AND-/OSS-`) rows in `SOURCE_INVENTORY.md`. This plan does not change
the Beta 0.1 decisions. Packet data is the primary bearer, cellular voice stays
experimental, and no outcome here can block the packet-data product.

No interoperability statement about any waveform or mode over a cellular voice call
(GSM, UMTS, VoLTE, VoNR, VoWiFi) may be made until the experiments and gates below have been
run and reported.

---

## 0. Rules for this matrix

1. **Externally generated vectors.** A stimulus or reference receiver comes from an
   implementation independent of the AndroidDialup DSP wherever one exists (PROJECT_PLAN
   rule 2; `TEST_VECTOR_PROVENANCE_R1.md` classes A–D). Where the project's own waveform is
   under test (CVDM), the channel (reference codec or live network) and the bit-error
   reference (ITU-T O.150 PRBS, CEL-084) are external. A second receiver, written only from
   the CVDM specification and sharing no code with the first, must agree with it (§1.4).
2. **Numbers.** Every factual number carries a source ID. Numbers tagged **[P]** are
   *project-chosen test parameters or gate thresholds*. They are not sourced claims. They are
   provisional, must be ratified at X1 kickoff, and may be changed only by a documented
   amendment of this file. Figures the DD obtained "via excerpt" (DD access note) must be
   re-verified against the downloaded specification before they become a pass/fail threshold.
3. **Pre-registration.** Each experiment states its prediction (from DD §1.3, §3.3) *before*
   it is run. Results that contradict a prediction are reported as such. They are never
   silently re-interpreted.
4. **Labelling.** Every live measurement records the access path, the negotiated speech codec
   and mode (or `UNKNOWN`), the audio entry point, the device model/firmware, the operator
   and RAT, the date, and the far-end topology. A result without a codec label can count only
   as "path-level" evidence, never as codec-level evidence (DD §6, last row; CEL-042).
5. **Reproducibility.** Simulation runs record the PRNG seed, the codec implementation and
   version hash, and the full frame-type and mode log (S1_MEDIA_DSP_CONTRACT §13.1). Live
   runs keep both raw recordings (injected and received), SHA-256 hashes, and rig
   configuration.
6. **Non-blocking.** X1 has its own gates (§4). None of them is an input to I1–I5 exit
   criteria.

---

## 1. Equipment, rigs and common assets

### 1.1 Rigs

| Rig | Description | Privilege on phone | Source basis | Used by |
|---|---|---|---|---|
| R-SIM | Offline codec-in-the-loop simulator per S1_MEDIA_DSP_CONTRACT §13.1–§13.3: reference/bit-exact codecs, DTX/VAD, frame-aligned loss, JBM, mode switching, tandem, terminal voice-processing models | n/a | CEL-055, CEL-056, CEL-057/058, CEL-059, CEL-081/082/083, CEL-086 | S-01..S-08 |
| R-HFP | Linux host acting as Bluetooth HFP hands-free unit (BlueZ + oFono + PipeWire/BlueALSA). It places and answers calls, injects audio into the uplink and records the downlink. SCO link is CVSD (8 kHz) or mSBC (16 kHz) | None (standard pairing) | CEL-046, CEL-047 | E-01..E-09, E-11, E-14, E-15 |
| R-ACC | Wired headset / USB-C audio accessory interface driven by a PC audio interface | None | DD §6 | E-03 (TTY accessory), E-10 |
| R-PRIV | Custom system image with a privileged system app holding `CAPTURE_AUDIO_OUTPUT` (capture only; there is no uplink injection API even with privilege) | System/privileged | AND-004, AND-005, CEL-044, CEL-045 | E-16 |
| R-FAR | Far-end terminations: (a) second phone on R-HFP; (b) landline reached by a mobile-to-PSTN call, terminated by a Linux host with analogue/ISDN capture and an independent hardware modem; (c) SIP/PSTN gateway leg recorded as G.711 | n/a | CEL-015, CEL-016, CEL-063 | all live |
| R-INCALL | AndroidDialup built as default dialer / `InCallService` for RTT and call-metadata capture | Default-dialer role | CEL-039, CEL-043 | E-12, codec labelling |

### 1.2 Test vectors (all external unless stated)

| Vector set | Content | Generator / source | Provenance class |
|---|---|---|---|
| V-CODEC | 3GPP codec conformance sequences: AMR-NB (incl. VAD/DTX/CN), AMR-WB (incl. VAD/DTX/CN), EVS (incl. DTX, PLC, JBM, AMR-WB IO) | TS 26.074 (CEL-081), TS 26.174 / G.722.2 (CEL-082), TS 26.444 (CEL-083) | B (redistribution per 3GPP/ITU terms; store fetch instructions + hashes if unclear) |
| V-SPEECH | Speech-like and technical test signals: composite source signal, artificial voice, PN sequence and multisine for TCL measurement | ITU-T P.501 test signal database (CEL-085) | B |
| V-PRBS | Payload bit streams PRBS 2^9−1 and 2^15−1 | ITU-T O.150 (CEL-084); generator written from the recommendation's polynomial, cross-checked against an existing BER tester or published sequence | B / derived |
| V-FSK | Bell 103, V.21, V.23, Bell 202 transmit audio carrying V-PRBS | minimodem (OSS-002), SpanDSP (OSS-001) | C |
| V-QAM | V.22, V.22bis, V.27ter, V.29, V.17 audio; V.32bis/V.34 handshake and data | SpanDSP test programs (OSS-001); hardware modem captures for V.32bis/V.34 | C / A |
| V-CTM | CTM-encoded Baudot text and plain Baudot 45.45 bit/s TTY audio | 3GPP TS 26.230 reference code (CEL-012), build CEL-054; V.18 Baudot definition CEL-035 | B / C |
| V-ERR | Frame-erasure and delay/error profiles | ITU-T G.191 STL error-insertion and frame-erasure tools (CEL-086); 3GPP JBM delay/error profiles shipped with TS 26.444 (CEL-083) | B |
| V-CVDM | Project CVDM waveform carrying V-PRBS | Project code (not external). Validated per §1.4 | project |

### 1.3 Standard measurements

| Code | Measurement | Definition |
|---|---|---|
| m-BER | Raw bit error ratio | Against the known V-PRBS sequence after the external reference receiver (V-FSK/V-QAM) or both CVDM receivers |
| m-SER | Symbol error ratio | CVDM only, pre-FEC |
| m-RES | Residual error after FEC/ARQ | Byte compare of delivered payload vs sent payload |
| m-GP | Goodput | Delivered payload bits / wall time, post-ARQ |
| m-HS | Handshake outcome | Last handshake phase reached (answer tone, V.8, training, CONNECT, data) for V-QAM and hardware modems |
| m-FT | Frame-type log | SPEECH / SID_FIRST / SID_UPDATE / NO_DATA per 20 ms frame (simulation: from encoder; live: inferred, see m-DTX) |
| m-DTX | DTX events | Simulation: count of SID frames during payload. Live: count of comfort-noise intervals detected as loss of the CVDM pilot with noise-like residual |
| m-ERS | Erasure pattern | Simulation: from V-ERR. Live: estimated from CVDM frame sequence numbers |
| m-ID | Insertions/deletions | Symbol slips detected by burst sync words (M8) |
| m-H | Path response | Per-tone gain and group delay from P.501 multisine and CVDM training preamble |
| m-LAT | Latency | One-way (sync-stamped recordings) and ARQ round-trip |
| m-CODEC | Codec label | Negotiated codec/mode/bandwidth from the best available source: Android call metadata, carrier config (CEL-042), operator logs, or band-limit fingerprint (energy above 3.4 kHz implies WB; above 7 kHz implies SWB/FB; CEL-006/007) |

### 1.4 Independence rule for CVDM

The CVDM transmitter and receiver are project code, so they cannot be validated against
themselves. X1 accepts a CVDM result only when all of these hold:

1. the channel is a 3GPP reference/bit-exact codec (S-stage) or a live network (E-stage);
2. errors are counted against V-PRBS (O.150, CEL-084), not against the transmitter's own
   buffer;
3. a second receiver, implemented from the written CVDM spec by a different author or agent
   with no shared code, decodes the same recordings and its m-BER agrees with the primary
   receiver's within the statistical confidence of the run ([P] 95 % binomial interval).

---

## 2. Experiment summary

| ID | Stage | Purpose | Path / codec | Prediction (pre-registered) | Motivated by |
|---|---|---|---|---|---|
| S-01 | Sim | Codec oracle conformance | GSM-FR, AMR-NB, AMR-WB, EVS | Bit-exact for reference code; open encoders may not be | CEL-055..059, CEL-081..083 |
| S-02 | Sim | DTX/VAD model validation | AMR-NB, AMR-WB, EVS with DTX | Reference timing reproduced | M6; CEL-003/004/073/074 |
| S-03 | Sim | Frame-aligned loss, PLC, JBM | AMR-NB, AMR-WB, EVS | Loss output is speech-like, not zero | M7, M8; CEL-005/008/009/010 |
| S-04 | Sim | Terminal voice-processing models | AEC-NLP, NS, AGC | Parametric only until E-11 calibrates | M11, M12; CEL-023/037/038 |
| S-05 | Sim | Legacy modem negative control | All codecs, modes below | Failure for FSK ≥ 300 bd and all QAM (DD §1.3) | DD §1.3, §3.3; CEL-011, CEL-034, CEL-063 |
| S-06 | Sim | CTM positive control (simulator calibration) | GSM-FR, AMR-NB (all modes), AMR-WB | CTM meets TS 26.231 | CEL-011..013 |
| S-07 | Sim | CVDM design sweep | All codecs + impairments | NB 1–4 kbit/s class reachable; WB/EVS unknown | DD §3.2, D1–D13; CEL-048, PAP-001 |
| S-08 | Sim | Tandem chains | AMR-WB→G.711→AMR-NB, EVS→AMR-WB IO, AMR-NB→G.711→AMR-NB | CTM survives; CVDM degrades | M10; CEL-015/016/063 |
| E-01 | Live | Rig baseline and codec labelling | each available path | Repeatable; WB/NB distinguishable | CEL-046/047; DD §6 |
| E-02 | Live | SCO link stage isolation | CVSD vs mSBC | SCO adds a measurable stage | CEL-047 |
| E-03 | Live | CTM/TTY positive control | GSM/UMTS CS (CTM), VoLTE TTY/RTT | Text delivered | CEL-011, CEL-040/041 |
| E-04 | Live | FSK negative control | all paths | Fails (Bell 103/V.21 possibly marginal on some NB paths) | DD §3.3; CEL-034 |
| E-05 | Live | QAM / fax negative control | all paths | Fails at handshake or data | DD §3.3; CEL-062..064 |
| E-06 | Live | CVDM over GSM/UMTS CS | FR/EFR/AMR-NB | 1–4 kbit/s class | CEL-048, CEL-050, PAP-001 |
| E-07 | Live | CVDM over VoLTE AMR-WB | AMR-WB set 0 | Unknown | DD §3.2 gap; CEL-033 |
| E-08 | Live | CVDM over VoWiFi | as VoLTE | ≤ E-07 owing to jitter | CEL-032 |
| E-09 | Live | CVDM over EVS (VoLTE-EVS, VoNR) | EVS 9.6–24.4, channel-aware if offered | Unknown | DD §5.3; CEL-007, CEL-031, CEL-061 |
| E-10 | Live | Wired / USB-C accessory entry point | subset of E-01, E-07 | Different uplink processing than HFP | DD §6 |
| E-11 | Live | Terminal AEC/NS/AGC characterisation | every rig × path | Full-duplex same-band suppressed | M11, M12; CEL-023 |
| E-12 | Live | IMS RTT as signalling channel | VoLTE/VoNR with RTT | Byte-exact, low rate | CEL-010, CEL-027, CEL-039, CEL-043 |
| E-13 | Survey | CSD / IWF availability | GSM/UMTS operators | Largely unavailable | CEL-017, CEL-068 |
| E-14 | Live | Call-topology / tandem matrix | same-op, cross-op, mobile→PSTN, mobile→SIP | Response changes per topology | M10; CEL-015/016 |
| E-15 | Live | Duration, handover, rate-switch robustness | best E-06..E-09 path | Outages occur; link layer recovers | M9; D12; CEL-014, CEL-025 |
| E-16 | Live | Privileged downlink capture cross-check | R-PRIV | Separates SCO stage from network | AND-004/005, CEL-044 |

---

## 3. Experiment definitions

### Stage S: simulation (no phone needed; may start once the I3 network simulator exists)

#### S-01 Codec oracle conformance
- **Hypothesis:** the simulator's codec stages are the standardised codecs, so simulated
  results describe the real codec and not an approximation.
- **Equipment:** R-SIM; implementations opencore-amr (AMR-NB enc/dec, AMR-WB dec; CEL-055),
  vo-amrwbenc (CEL-056), libgsm and SpanDSP gsm0610 (CEL-057/058, reference use only pending
  licence decision), and 3GPP EVS reference code TS 26.442/26.443 (CEL-059, test oracle only).
  Also the 3GPP AMR-NB/AMR-WB reference C code where an open implementation is not bit-exact.
- **Voice path / mode:** each codec at every mode it defines: AMR-NB 4.75–12.2 (8 modes),
  AMR-WB 6.6–23.85 (9 modes), EVS 5.9–128 kbit/s and AMR-WB IO (CEL-002, CEL-006, CEL-007).
- **Vectors:** V-CODEC (CEL-081, CEL-082, CEL-083); GSM-FR ETSI test sequences as referenced by
  SpanDSP (CEL-057).
- **Measurements:** bitstream and decoded-PCM comparison against the reference outputs.
- **Pass:** bit-exact for every mode used in later S-experiments. An implementation that is
  not bit-exact (vo-amrwbenc is a candidate) is replaced by the reference code for X1
  purposes and recorded as "non-reference".
- **Motivation:** PROJECT_PLAN rule 2; DD §5.4 item 3.

#### S-02 DTX/VAD model validation
- **Hypothesis:** the simulator reproduces the standard DTX timing: AMR sends 7 hangover
  SPEECH frames after VAD=0, then SID_FIRST, then a SID update every 8 frames (CEL-003), and
  EVS sends SID at a fixed interval of 3–100 frames or adaptively (CEL-073). Any faster
  parametric DTX model used for sweeps matches the reference encoder decisions.
- **Equipment:** R-SIM with reference encoders, DTX enabled; VAD1 and VAD2 for AMR-NB
  (CEL-004); AMR-WB VAD (CEL-074).
- **Vectors:** V-CODEC VAD/DTX sequences; V-SPEECH; V-CTM; V-FSK; V-QAM; candidate CVDM
  waveforms.
- **Measurements:** m-FT per input; frames from signal onset to first SID; count of SID
  frames during continuous data signals.
- **Pass:** (a) the V-CODEC DTX sequences reproduce exactly; (b) the parametric model agrees
  with the reference encoder frame-type log frame-for-frame on V-SPEECH, V-CTM and V-CVDM. If
  it does not, sweeps use the reference encoder only.
- **Recorded outcome (not pass/fail):** which data waveforms trigger DTX (M6) under each VAD
  option. This is the first evidence for D4.
- **Motivation:** M6; DD §2 row 1; DD §7 Q4.

#### S-03 Frame-aligned loss, PLC and JBM
- **Hypothesis:** cellular losses are whole-frame erasures concealed by codec PLC (CEL-005,
  CEL-008) and, on IMS paths, packet losses of 1–4 frames with JBM time-scaling (CEL-009,
  CEL-010). A sample-level PCM zero-fill model (the PCM_VBD §6 policy) does not represent
  them.
- **Equipment:** R-SIM frame-loss and JBM stages (S1_MEDIA_DSP_CONTRACT §13.2, §13.3); EVS
  reference JBM (TS 26.448).
- **Vectors:** V-ERR (G.191 STL erasure patterns, CEL-086; TS 26.444 delay/error profiles,
  CEL-083); V-CODEC PLC sequences.
- **Measurements:** decoded PCM vs reference decoder fed the same erasure pattern; m-ID
  under JBM; spectral similarity of concealed segments to adjacent frames.
- **Pass:** bit-exact decoder output vs reference for the same erasure pattern; erasure
  boundaries fall on 20 ms frame boundaries (CEL-002); JBM output matches the TS 26.444 JBM
  conformance outputs.
- **Motivation:** M7, M8; DD §2 rows 5–6.

#### S-04 Terminal voice-processing impairment models
- **Hypothesis:** the uplink AEC non-linear processor suppresses uplink energy that is
  correlated with the downlink (handset TCLw ≥ 46 dB, CEL-023). Noise suppression attenuates
  stationary non-speech spectra, and AGC rescales level (M11, M12). These are vendor
  proprietary, so only parametric models are possible.
- **Equipment:** R-SIM `TerminalVoiceProcessingModel` (S1_MEDIA_DSP_CONTRACT §13.1).
- **Vectors:** V-SPEECH (P.501 TCL signals, CEL-085); V-FSK; V-CVDM; double-talk mixtures.
- **Measurements:** model attenuation vs time for stationary tones; uplink suppression vs
  downlink activity.
- **Pass (S-stage):** models are deterministic, seeded and documented. **Use gate:** no
  S-07/S-08 conclusion may rely on S-04 parameters until they are fitted to E-11 measurements
  for the same rig and device class. Before that, S-07 runs with S-04 bypassed and with S-04
  at "worst plausible" settings, and reports both.
- **Motivation:** M11, M12; DD §7 Q5.

#### S-05 Legacy modem negative control (simulation)
- **Hypothesis (pre-registered failure):** FSK at 300 bd and above, and every DPSK/QAM/TCM
  mode, fails through the speech codecs (DD §1.3). Baudot 45.45 bd FSK is unreliable
  (CEL-011, CEL-034).
- **Equipment:** R-SIM; external receivers only (minimodem RX, SpanDSP RX, CTM reference for
  Baudot).
- **Codec modes:** GSM-FR; AMR-NB 12.2 (functional EFR proxy, CEL-069), 7.4, 4.75; AMR-WB 6.6,
  12.65, 23.85; EVS 9.6, 13.2, 24.4 (WB and SWB). Each runs DTX off and DTX on, with no loss
  and with V-ERR profiles at [P] 1 % and 3 % frame erasure.
- **Vectors:** V-FSK (Bell 103, V.21, V.23, Bell 202), V-QAM (V.22, V.22bis, V.27ter, V.29,
  V.17), Baudot from V-CTM.
- **Measurements:** m-BER, carrier-detect continuity, m-HS for interactive modes (SpanDSP
  modem pair through a bidirectional R-SIM chain), m-FT.
- **Pass (experiment complete):** all cells executed and reported. A cell is labelled
  SURVIVES only if m-BER ≤ [P] 1×10⁻³ over ≥ [P] 10⁵ bits with no loss of carrier. A
  SURVIVES cell is a hypothesis for E-04/E-05 only, never a claim.
- **Motivation:** DD §1.3 "expect failure" rows, §3.3; MASTER_RESEARCH_BRIEF "Do not claim a
  mode works without evidence."

#### S-06 CTM positive control (simulator calibration)
- **Hypothesis:** CTM (4-FSK, 200 bd, 200 Hz spacing, r=1/4 FEC with interleaving; CEL-053,
  STRONG SECONDARY, to be re-verified against TS 26.226) was designed for the GSM speech
  codecs and meets TS 26.231 minimum performance (CEL-013) through them.
- **Equipment:** R-SIM; TS 26.230 reference CTM TX/RX (CEL-012/054).
- **Codec modes / conditions:** the conditions that TS 26.231 specifies (extract from the
  spec at kickoff), plus the S-05 codec list.
- **Vectors:** V-CTM.
- **Measurements:** character error rate and delay as defined by TS 26.231.
- **Pass:** CTM meets TS 26.231 in every condition that the specification defines. **If CTM
  fails in the simulator, the simulator is wrong.** S-05, S-07 and S-08 results are then
  invalid until the defect is found. This is the main calibration of R-SIM against an
  external, standardised, known-good codec-surviving modem.
- **Motivation:** DD §3.1 row 1; DD §5.1 rank 3.

#### S-07 CVDM design sweep
- **Hypothesis:** a frequency/codebook-coded, constant-envelope, burst-synchronised waveform
  with FEC, interleaving and ARQ (D1–D13) delivers byte-exact data through every simulated
  codec path. Published NB reference points are about 1.2 kbit/s on real GSM calls (CEL-048)
  and up to about 4 kbit/s at 2.5 % SER on EFR (PAP-001 via CEL-077, SECONDARY figure).
- **Equipment:** R-SIM full chain (codec + DTX + frame loss + JBM + mode switching + S-04
  models per the use gate).
- **Codec modes:** S-05 list plus EVS channel-aware 13.2 kbit/s with offsets 2, 3, 5, 7
  (CEL-007, CEL-029), and GSM mode switching no faster than every 40 ms (CEL-014).
- **Vectors:** V-CVDM over V-PRBS; V-ERR.
- **Parameters swept:** tone count and spacing; symbol rate; alignment to the 5 ms subframe
  grid (CEL-002); FEC rate; interleaver span (always more than one 20 ms frame); burst and
  sync-word period; preamble length; level. The DD D-rule values are starting points
  marked [INFERRED] in the DD, not constraints.
- **Measurements:** m-SER, m-BER, m-RES, m-GP, m-DTX, m-ID, resync time after erasure
  bursts.
- **Pass:** for at least one parameter set per codec family (NB, WB, EVS): m-RES = 0 over the
  full payload of ≥ [P] 1 MiB; m-DTX = 0 during payload with DTX on; §1.4 receiver
  agreement; m-GP reported. No minimum goodput is set before data exists. The go/no-go
  decision uses the gate in §4.
- **Motivation:** DD §4.2, §5.4 item 2.

#### S-08 Tandem chains
- **Hypothesis:** transcoding at a media gateway re-applies M1–M5 and adds a second DTX/PLC
  (M10, CEL-015/016; mobile→G.711 transcoding reported by CEL-063).
- **Equipment:** R-SIM; G.711 from ITU-T G.191 STL (CEL-086).
- **Chains:** AMR-NB→G.711→AMR-NB; AMR-WB→G.711→AMR-NB; AMR-WB→G.711→AMR-WB;
  EVS→AMR-WB IO; EVS→G.711→AMR-NB.
- **Vectors:** V-CTM, best S-07 CVDM sets, V-FSK (Bell 103/V.21 only).
- **Measurements:** as S-07; change of m-H across the tandem.
- **Pass:** CTM still meets TS 26.231 through NB-terminated tandems (calibration). The CVDM
  degradation per chain is quantified, and at least one S-07 parameter set keeps m-RES = 0
  through every chain, or the failing chains are listed as unsupported topologies.
- **Motivation:** M10; DD §7 Q8.

### Stage E: live networks

Common setup for E-01..E-11, E-14, E-15: R-HFP on both ends where possible; R-FAR(b) for
mobile-to-PSTN. Before any data run, record m-CODEC and the device, operator and RAT
metadata (§0 rule 4). [P] At least 3 calls per condition, on [P] at least 2 device models
from different SoC vendors and [P] at least 2 operators where the path exists.

#### E-01 Rig baseline and codec labelling
- **Hypothesis:** R-HFP gives a repeatable bidirectional audio path through a real call, and
  the codec actually in use can be labelled.
- **Paths:** each available among GSM CS, UMTS CS, VoLTE, VoNR, VoWiFi.
- **Vectors:** V-SPEECH (P.501 multisine, artificial voice), V-PRBS-keyed reference tones.
- **Measurements:** m-H 50–7000 Hz, m-LAT, level transfer, clock offset (ppm) between
  injected and recorded streams, m-CODEC.
- **Pass:** for three repeated calls on the same path, m-CODEC is identical and m-LAT varies
  by no more than one codec frame (20 ms, CEL-002). Each call carries a codec label, or is
  marked `UNKNOWN` with the reason.
- **Motivation:** DD §6 (HFP rig as primary X1 entry point); DD §7 Q9.

#### E-02 SCO link stage isolation
- **Hypothesis:** the HFP SCO codec (CVSD at 8 kHz or mSBC at 16 kHz, CEL-047) is an extra
  waveform stage that must be separated from the cellular codec.
- **Method:** repeat E-01 and the S-07 best CVDM set with SCO forced to CVSD and then to mSBC
  on the same network path. Run the same vectors through open CVSD/mSBC implementations in
  R-SIM. Cross-check with E-16 where R-PRIV exists.
- **Measurements:** m-H, m-SER difference CVSD vs mSBC.
- **Pass:** the SCO contribution is quantified, so that later E-results can be stated as
  "network path + SCO(x)". If CVSD caps a WB network path at NB, WB results are taken only
  with mSBC.
- **Motivation:** DD §6 HFP caveat.

#### E-03 CTM / TTY positive control
- **Hypothesis:** the device's built-in modem-relay text path works: CTM on GSM/UMTS CS, and
  carrier TTY-over-VoLTE or RTT on IMS (CEL-011, CEL-040, CEL-041).
- **Equipment:** R-ACC with a TTY device or a Baudot 45.45 bit/s generator (CEL-035) on the
  headset port, `TTY_MODE_FULL`. Far end is a TTY or a Baudot decoder on a landline. Variant
  E-03b: CTM-encoded audio (V-CTM) injected through R-HFP with TTY mode OFF, decoded by the
  TS 26.230 receiver at the far end. This tests whether CTM audio survives the voice path as
  "speech".
- **Measurements:** character error rate per TS 26.231 method.
- **Pass:** E-03 meets TS 26.231 on at least one CS path. E-03b is recorded (no
  prediction). If E-03 fails where CTM is supported, the rig or device is faulty, and live
  negative results from that rig are not admissible.
- **Motivation:** DD §3.1, §5.1 rank 3.

#### E-04 FSK negative control (live)
- **Hypothesis (pre-registered failure):** Bell 103, V.21, V.23 and Bell 202 do not deliver
  usable data through cellular voice (DD §3.3; Baudot unreliability, CEL-011, CEL-034).
- **Vectors:** V-FSK injected at R-HFP; decoded at R-FAR by minimodem/SpanDSP from the
  recording. Interactive variant toward a hardware modem on R-FAR(b) for V.21 and V.23.
- **Measurements:** m-BER, carrier-detect continuity, m-FT inferred, m-HS (interactive).
- **Pass (experiment complete):** results per path and codec label. SURVIVES as in S-05,
  confirmed on [P] 2 devices and [P] 2 operators. A SURVIVES result is necessary, but not
  sufficient, for any FSK-over-cellular claim (§4 G-X1-4).
- **Motivation:** MASTER_RESEARCH_BRIEF practical-limits request; DD §3.3 roles.

#### E-05 QAM / fax negative control (live)
- **Hypothesis (pre-registered failure):** V.22, V.22bis, V.32bis, V.34 and G3 fax fail at
  training or data (DD §1.3; CEL-062, CEL-063, CEL-064).
- **Equipment:** an interactive modem on the phone side (SpanDSP live modem on the R-HFP
  host, or a hardware modem through an analogue line interface into R-HFP) against a
  hardware modem or fax machine on R-FAR(b).
- **Vectors:** V-QAM; hardware modem handshakes.
- **Measurements:** m-HS phase reached; m-BER where data phase is reached; whether the
  2100 Hz ANSam answer tone is observed to disable the network echo canceller (CEL-036).
- **Pass (experiment complete):** handshake traces archived per mode and path. SURVIVES
  requires CONNECT plus byte-exact transfer of ≥ [P] 64 KiB under V.42 between independent
  hardware peers.
- **Motivation:** DD §3.3 (V.32/V.34 "handshake only"), §5.1 rank 4.

#### E-06 CVDM over GSM/UMTS circuit-switched voice
- **Hypothesis:** the best S-07/S-08 NB parameter set delivers byte-exact data on real CS
  calls, in the throughput class of the published NB results (CEL-048, CEL-050, PAP-001).
- **Paths / codecs:** GSM-FR, GSM-EFR, AMR-NB (operator ACS), UMTS AMR-NB, where 2G/3G is
  still in service.
- **Vectors:** V-CVDM / V-PRBS.
- **Measurements:** m-SER, m-RES, m-GP, m-DTX, m-ID, m-ERS, m-LAT.
- **Pass:** m-RES = 0 over ≥ [P] 1 MiB on each tested path; m-DTX = 0; §1.4 agreement; m-GP
  and the live-vs-sim gap are reported per codec label.
- **Motivation:** DD §5.1 rank 2.

#### E-07 CVDM over VoLTE (AMR-WB)
- **Hypothesis:** AMR-WB set 0 {6.6, 8.85, 12.65} (CEL-033) carries CVDM. The coded band up
  to 6.4 kHz (CEL-006) may allow more tone channels than NB. Unmeasured (DD §3.2 gaps).
- **Paths / codecs:** VoLTE with AMR-WB; VoLTE forced to AMR-NB where the carrier permits,
  for comparison.
- **Measurements / pass:** as E-06, plus the m-H band edge. A WB tone plan is admitted only
  if this experiment passes (MEDIA_DSP §16.5).
- **Motivation:** DD §5.2 rank 3.

#### E-08 CVDM over VoWiFi
- **Hypothesis:** VoWiFi uses the VoLTE media chain (CEL-032) with higher jitter/loss
  variance, so more JBM time-scaling (M8) and erasures.
- **Measurements / pass:** as E-07; additionally m-ID rate vs E-07.
- **Motivation:** DD §2.1 VoWiFi row.

#### E-09 CVDM over EVS (VoLTE-EVS, VoNR)
- **Hypothesis:** EVS (mandatory for VoNR, CEL-031) carries CVDM. At ≥ 13.2 kbit/s the
  MDCT/transform cores may represent a stationary multi-tone better than ACELP (CEL-061;
  unmeasured, DD §7 Q3). The EVS JBM (CEL-009) guarantees M8.
- **Paths / codecs:** EVS as negotiated (record bit-rate, bandwidth, channel-aware offset if
  any; CEL-029); compare with S-07 EVS cells.
- **Measurements / pass:** as E-07; additionally the core-type log in simulation for the same
  waveform.
- **Motivation:** DD §5.3.

#### E-10 Wired / USB-C accessory entry point
- **Hypothesis:** the accessory uplink has different terminal voice processing from the HFP
  uplink (DD §2 AEC row; DD §7 Q5).
- **Method:** repeat E-01 and the E-07 best case through R-ACC on the same device and call
  path.
- **Pass:** differences in m-H, NS/AGC behaviour and m-SER are quantified per device. The
  entry point that X1 uses for later work is chosen on data.
- **Motivation:** DD §6.

#### E-11 Terminal AEC / NS / AGC characterisation
- **Hypothesis:** full-duplex same-band signals are suppressed by the terminal AEC-NLP
  (TCLw ≥ 46 dB handset requirement, CEL-023), and stationary tones are attenuated by NS over
  time (M11, M12).
- **Method:** per rig and path: (a) P.501 TCL signals (CEL-085); (b) stationary single and
  multi-tone at CVDM levels for ≥ [P] 60 s; (c) simultaneous same-band downlink and uplink
  CVDM ("double talk"); (d) half-duplex and frequency-separated duplex CVDM.
- **Measurements:** uplink level vs time; suppression depth vs downlink activity; m-SER for
  (c) and (d).
- **Pass:** S-04 parameters fitted per device class and committed with the data. D6
  (half-duplex or frequency-separated) is confirmed or refuted.
- **Motivation:** M11, M12; DD §7 Q5.

#### E-12 IMS real-time text as a signalling channel
- **Hypothesis:** RTT (RFC 4103/T.140, CEL-027) via `Call.RttCall` (CEL-043) gives a lossless
  low-rate text channel on carriers with RTT enabled. TS 26.114 specifies ≥ 300 ms sampling
  and up to 200 % redundancy (CEL-010).
- **Equipment:** R-INCALL on both ends (or a far-end IMS client), carriers with
  `KEY_RTT_SUPPORTED_BOOL` (CEL-042).
- **Vectors:** V-PRBS encoded into a printable framing (for example base64 in project frames
  with sequence numbers and CRC).
- **Measurements:** m-RES, m-GP in characters/s and payload bit/s, m-LAT, binary safety of
  UTF-8 end to end, behaviour across handover.
- **Pass:** m-RES = 0 over ≥ [P] 64 KiB; throughput and latency recorded per carrier.
- **Motivation:** DD §5.2 rank 2; DD §7 Q6.

#### E-13 CSD / IWF availability survey
- **Hypothesis:** network modem relay ("3.1 kHz audio ex PLMN", CEL-017, CEL-068) is no longer
  generally offered and is not reachable from Android telephony APIs.
- **Method:** operator enquiries; test SIM provisioning; search for any Android-accessible
  path. No engineering.
- **Pass:** a dated per-operator yes/no table.
- **Motivation:** DD §5.1 rank 1; DD §7 Q7.

#### E-14 Call-topology / tandem matrix
- **Hypothesis:** the number of codec stages depends on topology: same-operator mobile to
  mobile (TFO/TrFO possible, CEL-015/016), cross-operator, mobile to PSTN, mobile to SIP
  gateway. The project's real calls (mobile to gateway) are the relevant case.
- **Vectors:** V-CTM, best CVDM set, V-SPEECH.
- **Measurements:** m-H, m-CODEC at both ends where possible, m-SER, m-RES, m-GP; change in
  m-H as a tandem fingerprint.
- **Pass:** each topology the product would use has a measured CVDM result. Topologies with
  m-RES ≠ 0 are listed as unsupported.
- **Motivation:** M10; DD §7 Q8.

#### E-15 Duration, handover and rate-switch robustness
- **Hypothesis:** long calls see codec mode changes (40 ms granularity on GSM, CEL-014; CMR on
  IMS, CEL-025), handovers and short outages. A CVDM link layer with sequence numbers and ARQ
  keeps the logical session (D12, D13; PROJECT_PLAN rule 5).
- **Method:** ≥ [P] 30 min continuous transfers on the best E-06..E-09 path, mobile and
  stationary, with forced RAT changes where possible.
- **Measurements:** outage count/duration, retrain count, m-RES, m-GP over time,
  channel-quality field behaviour.
- **Pass:** m-RES = 0, and no logical-session loss for any outage shorter than the
  configured link timeout. Outage statistics are recorded to set that timeout.
- **Motivation:** M9; D12; D13.

#### E-16 Privileged capture cross-check
- **Hypothesis:** capturing the downlink inside the phone (`VOICE_DOWNLINK` with
  `CAPTURE_AUDIO_OUTPUT`, AND-004) separates the network path from the SCO stage.
  It is capture only, because no uplink injection API exists (DD §6).
- **Method:** repeat E-01 and the E-07 best case with simultaneous R-PRIV capture and R-HFP
  recording.
- **Pass:** the SCO contribution measured in E-02 is confirmed or corrected.
- **Motivation:** DD §6 rows 1–2. Optional; X1 gates do not depend on it.

---

## 4. Gates

| Gate | Requires | Consequence if not met |
|---|---|---|
| G-X1-0 Simulator accepted | S-01 pass for all codecs used; S-02, S-03 pass; **S-06 CTM calibration pass** | No S-05/S-07/S-08 result is admissible |
| G-X1-1 Live rig accepted | E-01 pass on ≥ 1 IMS path and, where still in service, ≥ 1 CS path; E-02 done; **E-03 pass on ≥ 1 path where CTM/TTY is supported** | No live negative result is admissible (it could be a rig fault) |
| G-X1-2 Controls reported | S-05, E-04, E-05 executed and published against their pre-registered predictions | No statement about legacy modes over cellular voice, positive or negative |
| G-X1-3 CVDM go/no-go | S-07 and S-08 pass; E-06 or E-07 or E-09 pass with §1.4 agreement; E-11 completed for the chosen entry point | Track closes with a documented negative result; packet data unaffected |
| G-X1-4 Claim gate | For any named mode and path: live pass on that path with a codec label, on [P] ≥ 2 devices and [P] ≥ 2 operators, with external vectors or independent receiver; for a legacy V-series or fax mode additionally an independent hardware peer (E-04/E-05 SURVIVES) | The mode is not described as working over cellular voice anywhere (UI, docs, release notes) |

The mode hierarchy that X1 tests (DD §5.4, MEDIA_DSP §16.3): packet data, then CVDM over the
voice call, then IMS RTT signalling, then legacy waveform passthrough. The last is a negative
control and is never shipped.

---

## 5. Reporting

Each experiment produces `docs/research/x1_results/<ID>-<date>.md` with the §0 rule 4
labels, the pre-registered prediction, the result, the raw-asset hashes and the gate
impact. Each result that changes a DD conclusion also gets a dated note in the DD. All
[P] values used are restated in the result file.

---

## 6. Open questions this matrix cannot settle by itself

1. The exact TS 26.231 conditions and thresholds (needed for S-06/E-03 pass criteria), and
   the exact CTM parameters from TS 26.226 (DD §7 Q1).
2. Whether opencore/vo-amrwbenc are bit-exact for every mode (S-01 will tell). If not, the
   3GPP reference code licence terms decide what can live in CI.
3. Licence for running the EVS reference code in CI (CEL-059, CEL-060).
4. Whether a public Android API exposes the negotiated voice codec (DD §7 Q9). Without it,
   m-CODEC relies on fingerprinting and operator data.
5. Lab access to still-operating 2G/3G CS networks, which governs E-03 and E-06.
