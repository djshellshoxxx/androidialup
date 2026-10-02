# Phase R1 Higher-Speed Modem Roadmap

This document records the architecture consequences of the V.32bis/V.90/V.92 standards review. It is not yet a coding specification.

## V.32bis

Primary source: ITU-T V.32bis (02/1991), in force.

### Key PHY requirements

- Full-duplex 2-wire operation using echo cancellation rather than frequency-division channel separation.
- QAM at 2400 symbols/s ±0.01%.
- Carrier frequency 1800 Hz ±1 Hz.
- Receiver must tolerate up to ±7 Hz received-frequency offset.
- Mandatory rates: 14,400, 12,000, 9600 and 7200 bit/s trellis coded; 4800 bit/s uncoded.
- Compatibility with V.32 at 9600 and 4800 bit/s.
- Startup exchanges rate sequences to select operating rate.
- Rate changes may occur without a full retrain.
- 14,400 bit/s groups six scrambled data bits per symbol interval before differential/trellis processing; 12,000 uses five.

### Architectural consequence

V.32bis is the first target in our roadmap where full-duplex echo cancellation is intrinsic to the physical layer rather than optional infrastructure. Therefore it must not be scheduled until the following shared DSP primitives have independent tests:

1. carrier NCO/mixer
2. interpolation/decimation and pulse-shaping filters
3. timing recovery at 2400 symbols/s
4. carrier/phase recovery
5. adaptive equalizer
6. echo canceller with double-talk/far-end robustness appropriate to modem training
7. differential decoder
8. trellis/Viterbi decoder
9. scrambler/descrambler
10. explicit startup/rate/retrain state machine

### Reuse decision

For first interoperability, prefer evaluating SpanDSP V.32bis as LINK/PORT or REFERENCE before committing to a clean-room implementation. Building V.32bis from scratch is possible, but it would create a large DSP validation burden unrelated to proving the Android/IP architecture.

## V.90

Primary source: ITU-T V.90 (09/1998), in force.

V.90 defines an asymmetric digital/analogue modem pair for up to 56,000 bit/s downstream and 33,600 bit/s upstream. The upstream path remains V.34-class analogue modulation while the high-rate downstream path exploits the fact that the server/digital modem is connected to the PSTN through a digital network path.

### Critical architecture fact

V.90 is not simply a faster symmetric acoustic QAM modem. Correct high-rate downstream operation depends on a suitable digital-side network topology and PCM path. Therefore an Android handset generating arbitrary acoustic modem audio cannot by itself recreate the digital-server side of a genuine V.90 connection.

### Project consequence

V.90 implementation should be split into two roles:

- **Analogue client modem role:** potentially implementable in software/SpanDSP-derived architecture on Android or gateway hardware.
- **Digital server modem role:** requires a controlled digital telephony interface or server/gateway architecture capable of the PCM-side assumptions of V.90.

For Beta and early field tests, a hardware modem/gateway is far more practical than attempting full V.90 server DSP.

### Research requirement before implementation

Before coding V.90 we need a dedicated topology experiment documenting:
- exact digital access path available in the lab,
- codec law and PCM clocking,
- number and location of analogue conversions,
- whether the provider path preserves the assumptions needed by V.90,
- achievable downstream rates using known-good commercial modems.

If those conditions cannot be created, V.90 remains a protocol/DSP research target but not a Beta interoperability requirement.

## V.92

Primary source: ITU-T V.92 (11/2000) plus in-force amendments/corrigendum.

V.92 extends V.90 with:
- upstream rates up to 48,000 bit/s using PCM upstream where supported,
- reduced startup time on recognized connections (Quick Connect),
- modem-on-hold procedures.

### Architectural consequence

V.92 is explicitly post-V.90. It should not influence Beta 0.1 module boundaries except in two ways:

1. Session state must be capable of temporary suspension/resumption rather than assuming every interruption is a final hangup.
2. Cached line/training profiles should be representable later without changing the persistent-state model.

PCM upstream and Quick Connect should not be implemented until ordinary V.90 interoperability exists.

## Updated standards implementation order

The practical dependency order is now:

1. V.21/Bell-103-class FSK
2. V.8 negotiation
3. V.22/V.22bis
4. V.42 LAPM
5. V.42bis
6. V.32/V.32bis shared high-speed DSP infrastructure
7. V.34
8. V.90, only with a verified digital-server test topology
9. V.92 enhancements

## Phase R1 decision

The Android/IP product must not make 56k capability a Beta gate. The difficult part of this project is two different problems that should remain separable:

- making Android behave like a useful modem endpoint over packet networks;
- reproducing increasingly complex legacy analogue/digital modem physical layers.

The first can reach a successful Beta using byte relay, real hardware modems and low-speed software PHYs while the higher-speed PHY work continues independently.
