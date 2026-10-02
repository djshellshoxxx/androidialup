# Beta 0.1 Architecture

Status: implementation baseline, subject to refinement as primary-standard research continues.

## 1. Architectural objective

Beta 0.1 must produce a working Android modem endpoint without making the success of the whole project depend on V.34/V.90 DSP or privileged cellular-call audio. The architecture must nevertheless permit those functions to be inserted later.

## 2. Layer model

```text
Computer / DTE
    |
    | USB CDC/ACM, USB accessory bridge, Bluetooth SPP where available, or TCP
    v
+---------------------------+
| DTE Multiplexer            |
+---------------------------+
    |
    v
+---------------------------+
| AT Parser + Command Model  |
| S registers / result codes |
+---------------------------+
    |
    v
+---------------------------+
| Modem State Machine        |
| COMMAND / DIAL / CONNECTED |
| ESCAPE / HANGUP / ANSWER   |
+---------------------------+
    |
    v
+---------------------------+
| Logical Session Manager    |
+---------------------------+
    |
    +--> BYTE_RELAY --------------------+
    |                                   |
    +--> PCM_VBD -----------------------+--> IP Transport --> Relay --> Gateway Backend
    |                                   |
    +--> MODEM_RELAY (future) ----------+
    |                                   |
    +--> LOCAL_SOFTMODEM --> Oboe audio |
    |                                   |
    +--> CELLULAR_VOICE_EXPERIMENT -----+
```

## 3. Thread/process model

Android app is split into Kotlin/Java control plane plus native C++ DSP/audio library.

Threads/coroutines:

- `Ui/Main`: presentation only.
- `DteIo`: reads/writes computer-facing stream.
- `AtEngine`: parses command-mode bytes and mutates modem state through serialized events.
- `SessionControl`: authentication, dialing, session lifecycle and relay control messages.
- `NetworkSelector`: receives Android network callbacks and maintains candidate networks.
- `PacketRx`: receives media/data packets, validates headers, inserts into reordering/jitter buffers.
- `PacketTx`: drains bounded queues into selected network socket.
- `AudioCallback`: Oboe realtime callback; no blocking, allocation, logging I/O or network calls.
- `DspWorker`: optional non-callback DSP work where bounded latency allows it.
- `Metrics`: snapshots counters; never blocks realtime paths.

All state-machine transitions are serialized through one event queue to avoid races between DTE input, network failure, incoming calls and DSP carrier events.

## 4. Core interfaces

```text
interface DteTransport {
    start(onBytes, onDisconnect)
    write(bytes)
    setSignals(dcd, dsr, cts, ri)
    stop()
}

interface SessionTransport {
    connect(endpoint, selectedNetwork, credentials) -> TransportSession
}

interface TransportSession {
    sendControl(ControlFrame)
    sendData(DataFrame)
    sendMedia(MediaFrame)
    close(reason)
}

interface ModeAdapter {
    onSessionOpen(params)
    onDteBytes(bytes)
    onNetworkFrame(frame)
    onTimer(now)
    close()
}

interface GatewayBackend {
    dial(target, mode, options)
    write(bytes)
    writePcm(samples, timestamp)
    hangup()
}
```

No mode adapter may call Android connectivity APIs directly. No DTE implementation may know whether the active bearer is Wi-Fi or cellular.

## 5. NetworkSelector

Policies:

```text
AUTOMATIC
WIFI_ONLY
CELLULAR_ONLY
PREFER_WIFI
PREFER_CELLULAR
```

Candidate attributes:

```text
NetworkCandidate {
    androidNetworkHandle
    transport: WIFI | CELLULAR | ETHERNET | OTHER
    validated: bool
    internet: bool
    metered: bool
    downstreamKbps
    upstreamKbps
    signalStrength?        // advisory only
    lastCapabilitiesTime
    lastProbeRttMs?
    lastProbeLoss?
}
```

### Pseudocode

```text
initialize(policy):
    candidates = concurrent map
    register callback for WIFI + INTERNET
    register callback for CELLULAR + INTERNET
    on every callback -> enqueue NETWORK_CHANGED event

onAvailable(network):
    caps = connectivityManager.getNetworkCapabilities(network)
    candidate = normalize(network, caps)
    candidates[network] = candidate
    reevaluate()

onCapabilitiesChanged(network, caps):
    candidates[network] = normalize(network, caps)
    reevaluate()

onLost(network):
    remove candidates[network]
    if network == selected:
        selected = null
        emit ACTIVE_NETWORK_LOST
    reevaluate()

reevaluate():
    eligible = candidates where internet && validated

    switch policy:
      WIFI_ONLY: eligible = eligible where transport == WIFI
      CELLULAR_ONLY: eligible = eligible where transport == CELLULAR
      PREFER_WIFI:
          sort key = (transport == WIFI ? 0 : 1, measuredPathScore)
      PREFER_CELLULAR:
          sort key = (transport == CELLULAR ? 0 : 1, measuredPathScore)
      AUTOMATIC:
          sort by measuredPathScore with hysteresis

    best = first eligible

    if selected is null:
        selected = best
        emit ACTIVE_NETWORK_SELECTED(best)
    else if best != selected and score(best) exceeds score(selected) by HANDOVER_MARGIN:
        emit BETTER_NETWORK_AVAILABLE(best)
        // Beta does not migrate an active session automatically.
```

`measuredPathScore` must prefer actual relay RTT/loss measurements over Android bandwidth hints once probes exist.

## 6. Per-network socket binding

Use the Android `Network` object returned by selection.

```text
openBoundUdp(network, remote):
    fd = socket(AF_INET6/AF_INET, SOCK_DGRAM, UDP)
    network.bindSocket(fd)
    connect(fd, remote)
    return fd

openBoundTcp(network, remote):
    socket = network.socketFactory.createSocket()
    socket.connect(remote, CONNECT_TIMEOUT)
    return socket
```

Do not use process-wide binding for ordinary session traffic.

## 7. Logical session identity

A logical call is not identified by a five-tuple. It is identified by a cryptographically random session ID created by the relay after authenticated negotiation.

```text
SessionId = 128-bit random
EndpointId = persistent installation public-key fingerprint or assigned account/device ID
CallId = 128-bit random per dial attempt
```

This permits later reconnection or QUIC migration without resetting the AT/modem state machine.

## 8. Control channel states

```text
DISCONNECTED
CONNECTING
AUTHENTICATING
IDLE
DIALING
RINGING
NEGOTIATING_MODE
CONNECTED
RECONNECTING
DISCONNECTING
FAILED
```

Timeouts are explicit and monotonic-clock based.

### Dial pseudocode

```text
ATD(target):
    require modemState == COMMAND
    modemState = DIALING
    emit result? none yet

    network = NetworkSelector.current()
    if network == null:
        return NO_CARRIER

    transport = SessionTransport.connect(RELAY, network, credentials)
    if transport fails before T_CONNECT:
        return NO_CARRIER

    callId = random128()
    send DIAL_REQUEST(callId, target, requestedMode, capabilities)

    wait serialized events:
       DIAL_ACCEPTED -> continue
       BUSY -> return BUSY
       NO_ROUTE -> return NO_CARRIER
       TIMEOUT -> return NO_ANSWER
       AUTH_FAIL -> return NO_CARRIER + diagnostic reason

    negotiate mode/capabilities
    install ModeAdapter
    set DCD=true
    modemState=CONNECTED
    return CONNECT [rate/mode string]
```

## 9. AT parser baseline

Parser must support command lines beginning with `AT` and terminate on configured command terminator. Initial commands:

```text
AT
ATE0 / ATE1
ATZ
AT&F
ATH / ATH0
ATA
ATD<target>
ATO
ATI / ATI0..n
ATS<n>?
ATS<n>=<value>
AT+MODE?
AT+MODE=<BYTE_RELAY|PCM_VBD|AUTO>
AT+NET?
AT+NET=<AUTO|WIFI|CELLULAR|PREFER_WIFI|PREFER_CELLULAR>
AT+DIAG?
```

The public command vocabulary should follow V.250 conventions where applicable; project-specific commands use `+` names and must not silently redefine a standardized command.

## 10. Escape sequence detector

`+++` is timing-sensitive and must not be implemented as a simple substring search.

State:

```text
lastDataTime
guardTimeMs (S12-derived or project default)
plusCount
candidateStart
bufferedPlusBytes
```

Pseudocode:

```text
onConnectedByte(b, now):
    if plusCount == 0:
        if b == '+' and now-lastDataTime >= guardTime:
            plusCount = 1
            candidateStart = now
            hold byte
        else:
            forward b
            lastDataTime = now
        return

    if b == '+' and interCharacterGap within allowed window and plusCount < 3:
        plusCount++
        hold byte
        if plusCount == 3:
            schedule ESCAPE_GUARD_EXPIRES at now+guardTime
        return

    cancel candidate
    forward all held '+' then b
    lastDataTime = now

onEscapeGuardExpires(now):
    if plusCount == 3 and no intervening data:
        discard held pluses
        enter COMMAND_ONLINE state
        emit OK
    else:
        flush held pluses to data mode
```

## 11. BYTE_RELAY mode

This is not modem audio. It is a clean transparent octet transport exposed behind modem semantics.

Frame fields:

```text
version: u8
kind: DATA
flags: u8
session_id: 16 bytes
stream_seq: u32
ack_seq: u32        // optional in Beta reliable-UDP mode
payload_len: u16
payload: bytes
crc32c: u32         // for corruption detection in custom UDP framing
```

If implemented over TLS/TCP/QUIC stream, transport reliability replaces custom ACK/retransmit logic. Beta should prefer a secure reliable stream for BYTE_RELAY and keep timed PCM on UDP.

Backpressure rule: bounded DTE TX queue. If remote flow stalls, lower CTS where DTE transport supports it; otherwise buffer only up to configured limit and fail the call rather than consuming unbounded memory.

## 12. PCM_VBD mode

Canonical network media format for first experiments:

```text
sample format: signed linear PCM 16-bit little-endian internally
network payload: define explicitly; G.711 µ-law/A-law is a later interoperability option
canonical media clock: 8000 Hz for narrowband voice-band tests
Android audio/DSP clock: usually 48000 Hz
packetization: initial 20 ms = 160 samples at 8 kHz
sequence: u16 or u32 monotonic modulo field
media timestamp: u32, increments by number of 8 kHz samples
```

Do not assert V.152 compliance yet. The mode is `PCM_VBD_EXPERIMENTAL` until the standard checklist is complete.

## 13. Jitter buffer

Inputs are ordered by extended sequence number and media timestamp.

State:

```text
map<extendedSeq, packet>
expectedSeq
playoutTimestamp
baseDelayMs
adaptiveDelayMs
jitterEstimate
latePackets
lostPackets
```

RFC-3550-style transit jitter estimate may be retained for diagnostics, but playout logic must use measured occupancy and late-arrival rate.

Pseudocode:

```text
onPacket(pkt, arrival):
    validate session/version/length
    seq = extendSequence(pkt.seq)
    if seq < expectedSeq - REORDER_WINDOW: drop duplicate/too-late
    store pkt
    update jitter estimator from (arrival - mediaTime(pkt.timestamp))

onPlayoutTick():
    pkt = remove expectedSeq
    if pkt exists:
        output pkt.samples
    else:
        output lossConcealmentForModemData()
        lostPackets++
    expectedSeq++

    periodically:
        if latePacketRate > highThreshold:
            adaptiveDelay += onePacketDuration up to max
        else if occupancy consistently high and lateRate near zero:
            adaptiveDelay -= onePacketDuration down to min
```

For modem data, loss concealment should initially insert silence/last-safe fill according to experiment mode; speech PLC that invents waveform content can harm modem demodulation and should not be used blindly.

## 14. Sample-clock drift correction

Network sender and receiver clocks will differ. A jitter buffer alone cannot solve sustained ppm error.

Measure long-term buffer occupancy error and drive a bounded asynchronous sample-rate converter.

```text
targetOccupancySamples
occupancyError = currentOccupancy - target
integrator += Ki * occupancyError
ratioCorrection = clamp(Kp*occupancyError + integrator, -MAX_PPM, +MAX_PPM)
resamplerRatio = nominalRatio * (1 + ratioCorrection*1e-6)
```

The controller must move slowly enough not to frequency-modulate the modem signal materially. Coefficients will be selected through simulation before locking them into the spec.

## 15. Audio engine

Native C++ / Oboe:

```text
open input/output using device native sample rate
request LOW_LATENCY
request EXCLUSIVE, accept SHARED fallback
use callback APIs
preallocate ring buffers
query xrun counters
```

Realtime callback pseudocode:

```text
onAudioReady(input, output, frames):
    // no allocation / locks / file or network I/O
    copy input frames into lock-free RX audio ring
    if TX audio ring has frames:
        copy next frames to output
    else:
        fill output with zero
        txUnderrunCounter++
    return Continue
```

## 16. Initial local FSK modem scope

First physical-layer milestone is Bell-103/V.21-class FSK, selected because it is independently testable with mature external implementations and does not require the complexity of echo-cancelled QAM.

Components:

- NCO or phase-accumulator tone generator
- transmit shaping/windowing sufficient to avoid discontinuities
- receive band limiting
- dual-tone energy/detector path
- symbol timing loop
- async framing where applicable
- carrier detect and squelch
- independent WAV vector testing

A later research document will choose exact mark/space frequencies, bit rates and framing directly from the relevant standard rather than from memory.

## 17. Gateway Beta

Fastest reproducible gateway:

```text
Linux host
  relay/gateway service
  + optional USB serial hardware modem backend
  + PCM loopback/test backend
  + test-vector backend
```

The first gateway need not use Asterisk or FreeSWITCH. Those should be introduced when SIP/PSTN/VBD interoperability is being tested, not as dependencies for basic Android session correctness.

## 18. Security baseline

- TLS 1.3 or QUIC TLS for control/reliable byte relay.
- Server authentication mandatory.
- Per-device credentials or public-key identity; never use a hardcoded shared project password.
- Session IDs are unpredictable and not authentication tokens by themselves.
- Media packets are authenticated/encrypted in production mode; experimental raw RTP may be limited to controlled lab networks.
- Diagnostics must not log credential material.

## 19. Diagnostics required from day one

Per session expose:

```text
selected Android network + transport type
relay endpoint
RTT min/avg/p95
packet loss and late rate
reordering count
jitter estimate
jitter-buffer target/current depth
clock correction ppm
DTE queue depths
DCD/CTS/DSR state
AT/modem state
media sequence/timestamp counters
input/output xruns
DSP carrier level/state
current modem mode/rate
last disconnect reason
```

## 20. Explicit non-goals for Beta 0.1

- Claiming V.34, V.90 or V.92 compliance.
- Seamless bearer handover during a call.
- Generic Play-store cellular-call audio injection/capture.
- Full V.150.1 compliance.
- Full V.152 compliance.
- T.38 fax gateway completeness.

These remain planned research/implementation tracks.