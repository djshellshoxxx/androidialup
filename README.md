# AndroidDialup

AndroidDialup is an experimental Android-based dial-up modem platform intended to expose a legacy modem-style DTE interface (AT commands plus serial-style data) while allowing the remote connection to travel over modern IP networks and, where practical, legacy/voice-band paths.

The project is research-first. Beta 0.1 will be built from a sourced engineering specification rather than from ad-hoc implementation decisions.

## Project goals

- Present a modem-like endpoint to computers using USB serial, Bluetooth serial where available, or TCP.
- Support AT-command control and modem call-state semantics.
- Use Wi-Fi and LTE/5G packet data as interchangeable IP transports.
- Evaluate modem relay, voice-band-data transport, remote-modem gateways, and full software modem DSP.
- Preserve a path toward real legacy modem interoperability.
- Investigate cellular voice only where Android platform privileges, device hardware, carrier behavior, and vocoder behavior make it technically meaningful.
- Build reproducible channel/network simulators and externally sourced regression vectors before claiming interoperability.

## Working conclusion for Beta 0.1

The initial architecture is **multi-mode with an IP-first core**:

1. **Primary:** Android DTE/AT endpoint + authenticated relay session over Wi-Fi or cellular packet data.
2. **Secondary:** voice-band-data/audio transport over IP using RTP/UDP-style timing, with a gateway able to terminate into a hardware modem, analog interface, or software DSP.
3. **Experimental:** direct cellular voice-channel modem operation on privileged/custom Android builds or hardware where access to call audio is possible.

The core protocol will be transport-independent so later work can add V.150.1-style modem relay, V.152 voice-band data, QUIC migration, direct modem DSP, or remote hardware modem backends without replacing the DTE/AT layer.

## Repository map

- `docs/MASTER_RESEARCH_BRIEF.md` — authoritative research/specification mandate supplied for the project.
- `docs/research/SOURCE_INVENTORY.md` — controlled research database with confidence classification.
- `docs/research/INITIAL_FINDINGS.md` — early synthesis and feasibility conclusions.
- `docs/spec/BETA_0_1_ARCHITECTURE.md` — implementation architecture for the first beta.
- `docs/spec/PROTOCOL_AND_MODULE_CONTRACTS.md` — wire framing and subsystem boundaries.
- `docs/experiments/EXPERIMENT_PLAN.md` — experiments required before stronger interoperability claims.
- `docs/PROJECT_PLAN.md` — staged implementation and validation plan.

## Evidence policy

Important design decisions should prefer, in order: official standards, original vendor/chipset documentation, textbooks, peer-reviewed/academic material, mature open-source implementations, university material, experienced-practitioner documentation, and finally anecdotal reports. Lower-confidence findings are explicitly marked.

## Scope status

This repository currently contains the research/specification foundation. Coding should begin only for modules whose API contracts and validation criteria have been written down.