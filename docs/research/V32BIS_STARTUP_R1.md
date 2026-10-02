# V.32bis Startup and Training — Phase R1

## Purpose

This note turns the V.32/V.32bis startup procedure into an implementation-facing state machine for later S1 work. It does not attempt to restate the whole Recommendation.

## Confirmed startup structure

For GSTN/PSTN operation, the answering modem first performs the V.25 answer procedure. RFC 4734 summarizes the standardized early V.32/V.32bis startup sequence: 1.8–2.5 s silence, then ANS; the calling modem waits until ANS has been detected for 1 s, then sends the AA pattern. AA is repeated `0000` at 4800 bit/s. After detecting AA for at least 100 ms, the answering modem is silent for 75 ± 20 ms and responds with AC, repeated `0011` at 4800 bit/s.

These AA/AC patterns are sufficiently distinctive that RFC 4734 assigns explicit real-time event codes to them. This is useful for both native waveform detection and future V.150.1/modem-relay work.

## Subsequent training phases

Secondary literature and V.32bis-derived implementation material consistently identify the following sequence classes after AA/AC:

- `S` / `S-bar` training states;
- optional special echo-canceller training;
- `TRN` scrambled training;
- rate signals `R1`, `R2`, `R3` / final rate exchange;
- transition sequence `E` / scrambled ones into data mode.

A historical modem patent describing V.32bis-compatible operation gives useful implementation timing evidence for the standard sequence: 256 S symbol intervals, followed by 16 complementary S intervals, then TRN for at least 1280 symbol intervals and no more than 8192 symbol intervals. Treat those timings as corroborating evidence until copied directly into the conformance checklist from the primary Recommendation.

## Receiver/training responsibilities

The startup state machine must expose separate hooks for:

1. answer-tone and phase-reversal detection;
2. AA/AC pattern detection;
3. echo-canceller acquisition;
4. receive equalizer acquisition;
5. carrier and symbol-timing loop acquisition;
6. TRN-quality measurement;
7. rate-sequence decode;
8. negotiated-rate validation;
9. transition to steady-state data mode;
10. retrain request / failure fallback.

Do not hide these inside a single `train()` call. Each stage needs timeout, confidence and diagnostics because V.32bis interop debugging depends on knowing which training stage failed.

## Proposed S1 state model

```text
IDLE
  -> WAIT_ANS
  -> DETECT_ANS
  -> SEND_AA              (caller)
     or WAIT_AA           (answerer)
  -> AA_AC_EXCHANGE
  -> ECHO_TRAIN_LOCAL
  -> ECHO_TRAIN_REMOTE
  -> EQ_TRAIN
  -> TRN
  -> RATE_EXCHANGE
  -> FINAL_SYNC
  -> DATA

Any training state
  -> RETRAIN
  -> FALLBACK_RATE
  -> HANGUP on terminal failure
```

Role-specific substates are required because caller and answerer alternate silence/transmit periods during echo-canceller training.

## Signal path implications

V.32bis uses full-duplex echo cancellation on a two-wire channel. The training state machine must therefore control transmit muting explicitly. Echo-canceller convergence must be evaluated only during the expected half-duplex training windows; otherwise the far-end training waveform contaminates the local echo estimate.

A single DSP callback should not own the startup schedule. Recommended separation:

```text
CallStateMachine
  owns timing and legal transitions

V32bisHandshakeDetector
  identifies ANS/AA/AC/S/TRN/rate sequences

EchoCanceller
  exposes reset/start_adapt/freeze/error metrics

AdaptiveEqualizer
  exposes reset/train/freeze/error metrics

V32bisCodec
  handles constellation/trellis/data symbols
```

## Modem-relay implication

RFC 4734 defines V32AA and V32AC events specifically because a packet gateway can detect these signals early and transition to modem relay. AndroidDialup should emit equivalent internal events even in waveform mode:

```text
MODEM_EVENT_V32_AA_DETECTED
MODEM_EVENT_V32_AC_DETECTED
MODEM_EVENT_V32_TRAINING_STARTED
MODEM_EVENT_V32_RATE_NEGOTIATED
MODEM_EVENT_V32_RETRAIN
```

That keeps the local DSP architecture compatible with future V.150.1 gateways.

## Test requirements

S1/I-series testing must include:

- recorded hardware-modem AA/AC captures;
- synthetic AA/AC generated independently from our detector;
- false-positive tests against voice, DTMF and V.21/V.22 signals;
- timing tolerance around the AA-detection and AC-response interval;
- TRN shortened to minimum legal duration;
- TRN near maximum legal duration;
- injected echo and delay spread;
- forced rate fallback;
- deliberate equalizer failure;
- deliberate echo-canceller non-convergence;
- retrain while data is active.

## Sources

Primary/institutional:
- ITU-T V.32bis (02/1991), in force.
- RFC 4734, *Definition of Events for Modem, Fax, and Text Telephony Signals*, section describing V.32/V.32bis AA and AC startup events.

Secondary/corroborating:
- 3am Systems V.32/V.32bis handshake technical page, adapted from ITU-T V.32.
- historical V.32bis-compatible modem patents describing S/TRN timing and training use.

## Decision

V.32bis implementation remains post-Beta, but its startup controller should be designed before the equalizer/echo-canceller APIs are frozen. The handshake is sufficiently structured that it should be a first-class state machine, not implicit logic distributed through DSP blocks.
