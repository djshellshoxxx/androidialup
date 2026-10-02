# Phase R2 Deep Dive: Cellular Voice Codecs, the Voice-Path Processing Chain, and Modem Signals

Status: research reference for Phase X1. Nothing here changes the Beta 0.1 decision that
packet data is the primary bearer and cellular voice is an isolated experimental track.
It adds constraints, a mechanism model, an evidence record and a ranked path.

Evidence classes follow `docs/MASTER_RESEARCH_BRIEF.md`: PRIMARY, STRONG SECONDARY,
SECONDARY, ANECDOTAL. Every numbered source is in `SOURCE_INVENTORY.md` with a `CEL-nnn`
(or earlier `PAP-/STD-/AND-/OSS-`) ID. Statements marked **[INFERRED]** are the author's
engineering reasoning from PRIMARY facts and are not themselves sourced claims; they are
inputs to experiments, not conclusions.

Access note: during this research pass the 3GPP/ETSI/ITU/IETF document hosts and most
publisher sites were not fetchable from the research container. Numbers quoted from those
documents were confirmed through indexed excerpts of the documents (clause text as surfaced
by search) and, where available, mirrors/secondary descriptions. Where a figure could only be
taken from a secondary description of a primary document, the row in the inventory says so
("via excerpt"). Phase X1 must re-verify every such number against the downloaded spec
before it becomes a pass/fail threshold.

---

## 0. Executive summary

1. Every cellular voice path in service today (GSM-FR/HR/EFR, AMR-NB, AMR-WB, EVS) is a
   parametric linear-prediction speech coder operating on 20 ms frames (plus look-ahead)
   with a short-term LPC envelope, a long-term/pitch predictor and a sparse pulse (or
   RPE/VSELP) excitation. None of them is a waveform coder. PRIMARY (CEL-002, CEL-006,
   CEL-007, CEL-019, CEL-020, CEL-021).
2. There is **no PRIMARY or STRONG SECONDARY evidence that any ITU-T V-series or Bell data
   modem mode (Bell 103, V.21, V.22, V.22bis, V.23, V.32, V.32bis, V.34) or Group 3 fax
   operates reliably through a GSM/UMTS/VoLTE/VoNR speech codec.** The literature that
   addresses data over cellular voice unanimously starts from the observation that
   conventional modem waveforms do not survive and designs speech-like or codec-aware
   waveforms instead (PAP-001, PAP-002, PAP-003, PAP-004, CEL-048, CEL-050, CEL-051).
   The strongest negative evidence is institutional: 3GPP created CTM (TS 26.226) because
   even 45.45 baud Baudot FSK text telephony was unreliable through the GSM speech codecs,
   and the FCC moved US accessibility from TTY to IP real-time text because Baudot tones over
   VoLTE/IP suffer "compression techniques that distort TTY tones" (CEL-011, CEL-034).
3. The GSM system itself never passed modem or fax waveforms through its speech codec. Data
   and fax used circuit-switched data bearers with a modem bank in the network interworking
   function (TS 29.007, TS 43.045): the network did modem relay. That architecture is the
   historical proof that "demodulate at the edge, carry bits, remodulate at the other edge"
   is the right model for cellular voice (CEL-017, CEL-018, CEL-068).
4. Documented codec-aware data-over-voice achieves roughly **1.2 kbit/s (Hermes, real GSM
   calls, low BER)**, **~2 kbit/s (Surrey/UniS secure-voice modem, as cited)**, and **up to
   ~4 kbit/s at 2.5 % symbol error rate on GSM-EFR (LaDue et al., as cited)**; CTM delivers
   TTY-equivalent throughput (45.45 bit/s payload class) with r=1/4 convolutional coding and
   interleaving (CEL-048, CEL-052, CEL-050, PAP-001, CEL-011, CEL-053). No comparable
   published numbers were found for EVS or VoNR; that is an open experiment.
5. Recommendation: treat the cellular voice call as **a low-rate, lossy, frame-structured
   byte bearer, never as an analogue line**. The only architecture consistent with the
   evidence is modem relay at the phone: the local softmodem / DTE semantics stay on the
   Android side, and the voice channel carries a codec-aware PHY with FEC and ARQ
   (V.150.1-like in spirit, not in waveform). Packet data remains primary; the voice
   bearer is a fallback/signalling channel, and the IMS real-time-text channel (RFC 4103)
   is a candidate zero-DSP signalling channel on VoLTE/VoNR. Legacy V-series waveform
   passthrough over cellular voice is retained only as a negative-control experiment.

---

## 1. Codec-by-codec analysis

### 1.1 Common structure of the CELP family (why it matters for modems)

All 3GPP speech codecs share a model that is the opposite of a transparent channel:

| Stage | What it does to a modem signal | Evidence |
|---|---|---|
| Pre-processing high-pass (80 Hz AMR-NB; 50 Hz AMR-WB; EVS also high-passes) and scaling | Irrelevant to voice-band modem spectra (300–3400 Hz) but confirms the input is treated as speech. | PRIMARY CEL-002 §5.1, CEL-006 |
| Short-term LP analysis (order 10 NB, 16 WB), coefficients quantised as LSP/ISP once (or twice) per 20 ms frame and interpolated per 5 ms subframe | The spectral envelope is a smooth 10/16-pole fit updated every 20 ms. Narrow tones become broad poles; the envelope changes stepwise every subframe, i.e. the "channel" seen by an equaliser is time-varying on a 5 ms grid. | PRIMARY CEL-002, CEL-006, CEL-007 |
| Long-term (adaptive codebook / pitch) predictor, lag range 18–143 samples at 8 kHz for AMR-NB/EFR (34–231 at 12.8 kHz for AMR-WB), with fractional resolution, gain quantised | Models periodicity in the 56–444 Hz pitch range. A modem carrier (e.g. 1080–2400 Hz) is periodic at a lag far below 18 samples; the predictor locks onto integer multiples and the phase of the reconstructed carrier is whatever the past synthesis buffer happened to hold. Phase/amplitude transitions at symbol boundaries (PSK/QAM) are unpredictable by definition, so they must be represented by the fixed codebook. **[INFERRED]** | PRIMARY CEL-002 (lag ranges, via excerpt), CEL-021 |
| Fixed (algebraic) codebook: 2 (4.75 kbit/s) to 10 (12.2 kbit/s) signed unit pulses per 40-sample subframe (AMR-NB); RPE 13 pulses/subframe (GSM-FR); VSELP vectors (GSM-HR) | The innovation that can represent a symbol transition is a handful of pulses per 5 ms. The reconstruction is "perceptually equivalent", not waveform-equivalent; waveform SNR of CELP for speech is typically low single-digit to ~15 dB segmental, far below what 16-QAM/TCM needs. **[INFERRED from codec structure; the SNR figure is a textbook generalisation, not a measured modem-channel number]** | PRIMARY CEL-002, CEL-019, CEL-020 |
| Gain quantisation (adaptive and fixed codebook gains, scalar or joint VQ, per subframe) | Amplitude of the reconstructed waveform is quantised per 5 ms; amplitude-coded constellations (V.22bis 16-QAM, V.32 and above) lose level information. **[INFERRED]** | PRIMARY CEL-002 |
| Decoder post-processing: adaptive formant + tilt postfilter with AGC (AMR-NB/EFR); AMR-WB and EVS apply post-processing including bass post-filter/high-band generation | Deliberately sharpens spectral peaks and alters the spectral tilt per subframe → additional time-variant linear distortion on top of the quantisation noise. | PRIMARY CEL-002 (postfilter clause), CEL-006, CEL-007 |
| 20 ms frame + look-ahead (5 ms AMR-NB except 12.2, 5 ms AMR-WB; EVS 32 ms algorithmic) | Fixed block delay; parameters are piecewise constant; nothing in the codec preserves inter-frame sample-clock continuity for the receiver on a packet network. | PRIMARY CEL-001, CEL-006, CEL-007/CEL-072 (via excerpt) |

### 1.2 Per-codec table

Figures are from the 3GPP specifications (PRIMARY) unless marked; "via excerpt" means the
clause text was confirmed through an indexed excerpt rather than a direct document read.

| Codec (spec) | Rate(s) kbit/s | Internal model | Frame / look-ahead | Band | Notable stages for modem signals | Evidence |
|---|---|---|---|---|---|---|
| GSM-FR (TS 46.010, "GSM 06.10") | 13 | RPE-LTP: order-8 LPC (LARs), LTP lag 40–120 samples, 4×5 ms subframes, regular-pulse excitation | 20 ms / 0 | 300–3400 Hz | Lowest-fidelity excitation model; LTP lag range 40–120 (66–200 Hz) cannot track carriers directly; no DTX-specific tone handling beyond VAD (TS 46.032 has information-tone detection) | PRIMARY CEL-019, CEL-022; STRONG SECONDARY CEL-057/CEL-058 (bit-exact open implementations) |
| GSM-HR (TS 46.020, "06.20") | 5.6 | VSELP, 4×5 ms subframes | 20 ms | 300–3400 Hz | Lowest bit budget of the family; expected worst case for any waveform **[INFERRED]** | PRIMARY CEL-020 (via excerpt) |
| GSM-EFR (TS 46.060) | 12.2 | ACELP, 244 bits/frame, 4 subframes, 10 pulses/subframe, adaptive postfilter | 20 ms | 300–3400 Hz | Functionally identical to AMR 12.2 (not bit-exact) | PRIMARY CEL-021; PRIMARY CEL-069 (RFC 3267 mode equivalence) |
| AMR-NB (TS 26.071/26.090/26.093/26.094/26.091) | 4.75, 5.15, 5.9, 6.7, 7.4, 7.95, 10.2, 12.2 + SID | ACELP; 80 Hz HP + ÷2 scaling; LP order 10; pitch 18–143 samples; 2–10 pulses/subframe; postfilter | 20 ms / 5 ms (0 for 12.2); 25 ms total | 300–3400 Hz | Mode can change every second frame (40 ms) on GSM (TS 45.009); DTX: 7-frame hangover then SID_FIRST, SID update every 8 frames; PLC = parameter substitution + progressive muting (TS 26.091); 7.4 = IS-641, 6.7 = PDC-EFR | PRIMARY CEL-001–CEL-005, CEL-014, CEL-069 |
| AMR-WB (TS 26.171/26.190/26.193/26.194/26.191; ITU-T G.722.2) | 6.6, 8.85, 12.65, 14.25, 15.85, 18.25, 19.85, 23.05, 23.85 + SID | ACELP at 12.8 kHz internal (16→12.8 kHz decimation, 6.4 kHz LP); 50 Hz HP + pre-emphasis; LP order 16; 6.4–7 kHz band synthesised from random excitation, gain coded only at 23.85 | 20 ms / 5 ms (+0.9375 ms resampling) | 50–6400 Hz coded; 6400–7000 Hz parametric | Nothing above 6.4 kHz carries waveform information in any mode; in the eight lower modes the high band is pure noise synthesis. VoLTE default codec set 0 = {6.6, 8.85, 12.65} (GSMA HD Voice/IR.92) | PRIMARY CEL-006 (via excerpt), CEL-033, CEL-030 |
| EVS Primary (TS 26.441–26.450) | 5.9 (VBR), 7.2, 8, 9.6, 13.2, 16.4, 24.4, 32, 48, 64, 96, 128 | Switched LP (ACELP at 12.8/16 kHz) / MDCT (TCX, HQ) cores chosen by a signal classifier (inactive, unvoiced, voiced, generic, transition, audio); parametric bandwidth extension for SWB/FB | 20 ms; 32 ms algorithmic delay | NB 4 kHz, WB 8 kHz, SWB 16 kHz, FB 20 kHz | **Only codec in the family with a transform core.** TR 26.952 shows EVS at ≥9.6–13.2 kbit/s is significantly better than AMR-WB at any rate for music/mixed content; this is the one place where a *stationary tonal* data waveform may be treated as "audio" rather than "speech". Whether that helps a modem is unmeasured. Channel-aware mode (13.2 WB/SWB) carries partial redundant copies of frame N with frame N+K, K ∈ {2,3,5,7}; DTX with fixed (3–100 frames) or adaptive SID interval | PRIMARY CEL-007, CEL-061, CEL-072, CEL-073, CEL-029, CEL-010 (via excerpt) |
| EVS AMR-WB IO | the 9 AMR-WB rates | AMR-WB bitstream-compatible mode of EVS | 20 ms | WB | Same constraints as AMR-WB; EVS decoder may apply its own post-processing | PRIMARY CEL-007 |
| VoLTE/VoWiFi defaults (GSMA IR.92/IR.51, 3GPP TS 26.114) | AMR all 8 modes and AMR-WB all 9 modes mandatory with source-controlled rate (DTX); EVS mandatory for SWB/FB terminals | — | RTP ptime 20 ms recommended; ≤4 non-redundant frames per packet; maxptime 80 (no redundancy) / 240 ms (with redundancy) | WB typical | DTMF is **not** sent in-band but as RFC 4733 telephone events (Annex G); text is RFC 4103/T.140 with ≥300 ms sampling and up to 200 % redundancy | PRIMARY CEL-010 (via excerpt), CEL-030, CEL-032, CEL-026, CEL-027 |
| VoNR defaults (GSMA NG.114) | EVS mandatory for all 5G voice configurations; AMR-WB IO retained for interworking | — | as TS 26.114 | SWB typical | Same media plane as VoLTE; EVS channel-aware and JBM behaviour apply | PRIMARY CEL-031 (via excerpt) |

### 1.3 Per-codec expectation for classical modulations **[INFERRED unless cited]**

| Waveform class | GSM-FR/HR | EFR / AMR-NB 12.2 | AMR-NB low modes (4.75–7.4) | AMR-WB | EVS (ACELP cores) | EVS (MDCT cores, if selected) |
|---|---|---|---|---|---|---|
| Single/dual tones, slow (DTMF-class, CTM 200 bd 4-FSK) | Tolerated by design: CTM was designed and tested for exactly these codecs (PRIMARY CEL-011, CEL-013) | same | same (CTM targets all GSM codecs) | expected OK | expected OK | expected OK |
| Binary FSK 45–300 bd (Baudot, Bell 103, V.21) | Unreliable: this is the problem CTM exists to solve (PRIMARY CEL-011 scope; CEL-034) | unreliable | worse | unmeasured | unmeasured | unmeasured |
| FSK 1200 bd (V.23, Bell 202) | expect failure (symbol ≈ 6.7 samples; shorter than any codebook pulse spacing resolution the LTP can follow) | expect failure | expect failure | unmeasured | unmeasured | unmeasured |
| DPSK/QAM 600 bd (V.22/V.22bis) | expect failure (phase per symbol not representable) | expect failure | expect failure | expect failure | expect failure | unmeasured |
| TCM/QAM 2400+ bd with echo cancellation (V.32/V.32bis/V.34), fax V.29/V.17 | expect failure; industry reports "compressed codecs like AMR destroy T.30 modem tones" (SECONDARY CEL-063, CEL-062, CEL-064) | expect failure | expect failure | expect failure | expect failure | expect failure |
| Codec-aware speech-like symbols (PAP-001/002/003, CEL-048, CEL-050, CEL-051) | 1.2–4 kbit/s demonstrated (see §3) | best case of the NB family | lower rates | unmeasured | unmeasured | unmeasured |

Rows marked "expect failure" are inferences from codec structure (§1.1) plus the absence of
any positive report; they are **hypotheses for the X1 negative-control experiments**, not
findings.

---

## 2. Voice-path processing beyond the codec

The codec is only one stage. The table lists each stage, what it does to a data waveform,
by which path it is present, and whether the AndroidDialup endpoint can avoid it.

| Stage | Effect on modem signals | GSM/UMTS CS | VoLTE / VoNR | VoWiFi | Avoidable by the app? | Evidence |
|---|---|---|---|---|---|---|
| VAD / DTX / SID comfort noise | A stationary, noise-like (QAM) or non-speech signal may be classified inactive; after a 7-frame hangover the encoder sends SID_FIRST and then one SID per 8 frames (160 ms); the far end synthesises comfort noise. The AMR/GSM VADs include tone-detection branches intended to keep *information tones* active, which is why tone-based schemes (CTM, DTMF) survive and noise-like ones may not. | Present; MS-side DTX is network-controlled | Mandatory to support in IR.92; EVS DTX fixed or adaptive SID interval; network can enable | as VoLTE | No (encoder is in the modem/IMS stack). Mitigation: waveform must look "active" to the VAD. | PRIMARY CEL-003, CEL-004, CEL-022, CEL-073, CEL-030 |
| Acoustic echo control (handset AEC + non-linear processor) | Handset must meet TCLw ≥ 46 dB (handset/headset), i.e. must suppress downlink-correlated uplink content. A full-duplex same-band modem (V.32, V.22 echo-cancelled or FDM close bands) presents uplink energy correlated with downlink → NLP attenuates/clips it. | Present (terminal) | Present | Present | Partly: TTY mode, wired headset/accessory and Bluetooth HFP paths change the acoustic topology but the uplink voice-processing chain on the AP/modem DSP still runs; unknown per device → experiment. | PRIMARY CEL-023; code evidence CEL-045 |
| Network echo canceller (G.168) at MGW/PSTN gateway | Modem answer tone ANSam (2100 Hz with 180° reversals) disables G.165/G.168 cancellers; this protects the PSTN leg but not the handset's acoustic processing. | Present toward PSTN | Present toward CS/PSTN interworking | same | Yes for the network EC (standard tone); no for terminal AEC. | PRIMARY CEL-036 |
| AGC / noise suppression (terminal uplink and sometimes network) | NS treats stationary non-speech spectral content as noise and attenuates it; AGC rescales amplitude. Both are DSP-vendor proprietary ("voice processing", e.g. Qualcomm ECNS chains). | Present | Present | Present | Not from an ordinary app; the AudioEffect NS/AEC/AGC classes control effects on *app capture sessions* (VOICE_COMMUNICATION etc.), not the telephony uplink. `UNPROCESSED` capture source exists for app recording only. | PRIMARY CEL-037, CEL-038; SECONDARY (vendor) search summaries |
| Packet loss concealment | AMR: parameter substitution from last good frame, then progressive muting (TS 26.091); EVS: per-module concealment with fade-to-background (TS 26.447). Either way the receiver output during loss is an *extrapolated speech-like waveform*, not silence: a modem receiver sees plausible but wrong symbols. | Frame erasures on radio (BFI) | RTP loss, late frames | same | No. Must be modelled in the simulator; design with FEC/ARQ. | PRIMARY CEL-005, CEL-008 |
| Jitter buffer management with time-scale modification | TS 26.448 JBM adapts playout with WSOLA on active signal, OLA at low energy, comfort-noise insertion/deletion in DTX, insertion of concealed frames on underflow. This inserts/deletes whole pitch periods → symbol insertions/deletions as Hermes observed on real calls. | Not on CS (circuit timing) | Yes | Yes | No. Receiver must tolerate timing insertions/deletions (sync words, per-burst resync). | PRIMARY CEL-009; STRONG SECONDARY CEL-048 |
| Rate adaptation | GSM AMR: codec mode may change every second frame (40 ms) within the Active Codec Set via in-band CMI/CMR (TS 45.009). IMS: CMR in RTP payload (RFC 4867), mode-change-period/neighbour constraints negotiated in SDP; EVS can switch bit-rate/bandwidth per frame. | Yes | Yes | Yes | No. Design must not assume a constant quantisation fidelity. | PRIMARY CEL-014, CEL-025, CEL-029 |
| Transcoding / tandem | Mobile-to-mobile within one operator may run TFO (in-band, TS 28.062) or TrFO/OoBTC (TS 23.153) and avoid tandem coding. Mobile-to-PSTN or inter-operator calls commonly transcode (e.g. AMR→G.711→AMR or AMR→AMR-WB). Each stage re-applies §1.1. | Yes | Yes (MGW) | Yes | No; only call topology (same operator, both VoLTE) reduces it. | PRIMARY CEL-015, CEL-016; SECONDARY CEL-063 |
| RTP packetisation (RFC 4867, EVS TS 26.445 Annex A) | 20 ms ptime recommended; up to 4 frames/packet; redundancy via re-sending earlier frames (max-red); bandwidth-efficient vs octet-aligned. Affects loss burst structure (one lost packet = 20–80 ms). | n/a | Yes | Yes | No (IMS stack). | PRIMARY CEL-025, CEL-010 |
| Header compression (ROHC profile 0x0001 RTP/UDP/IP, RFC 3095, PDCP TS 36.323) | Transparent to media content; only affects loss statistics (context damage can cause bursts). | n/a | Yes (mandatory for VoIP UEs) | n/a (untrusted Wi-Fi uses IPsec) | No. | PRIMARY CEL-024, CEL-028 |
| Android-side capture/injection | Ordinary apps cannot capture call uplink/downlink (`VOICE_CALL/VOICE_UPLINK/VOICE_DOWNLINK` need `CAPTURE_AUDIO_OUTPUT`, system-only); Android 10+ delivers silence to third-party in-call capture; there is no public API to inject audio into the uplink. System apps with `CAPTURE_AUDIO_OUTPUT`/`CONTROL_INCALL_EXPERIENCE` (e.g. BCR) can record. | Yes | Yes | Yes | Only via (a) system/privileged app on a custom image, (b) external accessory audio (wired headset, USB-C audio, **Bluetooth HFP hands-free unit**), (c) the built-in TTY/CTM path (not programmable). | PRIMARY AND-004, AND-005; STRONG SECONDARY CEL-044, CEL-046, CEL-047 |

### 2.1 What can be disabled and what cannot (summary by path)

| Path | Codec | DTX | Terminal AEC/NS/AGC | PLC | JBM time-scaling | Rate switching | Tandem |
|---|---|---|---|---|---|---|---|
| GSM CS | FR/HR/EFR/AMR (network choice) | network-controlled; cannot be disabled by UE app | cannot be disabled by app | radio erasures | none (circuit) | every 40 ms possible | likely toward PSTN |
| UMTS CS | AMR-NB (AMR-WB optional) | as above | as above | as above | none | per TS 26.103 control | TrFO possible |
| VoLTE | AMR-WB (set 0) / AMR / EVS | mandatory capability, operator-enabled | as above | RTP loss + EVS/AMR PLC | yes | CMR per packet | MGW toward CS |
| VoNR | EVS (mandatory) | as above | as above | as above | yes | per frame | MGW toward CS |
| VoWiFi | as VoLTE (IR.51 reuses IR.92) | as above | as above | more jitter/loss variance | yes | as VoLTE | as VoLTE |

Net: **no stage can be disabled from the application**. The only levers are (1) which
physical audio entry point is used (§6) and (2) the waveform design (§4).

---

## 3. Empirical record

### 3.1 Standards-level and institutional evidence

| Item | Finding | Class | ID |
|---|---|---|---|
| 3GPP TS 26.226 CTM | 3GPP defined a dedicated "Cellular Text Telephone Modem" to carry text telephony *through the speech codec path*, with "an improved modulation technique, including error protection, interleaving and synchronization", explicitly because Baudot/V.18 TTY signalling is not reliable through GSM speech codecs. CTM is 4-level FSK at 200 baud with 200 Hz tone spacing (modulation index 1), a rate-1/4 convolutional code, interleaving and scrambling, and a resynchronisation preamble; the reference implementation (TS 26.230) interworks with 45.45 bit/s Baudot per ITU-T V.18. Minimum performance is specified in TS 26.231. | PRIMARY (spec existence and scope); STRONG SECONDARY (parameter values via CEL-053, CEL-054, de.wikipedia summary) | CEL-011, CEL-012, CEL-013, CEL-053, CEL-054, CEL-079 |
| FCC RTT Report & Order (2016) | TTY on IP networks suffers "susceptibility to packet loss, compression techniques that distort TTY tones, and echo or other noises"; the FCC therefore permitted IP-based RTT (RFC 4103/T.140) in place of TTY on VoLTE. | PRIMARY (regulatory finding) | CEL-034 |
| 3GPP TS 26.114 / AOSP RTT | In IMS, CTM is not used; text is T.140 over RTP (RFC 4103) with ≥300 ms sampling and up to 200 % redundancy; Android implements RTT via `Call.RttCall`/`Connection.RttTextStream` with IMS `@SystemApi`s. DTMF is likewise not in-band but RFC 4733 events. | PRIMARY | CEL-010, CEL-027, CEL-026, CEL-039, CEL-043 |
| GSM circuit-switched data / fax | GSM data (2.4–14.4 kbit/s, HSCSD) and Group 3 fax (TS 43.045) use dedicated channel codings and an interworking function whose modem negotiates V-series rates with the PSTN ("autobauding type 1": the MSC/IWF may select any speed and modem type it can negotiate with the remote modem). The speech codec was never in the fax/data path. | PRIMARY | CEL-017, CEL-018, CEL-068 |
| VoIP/fax industry | Fax/modem over IP requires G.711 with VAD off, EC disabled/handled, low jitter and loss, or T.38 relay; compressed codecs (G.729, GSM, AMR) are reported to destroy T.30 tones; mobile operators transcode AMR to G.711 at the media gateway toward fixed networks. | STRONG SECONDARY (vendor engineering guidance) / SECONDARY | CEL-062, CEL-063, CEL-064 |
| Alarm industry | LTE "dial-capture" communicators demodulate Contact ID (DTMF) / SIA (V.21 FSK) **at the premises** and relay events over cellular data rather than pass the tones through a cellular voice call. | SECONDARY (product documentation; practice inference) | CEL-065, CEL-066 |

### 3.2 Codec-aware data-over-voice results

| System | Channel | Waveform | Result | Class | ID |
|---|---|---|---|---|---|
| Hermes (Dhananjay et al., MobiCom 2010) | Real GSM voice calls, unknown codec/backhaul | Narrow-band "voice-like" modulation with transcoding robust to bit flips, insertions and deletions; adapts parameters to observed BER | ≈1.2 kbit/s goodput with very low BER; explicitly notes VAD and AGC as additional distortions | STRONG SECONDARY (peer-reviewed; abstract-level access only) | CEL-048 |
| LaDue, Sapozhnykov, Fienberg (IEEE TVT 2008) | GSM HR, FR, EFR, AMR (simulated/real per paper) | Codebooks of speech-like symbols | Up to 4 kbit/s at 2.5 % SER on EFR (as cited by later work) | STRONG SECONDARY (figure via citing works) | PAP-001, CEL-077 |
| Shahbazi / Boloursaz et al. (2010, 2013) | GSM AMR (simulated) | Speech-like symbol codebooks; vocoder modelled as a discrete memoryless channel | "Higher data rates and lower SER than previously reported" (numbers not captured) | STRONG SECONDARY | PAP-002, PAP-003 |
| Katugampala, Al-Naimi, Villette, Kondoz (EUSIPCO 2005; UniS/MulSys) | Real GSM voice | Pseudo-speech modulation + 1.9 kbit/s speech coder for end-to-end secure voice | ≈2 kbit/s with 0 % BER reported in follow-on literature; earlier best 1.6 kbit/s | SECONDARY (figures via secondary summaries) | CEL-050, CEL-052 |
| Krasnowski, Lebrun, Martin (2021/2022) | AMR (various rates), Opus-SILK 48 kbit/s, real 3G calls and VoIP | Pseudo-speech "data over voice" with harmonic/speech-like structure; AFSK, PSK, OOK compared | Scheme survives AMR at several rates and Opus 48 kbit/s; bit-rate figures not captured | STRONG SECONDARY (open-access paper; abstract-level access) | CEL-051 |
| Covert channel over cellular voice (arXiv 1504.05647) | Real GSM call from an Android phone with a user-mode rootkit injecting audio | Simple audio modem | 13 bit/s at 0.018 % BER — illustrates how low naïve injection can go | SECONDARY | CEL-049 |
| Huang et al. 2026 (CMC) | Cellular voice (see PAP-004) | Framed/chirp-like signalling with FEC and retransmission | See PAP-004; numbers not re-captured in this pass | STRONG SECONDARY | PAP-004 |
| CTM (3GPP) | All GSM codecs, by specification | 4-FSK 200 bd, r=1/4 FEC, interleaving | TTY-class throughput (45.45 bit/s Baudot payload) with specified minimum performance | PRIMARY | CEL-011, CEL-013 |

**Gaps:** no published measurements were found for data-over-voice through EVS (any mode),
through VoNR, or through VoWiFi; none for AMR-WB specifically beyond Krasnowski's AMR
family tests. These are first-order X1 experiments.

### 3.3 Practical limit table per legacy modem mode

"Limit" is stated as the best-supported statement, not a measured rate.

| Mode | Nominal | Evidence state over GSM/UMTS CS | Over VoLTE/VoNR | Recommended X1 role |
|---|---|---|---|---|
| Baudot 45.45 bd FSK (reference point) | 45 bit/s | Unreliable enough that 3GPP standardised CTM (PRIMARY CEL-011) | FCC: unreliable (PRIMARY CEL-034) | Calibration negative control (Baudot vectors from ITU-T V.18 / CTM test files) |
| Bell 103 / V.21 | 300 bit/s | No positive evidence; expected unreliable **[INFERRED]** | No evidence | Negative control E-04 |
| V.23 | 1200/75 bit/s | No evidence; expected failure **[INFERRED]** | No evidence | Negative control E-04 |
| V.22 / V.22bis | 1200 / 2400 bit/s | No evidence; expected failure **[INFERRED]** | No evidence | Negative control E-05 |
| V.32 / V.32bis | 9.6 / 14.4 kbit/s | No evidence; expected failure; industry fax-over-AMR failure reports (SECONDARY) | No evidence | Negative control E-05 (handshake only) |
| V.34 | ≤33.6 kbit/s | Expected failure (as above) | No evidence | Not worth lab time beyond handshake capture |
| Group 3 fax V.27ter/V.29/V.17 | 2.4–14.4 kbit/s | Industry: fails over compressed codecs; GSM used TS 43.045 adaptor instead | Industry: fails | Negative control E-05 |
| CTM | TTY-class | Designed to work (PRIMARY) | Not used in IMS (RTT instead) | Positive control E-03 |
| Codec-aware DoV | 1–4 kbit/s class (NB codecs, SECONDARY/STRONG SECONDARY) | Unmeasured on WB/EVS | Primary X1 target E-06..E-09 |

---

## 4. Mechanism taxonomy and design rules

### 4.1 Failure mechanisms (M-codes are referenced by the test plan and simulator contract)

| Code | Mechanism | Stage | Signals most affected | Source basis |
|---|---|---|---|---|
| M1 | Spectral-envelope smoothing: LPC order 10/16 updated per 20 ms, interpolated per 5 ms | LP analysis/quantisation | Narrow tones (frequency fine structure), multi-tone with close spacing | PRIMARY CEL-002/006 + [INFERRED] |
| M2 | Periodicity model mismatch: adaptive codebook lag 18–143 (NB) cannot represent carrier phase; phase continuity across subframes is not preserved | LTP | PSK, QAM, TCM; coherent detection generally | PRIMARY lag ranges + [INFERRED] |
| M3 | Sparse innovation: 2–10 pulses per 40 samples (AMR) | Fixed codebook | Any waveform with >1 transition per subframe, i.e. symbol rates ≳ 200 bd | PRIMARY + [INFERRED] |
| M4 | Gain quantisation and AGC/postfilter gain control | Gains, postfilter | Amplitude-coded constellations (16-QAM and up), AM components of QAM | PRIMARY + [INFERRED] |
| M5 | Time-variant linear distortion: postfilter tilt/formant emphasis per subframe | Decoder post-processing | Equaliser-dependent modes (V.22bis+); training sequences | PRIMARY CEL-002 + [INFERRED] |
| M6 | DTX misclassification → SID/comfort noise substitution after 7-frame hangover | VAD/DTX | Noise-like (QAM) and low-level signals; idle marks | PRIMARY CEL-003/004 |
| M7 | Loss concealment outputs plausible speech, not erasure flags | PLC | Everything; receivers cannot detect loss from the audio alone | PRIMARY CEL-005/008 |
| M8 | Timing insertions/deletions by JBM time-scaling and frame-based adaptation | JBM | Symbol timing recovery, long frames without resync | PRIMARY CEL-009; STRONG SECONDARY CEL-048 |
| M9 | Fidelity changes at 40 ms (GSM) / per packet (IMS) due to mode switching | Rate adaptation | Receiver SNR estimators, adaptive thresholds | PRIMARY CEL-014/025 |
| M10 | Tandem coding multiplies M1–M5 and adds a second DTX/PLC | Transcoding | All | PRIMARY CEL-015/016 |
| M11 | Terminal AEC/NLP suppresses uplink energy correlated with downlink | Terminal voice processing | Full-duplex same-band modems (V.32, V.34; V.22 near-band); any simultaneous two-way scheme | PRIMARY CEL-023 + [INFERRED] |
| M12 | Noise suppression attenuates stationary non-speech spectra; AGC rescales | Terminal voice processing | Constant-envelope carriers with stationary spectra | PRIMARY CEL-037/038 (effects exist) + vendor summaries |
| M13 | Band limits: NB codecs 300–3400 Hz; AMR-WB waveform-codes ≤6.4 kHz; EVS SWB/FB high bands are parametric | Codec bandwidth/BWE | Any energy placed above the coded band | PRIMARY CEL-006/007 |
| M14 | Block delay 25–32 ms per codec stage plus jitter buffer: round-trip latency hurts ARQ and V.42-style windows | Framing/JBM | Stop-and-wait ARQ; modems with tight turn-around timers (V.23, half-duplex fax) | PRIMARY CEL-001/007 |

### 4.2 Design rules for a codec-surviving waveform (project "Cellular Voice Data Mode", CVDM)

Each rule states the mechanism it addresses. Rules are **design hypotheses to be validated
in X1**, grounded in the codec structure and the published codec-aware designs.

| Rule | Statement | Addresses | Basis |
|---|---|---|---|
| D1 | Symbol rate ≤ 200 bd per tone channel, symbol boundaries aligned to the 5 ms subframe grid where possible; design budget 100–400 bit/s gross per tone channel, with multi-channel/multi-level extension evaluated separately | M3, M1 | CTM uses 200 bd 4-FSK (CEL-053); AMR subframe = 5 ms (CEL-002) |
| D2 | Prefer frequency- or codebook-coded symbols (MFSK, speech-like symbol codebooks) over phase- or amplitude-coded symbols | M2, M4, M5 | CTM; PAP-001/002/003; CEL-051 (AFSK/PSK/OOK comparison) |
| D3 | Place tones inside 300–3400 Hz for NB; keep every tone ≥ 2 × pitch-lag-related spacing apart (≥ 200 Hz) so LPC poles resolve them; avoid > 3.4 kHz unless the negotiated codec is WB and the X1 WB experiment passes | M1, M13 | CTM tone spacing 200 Hz; AMR-WB band facts (CEL-006) |
| D4 | Keep the signal "voice-active": continuous energy, spectral change at least every few hundred ms, no idle silence; insert a periodic low-cost "keep-alive" symbol pattern instead of idle; measure DTX trigger rate as a pass criterion | M6 | TS 26.093/094 hangover and SID timing (CEL-003/004); Hermes VAD note (CEL-048) |
| D5 | Constant envelope, moderate level (−20 to −12 dBm0 class), no amplitude information; let AGC be harmless | M4, M12 | [INFERRED] |
| D6 | Half-duplex or strictly frequency-separated two-way operation; never rely on same-band full duplex through the handset path | M11 | TS 26.131 TCLw (CEL-023) |
| D7 | Short, self-synchronising bursts: sync word every ≤ 200 ms, burst length chosen so a 20 ms concealment or a JBM insertion/deletion loses at most one interleaver span; receiver resynchronises per burst rather than running a free-running symbol clock | M7, M8 | CTM resync preamble; Hermes insertion/deletion handling |
| D8 | Mandatory FEC with interleaving across ≥ 3 codec frames (60 ms) plus ARQ at the link layer with sequence numbers; FEC rate in the 1/2–1/4 range initially | M7, M9 | CTM r=1/4 + interleaving; PAP-004 FEC+ARQ |
| D9 | Receiver uses soft decisions from tone-energy ratios, not hard thresholds; adapt thresholds per 40 ms window to tolerate mode switching | M9 | [INFERRED] |
| D10 | Training/preamble: a 300–500 ms alternating-tone preamble that both estimates the per-tone gain/delay response and exercises the VAD into the active state before payload; re-train on detected tandem change (sudden response change) | M5, M6, M10 | CTM resync design; [INFERRED] |
| D11 | Throughput budget to plan against, before measurement: NB codec paths 0.3–1.2 kbit/s net reliable, up to ~2–4 kbit/s best-effort; WB/EVS unknown. Do not promise rates until E-06..E-09 report | — | §3.2 |
| D12 | Link layer must survive 1–3 s outages (handover, mode change, re-transcoding) without dropping the logical call; re-use the project's session identity model | M9, M10 | PROJECT_PLAN rule 5 |
| D13 | Every CVDM frame carries a channel-quality field (observed erasures, DTX events) so the peer can step down rate — the AMR CMR idea applied at the application layer | M9 | RFC 4867 CMR concept (CEL-025) |

### 4.3 Why V.150.1-style modem relay at the phone is the only consistent architecture

V.150.1 (STD-001) exists because carrying modem *waveforms* through packet networks is
fragile even with G.711; the cellular voice path is strictly more hostile (§1–§2). GSM's
own IWF (CEL-017) and the alarm industry's dial-capture communicators (CEL-065) both put the
demodulator before the lossy segment. For AndroidDialup this means: the DTE/AT/V.42 layers
and any local V-series softmodem terminate on the Android side; the voice call carries a
CVDM byte stream; the gateway terminates CVDM and bridges to BYTE_RELAY/hardware modem
exactly as in the IP design. The Beta `BYTE_RELAY` abstraction already matches this.

---

## 5. Decision analysis per cellular voice path

Scoring: evidence-backed feasibility (E), expected net throughput (T), engineering cost (C),
dependency on privileged Android access (A). Ranking is a recommendation for X1 priorities.

### 5.1 GSM / UMTS circuit-switched voice

| Rank | Option | Assessment |
|---|---|---|
| 1 | **Network CSD / IWF modem relay** (TS 29.007 "3.1 kHz audio ex PLMN", RLP non-transparent) where the operator still offers CSD | The network's own V.32/V.34 modem talks to the PSTN; the phone carries bits. Highest fidelity by design (PRIMARY CEL-017). Availability is the problem: CSD is being withdrawn with 2G/3G sunsets and is not exposed by modern Android telephony APIs (A: high; E: high where offered). Worth one survey experiment (E-13), not engineering. |
| 2 | **Codec-aware CVDM byte bearer** (D1–D13), modem relay at the phone | 1–4 kbit/s class evidence for NB codecs (§3.2). Needs an audio entry point (§6). This is the X1 engineering target. |
| 3 | **CTM/TTY path as a carrier** | Standardised, works by design, but TTY-class throughput and not programmable from apps; useful only as a positive control and as a proof that the device's voice path passes 200 bd 4-FSK. |
| 4 | **Legacy V-series passthrough** | No evidence of success; multiple PRIMARY/STRONG SECONDARY indications of failure. Keep only as a negative-control experiment; never a product mode. |
| 5 | Fall back to packet data | Always available on the same device; the voice-path work must never block it (PROJECT_PLAN Phase X1 rule). |

### 5.2 VoLTE / VoWiFi (AMR-WB default, EVS where negotiated)

| Rank | Option | Assessment |
|---|---|---|
| 1 | **Packet data** | Same radio, orders of magnitude more throughput; the voice path only matters when IP connectivity to the relay is impossible but a voice call is possible (rare: e.g. voice-only plans, roaming data blocks). |
| 2 | **IMS real-time text (RFC 4103/T.140) as a signalling/very-low-rate channel** | Zero DSP, lossless text transport with redundancy, exposed to in-call apps via `Call.RttCall` (InCallService) — but only on carriers/devices with RTT enabled (`KEY_RTT_SUPPORTED_BOOL`), character-oriented, and with ≥300 ms sampling; practical byte rate unknown → E-12. Candidate for session signalling/keepalive and as the "voice path only as signalling channel" option. |
| 3 | **CVDM over AMR-WB/EVS** | No published data; AMR-WB coded band to 6.4 kHz offers more tone slots; EVS MDCT cores might treat a stationary multi-tone as "audio". Must be measured (E-07..E-09). |
| 4 | Legacy V-series passthrough | As 5.1 rank 4; additionally JBM time-scaling (M8) and RTP loss (M7). Negative control only. |

### 5.3 VoNR (EVS mandatory)

Same as 5.2. EVS channel-aware mode (if negotiated) improves resilience to loss for the
codec's own parameters but does nothing for M1–M5; the EVS JBM (TS 26.448) is the reference
JBM, so M8 is guaranteed present. EVS-specific experiments (E-09) are the only way to rank
CVDM here.

### 5.4 Recommendation

1. **Keep IP-first.** Cellular voice is a fallback/signalling bearer; failure of this track
   must not block Beta.
2. **Define `CELLULAR_VOICE_EXPERIMENTAL` as a byte bearer with a codec-aware PHY (CVDM),
   not as a waveform transport.** The mode hierarchy is: packet data → CVDM over voice →
   IMS RTT signalling → legacy waveform passthrough (negative control, never shipped).
3. **Build the codec-in-the-loop simulator first** (S1_MEDIA_DSP_CONTRACT §16): AMR-NB/AMR-WB
   via Apache-2.0 opencore-amr/vo-amrwbenc, GSM-FR via LGPL libgsm/SpanDSP (reference only
   pending license decision), EVS via the 3GPP reference C code under its IPR terms (use as
   a test oracle, not shipped code), plus DTX/PLC/JBM/mode-switch/tandem models M6–M10 and
   simple AEC-NLP/NS/AGC impairment models M11–M12.
4. **Run the X1 experiment matrix** (`CELLULAR_VOICE_EXPERIMENTS_R2.md`) before any claim.
   The Bluetooth HFP hands-free rig is the first lab entry point because it needs no
   privileged Android build.
5. **Interoperability claims for V-series over cellular voice are out of scope for the
   product** unless E-04/E-05 produce a positive result, which the evidence does not predict.

---

## 6. Android platform reality for call-audio access

| Access route | What it gives | Privilege | Evidence | X1 use |
|---|---|---|---|---|
| `AudioRecord` with `VOICE_CALL/VOICE_UPLINK/VOICE_DOWNLINK` | Call audio capture | `CAPTURE_AUDIO_OUTPUT` (system/privileged only); Android 10+ returns silence to ordinary apps | PRIMARY AND-004, AND-005; STRONG SECONDARY CEL-044 (BCR documents `CAPTURE_AUDIO_OUTPUT` + `CONTROL_INCALL_EXPERIENCE` as system app) | Custom ROM/system-app track only |
| Uplink injection from an app | None; no public API to write into the telephony uplink; in-call audio routing is handled by the audio HAL/modem (`voice.c`, `tty_mode`) | n/a | STRONG SECONDARY CEL-045 (HAL code); PRIMARY CEL-038 (effects config is per capture source, not telephony) | Not available |
| `InCallService` / `Call` | Call control, RTT text stream, codec extras — not media | `BIND_INCALL_SERVICE` (default dialer role) | PRIMARY CEL-043, CEL-040 | RTT channel (E-12); codec identification |
| TTY mode (`TelecomManager.TTY_MODE_FULL/HCO/VCO`) with a wired TTY accessory | The modem/DSP demodulates Baudot from the accessory and transports it as CTM (CS) or carrier-specific TTY-over-VoLTE (`ImsMmTelManager.isTtyOverVolteEnabled`, `KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL`) | User setting; accessory hardware | PRIMARY CEL-040, CEL-041, CEL-042 | Positive control: proves a standardised modem-relay path exists on the device; not programmable |
| RTT (`Call.RttCall`, `Connection.RttTextStream`) | Character stream over IMS (RFC 4103) | InCallService; carrier `KEY_RTT_SUPPORTED_BOOL` | PRIMARY CEL-039, CEL-043 | E-12 |
| Wired headset / USB-C audio accessory | Analogue/digital audio in and out of the call at the accessory interface; terminal voice processing still applies | None (hardware) | Android accessory specs (AND docs); experiment | E-10 (secondary rig) |
| **Bluetooth HFP hands-free unit** (Linux host as HF with BlueZ/oFono/PipeWire) | Full call audio in both directions at the HF, CVSD (8 kHz) or mSBC (16 kHz) on the SCO link; audio can be injected and recorded from the PC | None on the phone (standard pairing) | STRONG SECONDARY CEL-046, CEL-047 | **Primary X1 lab rig** (E-01 onward). Caveat: the SCO codec (CVSD/mSBC/LC3-SWB) is an additional waveform/sub-band stage to characterise (E-02) |
| Codec identification | `android.telecom.Connection` audio-codec extras / `CallQuality` codec type on recent API levels (to verify exact API and level in X1) | InCallService | Verify in X1 (not confirmed in this pass) | Needed to label every live measurement with the negotiated codec |

---

## 7. Open questions / unknowns

1. Exact CTM tone frequencies, interleaver depth and net payload rate from TS 26.226 §§
   (only the 200 bd / 200 Hz / r=1/4 facts were confirmed via secondary descriptions).
2. Krasnowski et al. and Huang et al. measured rates and BER per codec (full text not
   reachable in this pass).
3. Behaviour of EVS's speech/music classifier and MDCT cores on stationary multi-tone
   signals — could EVS at ≥13.2 kbit/s be *better* than AMR-WB for CVDM?
4. Whether EVS/AMR VAD tone-detection branches keep a 4-FSK CVDM signal "active" at the
   levels and spacings chosen (D4), per codec and per mode.
5. What terminal uplink processing (NS/AEC/AGC) is applied to the HFP SCO uplink and the
   wired-accessory uplink, per device/vendor.
6. Practical character throughput of IMS RTT on real carriers (and whether binary-safe
   transport via UTF-8 is accepted end to end).
7. Which operators still provide CSD "3.1 kHz audio ex PLMN" and whether any Android path
   can place such a call.
8. The real-world distribution of tandem topologies (AMR-WB→G.711→AMR, EVS→AMR-WB IO) for
   mobile-to-gateway calls the project would actually place.
9. The exact Android API (and API level) that exposes the negotiated voice codec to an
   InCallService.
