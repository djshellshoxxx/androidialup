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
