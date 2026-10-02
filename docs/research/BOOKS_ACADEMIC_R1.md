# Phase R1 Books, University Material, and Academic Sources

This inventory favors legal publisher/university access. Copyrighted material is referenced, not copied into the repository.

## B-001 — Data Communications Principles

- Authors: Richard D. Gitlin, Jeremiah F. Hayes, Stephen B. Weinstein
- Edition: 1st
- Year: 1992
- Publisher: Springer / Plenum
- Legal access: publisher page/preview, DOI `10.1007/978-1-4615-3292-7`
- Access status: subscription/preview; chapter metadata visible
- Relevance: exceptionally high for this project because the book is organized around the exact classical data-modem problems we need
- Chapters/pages identified from publisher TOC:
  - Passband Data Transmission, pp. 305–402
  - Synchronization: Carrier and Timing Recovery, pp. 403–464
  - Automatic and Adaptive Equalization, pp. 517–605
  - Echo Cancellation, pp. 607–665
- Use: authoritative secondary source for DSP design choices once full legal access is available
- Confidence: STRONG SECONDARY

Phase R1 decision: treat this as the primary textbook target for carrier/timing loops, adaptive equalization and echo cancellation. Do not infer implementation constants from the chapter titles alone; inspect chapters before freezing DSP algorithms.

## U-001 — University of Toronto ECE1392H, Integrated Circuits for Digital Communications

- Institution: University of Toronto
- Instructor: David A. Johns
- Course page: `https://www.eecg.toronto.edu/~johns/ece1392/ece1392.html`
- Access: public university course page with downloadable lecture PDFs
- Topics directly relevant:
  - system overview
  - equalization
  - modulation
  - timing recovery
  - echo cancellation and 2-to-4 wire hybrids in course scope
- Use: practical implementation-oriented supplement, especially for equalizer/clock-recovery architecture
- Confidence: STRONG SECONDARY / UNIVERSITY MATERIAL

## U-002 — MIT 6.450 Principles of Digital Communications I

- Institution: Massachusetts Institute of Technology OpenCourseWare
- Instructors: Robert Gallager, Lizhong Zheng
- Year: Fall 2006
- Course page: `https://ocw.mit.edu/courses/6-450-principles-of-digital-communications-i-fall-2006/`
- Access: public OCW lecture notes/PDFs
- Relevant coverage:
  - sampling/aliasing
  - PAM/QAM and signal constellations
  - channels, modulation and demodulation
  - detection, coding and decoding
- Use: foundational derivations and validation of modulation/detection math; less voiceband-modem-specific than Gitlin/Hayes/Weinstein
- Confidence: STRONG SECONDARY / UNIVERSITY MATERIAL

## T-001 — Adaptive equalization for modem constellation identification

- Author: Richard Dale Wesel
- Type: MIT M.S. thesis
- Year: 1989
- Repository: MIT DSpace
- Legal access: full printable PDF is hosted by MIT for viewing; repository notes copyright restrictions on reproduction/distribution
- Persistent record: `http://hdl.handle.net/1721.1/29857`
- Relevance: modem-specific adaptive equalization and constellation identification
- Use: research/reference only; do not redistribute PDF in this repo
- Confidence: STRONG SECONDARY

## T-002 — Fast training of a high-speed voiceband data modem receiver

- Author: Risto Kari
- Type: doctoral dissertation
- Year: 1990/1991 bibliographic record
- Record: University of Oulu / Finna
- Relevance: high-speed voiceband modem receiver training
- Current access status: bibliographic record located; full digital text not yet confirmed from the initial pass
- Confidence: SECONDARY until full text inspected

## P-001 — An adaptive fractional T/2 equalizer for high-efficient telephone-channel data modem

- Author: Vladimir Ratko Krstic
- Venue/year: Mediterranean Electrotechnical Conference, 1991
- DOI: `10.1109/MELCON.1991.161877`
- Public author-upload listing located on ResearchGate
- Relevance: fractionally spaced LMS equalization for a V.32/V.33-class telephone-channel modem and carrier-phase tracking
- Use: compare practical equalizer architecture against textbook/standard requirements
- Confidence: STRONG SECONDARY once full text is reviewed from a lawful accessible copy

## V-001 — Texas Instruments V.34 implementation application report

A historical TI application report titled approximately *V.34 Transmitter and Receiver Implementation on the TMS320C50 DSP* (report identifier SPRA159) was located through secondary indexing. The available searchable excerpt discusses a phase-splitting fractionally-spaced equalizer and LMS adaptation.

Phase R1 status: **source not yet accepted into the controlled inventory as a primary vendor document** because the initial result was a third-party document host. Locate the original TI archive/mirror or another provenance-strong copy before using it for implementation decisions.

## Implementation synthesis so far

The source mix supports the following research order:

1. Use the ITU standard to define observable signaling, rates, sequences and compliance behavior.
2. Use Gitlin/Hayes/Weinstein for classical modem synchronization, equalization and echo-cancellation theory.
3. Use public university material for implementable loop/equalizer derivations and numerical intuition.
4. Compare mature SpanDSP implementation choices and historical vendor application reports against those derivations.
5. Freeze an algorithm only after an independently generated test strategy exists.

## Next book/document targets

- obtain inspectable legal access to the relevant Gitlin/Hayes/Weinstein chapters
- locate original TI SPRA159 or an official TI archive
- Rockwell/Conexant V.34/V.90 chipset/reference manuals
- Lucent/Agere modem DSP documentation
- USRobotics Courier/V.Everything technical manuals and AT/register documentation
- Analog Devices modem DSP application notes
- older telephone transmission texts with channel/2-wire hybrid/echo models
