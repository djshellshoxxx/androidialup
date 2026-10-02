# External Test Vector and Capture Provenance — Phase R1

## Goal

The modem DSP must never be considered validated only because our encoder can decode our own decoder output. Independent assets are mandatory.

## Asset classes

### Class A — project-owned hardware captures

Preferred source for interoperability evidence.

Capture real commercial modems connected through a controlled analogue path. Store:

- modem make/model;
- chipset if known;
- firmware revision;
- AT initialization string;
- negotiated standard/rate;
- sample rate / sample format;
- analogue interface used;
- gain settings;
- line simulator settings;
- exact scenario and expected decode;
- SHA-256 of raw and normalized files.

Because the project creates these recordings itself, redistribution provenance is clear while the signal source remains implementation-independent.

### Class B — standards/test-lab vectors with explicit redistribution terms

Use when an ITU/vendor/academic source supplies formal vectors and the access terms permit storing them in the repository. If redistribution is not explicit, store only the acquisition instructions and hashes/metadata, not the binary asset.

### Class C — open-source generated vectors

Examples: minimodem or SpanDSP used as an external generator.

These are useful independent *runtime generators*, but generated-output licensing/provenance must be reviewed separately from source-code licensing before checking generated WAVs into this repository.

For safety, Phase R1 policy is:

- keep generator commands and version hashes;
- regenerate during local/CI tests when licensing permits execution;
- do not vendor GPL source into AndroidDialup merely to obtain test output.

### Class D — third-party historical captures

Examples include modem WAV files discovered in unrelated repositories.

Do not redistribute unless the file's creator, capture provenance and license/permission are known. URLs, hashes and technical observations may be recorded as research references.

## SpanDSP test framework finding

SpanDSP contains dedicated modem regression programs. Its V.22bis test connects two independent modem instances through a telephone-line model and performs BER testing under configurable signal/noise conditions. The test can also decode an external audio file and can log generated audio.

Useful mechanisms visible in the test harness include:

- bidirectional telephone-line model;
- configurable noise level;
- signal level control;
- codec/rate-bit impairment options;
- BER test patterns;
- retrain injection;
- equalizer inspection;
- carrier and symbol-timing diagnostics;
- WAV input/output.

The SpanDSP test source is GPLv2 even though the core library is LGPL 2.1. We should reproduce the *testing concepts* in our own harness unless the project's eventual licensing makes direct reuse appropriate.

## Minimum Phase I4 asset set

Before declaring the V.21/Bell-103-class PHY complete, obtain at least:

1. one calling-modem hardware capture;
2. one answering-modem hardware capture;
3. one independent software-generated vector;
4. one noisy/attenuated hardware capture;
5. one frequency-offset vector;
6. one sample-clock-offset vector;
7. non-modem negative-control audio.

For V.22bis add:

- complete handshake capture;
- 1200-bit/s and 2400-bit/s data captures;
- retrain capture;
- rate fallback capture;
- equalizer-stressing line capture.

For V.32bis add:

- ANS/AA/AC startup capture;
- S/TRN/rate-exchange sequence capture;
- 4800/9600/14400 negotiated sessions where supported;
- forced retrain;
- echo-path variation.

## Capture file layout

```text
test-assets/
  manifest.json
  owned/
    <standard>/
      <capture-id>/
        rx.wav
        tx.wav
        stereo-line.wav
        metadata.json
        expected.json
  generated/
    manifest.json
  external-references/
    manifest.json
```

Binary assets should only enter `owned/` or `generated/` after provenance review.

## Metadata schema draft

```json
{
  "id": "v21-hw-001",
  "standard": "V.21",
  "direction": "duplex",
  "ownership": "project-captured",
  "source_device": {
    "vendor": "",
    "model": "",
    "chipset": "",
    "firmware": ""
  },
  "audio": {
    "sample_rate_hz": 48000,
    "channels": 2,
    "sample_format": "S16LE"
  },
  "expected": {
    "connect_rate": 300,
    "payload_sha256": ""
  },
  "provenance": {
    "captured_by_project": true,
    "redistributable": true
  }
}
```

## Decision

The project will create its own commercial-hardware capture corpus as the primary independent regression source. Unclear third-party WAVs remain research references only. SpanDSP/minimodem are independent test oracles, not automatically vendored test dependencies.
