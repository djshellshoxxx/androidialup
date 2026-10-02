# Asterisk vs FreeSWITCH/SpanDSP — Phase R1

## Question

Should the first AndroidDialup gateway be built around Asterisk, FreeSWITCH, or a small purpose-built relay daemon?

## Asterisk findings

Current Asterisk source includes a SpanDSP-backed fax resource module (`res/res_fax_spandsp.c`). It supports:

- G.711/audio fax operation;
- T.38 terminal operation;
- T.38 gateway operation;
- V.21 detection;
- SpanDSP T.30/fax state handling;
- Asterisk `AST_FRAME_MODEM` frames for T.38 packet transport.

The module processes 160-sample blocks at 8 kHz (20 ms media blocks), a useful reference point for packetized voiceband-data experiments.

Asterisk therefore demonstrates mature PBX integration of SpanDSP, G.711 audio, V.21 detection and T.38 switching. It is valuable for fax and VBD experiments.

It does **not** present itself as a general-purpose V.21/V.22/V.32/V.34 data-modem DTE endpoint. Its public integration here is focused on fax/T.30/T.38.

Asterisk also has configurable jitter-buffer behavior. That is useful as a comparison/reference for ordinary voice handling, but modem VBD cannot blindly inherit speech-oriented jitter interpolation or comfort-noise assumptions. V.152 requirements remain authoritative for modem waveform transport.

## FreeSWITCH findings

FreeSWITCH contains `mod_spandsp_modem.c`, which is more directly useful to this project because it constructs PTY/serial-like modem endpoints and drives SpanDSP's T.31 softmodem state. The module has explicit modem call states including ONHOOK, OFFHOOK, RINGING, ANSWERED, DIALING and CONNECTED.

That makes FreeSWITCH a better reference for the DTE/DCE side of a software modem lab.

## Purpose-built relay daemon

For Beta 0.1, the project still benefits from avoiding either full PBX framework in the critical path.

A small Linux service can provide:

```text
Android relay protocol
      |
session/authentication layer
      |
serial backend
      |
real USB/RS-232 modem
```

Advantages:

- minimal moving parts;
- deterministic DTE byte path;
- easier packet tracing;
- direct AT command passthrough/mapping;
- simplest way to prove Wi-Fi and LTE/5G transport independently of softmodem DSP;
- no PBX transcoding or media policy accidentally affecting modem behavior.

## Recommended gateway roles

### Beta 0.1 primary

`androidialup-gatewayd` — purpose-built lightweight relay with real hardware modem backend.

### Lab backend A

FreeSWITCH + SpanDSP — PTY softmodem, T.31/T.38 and modem-state reference environment.

### Lab backend B

Asterisk + SpanDSP — G.711/T.38/V.21/fax and PBX media-path experiments.

### Later

Direct SpanDSP library host without a PBX, if the project chooses to link/port mature DSP components for data modem operation.

## Interoperability test matrix

The gateway test suite should eventually test equivalent media through:

1. direct PCM loopback;
2. Android private PCM/VBD relay;
3. Asterisk G.711 bridge;
4. FreeSWITCH G.711 bridge;
5. V.152-compatible RTP path;
6. T.38 path for fax-only tests;
7. real hardware modem PSTN path.

This separates DSP defects from PBX/media defects.

## Licensing note

Asterisk source is GPLv2. Treat its implementation as a reference unless project licensing deliberately accepts GPL implications. FreeSWITCH's relevant module is MPL 1.1 while SpanDSP library code is LGPL 2.1, with test/support code including GPL components. Exact-file licensing must be reviewed before copying or linking anything.

## Decision

Asterisk does not replace the Beta relay server. It becomes an interoperability/reference environment. FreeSWITCH remains the stronger softmodem lab reference, while the hardware-modem relay daemon remains the shortest path to a working Beta connection.
