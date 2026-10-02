# Phase R1 Deep Dive: V.42 and V.42bis

## V.42

Primary source reviewed: ITU-T V.42, with the 2002 edition currently in force. The 1993 document was also inspected for LAPM frame/procedure detail and is superseded by the later revision.

V.42 provides error-correcting procedures for duplex V-series DCEs accepting asynchronous DTE data and transmitting synchronously. Its principal protocol is LAPM, an HDLC-based link layer.

### Core implementation requirements

LAPM must provide:
- frame delimiting/alignment and transparency,
- user-data and break transfer,
- sequence control,
- error detection,
- recovery/retransmission,
- flow control,
- parameter negotiation,
- connection initialization and orderly release.

Frames are delimited with HDLC flag `01111110`. Transparency uses zero insertion after five contiguous one bits; the receiver removes that inserted zero. The frame also carries address, control, information where applicable, and FCS fields.

The in-force 2002 revision additionally includes suspension/resumption behavior needed by V.92 modem-on-hold and negotiation references for V.44 compression. Beta 0.1 does not need modem-on-hold, but the internal API must not make later suspension impossible.

### Architectural decision

V.42 belongs above a successfully trained modem PHY and below optional V.42bis compression. Do not intertwine LAPM retransmission state with Android IP transport retransmission. They solve different problems:

- relay/IP reliability protects Android-to-gateway transport;
- LAPM protects the emulated end-to-end legacy modem link and must remain observable to the remote modem.

For early interoperability, SpanDSP's V.42 module is a serious LINK/PORT candidate. A clean-room implementation remains feasible because LAPM is structurally HDLC-like, but it should not be chosen merely to avoid a library dependency.

### Required conformance tests

- flag detection and frame alignment
- bit stuffing/de-stuffing at all boundary cases
- valid/invalid FCS
- I/S/U frame encode/decode
- modulo sequence wrap
- retransmission after damaged/lost frame
- local and remote busy/flow-control behavior
- connection setup/release
- XID parameter negotiation
- break transfer behavior
- fuzzed malformed frames without buffer overrun/state corruption

## V.42bis

Primary source: ITU-T V.42bis (01/1990), still in force.

V.42bis is a dictionary-based compression protocol negotiated for use with an error-corrected modem connection. It maintains synchronized encoder/decoder dictionaries and can transition dynamically between compressed and transparent modes.

### Standard parameters

The standard defines, among others:
- N1: maximum codeword size in bits
- N2: total number of codewords
- N3: character size, fixed at 8
- N4: alphabet size = 2^N3
- N5: first dictionary entry available for strings
- N6: number of control codewords, 3
- N7: maximum string length
- C1: next empty dictionary entry
- C2: current codeword size
- C3: threshold for increasing codeword size
- P0: compression request/negotiation
- P1: negotiated number of codewords
- P2: negotiated maximum string size

Compressed-mode control codewords include ETM (enter transparent mode), FLUSH and STEPUP. Transparent-mode commands include ECM (enter compression mode), EID and RESET.

### Important behavioral finding

The standard requires the compression function to periodically determine whether compression is beneficial, but deliberately does not prescribe the exact compressibility test. Therefore our implementation may choose a deterministic local heuristic while remaining interoperable, provided mode transitions and dictionary behavior follow the standard.

The decoder declares a compression error for conditions such as an impossible STEPUP, a codeword equal to the next free dictionary entry, an empty dictionary entry, or a reserved command. Recovery can require re-establishment of the error-corrected connection.

### Architecture decision

Treat V.42bis as a replaceable stream transform above V.42 LAPM. Expose a narrow interface:

```
compressor.init(negotiatedParams)
compressor.pushDteBytes(bytes) -> zeroOrMoreLapmPayloads
compressor.onFlush()
compressor.reset()

decompressor.init(negotiatedParams)
decompressor.pushLapmPayload(bytes) -> zeroOrMoreDteBytes
decompressor.onProtocolError() -> C_ERROR
```

No Android/network code may be visible to this layer.

### Reuse decision

SpanDSP includes V.42 and V.42bis modules under its core LGPL-2.1 licensing. Phase S1 should compare linking those modules against a clean-room implementation based on interoperability, binary size, Android NDK friction and desired final project licensing. Do not copy GPL test/support code into production.

### Required independent tests

- dictionary initialization/reset
- dictionary growth and codeword-width STEPUP boundaries
- transparent -> compressed -> transparent transitions
- FLUSH semantics and octet alignment
- RESET synchronization
- incompressible random data
- highly repetitive data
- all 256 byte values including escape-related cases
- maximum negotiated dictionary/string limits
- injected invalid codewords and deterministic C-ERROR handling
- cross-test encoder and decoder against an independent implementation rather than only self-round-trip

## Layering decision

The legacy data path is now:

`DTE bytes -> optional V.42bis -> V.42 LAPM -> modem PHY -> analog/PCM path`

and the receive path is the inverse. The Android relay path transports whichever architecture mode is negotiated and does not replace V.42 when the goal is genuine legacy-modem interoperability.
