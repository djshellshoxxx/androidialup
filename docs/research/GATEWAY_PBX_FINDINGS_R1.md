# Phase R1 PBX / Gateway Findings

## FreeSWITCH + SpanDSP

Source-level inspection shows FreeSWITCH `mod_spandsp` includes actual soft-modem support, not only fax send/receive helpers.

Relevant files:
- `src/mod/applications/mod_spandsp/mod_spandsp_modem.c`
- `src/mod/applications/mod_spandsp/mod_spandsp_modem.h`
- `src/mod/applications/mod_spandsp/mod_spandsp.c`

### Important implementation findings

`mod_spandsp_modem.c` is explicitly a T.31 soft modem. On POSIX systems it creates a PTY master/slave pair and exposes the slave through a device link. It initializes a SpanDSP `t31_state_t`, connects AT-command output to the PTY, provides call-control callbacks, and also exposes a T.38 core. Its modem state machine includes INIT, ONHOOK, OFFHOOK, ACQUIRED, RINGING, ANSWERED, DIALING, CONNECTED and HANGUP.

This proves a practical architecture pattern for AndroidDialup's Linux-side gateway:

`Android relay session <-> gateway service <-> modem abstraction <-> PBX/PSTN/media`

with a PTY/serial-style control plane available to ordinary modem-aware software.

### License

The inspected FreeSWITCH modem module source carries MPL 1.1 headers. SpanDSP core remains LGPL 2.1, with some tests/support code under GPL. Any reuse must preserve exact component boundaries and notices.

## Gateway architecture decision

For Beta work, implement two gateway backends behind the same interface:

### Backend A: hardware serial modem

Fastest route to real legacy-modem interoperability.

```
GatewayBackend
  dial(number)
  answer()
  hangup()
  writeDte(bytes)
  readDte() -> bytes
  setControlLines(...)
  getStatus()
```

Implementation uses a Linux USB/RS-232 modem and a dedicated serial worker. This backend does not require PBX DSP integration to prove the end-to-end Android-over-IP architecture.

### Backend B: FreeSWITCH/SpanDSP research backend

Use as a lab/reference path for:
- virtual PTY modem behavior
- T.31 fax-modem integration
- T.38 experiments
- telephony media routing
- comparison against our AT/call-state abstraction

Do not make FreeSWITCH a mandatory AndroidDialup server dependency for Beta 0.1. It is too large to require merely for byte relay or a USB hardware-modem backend.

## Why both backends matter

The hardware backend gives the shortest path to proving that an Android DTE can drive a real modem call over Wi-Fi/LTE packet data.

The FreeSWITCH/SpanDSP backend gives a mature software reference for modem/PTTY/call-control design and a platform for later SIP/T.38/VBD work.

## Asterisk finding

Asterisk documentation and source ecosystems are strong for fax detection and T.38 handling, but current Phase R1 evidence does not show a comparably direct general-purpose data-modem PTY endpoint. Therefore Asterisk remains useful for SIP/PSTN interoperability experiments, but FreeSWITCH/SpanDSP is the stronger software-modem research target for this project.

## Beta gateway recommendation

Primary Beta gateway:

`Linux + custom lightweight relay daemon + USB hardware modem`

Secondary lab gateway:

`Linux + FreeSWITCH + SpanDSP`

Later production-capable gateway options can add SIP trunk/FXO/PRI interfaces behind the same `GatewayBackend` contract.

## Required experiments

1. Connect a known USB modem to Linux and verify dial/answer/data/result-code behavior through a minimal serial daemon.
2. Measure DTE byte latency across Android -> relay -> gateway under Wi-Fi and LTE.
3. Install FreeSWITCH with modem support and confirm PTY device creation.
4. Exercise AT commands through the FreeSWITCH-created PTY and capture state transitions.
5. Capture 8 kHz media during a software-modem/fax session for regression analysis.
6. Verify whether SIP providers/ATAs used for testing preserve V.21/V.22bis waveforms under G.711 with VAD/AEC disabled.
7. Keep T.38 experiments separate from generic data-modem transport because T.38 terminates fax protocol semantics rather than preserving arbitrary modem sessions.
