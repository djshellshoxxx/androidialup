# Phase R1 PHY Implementation Notes

This document converts primary-standard findings into concrete implementation requirements. It is research output, not yet the frozen S1 coding specification.

## V.21 implementation profile

Primary source: ITU-T V.21 (11/1988), in force.

### Core PHY

- Full duplex, two FSK channels, up to 300 bit/s each.
- Binary FSK; modulation rate equals data signalling rate.
- Channel 1 nominal mean frequency: 1080 Hz.
- Channel 2 nominal mean frequency: 1750 Hz.
- Frequency deviation: ±100 Hz.
- Channel 1 characteristic frequencies: binary 0 = 1180 Hz, binary 1 = 980 Hz.
- Channel 2 characteristic frequencies: binary 0 = 1850 Hz, binary 1 = 1650 Hz.
- Modulator output frequency tolerance: ±6 Hz.
- Receiver must tolerate up to ±12 Hz total offset from nominal.
- Calling station transmits on channel 1 and receives channel 2; answering station uses the opposite allocation.
- Signal detector guidance: ON above -43 dBm, OFF below -48 dBm, with at least 2 dB hysteresis.

### Beta implementation decision

BUILD OUR OWN for V.21 TX/RX. The algorithm is sufficiently small, primary-source-defined, and valuable as the first independent physical layer. Validate it against SpanDSP and another independent generator/decoder.

### Proposed internal sample architecture

Use 48 kHz inside Android/native audio because it aligns with common Android hardware. Run the V.21 detector either directly at 48 kHz or through an integer decimator to 8 kHz after anti-alias filtering. The reference telephony test path shall operate at 8 kHz PCM to match common PSTN/VoIP infrastructure.

### Receiver candidate

For Beta, use dual matched-energy detectors per channel rather than an FFT. A Goertzel pair or quadrature correlator is adequate at 300 baud. Symbol timing should integrate discriminator energy over a recovered 300-symbol/s clock and tolerate the ±12 Hz carrier offset required by V.21.

Required independent tests:
1. Exact 980/1180/1650/1850 Hz tone generation.
2. ±6 Hz TX tolerance test.
3. ±12 Hz RX offset test.
4. Caller/answer channel allocation test.
5. Random asynchronous 8N1 round trip against an independent implementation.
6. AWGN, attenuation and band-limit sweeps through the channel simulator.
7. Dropout/reacquisition and signal-detector hysteresis tests.

## V.22bis implementation profile

Primary source: ITU-T V.22bis (11/1988), in force.

### Core PHY

- Duplex frequency-division modem.
- 600 baud nominal symbol rate.
- 2400 bit/s uses 16-state QAM: four bits per symbol.
- 1200 bit/s fallback uses two bits per symbol and is compatible with V.22 modes specified by V.22bis.
- Calling modem transmits low channel and receives high channel; answering modem does the reverse.
- Low-channel carrier: 1200 Hz ±0.5 Hz.
- High-channel carrier: 2400 Hz ±1 Hz.
- Optional high-channel guard tone: 1800 Hz ±20 Hz, with a national 550 Hz alternative.
- Receiver must tolerate received frequency offsets up to ±7 Hz.
- Transmit spectrum uses square-root raised-cosine shaping with 75% roll-off.
- Self-synchronizing scrambler/descrambler polynomial: 1 + x^-14 + x^-17.
- The scrambler includes protection against a run of 64 consecutive output ones by inverting the next input bit.

### 2400-bit/s differential quadrant mapping

For the first two bits of each quadbit:
- 00 -> +90° quadrant change
- 01 -> 0°
- 11 -> -90° / 270°
- 10 -> 180°

The remaining two bits select one of four points in the resulting quadrant according to the standard constellation.

### Handshake facts that must become state-machine timers

For 2400-bit/s interworking:
- Calling side detects 155 ±10 ms of unscrambled binary 1.
- It then remains silent another 456 ±10 ms.
- It transmits the repetitive 00/11 double-dibit pattern at 1200 bit/s for 100 ±3 ms, then scrambled binary 1.
- After rate selection, the modem switches to 2400-bit/s scrambled binary 1 on the defined timing; the receiver may begin 16-way decisions after 450 ±10 ms and the transmitter switches after 600 ±10 ms from the rate indication event.
- After 200 ±10 ms of 2400-bit/s scrambled binary 1, the modem may become ready to transmit.
- Detection of 32 consecutive bits of 2400-bit/s scrambled binary 1 indicates readiness to receive data.

Retrain uses the same 00/11 training pattern, 1200-bit/s scrambled ones, transition to 2400-bit/s training, and a recommended 1.2 s maximum expected two-way propagation delay before repeating.

### Phase R1 implementation decision

Do not implement V.22bis before the V.21/AT/transport foundation is proven. For V.22bis, first build an independent conformance harness and compare:
- local clean-room implementation feasibility,
- SpanDSP `v22bis` module behavior,
- hardware-modem captures.

The likely S1 choice is either LINK/PORT SpanDSP for the first interoperable V.22bis path or BUILD OUR OWN only if licensing/integration costs outweigh the benefit.

## Dependency ordering now justified by standards

1. NCO/tone generation
2. filters and signal-power measurement
3. V.21 FSK TX/RX
4. asynchronous framing
5. V.8 negotiation
6. V.22/V.22bis QAM path
7. V.42 LAPM
8. V.42bis compression
9. later V.32bis/V.34

V.8 depends on V.21-class signalling, so V.21 is a foundational dependency rather than merely a legacy low-speed option.
