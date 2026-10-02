# Phase R1 Primary Standards Matrix

Status: active research document. This matrix records exact standards/versions and the implementation questions they control. A row marked `INDEXED` means the official edition/status has been verified. `DEEP-READ` means implementation-relevant clauses have also been extracted.

## ITU-T modem and fax standards

| Standard | In-force edition verified | Status | Implementation relevance | Phase R1 notes |
|---|---:|---|---|---|
| V.8 | 11/2000 | DEEP-READ | Session startup and modem capability negotiation | Official free PDF. Key clauses: 4 overview; 5 coding; 6 code tables; 7 CI/ANSam/CM/JM; 8 call/answer startup. CM is V.21 low-channel at 300 bit/s. This means a V.8 implementation depends on a working V.21 primitive and modem connect-tone detector before higher-rate PHYs. |
| V.21 | 11/1988 | INDEXED | 300 bit/s duplex FSK | Candidate first standards-based softmodem PHY and prerequisite for V.8/fax control-channel work. Official ITU page verified. Full clause extraction pending. |
| V.22 | 11/1988 | INDEXED | 1200 bit/s duplex | First PSK-class expansion after FSK baseline. Official ITU page verified. Full clause extraction pending. |
| V.22bis | 11/1988 | INDEXED | 2400 bit/s duplex | Adds 2400 bit/s mode using frequency division. Official free PDF exists. Full clause extraction pending. |
| V.32 | 03/1993 | INDEXED | Duplex to 9600 bit/s | Requires echo cancellation, adaptive equalization and trellis/QAM-era receiver work. |
| V.32bis | 02/1991 | INDEXED | Duplex to 14.4 kbit/s | High-complexity pre-V.34 target. Official ITU page verified. |
| V.34 | 02/1998 family / in-force ITU edition already inventoried | INDEXED | Duplex to 33.6 kbit/s | Requires extensive training, symbol-rate negotiation, nonlinear encoding/mapping, equalization and echo cancellation. Not a Beta 0.1 PHY. Clause extraction pending. |
| V.90 | 09/1998 | INDEXED | Digital/analog pair, downstream to 56 kbit/s | Architecture is asymmetric and presumes a digital network-side modem plus analog subscriber side; cannot be treated as simply “faster V.34.” Official free PDF verified. |
| V.92 | 11/2000 + Amd 1 (07/2001), Amd 2 (03/2002), Corr 1 (07/2003) | INDEXED | Enhancements to V.90 | Preserve as later standards-expansion target. |
| V.42 | 03/2002 + Corr 1 (07/2003) | INDEXED | LAPM/error-correcting procedures | Sits above the physical modem and below V.42bis compression. Must remain modular so relay mode can terminate/re-originate error control independently at gateways where required. |
| V.42bis | 01/1990 | INDEXED | DCE data compression with error correction | Compression layer; only meaningful once error-controlled link behavior is correct. |
| V.150.0 | 01/2003 | INDEXED | MoIP architectural foundation | Defines architectural context for modem-over-IP. Official free PDF verified. |
| V.150.1 | 01/2003 incl. Corr 1, plus later amendments | DEEP-READ | Modem relay and VBD interworking | Central long-term IP architecture. Clauses 8-9 split VBD and modem relay. Clause 25 + Annex B define IP transport and SPRT. Annex C defines state signalling events; Annex E SDP. |
| V.152 | 09/2010 | DEEP-READ | Voice-band data over IP as encoded audio | Central timed-PCM/VBD reference. Clause 6 mandates RTP carriage of VBD samples, constant end-to-end latency, no VAD/comfort noise during data, minimal-distortion codec, and G.711 A-law + mu-law support. RFC 2198 redundancy and RFC 5109 FEC are options. |
| V.250 | 07/2003 + Supplement 1 (06/2001) | INDEXED | AT-style asynchronous dial/control | Baseline command/control reference for Android DTE interface. Full command-by-command matrix remains a Phase S1 deliverable. |
| T.30 | 09/2005 + Amd 1 (01/2007) | INDEXED | Group 3 fax procedures on GSTN | Separate fax feature track; useful because V.21 control-channel behavior and modem/fax tones provide independent PHY test material. |
| T.38 | 11/2015 | INDEXED | Real-time G3 fax over IP | Separate fax relay path. Do not conflate with generic modem relay. Official free PDF verified. |

## IETF transport/session standards

| RFC | Role | Phase R1 decision |
|---|---|---|
| RFC 3550 | RTP/RTCP | Use as the normative timing/sequence model for PCM/VBD experiments. RTP does not itself provide reliable delivery. |
| RFC 3261 | SIP | Optional gateway/PBX signalling integration; not required for the first custom relay control plane. |
| RFC 3264 | SDP Offer/Answer | Needed if interoperating with SIP/V.152 gateways. V.152 explicitly uses this model. |
| RFC 8866 | SDP | Current SDP syntax reference; supersedes RFC 4566. V.150.1/V.152 historical text references earlier SDP editions, so interoperability profiles must distinguish historical normative references from current parser support. |
| RFC 8489 | STUN | NAT mapping/connectivity discovery only; not sufficient by itself for mobile CGNAT. |
| RFC 8445 | ICE | Candidate peer-to-peer path selection when direct connectivity is attempted. Not needed for relay-first Beta 0.1. |
| RFC 8656 | TURN | Standard relay option. Architecture remains relay-first because LTE/5G clients frequently sit behind CGNAT. |
| RFC 2198 | RTP redundancy | Optional V.152 protection against short packet-loss bursts. Adds bandwidth/latency; test before enabling. |
| RFC 5109 | Generic RTP FEC | Optional V.152 protection. XOR-based generic FEC; test independently from redundancy and from modem-specific loss concealment. |
| RFC 4733 | Telephony/modem/fax event transport | Referenced by V.152 for in-band-event signalling. Needed only for standards-oriented V.152/SIP interoperability, not the first private PCM tunnel. |
| RFC 9000 | QUIC | Candidate for later Wi-Fi/cellular path migration because connection identity can survive address changes. Do not force QUIC into Beta 0.1 before basic relay behavior is proven. |

## Architecture-changing findings from the primary documents

### 1. V.152 is stricter than “send WAV over UDP”

V.152 clause 6 requires voice-band samples to be carried using RTP and requires a VBD mode with constant end-to-end latency, a codec suitable for modem/fax waveforms, VAD/comfort-noise disabled during data, and no speech-oriented DC-removal behavior that damages the signal. Interoperability requires G.711 A-law and mu-law capability. Therefore our `PCM_VBD` experiment can begin as a private framed transport, but a claim of V.152 compliance requires a distinct standards profile using RTP, negotiation, media-state transitions, and the mandatory codec behavior.

### 2. V.150.1 is not just reliable byte relay

V.150.1 clause 25 requires a transport that is reliable, packet preserving, low-latency, sequenced where needed, supports expedited delivery and flow control, and can transition relative to RTP media. The defined default is SPRT over UDP. Annex B defines four transport channels: acknowledgement-only, reliable sequenced data, expedited reliable sequenced control/signalling, and unreliable sequenced data. A generic TCP tunnel can prove the Android DTE/session architecture but is not V.150.1 modem relay.

### 3. V.8 should be implemented after V.21 primitive validation

The V.8 CM signal is transmitted at 300 bit/s using the V.21 low-band channel. That establishes a clean implementation dependency: validate V.21 TX/RX and answer-tone detection first, then implement V.8 negotiation/state machinery. This also gives better independent testability than implementing V.8 and its PHY together.

### 4. V.90 is a system architecture, not only a modulation upgrade

The V.90 title itself specifies a digital-modem/analogue-modem pair and asymmetric rates. The project must not promise V.90 interoperability until the gateway side can provide the required digital-network-side behavior. A hardware modem or suitable digital telephony gateway may be the fastest path for V.90-era interoperability experiments.

## Remaining primary-standard work before Phase R1 exit

- Extract exact V.21 mark/space frequencies, channel roles, tolerances and receiver criteria.
- Extract V.22/V.22bis carrier frequencies, symbol rates, constellation/coding/training requirements.
- Extract V.32/V.32bis startup, echo-canceller training, constellation and trellis details.
- Deep-read V.34 and create a module-level dependency graph before any implementation commitment.
- Deep-read V.42 LAPM frame/state/timer rules and V.42bis dictionary rules.
- Create V.250 command matrix and identify the exact minimum Beta command subset.
- Extract T.30/T.38 only to the depth needed for the independent fax track.
- Review all in-force amendments/corrigenda that affect packet formats or interoperability for V.150.1/V.152.

## Primary URLs

- https://www.itu.int/rec/T-REC-V.8/en
- https://www.itu.int/rec/T-REC-V.21/en
- https://www.itu.int/rec/T-REC-V.22
- https://www.itu.int/rec/T-REC-V.22bis
- https://www.itu.int/rec/T-REC-V.32/
- https://www.itu.int/rec/T-REC-V.32bis/
- https://www.itu.int/rec/T-REC-V.34
- https://www.itu.int/rec/T-REC-V.42
- https://www.itu.int/rec/T-REC-V.42bis
- https://www.itu.int/rec/T-REC-V.90/en
- https://www.itu.int/rec/T-REC-V.92/
- https://www.itu.int/rec/T-REC-V.150.0/en
- https://www.itu.int/rec/T-REC-V.150.1
- https://www.itu.int/rec/T-REC-V.152
- https://www.itu.int/rec/T-REC-V.250
- https://www.itu.int/rec/T-REC-T.30/
- https://www.itu.int/rec/T-REC-T.38/e
- https://www.rfc-editor.org/rfc/rfc3550.html
- https://www.rfc-editor.org/rfc/rfc3264.html
- https://www.rfc-editor.org/rfc/rfc8866.html
- https://www.rfc-editor.org/rfc/rfc2198.html
- https://www.rfc-editor.org/rfc/rfc5109.html
