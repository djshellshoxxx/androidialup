# V.90 Network Topology Constraints — Phase R1

## Core conclusion

A genuine V.90 connection is asymmetric by architecture, not merely by configured bit rate. ITU-T V.90 defines a *digital modem* and an *analogue modem* pair. The digital modem is connected locally to a digital switched network; the analogue modem is reached through the PSTN analogue subscriber loop. V.90 permits up to 56 kbit/s downstream from the digital modem and up to 33.6 kbit/s upstream from the analogue modem.

Therefore AndroidDialup cannot obtain genuine 56k downstream simply by generating a faster analogue waveform at both ends. A V.90-capable test environment must provide the correct digital-network-side endpoint.

## Architectural consequence

Three different scenarios must be kept separate:

### A. Analogue modem to analogue modem

Two ordinary POTS modems connected through analogue interfaces can negotiate legacy V-series modes such as V.34, but this is not the topology that enables V.90's PCM downstream path.

### B. Analogue client modem to digital V.90 server modem

This is the relevant V.90 topology. One end is the analogue client modem; the server-side modem has a digital relationship to the switched network and emits downstream PCM-oriented symbols based on the digital network timing/quantization path.

### C. Android-to-IP relay-to-remote hardware modem

This can provide real dial-up connectivity for Beta, but if the remote gateway merely contains an ordinary analogue USB modem, the end-to-end legacy call should be expected to fall back to V.34-class operation unless the far-end service itself supplies a V.90 digital modem.

## What the ITU recommendation explicitly leaves outside the modem spec

V.90 specifies the coding, startup signals/sequences, operating procedures and DTE-DCE functionality for the digital/analogue modem pair. The exact network interface and local signalling rate used to attach the digital modem to the digital switched network are national/network matters rather than fixed by V.90 itself.

This means our later V.90 lab requires a separate telephony-infrastructure decision; a correct modem DSP implementation alone is insufficient.

## Candidate lab topologies for later research

1. Real PSTN/ISDN/PRI environment with a known digital modem bank or access server.
2. Legacy remote-access server hardware with PRI/T1/E1 digital trunk and V.90 server modems.
3. A controlled PCM-network simulator that reproduces the digital-side assumptions closely enough to test V.90 coding and startup, but this would validate our DSP only, not full PSTN interoperability.
4. Historical open-source or research digital-modem implementations, if a sufficiently complete and licensable implementation can be located.

A SIP trunk plus ordinary G.711 endpoint is **not automatically equivalent** to a V.90 digital modem server. The downstream encoder must implement V.90's digital-modem behavior.

## Beta implication

Beta 0.1 should expose modem capability separately from bearer capability:

```text
capabilities.local_phy = [V21, ...]
capabilities.gateway = [HARDWARE_ANALOG_MODEM, PCM_VBD, ...]
capabilities.remote_service = [UNKNOWN, V34_ANALOG, V90_DIGITAL, ...]
```

Do not report `CONNECT 56000` simply because the Android-to-gateway IP path is faster than 56 kbit/s. The CONNECT rate refers to the emulated/actual modem link, not the Internet relay bandwidth.

## V.92 implication

V.92 remains dependent on the V.90 digital/analogue architecture while adding faster upstream operation, Quick Connect and modem-on-hold. It therefore stays after successful V.90 topology validation.

## Historical chipset evidence

The Conexant/Rockwell command-reference archive discovered during R1 points to multiple distinct modem families: host-controlled, host-processed and HCF/HSF V.90/K56flex devices. Those documents are useful for AT/register behavior and implementation-era architecture, but a normal client PCI/USB modem still represents the analogue-client side. It does not by itself solve the digital-server requirement.

## Required experiment before V.90 implementation is scheduled

`EXP-V90-TOPOLOGY-001`

Goal: establish a laboratory path in which a known analogue V.90 client connects to a known digital V.90 server and negotiates above V.34 rates.

Record:
- client modem make/chipset/firmware;
- digital modem/access-server make/chipset/firmware;
- PSTN/trunk topology;
- digital trunk type;
- negotiated downstream/upstream rates;
- retrain/fallback behavior;
- audio/PCM captures where legally and technically possible;
- server/client diagnostics.

Exit condition: at least one repeatable >33.6-kbit/s downstream V.90 connection in a controlled test topology.

## Sources

- ITU-T V.90 (09/1998), in force: digital modem + analogue modem pair, up to 56 kbit/s downstream and 33.6 kbit/s upstream.
- ITU-T V.92 (11/2000 + amendments/corrigendum), enhancement of V.90.
- Conexant/Rockwell HCF/host-controlled/host-processed V.90 command/reference-document index recovered from historical Linuxant archive.

## Decision

V.90 is a topology-dependent later milestone. The Beta architecture will preserve a capability model for it, but Phase S1 must not imply that an Android softmodem plus an analogue USB modem gateway can inherently produce a standards-valid 56k connection.
