# V.34 Implementation Architecture — Phase R1

## Why V.34 is a separate engineering tier

V.34 is not a modest extension of V.32bis. The in-force 02/1998 Recommendation adds a substantially more complex startup and data-path design, including line probing, multiple symbol rates, constellation shaping, multidimensional trellis coding, precoding and nonlinear encoding. The standard also reaches 33.6 kbit/s.

## Major implementation blocks identified

The V.34 transmit path described by the standard and contemporary technical literature requires at minimum:

1. framing of user bits into V.34 data/mapping frames;
2. shell mapping / constellation shaping;
3. multidimensional trellis-coded modulation;
4. differential encoding;
5. precoding based on remote-receiver equalization requirements;
6. optional nonlinear encoding;
7. constellation mapping;
8. pulse shaping and selected spectral pre-emphasis;
9. carrier modulation at negotiated symbol/carrier parameters.

The receive side is not prescribed as one exact architecture by V.34, so receiver design remains an implementation choice. It nevertheless needs robust carrier recovery, symbol timing recovery, adaptive equalization, trellis decoding, inverse mapping, frame recovery and negotiation-state handling.

## Startup is an independent subsystem

V.34 startup must not be embedded in the steady-state QAM decoder. The startup process determines channel characteristics and negotiates operating parameters before normal user data is possible.

Required startup responsibilities include:

- V.8/V.8bis-related capability negotiation where applicable;
- answer/call signaling;
- line probing and channel measurement;
- symbol-rate selection;
- carrier-frequency selection;
- transmit level / pre-emphasis selection;
- equalizer and echo-canceller training;
- exchange of modem parameters;
- final data-rate and coding selection;
- transition to primary-channel data.

The 02/1998 Recommendation includes variables and procedures for shell mapping, precoding, pre-emphasis, trellis encoding and mapping-frame construction. These must become separate implementation modules rather than one monolithic V34 modem class.

## Proposed module boundaries

```text
V34Controller
V34Startup
V34LineProbe
V34ParameterNegotiator
V34FrameBuilder
V34ShellMapper
V34TrellisEncoder
V34TrellisDecoder
V34Precoder
V34NonlinearEncoder
V34ConstellationMapper
V34PulseShaper
V34Demapper
V34EqualizerAdapter
V34Diagnostics
```

Shared lower-level modules should remain standard-neutral where technically sound:

```text
NCO
CarrierPLL
SymbolTimingLoop
FIR/IIR
ComplexAdaptiveEqualizer
EchoCanceller
ViterbiCore
PowerMeter
AGC
```

## Shell mapping

The standard's shell-mapping variables and contemporary V.34 literature show that shaping is not optional implementation decoration. It is part of mapping user data onto lower-average-energy multidimensional signal regions. Therefore it must have its own deterministic unit tests.

Minimum tests:

- known input-bit to ring-selection examples from primary/secondary references;
- boundary values for every supported constellation size;
- reversibility with demapper;
- exact frame-bit accounting;
- cross-check against an independent implementation before claiming interoperability.

## Trellis/Viterbi

V.34 uses multidimensional trellis-coded modulation. The project should implement a generic Viterbi engine only if its API can represent the required V.34 trellis structures without contaminating it with V.34-specific state. Otherwise a dedicated V34TrellisDecoder is safer.

The high-speed roadmap must not assume the V.32bis decoder can simply be reused unchanged.

## Precoding and equalization

The V.34 architecture uses transmitter precoding to reduce receiver equalizer noise enhancement. This creates a tighter dependency between startup channel characterization, remote-receiver parameter exchange and the steady-state transmitter.

Implication: `AdaptiveEqualizer` and `V34Precoder` cannot be designed independently. S1 should define a channel-response/equalizer representation that can be serialized into V.34 startup parameter exchange and consumed by the precoder.

## Sample-rate strategy

Android's native audio rate will commonly be 48 kHz, whereas telephony/modem algorithms are naturally expressed around voiceband rates and negotiated symbol rates. Do not force V.34 directly onto device sample cadence.

Recommended architecture:

```text
Android/Oboe native stream (usually 48 kHz)
        |
clock-domain / rate adapter
        |
canonical modem DSP stream
        |
V.34 front end and symbol timing
```

The exact canonical DSP rate remains an S1 decision after profiling SpanDSP and our intended symbol-rate set.

## Reuse assessment

SpanDSP contains V.34 structures/source behind build-time support flags. That makes it valuable for architecture archaeology and possibly for linkage/porting subject to exact version maturity and LGPL obligations. It should not be assumed production-ready until interoperability tests show which V.34 modes are actually implemented and stable.

For Beta 0.1: REFERENCE ONLY.
For later high-speed work: evaluate LINK/PORT versus independent implementation after V.21/V.22bis/V.32bis infrastructure is validated.

## Primary evidence

- ITU-T V.34 (02/1998), current in-force edition.
- Contemporary V.34 technical literature explaining shell mapping, multidimensional TCM, nonlinear precoding and startup/operating procedures.
- TI modem DSP implementation literature catalogued elsewhere in this repo.

## Decision

V.34 is explicitly outside Beta 0.1 implementation. However, S1 must avoid API choices that make V.34 impossible. In particular, the equalizer, echo canceller, timing loop, Viterbi infrastructure, channel characterization and call-state diagnostics must remain reusable by a later V.34 stack.
