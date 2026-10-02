# S1 Media and DSP Contract

Status: normative interface contract; detailed modem PHY algorithms may evolve behind these interfaces.

## 1. Clock domains

The implementation recognizes three independent clocks:

```text
NETWORK_MEDIA_CLOCK = 8000 Hz for PCM_VBD_EXPERIMENTAL
ANDROID_AUDIO_CLOCK = device native rate, commonly 48000 Hz
MODEM_SYMBOL_CLOCK  = protocol-specific
```

No code may assume these clocks are phase-locked.

## 2. PCM canonical types

```text
Pcm8kBlock {
  timestamp: u64        // first 8 kHz sample index
  sample_count: u16
  samples: int16[sample_count]
}

AudioNativeBlock {
  frame_index: u64
  sample_rate: u32
  samples: float or int16 implementation-defined internally
}
```

Network-facing private PCM uses signed int16. Native DSP may use float internally.

## 3. Jitter buffer interface

```text
interface MediaJitterBuffer {
  reset(initial_seq, initial_timestamp)
  insert(MediaPacket, arrival_monotonic_ns) -> InsertResult
  pull(playout_monotonic_ns) -> Pcm8kBlock
  stats() -> JitterStats
}
```

`insert` never blocks playout. `pull` never waits for network I/O.

## 4. Jitter buffer bounds

Beta experimental defaults:

```text
packet_duration_ms = 20
minimum_delay_ms   = 40
initial_delay_ms   = 80
maximum_delay_ms   = 240
reorder_window_packets = 64
capacity_packets   = 512
```

These are experimental defaults, not V.152 claims.

## 5. Sequence handling

16-bit wire sequence is extended to monotonically increasing 64-bit sequence near the current expected sequence.

```text
extend(seq16, reference64):
  base = reference64 & ~0xffff
  candidates = [base|seq16, (base-65536)|seq16, (base+65536)|seq16]
  return candidate with minimum absolute distance to reference64
```

Packets too far behind expected beyond reorder window are late/drop. Duplicate extended sequence is drop/duplicate.

## 6. Loss fill

Initial policy:

```text
MISSING_PACKET -> output 160 zeros for one normal packet duration
```

No waveform extrapolation, time-stretch speech PLC, comfort noise, or pitch-based concealment is allowed by default.

Alternative loss-fill strategies must be experimental flags with separate measurements.

## 7. Occupancy controller

Clock drift is corrected using a slow ASRC ratio controller.

Definitions:

```text
target = target buffered media samples
error = current_buffered_samples - target
```

Controller runs no faster than once per 20 ms packet period.

Normative bounds:

```text
MAX_CORRECTION_PPM = 300
MAX_SLEW_PPM_PER_SECOND = 25
```

The exact Kp/Ki coefficients are an experiment-derived configuration value, not hardcoded protocol behavior.

```text
updateClockController(error, dt):
  integral = clamp(integral + Ki*error*dt, -300, +300)
  desired = clamp(Kp*error + integral, -300, +300)
  correction_ppm = slewLimit(previous, desired, 25 ppm/s, dt)
  ratio = nominal_ratio * (1 + correction_ppm/1_000_000)
```

The implementation MUST expose configured Kp/Ki and live correction ppm in diagnostics.

## 8. Native audio ring contract

Two bounded SPSC rings:

```text
captureRing: AudioNativeBlock producer=audio callback, consumer=DSP worker
playbackRing: producer=DSP worker, consumer=audio callback
```

Audio callback:

- no malloc/new;
- no Java/JNI callback into blocking code;
- no mutex that can contend with non-realtime thread;
- no logging I/O;
- no network calls;
- bounded copy only.

## 9. DSP interface

```text
interface ModemPhy {
  configure(PhyConfig)
  reset(role)
  pushRxPcm(PcmBlock)
  pullTxPcm(maxSamples) -> PcmBlock
  pushTxBits(BitBlock)
  pullRxBits(maxBits) -> BitBlock
  pollEvents() -> list<PhyEvent>
  metrics() -> PhyMetrics
}
```

Events:

```text
CARRIER_ON
CARRIER_OFF
TRAINING_STARTED
TRAINING_SUCCEEDED
TRAINING_FAILED
RATE_CHANGED
RETRAIN_STARTED
RETRAIN_SUCCEEDED
RETRAIN_FAILED
PROTOCOL_ERROR
```

## 10. FSK Beta PHY contract

The first independent PHY implementation supports a V.21-class profile from the R1 standard extraction.

Configuration supplies exact frequencies/role so the generic FSK engine is not hardcoded to one channel.

```text
FskProfile {
  bit_rate
  mark_hz
  space_hz
  sample_rate
  rx_frequency_tolerance_hz
  carrier_threshold_dbm0
}
```

Required implementation blocks:

```text
NCO TX tone generator
continuous-phase symbol transition
RX band limiting
mark/space energy estimation or equivalent coherent detector
carrier detector
symbol-timing estimator
bit slicer
async/synchronous framing adapter
```

The detector implementation may differ from SpanDSP/minimodem; external vectors establish correctness.

## 11. Bit and byte boundary

PHY modules operate on bits. Link/framing modules convert between bits and octets.

No V.42 implementation is embedded inside the QAM/FSK demodulator.

Layering:

```text
DTE bytes
  -> optional V.42bis compressor
  -> V.42/LAPM framing
  -> modem PHY bit stream
```

Reverse on receive.

BYTE_RELAY bypasses all three modem-link layers because it represents a clean project transport rather than an analogue modem waveform.

## 12. DSP reset semantics

`reset(role)` clears:

- carrier/timing/equalizer state;
- scrambler state where owned by PHY;
- training state;
- buffered partial symbols;
- error counters that are per-call.

Lifetime aggregate metrics may exist separately but SHALL NOT influence current-call DSP state.

## 13. Channel simulator interface

```text
ChannelImpairment {
  gain_db
  awgn_dbm0?
  frequency_offset_hz
  sample_clock_ppm
  delay_ms
  echo_paths[] { delay_ms, gain_db }
  bandlimit_profile
  clipping_level?
}
```

Simulator can be inserted between independent TX/RX modules or applied to external WAV assets.

### 13.1 Cellular voice path model (X1 extension)

`ChannelImpairment` models an analogue-like line. It SHALL NOT be used alone to predict
behaviour over a cellular voice call, because that path is a frame-based, parametric,
time-variant channel (§16.2). For `CELLULAR_VOICE_EXPERIMENTAL` work the simulator SHALL
also provide an ordered stage chain:

```text
CellularVoicePathModel {
  seed: u64
  stages: list<VoicePathStage>        // applied in order: uplink terminal -> network -> downlink terminal
}

VoicePathStage =
    TerminalVoiceProcessingStage {     // M11, M12 (§16.2); parametric, calibrated by X1 E-11
        aec_nlp: { enabled, suppression_db, double_talk_detector_profile }?
        noise_suppression: { enabled, max_attenuation_db, adaptation_time_ms }?
        agc: { enabled, target_level_dbm0, attack_ms, release_ms }?
        calibration_ref: string        // X1 result file the parameters were fitted to, or "UNCALIBRATED"
    }
  | ScoLinkStage { codec: CVSD | MSBC | LC3_SWB, implementation_id, version_hash }
  | SpeechCodecStage {
        codec: GSM_FR | GSM_HR | AMR_NB | AMR_WB | EVS | EVS_AMRWB_IO | G711_A | G711_U
        mode: codec-specific rate/mode id
        evs_bandwidth?: NB | WB | SWB | FB
        evs_channel_aware_offset?: 0 | 2 | 3 | 5 | 7
        dtx: bool
        vad_option?: AMR_VAD1 | AMR_VAD2
        implementation_id, version_hash, bit_exact_verified: bool
    }
  | VadDtxModel { REFERENCE_ENCODER | PARAMETRIC { profile_id } }
  | FrameLossStage    { see §13.2 }
  | JitterBufferStage { see §13.3 }
  | ModeSwitchStage   { schedule: list<(frame_index, mode)>, min_interval_frames }
}
```

Normative rules:

1. `SpeechCodecStage` SHALL run a real encoder followed by a real decoder. It SHALL NOT use a
   filter, noise or "codec-like" approximation. The implementation SHALL be verified
   bit-exact against the 3GPP/ITU conformance sequences for every mode used (TS 26.074,
   TS 26.174, TS 26.444; CEL-081, CEL-082, CEL-083), or flagged
   `bit_exact_verified = false`. Results from unverified stages are not admissible X1
   evidence. Candidate implementations: opencore-amr and vo-amrwbenc (Apache-2.0; CEL-055,
   CEL-056), GSM 06.10 from libgsm/SpanDSP (reference use pending licence decision; CEL-057,
   CEL-058), and the EVS 3GPP reference code as an offline test oracle only (CEL-059). AMR-NB
   12.2 MAY stand in for GSM-EFR, labelled as a functional proxy (CEL-069).
2. Valid modes: AMR-NB 4.75–12.2 kbit/s (8 modes; CEL-002), AMR-WB 6.6–23.85 kbit/s
   (9 modes; CEL-006), EVS 5.9–128 kbit/s and AMR-WB IO (CEL-007). EVS channel-aware offsets
   are 2, 3, 5 or 7 frames at 13.2 kbit/s (CEL-007, CEL-029).
3. `VadDtxModel = REFERENCE_ENCODER` uses the codec's own VAD/DTX. A `PARAMETRIC` model MAY be
   used only after X1 S-02 shows that it reproduces the reference encoder frame-type sequence
   (AMR: 7 hangover frames, then SID_FIRST, then a SID update every 8 frames, CEL-003; EVS:
   fixed SID interval 3–100 frames or adaptive, CEL-073).
4. `ModeSwitchStage` SHALL NOT switch AMR modes on a GSM-modelled path faster than every second
   frame (40 ms; CEL-014). On IMS-modelled paths it MAY switch per packet (CEL-025) and, for
   EVS, per frame (CEL-007).
5. `TerminalVoiceProcessingStage` parameters have no standard source. Every run SHALL record
   `calibration_ref`. Conclusions drawn with `UNCALIBRATED` parameters SHALL be reported as
   bounds (stage bypassed vs worst plausible), never as predictions.
6. Tandem topologies (M10) are expressed as more than one `SpeechCodecStage` in the chain,
   for example AMR_WB → G711_A → AMR_NB (CEL-015, CEL-016). G.711 SHALL come from an
   independent reference such as ITU-T G.191 STL (CEL-086).

Every run SHALL emit a `VoicePathTrace`:

```text
VoicePathTrace {
  seed, stage list with implementation ids and version hashes
  pcm_in, pcm_out                       // per stage boundary on request
  frame_types[]  : SPEECH | SID_FIRST | SID_UPDATE | NO_DATA | ERASED   (per 20 ms frame)
  modes[]        : codec mode per frame
  erasures[]     : frame indices erased and the loss event that caused them
  jbm_events[]   : time-scale insertions/deletions, concealed-frame insertions
}
```

### 13.2 Frame-aligned loss model

Losses on a cellular voice path are whole-codec-frame erasures concealed by the decoder's
PLC (CEL-005, CEL-008). They are not missing PCM samples. Therefore:

```text
FrameLossStage {
  frame_ms: 20                         // CEL-002, CEL-006, CEL-007
  frames_per_packet: 1..4              // IMS RTP; 1 for CS radio frames (CEL-010)
  pattern:
      BERNOULLI      { p_packet }
    | GILBERT_ELLIOTT{ p_good_to_bad, p_bad_to_good, loss_in_good, loss_in_bad }
    | PATTERN_FILE   { path, sha256 }  // e.g. ITU-T G.191 STL frame-erasure patterns (CEL-086)
  delivery: BAD_FRAME_INDICATION       // erased frames reach the decoder as BFI / NO_DATA
}
```

1. Erasures SHALL fall on encoder frame boundaries. One packet loss erases
   `frames_per_packet` consecutive frames.
2. Erased frames SHALL be passed to the decoder as bad-frame/lost-frame indications so that
   the standard PLC runs. Zero-filling decoded PCM is not a valid cellular loss model. The §6
   zero-fill policy applies only to the project's own PCM_VBD jitter buffer.
3. Random patterns SHALL record parameters and seed (§14). Pattern files SHALL record their
   hash and provenance.

### 13.3 Jitter-buffer time-scaling stage

IMS paths (VoLTE, VoNR, VoWiFi) apply receiver jitter-buffer management that inserts and
deletes signal (CEL-009). The simulator SHALL provide:

```text
JitterBufferStage {
  kind: NONE | EVS_REFERENCE_JBM       // TS 26.448 reference JBM (CEL-009)
  delay_error_profile: { path, sha256 } // e.g. TS 26.444 JBM profiles (CEL-083) or G.191 STL conversion (CEL-086)
}
```

`NONE` is valid only for CS-modelled paths. X1 S-03 SHALL confirm that
`EVS_REFERENCE_JBM` output matches the TS 26.444 conformance outputs before use.

## 14. Network simulator interface

```text
NetworkImpairment {
  base_delay_ms
  jitter_distribution
  packet_loss_fraction
  duplicate_fraction
  reorder_fraction
  burst_loss_model?
  sender_clock_ppm
}
```

Random tests record PRNG seed so every failure is reproducible.

## 15. Validation rule

A modem PHY is not considered correct because its own transmitter and receiver interoperate.

At least one of:

- independent open-source implementation;
- commercial hardware modem capture;
- standard-derived independent vector;

must validate each claimed modulation mode before the project labels it interoperable.

## 16. Cellular voice path constraints

Status: normative for any work on `CELLULAR_VOICE_EXPERIMENTAL` (a reserved mode,
S1_SPEC_FREEZE §10.1). This section does not change BYTE_RELAY or PCM_VBD_EXPERIMENTAL, and
does not add a Beta 0.1 deliverable. The evidence is in
`docs/research/CELLULAR_VOICE_CODECS_R2.md` (DD) and the experiments in
`docs/research/CELLULAR_VOICE_EXPERIMENTS_R2.md` (X1). Values marked † were taken from
indexed excerpts of the specification (DD access note). X1 SHALL re-verify them against the
downloaded specification before using them as a pass/fail threshold.

### 16.1 Non-blocking rule

Cellular voice is an isolated experimental track. No Beta 0.1 or later packet-data
acceptance criterion SHALL depend on it. Failure, closure or postponement of the track SHALL
NOT block BYTE_RELAY, PCM_VBD_EXPERIMENTAL, I1–I5 exit criteria or packet-data releases.

### 16.2 Channel model

A cellular voice call (GSM, UMTS, VoLTE, VoNR, VoWiFi) SHALL be treated as a lossy,
frame-structured, time-variant, non-linear byte-bearer candidate. It SHALL NOT be treated as
a 3.1 kHz analogue line. Implementations SHALL NOT assume any of the following:

| Assumption that does not hold | Reason | Source |
|---|---|---|
| Waveform fidelity (phase, amplitude, fine spectral structure) | Every deployed codec is a 20 ms-frame LP/CELP or switched LP/MDCT coder, not a waveform coder (M1–M5) | CEL-002, CEL-006, CEL-007, CEL-019..021 |
| Continuous transmission | VAD/DTX substitutes comfort noise after a hangover (AMR: 7 frames, then SID every 8 frames; EVS: SID interval 3–100 frames or adaptive) (M6) | CEL-003, CEL-073 |
| Loss is observable in the audio | PLC outputs plausible extrapolated speech (M7) | CEL-005, CEL-008 |
| Sample-clock continuity | IMS jitter-buffer management time-scales the signal (M8) | CEL-009 |
| Constant quantisation fidelity | AMR mode can change every 40 ms on GSM; CMR per packet on IMS; EVS per frame (M9) | CEL-014, CEL-025, CEL-007 |
| One codec stage | Tandem transcoding toward PSTN or between operators unless TFO/TrFO applies (M10) | CEL-015, CEL-016 |
| Full-duplex same-band transparency | Terminal echo control is mandatory (handset TCLw ≥ 46 dB) (M11) | CEL-023 |
| Stationary spectra pass unchanged; constant gain | Terminal noise suppression and AGC (M12) | CEL-037, CEL-038 |
| Energy outside the coded band carries information | NB codecs 300–3400 Hz; AMR-WB waveform-codes only up to 6.4 kHz†, above that parametric (M13) | CEL-019, CEL-006 |
| Low latency | Codec delay 25 ms (AMR-NB)† to 32 ms (EVS)† per stage, plus jitter buffer (M14) | CEL-001, CEL-007 |
| The application can disable any stage | No stage is disableable from an app; codec negotiation is carrier-configured | CEL-042, DD §2.1 |

### 16.3 Mode hierarchy (normative ordering)

When a path to the relay or gateway is needed, implementations and documentation SHALL rank
bearers in this order:

1. **Packet data** (Wi-Fi or cellular IP; BYTE_RELAY, PCM_VBD_EXPERIMENTAL over IP). Always
   preferred whenever IP reachability exists.
2. **CVDM over the voice call:** `CELLULAR_VOICE_EXPERIMENTAL` as a byte bearer with a
   codec-aware PHY (§16.4–§16.5). Eligible only after X1 gate G-X1-3.
3. **IMS real-time text** (RFC 4103/T.140 via `Call.RttCall`) as a signalling or very-low-rate
   channel on carriers that enable RTT (CEL-010, CEL-027, CEL-039, CEL-043). Eligible only
   after X1 E-12.
4. **Legacy V-series/fax waveform passthrough over the voice call:** negative-control
   experiment only. It SHALL NOT have a mode identifier, SHALL NOT be selectable by `AUTO`,
   and SHALL NOT be shipped (DD §3.3, §5.4; CEL-011, CEL-034, CEL-063).

### 16.4 Architecture: modem relay at the phone

1. V-series PHYs, V.42/LAPM and V.42bis SHALL terminate on the Android side or on the gateway.
   They SHALL never run across a cellular speech codec. This is the architecture of GSM's own
   data and fax service (network IWF modem, CEL-017, CEL-018) and of V.150.1 modem relay
   (STD-001).
2. `CELLULAR_VOICE_EXPERIMENTAL` SHALL carry DTE bytes (as BYTE_RELAY does), framed by a
   CVDM link layer with sequence numbers, FEC and ARQ. The gateway SHALL terminate CVDM and
   bridge into the existing `GatewayBackend` byte interface (S1_SPEC_FREEZE §4.4).
3. The CVDM PHY SHALL implement the `ModemPhy` interface (§9) so that it can be tested with
   the §13 simulator.

### 16.5 CVDM waveform constraints

These rules constrain the design space. Numeric PHY parameters (tone plan, symbol rate,
FEC rate, interleaver depth, burst and sync period, level) are X1 outputs (S-07, E-06..E-09).
They are not fixed here. The DD D1–D13 values are hypotheses marked [INFERRED].

1. Symbols SHALL be frequency-coded or speech-like-codebook-coded. Information SHALL NOT be
   carried in absolute phase or amplitude (M2, M4, M5; DD D2; PAP-001..003, CEL-051).
2. Symbol timing SHOULD align with the codec's 5 ms subframe grid within 20 ms frames
   (CEL-002) (DD D1).
3. On NB paths all tones SHALL lie within 300–3400 Hz (CEL-019). A WB tone plan, never above
   6.4 kHz† (CEL-006), MAY be used only on a path whose negotiated codec is WB or wider and
   only after X1 E-07 or E-09 passes on that codec.
4. The transmitter SHALL NOT emit idle silence during a session. It SHALL keep the signal
   voice-active (keep-alive symbols, preamble that exercises the VAD), and the receiver
   SHALL report DTX events (M6; CEL-003, CEL-073; DD D4, D10).
5. FEC with interleaving spanning more than one 20 ms codec frame, plus link-layer ARQ with
   sequence numbers, are mandatory (M7, M9; CTM precedent of convolutional coding with
   interleaving, CEL-011, CEL-053; DD D8).
6. Receivers SHALL resynchronise per burst using sync words and SHALL tolerate symbol
   insertions and deletions. A free-running symbol clock across bursts is not permitted
   (M8; CEL-009, CEL-048; DD D7).
7. Two-way operation SHALL be half-duplex or strictly frequency-separated. Same-band full
   duplex through the terminal is not permitted (M11; CEL-023; DD D6).
8. Every CVDM frame SHALL carry a channel-quality field (observed erasures, DTX events) so the
   peer can step the rate down. This is the CMR concept applied at the application layer
   (M9; CEL-025; DD D13).
9. The CVDM link SHALL keep the logical session across short outages caused by handover,
   mode change or re-transcoding (PROJECT_PLAN rule 5; DD D12). The outage timeout is set
   from X1 E-15 data.

### 16.6 Android platform constraints

1. Ordinary apps cannot capture call audio (`VOICE_CALL/UPLINK/DOWNLINK` require
   `CAPTURE_AUDIO_OUTPUT`; Android 10+ gives third-party capture silence), and no public API
   injects audio into the call uplink (AND-004, AND-005, CEL-044, CEL-045). The product SHALL
   NOT use hidden, reflective or root-only workarounds for this. Generic Play-store call
   audio injection/capture stays a non-goal (BETA_0_1_ARCHITECTURE §20).
2. Permitted X1 entry points: an external Bluetooth HFP hands-free unit (CEL-046, CEL-047;
   the SCO codec, CVSD 8 kHz or mSBC 16 kHz, is an extra stage that SHALL be modelled,
   §13.1), wired/USB accessory audio, a privileged system image (capture only), the
   `InCallService` RTT stream (CEL-043), and the platform TTY mode as a positive control
   (CEL-040, CEL-041).

### 16.7 Labelling and diagnostics

Every cellular voice measurement, and every `CELLULAR_VOICE_EXPERIMENTAL` session, SHALL
record: access path (GSM_CS, UMTS_CS, VOLTE, VONR, VOWIFI), negotiated codec, mode and
bandwidth (or `UNKNOWN` with the reason), audio entry point (HFP_CVSD, HFP_MSBC, WIRED, USB,
PRIVILEGED_CAPTURE), DTX events, estimated erasures, insertions/deletions, goodput and
residual errors. The mode SHALL be labelled experimental in negotiation and diagnostics
(PROJECT_PLAN rule 6).

### 16.8 Claims

No document, UI or release note SHALL state that any mode or waveform works over a cellular
voice call unless X1 gate G-X1-4 has been met for that mode and path. A legacy V-series or
fax claim additionally requires an E-04/E-05 SURVIVES result against an independent hardware
peer. The evidence does not predict one (DD §1.3, §3.3).

### 16.9 Reference figures (informative)

| Figure | Value | Source |
|---|---|---|
| Codec frame / subframe | 20 ms / 5 ms | CEL-002, CEL-019 |
| AMR-NB algorithmic delay | 25 ms† | CEL-001 |
| EVS algorithmic delay | 32 ms† | CEL-007 |
| AMR DTX | 7 hangover frames, SID_FIRST, SID every 8 frames | CEL-003 |
| EVS DTX | SID interval 3–100 frames or adaptive | CEL-073 |
| GSM AMR mode-change granularity | 40 ms | CEL-014 |
| VoLTE default AMR-WB set 0 | 6.6, 8.85, 12.65 kbit/s | CEL-033 |
| IMS packetisation | ptime 20 ms; ≤ 4 non-redundant frames/packet; maxptime 80 ms (240 ms with redundancy)† | CEL-010 |
| IMS RTT | ≥ 300 ms sampling, up to 200 % redundancy† | CEL-010 |
| CTM | 4-FSK, 200 bd, 200 Hz spacing, r = 1/4 FEC + interleaving (to re-verify vs TS 26.226) | CEL-053, CEL-011 |
| Published codec-aware data-over-voice | ≈ 1.2 kbit/s real GSM (CEL-048); up to ≈ 4 kbit/s at 2.5 % SER on EFR (PAP-001 via CEL-077, SECONDARY) | DD §3.2 |

These are reference points. They are not throughput commitments (DD D11).
