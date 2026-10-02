# S1 Beta 0.1 Acceptance and Test Plan

Status: normative acceptance matrix for Phase I1 and later Beta modules.

## 1. Test layers

```text
UNIT
PROTOCOL
STATE_MACHINE
NETWORK
GATEWAY
END_TO_END
IMPAIRMENT
HARDWARE_INTEROP
CELLULAR_VOICE_EXPERIMENT   (Phase X1 only; never part of a Beta exit gate, see §16)
```

No module is accepted solely because it round-trips against itself.

## 2. AT parser tests

Required cases:

- `AT` -> OK
- mixed-case commands
- E/Q/V state changes
- syntax validation atomicity
- line overflow recovery
- unknown command -> ERROR
- valid and invalid S-register accesses
- text/numeric result mapping
- quiet mode suppressing results
- dial success/failure mapping
- online-command transitions
- exact escape pre/post guard timing boundaries
- binary online data containing 0x00, 0x0D, 0x2B, 0xFF

## 3. State-machine tests

Every valid transition must have at least one test. Invalid transitions must remain deterministic.

Race scenarios:

```text
hangup while connecting
network lost while CONNECT event is in flight
DTE disconnect during dial
remote hangup simultaneous with local hangup
auth failure after cancellation
late gateway CONNECT after call generation changed
```

Assertions:

- one terminal state;
- at most one terminal DTE result;
- DCD ends low;
- backend lease released;
- stale events do not resurrect call.

## 4. Wire protocol tests

Golden binary vectors SHALL be stored in `protocol/test_vectors/`.

Required vectors:

```text
HELLO
HELLO_ACK
AUTH challenge/response envelope
DIAL_REQUEST
DIAL_ACCEPTED
DIAL_FAILED
CALL_PROGRESS
DATA_BYTES empty-invalid
DATA_BYTES normal
FLOW_STATUS
HANGUP_REQUEST
CALL_TERMINATED
```

Negative vectors:

- wrong magic;
- unsupported version;
- header shorter than fixed header;
- payload > maximum;
- truncated frame;
- reserved bits set;
- duplicate/overlapping BYTE_RELAY sequence;
- invalid session/call ID for current state.

Fuzz parser with arbitrary length-prefixed input under bounded memory monitoring.

## 5. NetworkSelector tests

Use fake candidates and deterministic probe values.

Verify all five policies, validation filtering, metered/roaming penalty, hysteresis, tie breakers, selected-network loss, and no automatic migration during an active Beta call.

Android instrumentation tests verify sockets are actually bound to the selected `Network` rather than the process default.

## 6. Queue/backpressure tests

Fill queues to:

```text
low_water - 1
low_water
high_water
max - 1
max
attempt max + 1
```

Verify CTS behavior, stall timer, recovery, and FLOW_CONTROL_TIMEOUT.

No BYTE_RELAY payload may be silently discarded.

## 7. Loopback end-to-end acceptance

Topology:

```text
PC test client -> Android DTE TCP -> relay -> gatewayd loopback -> relay -> Android -> PC
```

Acceptance:

1. `AT` returns OK.
2. `ATDloopback` progresses to CONNECT.
3. Send 1 MiB pseudorandom binary payload.
4. Receive byte-for-byte identical payload.
5. No sequence gaps/duplicates.
6. Queue limits not exceeded.
7. Escape to ONLINE_COMMAND with timed `+++`.
8. `ATO` resumes data without reconnect.
9. `ATH` cleanly terminates and DCD drops.

Run on Wi-Fi and cellular packet data separately.

## 8. Network failure acceptance

During 1 MiB loopback transfer:

- disable selected Wi-Fi while WIFI_ONLY active;
- disable mobile data while CELLULAR_ONLY active;
- remove Internet validation via controlled test network;
- kill relay connection;

Beta expected behavior: active call terminates predictably with NO CARRIER and NETWORK_LOST/transport diagnostic. No hidden bearer migration is expected.

## 9. Relay/gateway failure tests

Inject:

- gateway unavailable;
- backend busy;
- backend no dialtone;
- backend no answer;
- backend remote hangup;
- gateway process death;
- TLS close mid-call;
- heartbeat timeout;
- serial write stall.

Verify exact internal reason and DTE-compatible result mapping.

## 10. Hardware modem acceptance

Topology:

```text
Android -> relay -> gatewayd -> USB/RS232 hardware modem -> controlled remote modem
```

Minimum evidence:

- gateway initializes modem;
- real dial attempt issued;
- CONNECT is sourced from hardware modem result, not fabricated;
- arbitrary binary DTE data crosses established modem call in both directions;
- hardware flow control measured where available;
- DTR or configured hangup strategy reliably terminates carrier;
- at least one retry after failure succeeds without restarting gatewayd.

Record vendor/model/firmware and remote peer.

## 11. PCM experimental tests

Before real network testing, use deterministic WAV/PCM source.

Verify:

- sequence extension/reordering;
- 20 ms packet timestamp increments of 160;
- late packet counting;
- bounded jitter-buffer depth;
- no speech PLC enabled;
- clock correction responds to controlled sender ppm error without unbounded drift.

Impairment matrix begins with:

```text
loss: 0, 0.1, 0.5, 1, 2 percent
reorder: 0, 0.1, 0.5 percent
one-way jitter: 0, 5, 10, 20, 40 ms
clock error: -100, -50, 0, +50, +100 ppm
```

Actual modem waveform survivability is measured later with independent external vectors.

These PCM tests characterise the IP path only. They SHALL NOT be cited as evidence about
cellular voice calls (BETA_0_1_ARCHITECTURE §12.1). Cellular voice is covered by §16.

## 12. Diagnostics acceptance

For every active call diagnostic snapshot must provide:

```text
call/session IDs in safe form
modem state
session state
selected bearer
relay RTT
queue depths/high water
DCD/DSR/CTS/RI
bytes rx/tx
terminal reason when ended
```

PCM mode additionally exposes loss, late, reorder, jitter depth and clock correction ppm.

No credential secret may appear in captured logs.

## 13. Resource tests

Run 1000 sequential loopback calls.

Acceptance:

- no monotonic growth in open sockets/threads beyond expected caches;
- backend leases return to zero;
- queue objects are released;
- no stale call IDs remain active;
- memory reaches stable bounded range.

## 14. Soak test

BYTE_RELAY loopback call for 8 hours with bidirectional pseudorandom stream and periodic idle gaps.

Track:

```text
bytes
errors
RTT
queue high-water
memory
thread count
heartbeat failures
DTE result anomalies
```

Zero byte corruption is required.

## 15. I1 exit criteria

Phase I1 passes only when all are true:

1. Android connects to relay using explicitly selected Wi-Fi.
2. Android connects using explicitly selected cellular packet data.
3. `ATD` creates an end-to-end loopback call.
4. BYTE_RELAY transports arbitrary binary data bidirectionally.
5. escape/ATO/ATH behavior matches S1.
6. queue/backpressure tests pass.
7. forced network/relay failures terminate deterministically.
8. structured diagnostics identify terminal reason.
9. no unbounded resource growth in sequential-call test.

Hardware-modem dial is the later I5 exit gate, not required to claim I1 complete.

## 16. Cellular voice experiments (Phase X1, non-gating for Beta)

Status: these rows define the X1 acceptance matrix. They are not I1–I5 exit criteria, and a
failure here SHALL NOT block any packet-data gate (S1_MEDIA_DSP_CONTRACT §16.1). The full
definitions (equipment, vectors, measurements, pre-registered predictions) are in
`docs/research/CELLULAR_VOICE_EXPERIMENTS_R2.md` (X1). The simulator interfaces are in
S1_MEDIA_DSP_CONTRACT §13.1–§13.3. Values tagged [P] are project-chosen test parameters
defined in X1 §0. They are not sourced claims.

### 16.1 Simulation rows (rig R-SIM)

| ID | Test | External vectors / oracle | Pass criterion | Gate |
|---|---|---|---|---|
| S-01 | Codec stages bit-exact | 3GPP TS 26.074, 26.174, 26.444 sequences (CEL-081..083) | Bit-exact for every mode used, else stage flagged non-reference and excluded | G-X1-0 |
| S-02 | DTX/VAD timing | V-CODEC DTX sequences; P.501 signals (CEL-085) | Reference DTX reproduced (AMR 7-frame hangover, SID_FIRST, SID every 8 frames, CEL-003; EVS SID interval, CEL-073); parametric model matches reference frame-for-frame | G-X1-0 |
| S-03 | Frame-aligned loss, PLC, JBM | G.191 STL erasure patterns (CEL-086); TS 26.444 JBM profiles | Decoder output bit-exact vs reference for same erasure pattern; erasures on 20 ms boundaries; JBM matches TS 26.444 outputs | G-X1-0 |
| S-04 | Terminal AEC/NS/AGC models | P.501 TCL signals | Deterministic and seeded; not used for predictions until fitted to E-11 | G-X1-3 (use gate) |
| S-05 | Legacy modem negative control | minimodem/SpanDSP vectors; external receivers only | All cells executed and reported against the pre-registered failure prediction | G-X1-2 |
| S-06 | CTM calibration | TS 26.230 reference CTM (CEL-012) | Meets TS 26.231 (CEL-013) in its specified conditions; failure invalidates the simulator | G-X1-0 |
| S-07 | CVDM design sweep | O.150 PRBS (CEL-084); independent second receiver | Residual error 0 over ≥ [P] 1 MiB per codec family; no SID frames during payload with DTX on; goodput reported | G-X1-3 |
| S-08 | Tandem chains | G.711 from G.191 STL | CTM still passes on NB-terminated tandems; CVDM residual error 0, or the chain is listed unsupported | G-X1-3 |

### 16.2 Live rows (rigs R-HFP, R-ACC, R-INCALL, R-PRIV)

Every live row SHALL record path, codec label, entry point, device, operator and topology
(S1_MEDIA_DSP_CONTRACT §16.7).

| ID | Test | Path | Pass criterion | Gate |
|---|---|---|---|---|
| E-01 | Rig baseline + codec labelling | each available | 3 repeat calls: same codec label, latency spread ≤ one 20 ms frame | G-X1-1 |
| E-02 | SCO stage isolation (CVSD vs mSBC, CEL-047) | each available | SCO contribution quantified | G-X1-1 |
| E-03 | CTM/TTY positive control | CS (CTM), IMS (TTY/RTT) | Meets TS 26.231 on ≥ 1 path where supported; failure makes rig negatives inadmissible | G-X1-1 |
| E-04 | FSK negative control (Bell 103, V.21, V.23, Bell 202) | all | Executed and reported; SURVIVES only per X1 §3 E-04 | G-X1-2 |
| E-05 | QAM/fax negative control (V.22, V.22bis, V.32bis, V.34, G3 fax) | all | Handshake traces archived; SURVIVES requires CONNECT + byte-exact ≥ [P] 64 KiB with independent hardware peers | G-X1-2 |
| E-06 | CVDM over GSM/UMTS CS | FR/EFR/AMR-NB | Residual error 0 over ≥ [P] 1 MiB; no DTX events; two-receiver agreement | G-X1-3 |
| E-07 | CVDM over VoLTE AMR-WB | AMR-WB set 0 (CEL-033) | As E-06; WB tone plan admitted only on pass | G-X1-3 |
| E-08 | CVDM over VoWiFi | as VoLTE (CEL-032) | As E-07 | G-X1-3 |
| E-09 | CVDM over EVS (VoLTE-EVS, VoNR) | EVS (CEL-007, CEL-031) | As E-07 | G-X1-3 |
| E-10 | Wired/USB accessory entry point | subset | Differences vs HFP quantified | — |
| E-11 | Terminal AEC/NS/AGC characterisation | every rig × path | S-04 parameters fitted; half-duplex rule (MEDIA_DSP §16.5 item 7) confirmed or refuted | G-X1-3 |
| E-12 | IMS RTT signalling channel (CEL-027, CEL-043) | VoLTE/VoNR with RTT | Byte-exact over ≥ [P] 64 KiB; throughput/latency per carrier | — (hierarchy step 3 eligibility) |
| E-13 | CSD/IWF availability survey (CEL-017) | GSM/UMTS operators | Dated per-operator table | — |
| E-14 | Call-topology/tandem matrix | same-op, cross-op, mobile→PSTN, mobile→SIP | Each product topology measured; failing ones listed unsupported | G-X1-4 |
| E-15 | Duration/handover/rate-switch robustness | best E-06..E-09 path | Residual error 0 over ≥ [P] 30 min; no logical-session loss for outages below the link timeout | G-X1-4 |
| E-16 | Privileged downlink capture cross-check (AND-004) | R-PRIV | SCO contribution confirmed or corrected | — (optional) |

### 16.3 Claim gate

No test report, UI string or release note may state that a mode works over a cellular voice
call unless G-X1-4 (X1 §4) is met for that mode and path.
