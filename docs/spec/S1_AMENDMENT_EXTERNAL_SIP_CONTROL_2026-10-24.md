# S1 amendment for external SIP server control

Date: 2026-10-24  
Status: accepted architectural direction from the project owner; implementation deferred.

## Decision and scope

AndroidDialup will control a **user-controlled server external to the phone** that owns SIP call handling and the dial-up modem/data bridge. Android is the control UI and, where retained, a modem-style DTE/AT and transparent byte-relay endpoint. It is **not** the modem waveform engine over the phone's cellular voice/baseband path.

The phone reaches the server over Wi-Fi or cellular **packet data**. LTE/5G data here is ordinary IP connectivity, not a cellular voice call. This addendum records direction only: Androidialup remains far down the implementation priority queue. It authorizes no application implementation, server deployment, software installation, live calls, purchases or credential changes.

## Responsibility boundary

```text
Android control UI
  + optional existing computer-facing DTE/AT and byte forwarding
        |
        | authenticated, encrypted IP control and optional byte stream
        v
User-controlled external server
  control/session service
    -> SIP call-control component
    -> explicit modem engine and byte/media bridge
        |
        | chosen and qualified telephony/media path
        v
Remote modem and its terminal, BBS or dial-up access endpoint
```

The server owns dial/answer/hangup execution, call progress, backend lifecycle and modem termination. Android requests actions and displays server-reported state; it does not inject modem tones into cellular calls. Existing DTE/AT state and wire contracts remain unchanged until a separately specified migration.

A SIP registrar, proxy or PBX alone is not the required data bridge. The server needs a concrete modem implementation that converts the relayed bytes to and from the chosen modem/telephony interface. Asterisk may supply SIP routing/control, but selecting Asterisk does not select or validate that modem implementation. The control-service interface, and any internal Asterisk AMI/ARI integration, remain undecided. No raw PBX administration endpoint is to be exposed as the phone's unauthenticated control API.

Retain the existing TLS, certificate-validation, per-device authentication, bounded-queue and redacted-diagnostics requirements. Keep SIP-trunk/provider secrets on the server; the phone should receive only scoped control access. A SIP answer or RTP stream alone must not produce a modem `CONNECT`: success requires the selected backend's actual modem/session establishment evidence.

## Why this changes the selected path

The owner's concern is that cellular speech compression, limiters and other voice processing can destroy modem waveforms. The existing cellular-codec research already separates packet data from cellular voice. This decision avoids depending on cellular-call audio access or waveform survival; it is not a claim that every possible data-over-voice experiment is mathematically impossible.

SIP controls session establishment rather than guaranteeing modem media or payload delivery ([RFC 3261](https://datatracker.ietf.org/doc/html/rfc3261)). Asterisk bridges can transcode or manipulate media, or route it directly depending on bridge type ([Asterisk Bridges](https://docs.asterisk.org/Fundamentals/Key-Concepts/Bridges/)). As a concrete vendor example, Cisco's modem-passthrough documentation treats G.711, voice-processing controls, packet loss and jitter handling as additional requirements, not consequences of SIP alone ([Cisco Modem Passthrough over VoIP](https://www.cisco.com/c/en/us/td/docs/ios-xml/ios/voice/cube/ios-xe/config/ios-xe-book/voi-sipsip-extded-sbc.html)).

## Decisions still required before implementation

- **Endpoint and equipment:** identify the actual remote modem/access service and originating modem engine. Choose hardware modem plus appropriate analog interface/ATA/line, a server-side softmodem with a documented data interface, or a compatible modem-relay arrangement. A hardware modem's serial connection alone does not supply a telephone line. A local controlled lab and a SIP/PSTN trunk are distinct topologies; neither is automatically selected here.
- **Protocols:** choose and license-review the modem implementation and supported V-series rates; distinguish terminal/BBS bytes from PPP/SLIP dial-up networking. Define where any PPP session and IP routing terminate. Do not imply that dialing a modem automatically gives Android Internet access.
- **Control and data contracts:** define the narrow server control adapter and any needed byte-stream changes, preserving existing call IDs, authentication, flow control and result-code semantics. Do not silently replace ADUP with raw SIP.
- **Qualified media path:** document both endpoints and every ATA, PBX/SBC and provider leg. For waveform passthrough, start with a G.711 PCMU/PCMA candidate, exclude unqualified speech-codec transcoding, and verify echo cancellation, VAD/DTX, silence suppression, comfort noise, gain/limiting, packet loss/PLC, jitter buffering, resampling and clock drift. Negotiated G.711 at one leg does not prove end-to-end transparency. V.150.1 relay or V.152 support must be established for the actual endpoints, not inferred from a product label.

## Hardware acceptance and retained history

When implementation is separately prioritized and authorized, qualify a controlled end-to-end hardware path before making interoperability claims: modem negotiation, byte-exact bidirectional binary transfer, sustained flow control, hangup/reconnect and failure reporting. Record equipment/firmware, modem mode/rate, actual codec/media route, processing settings and measured loss/jitter. Loopback tests and a successful SIP call are not hardware acceptance.

This amendment takes precedence only for the selected product architecture over conflicting phone-side modem or cellular-voice aspirations in [the Beta architecture](BETA_0_1_ARCHITECTURE.md), [S1 freeze section 10.1](S1_SPEC_FREEZE.md), [gateway section 4b](S1_GATEWAY_BACKEND.md) and [the project plan's I4/I5/X1 tracks](../PROJECT_PLAN.md). The previous no-user-hardware hosted-trunk assumption is no longer mandatory; the equipment decision above is open. Existing IP relay, DTE/AT, security and implementation evidence remain applicable.

The local-softmodem, PCM/VBD, cellular-voice/CVDM and other research documents and code are preserved as historical or deferred alternatives, **not deleted and not active work authorization**. This amendment does not add a mode identifier, change wire bytes, declare SIP/modem support implemented or establish hardware acceptance.
