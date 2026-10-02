# Phase R1 Deep Dive: V.150.1 Modem Relay vs V.152 Voice-Band Data

## Purpose

This document decides how AndroidDialup should separate modem relay from waveform transport. The two modes solve different problems and must not share a falsely simplified protocol abstraction.

## V.150.1

Official source: ITU-T V.150.1 (01/2003), including incorporated corrigenda, plus later amendments.

The standard defines gateway-to-gateway interworking for V-series DCEs across IP. It explicitly supports both transparent modem-signal transport and termination of modem signals at gateways with data relayed between them.

### Relevant clause map

- Clause 6: MoIP gateway functions
- Clause 7: audio mode
- Clause 8: voice-band data mode
- Clause 9: modem relay mode
- Clauses 11-14: PHY/error-control/trans-compression/data-transfer behavior in relay mode
- Clause 15: gateway protocol definitions/messages
- Clauses 19-22: call setup, discrimination, transport switching and modem-relay operation
- Clause 25: IP transport
- Annex B: SPRT
- Annex C: State Signalling Events
- Annex E: SDP description for SPRT modem relay

### Transport requirements

Clause 25 requires the MoIP transport to be reliable, duplex, packet preserving, identifiable relative to RTP, error detecting/correcting, non-corruptive/non-erasing/non-duplicating, able to provide sequenced and expedited delivery, low latency, flow control and lightweight behavior.

The default defined transport is Simple Packet Relay Transport (SPRT), encapsulated in UDP/IP.

### SPRT architecture

SPRT uses distinct transport channels rather than pretending all modem data has identical delivery requirements:

- TC0: unreliable, unsequenced; acknowledgement use
- TC1: reliable, sequenced; data
- TC2: expedited, reliable, sequenced; control/signalling
- TC3: unreliable, sequenced; ordered data that does not require reliable delivery

The header carries a SubSession ID, payload type, transport-channel identifier, sequence number and acknowledgement information. RTP and SPRT may share IP/UDP addressing when payload-type values distinguish them.

### AndroidDialup consequence

Our early `BYTE_RELAY` mode can use a conventional reliable stream to prove the application architecture, but it must be explicitly named a private Beta transport and must not be called V.150.1. A standards-oriented modem-relay module will need:

- modem/media state signalling
- gateway capability exchange
- call discrimination
- SPRT channels and acknowledgement/retransmission behavior
- DCE-side modem termination behavior
- correct treatment of V.42/error control and compression across the gateway boundary
- SDP/SIP/H.245/H.248 interop only where those environments are targeted

## V.152

Official source: ITU-T V.152 (09/2010) plus Implementors Guide.

V.152 transports voice-band data as encoded audio over packet networks. It is therefore the standards reference for preserving the modem waveform rather than terminating the modem at the gateway.

### Relevant clause map

- Clause 6: definition of VBD mode
- 6.1: minimum requirements
- 6.2: echo canceller behavior
- 6.3: IP transport services
- Clause 7: negotiation/codec selection
- Clause 8: RFC 4733 modem/fax/text events
- Clause 9: VBD stimuli
- Clause 10: audio/VBD state transitions
- Clause 11: optional remote indication of transitions
- Annex B: data-signal detection and silence insertion

### Mandatory media properties

V.152 requires voice-band modulated samples to travel using RTP. During VBD mode an implementation must use a codec that introduces minimal distortion, maintain constant end-to-end latency, disable VAD and comfort-noise generation during data, and disable speech-processing DC-removal behavior that can damage the waveform.

For interoperability, both G.711 A-law and G.711 mu-law must be supported as VBD codecs.

RFC 2198 redundancy and RFC 5109 FEC are optional extensions, not baseline mandatory behavior.

Echo cancellation is independent of VBD state. A compliant implementation does not have to provide echo cancellation; if present on the VBD path, it must follow the referenced G.168 requirements.

### AndroidDialup consequence

The initial PCM experiment should intentionally separate two profiles:

`PCM_VBD_PRIVATE`
- controlled AndroidDialup framing
- sequence number
- sample timestamp
- 8 kHz mono PCM payload
- explicit diagnostics
- impairment simulator
- no compliance claim

`V152_RTP`
- RTP framing
- negotiated VBD codec/payload type
- PCMA/PCMU interoperability
- audio-to-VBD media-state transitions
- speech enhancement disabled
- constant-latency playout target
- optional RFC 2198 and RFC 5109 protection after baseline testing
- event signalling where required

This separation lets us prototype quickly without creating a protocol that later prevents standards compliance.

## Decision matrix

| Situation | Preferred mode | Reason |
|---|---|---|
| Both gateways controlled and can demodulate legacy modem | V.150.1-style modem relay | Removes continuous waveform sensitivity to jitter, packet loss and sample-clock error. |
| Need transparent path to arbitrary legacy analog modem behind a gateway | V.152-style VBD | Preserves modem waveform end-to-end across the IP segment. |
| First Beta proof of Android network/session architecture | Private byte relay | Lowest implementation risk; validates DTE, network selection, auth/session, CGNAT relay. |
| First Beta proof of waveform transport | `PCM_VBD_PRIVATE` | Allows instrumentation before standards signalling complexity. |
| SIP/PBX standards interop | V.152 or V.150.1 profile | Use actual standards negotiation and media transitions. |
| Cellular voice bearer | Neither directly guarantees success | Speech codecs such as AMR/EVS are a different hostile channel and require empirical characterization. |

## Packet-loss strategy

For waveform mode, ordinary speech packet-loss concealment cannot be assumed safe. Modem signals are phase/timing sensitive. Phase R1/S1 experiments must compare:

1. no protection
2. packet repetition/hold only as a negative-control strategy
3. RFC 2198 temporal redundancy
4. RFC 5109 FEC
5. modem-aware erasure treatment

Metrics:
- recovered bit error rate
- retrain count
- carrier loss
- equalizer divergence
- negotiation failure rate
- latency/jitter-buffer occupancy

Do not select FEC/redundancy solely on audio quality.

## Clock-drift strategy

V.152 requires constant end-to-end latency, but independently clocked audio endpoints drift. AndroidDialup therefore needs a slow sample-clock control loop around the jitter buffer.

Proposed experiment-level controller:

```text
state:
    target_fill_samples
    filtered_error
    resample_ratio = 1.0

for each control interval:
    error = jitter_buffer.fill_samples - target_fill_samples
    filtered_error = LPF(filtered_error, error)
    correction_ppm = clamp(Kp * filtered_error + Ki * integral(error), -MAX_PPM, +MAX_PPM)
    resample_ratio = 1.0 - correction_ppm * 1e-6
    feed ratio to high-quality fractional resampler

if underflow/overflow or correction saturates persistently:
    raise CLOCK_UNLOCK diagnostic
    request controlled buffer re-centering or session retrain depending mode
```

Constants are deliberately not frozen in Phase R1. They must be measured against Android device clocks and gateway hardware.

## Phase R1 decision

Beta 0.1 architecture remains multi-mode:

1. `BYTE_RELAY` for fast functional end-to-end proof
2. `PCM_VBD_PRIVATE` for waveform transport experimentation
3. later `V152_RTP` standards profile
4. later V.150.1/SPRT modem-relay profile

This keeps the Android-facing modem interface stable while allowing the network-side mechanism to evolve from simple relay to waveform transport or true modem relay without replacing the DTE layer.
