You are a senior telecommunications engineer, modem DSP engineer, Android networking/NDK engineer, VoIP engineer, embedded engineer, and protocol implementation engineer.

Your assignment is to perform deep technical research and then produce an IMPLEMENTATION-LEVEL ENGINEERING SPECIFICATION for an Android-based dial-up modem system.

This document will become the authoritative specification from which coding agents build the first beta. Do not merely describe concepts. Resolve architectural questions using standards, measurements, prior implementations, engineering literature, textbooks, source code, and reproducible experiments.

The central research question is:

CAN AN ANDROID DEVICE IMPLEMENT A PRACTICAL DIAL-UP MODEM WHOSE CONNECTION TO THE REMOTE SIDE TRAVELS OVER EITHER:

1. Wi-Fi / Internet
2. cellular packet data such as LTE/5G
3. cellular voice service

Ideally the same Android modem application should operate over Wi-Fi OR cellular data, automatically or manually selected.

Cellular voice-call modem operation is desirable only if technically practical.

Rooting, custom Android builds, specialized Android hardware, external hardware, a custom server/gateway, SIP infrastructure, or a small analog telephone interface are acceptable.

Do not artificially constrain the project to ordinary unmodified consumer Android APIs if a significantly better architecture exists.

DEEP RESEARCH REQUIREMENT

Do not limit research to scientific papers.

The research phase must actively search across ALL useful source categories below.

1. OFFICIAL STANDARDS

Research primary standards first where possible.

Include:

ITU-T V.8
ITU-T V.21
ITU-T V.22
ITU-T V.22bis
ITU-T V.32
ITU-T V.32bis
ITU-T V.34
ITU-T V.90
ITU-T V.92
ITU-T V.42
ITU-T V.42bis
ITU-T V.150.0
ITU-T V.150.1
ITU-T V.152
ITU-T V.250
ITU-T T.30
ITU-T T.38

Also locate relevant IETF RFCs for:

RTP
SIP
SDP
NAT traversal
ICE
STUN
TURN
QUIC
packet redundancy
FEC
real-time media transport

For each standard, identify the specific sections applicable to this project.

2. BOOKS AND TEXTBOOKS

Actively search for PDF books, scanned books, archived technical books, university-hosted texts, publisher samples, Internet Archive borrowable material, public-domain works, text files/ezines, and downloadable technical references related to:

data modem theory
telephone channel characteristics
QAM modems
trellis-coded modulation
adaptive equalization
echo cancellation
carrier recovery
symbol timing recovery
phase-locked loops
Viterbi decoding
telephone networks
PCM systems
56K modem architecture
DSP implementation
VoIP
RTP
modem-over-IP
fax modem architecture
software-defined radio concepts applicable to modems

Especially search for older telecommunications and modem engineering books from the 1970s through early 2000s, because many contain implementation details absent from modern summaries.

Useful categories may include books on:

Data Communications Principles
Data Modems
Digital Communications
Digital Signal Processing
Adaptive Signal Processing
Telecommunication Transmission Systems
Telephone Switching and Transmission
Voiceband Data Transmission
Digital Modulation
Error Control Coding
Practical DSP

For every useful book found:

record title
author
edition
publication year
publisher
legal access URL
whether full PDF, preview, borrowable copy, or partial access
specific chapters/pages relevant to this project
key algorithms or implementation details learned

Do not rely solely on snippets from search results.

If a PDF is available, inspect the actual PDF and identify the most useful sections.

3. UNIVERSITY MATERIAL

Search for:

course notes
lecture PDFs
theses
dissertations
technical reports
lab manuals
DSP assignments
modem implementation projects
software modem projects
telecommunications courses

Prioritize university-hosted PDFs when they contain implementation details or derivations.

4. HISTORICAL MODEM DOCUMENTATION

Search for original technical manuals and application notes from modem chipset manufacturers such as:

Rockwell
Conexant
Lucent
Agere
USRobotics
3Com
Motorola
Texas Instruments
Analog Devices
Silicon Labs
Cirrus Logic
Zilog

Look for:

chipset programming manuals
AT command manuals
DSP documentation
reference designs
application notes
line interface designs
DAA designs
V.34 implementation notes
V.90/V.92 architecture documentation
training sequence descriptions
debugging procedures
register descriptions
line-quality metrics

These sources may contain extremely valuable implementation details.

5. PATENTS

Search patents only where useful for understanding historical implementations.

Relevant areas include:

V.34
V.90
V.92
echo cancellation
adaptive equalization
precoding
shell mapping
trellis coding
PCM downstream modulation
timing recovery
modem relay

Use patents as explanatory sources, not as the sole basis for implementation.

Identify patent status where relevant before recommending direct implementation of unusually specific patented techniques.

6. OPEN-SOURCE PROJECTS

Perform a deep search for related open-source projects.

Do not restrict the search to well-known projects.

Search GitHub, GitLab, SourceForge, old project archives, package repositories, university repositories, and historical mirrors.

Investigate at minimum where applicable:

SpanDSP
Asterisk
FreeSWITCH
minimodem
Dire Wolf
fldigi
multimon-ng
soundmodem
Linmodem
slmodem
DSP modem projects
softmodem projects
fax modem projects
V.21 implementations
V.22 implementations
V.23 implementations
V.32 implementations
V.34 experiments
V.90 experiments
T.38 implementations
V.150 implementations
RTP modem transport
PPP implementations
SLIP implementations
software TNCs
AX.25 modem implementations

Also search for:

Android acoustic modem projects
Android FSK modem projects
Android ultrasonic modem projects
Android audio networking projects
WebRTC modem experiments
VoIP modem passthrough projects
Asterisk modem passthrough patches
FreeSWITCH modem passthrough work
PBX modem compatibility projects

For every useful repository found, document:

repository URL
project status
last update
language
license
implemented standards
DSP algorithms used
architecture
reusable modules
test assets
waveform test files
protocol parsers
known limitations
interoperability reports

Explain obligations for:

MIT
BSD
Apache-2.0
LGPL
GPL
AGPL
MPL
public domain
custom licenses

Do not copy code from an incompatible source.

If code cannot be reused, extract only general engineering concepts and independently implement them.

7. SOURCE CODE ARCHAEOLOGY

For abandoned but useful modem projects, inspect the source code even if documentation is weak.

Look specifically for implementations of:

oscillators
filters
Goertzel detectors
FSK demodulators
QPSK/QAM demodulators
AGC
PLL
timing recovery
scramblers
descramblers
adaptive equalizers
echo cancellers
Viterbi decoders
training state machines
AT parsers
V.42 LAPM
V.42bis
fax tone detection
RTP jitter buffers
sample clock compensation

Document which exact source files are useful.

8. BINARY AND TEST ASSETS

Search open-source repositories for:

PCM recordings
WAV modem captures
training sequences
reference bitstreams
test vectors
constellation captures
PCAPs
RTP captures
fax recordings
modem negotiation traces

These can become regression-test assets.

Prefer externally generated vectors rather than relying solely on our own encoder and decoder.

9. HISTORICAL INTERNET ARCHIVES

Use archived documentation where appropriate.

Search:

Internet Archive
Wayback Machine
old FTP mirrors
archived vendor websites
old Linux HOWTOs
modem FAQs
telecommunications mailing lists
Usenet archives
old developer forums

Historical modem engineers frequently documented interoperability details that may not exist in formal standards.

Treat informal claims as secondary evidence and verify them where possible.

10. PRACTICAL REPORTS AND FIELD EXPERIENCE

Search technical forums, mailing lists, PBX communities, ham-radio communities, Asterisk/FreeSWITCH forums, DSP communities, and modem restoration communities for empirical reports involving:

modems over VoIP
modems over G.711
modems over cellular calls
VoLTE modem behavior
ATA compatibility
fax passthrough
V.90 over digital PBXs
V.34 over SIP
codec effects on modem signals

Use these sources to identify real-world failure modes and experiments.

Do not treat forum claims as authoritative unless independently supported.

RESEARCH DATABASE

Before writing the engineering specification, create a research inventory.

Each source should contain:

ID
source type
title
author/project
date
URL
license/access status
technical relevance
specific information extracted
confidence level

Classify confidence:

PRIMARY
STRONG SECONDARY
SECONDARY
ANECDOTAL

LITERATURE SYNTHESIS

Do not merely list sources.

For each major subsystem, compare what multiple sources say.

Example:

SYMBOL TIMING RECOVERY

Source A:
Gardner detector appropriate under specific sampling conditions.

Source B:
Mueller and Müller useful under different sampling conditions.

Open-source Project C:
uses a particular timing loop successfully.

Decision:
Use algorithm X for Beta because...

Apply this approach to:

timing recovery
carrier recovery
adaptive equalization
echo cancellation
modulation
handshake detection
jitter buffering
packet transport
clock correction
AT parsing
error correction

CODE REUSE STRATEGY

Create a section called:

OPEN-SOURCE REUSE PLAN

For each subsystem identify:

BUILD OUR OWN
ADAPT EXISTING CODE
LINK EXISTING LIBRARY
PORT EXISTING MODULE
REFERENCE ONLY

Include rationale.

Examples:

FSK modem
QAM modem
V.42
V.42bis
RTP
jitter buffer
SIP
PPP
USB serial
DSP filters
FFT
fax support

Avoid rewriting mature infrastructure without a reason.

Conversely, do not bring in massive frameworks for very small functionality.

ARCHITECTURAL RESEARCH QUESTION

Determine whether the best project design is:

A. FULL SOFTWARE MODEM

Android performs complete modulation/demodulation.

B. HYBRID

Android performs control and networking while an existing modem DSP library provides part of the physical layer.

C. MODEM RELAY

Android interprets AT commands and exchanges modem relay data across Wi-Fi/cellular data without continuously emulating a telephone waveform.

D. REMOTE MODEM

Android acts as the user interface and AT endpoint while a server or hardware modem performs the actual legacy modem connection.

E. MULTI-MODE

Support several architectures behind one modem interface.

Evaluate each based on research.

CORE TRANSPORT COMPARISON

Perform a rigorous comparison between:

Wi-Fi packet data
LTE/5G packet data
cellular voice

For each determine:

reliability
latency
jitter
packet loss
NAT
CGNAT
incoming connectivity
power consumption
Android API accessibility
carrier restrictions
audio processing
maximum realistic modem speed
compatibility with legacy analog modems
required gateway infrastructure
difficulty
portability across Android devices

Select:

PRIMARY TRANSPORT
SECONDARY TRANSPORT
EXPERIMENTAL TRANSPORT

for Beta 0.1.

Do not choose before completing research.

WIFI AND CELLULAR DATA

Design Wi-Fi and cellular packet data as equivalent underlying IP transports.

Investigate whether Android should use:

ConnectivityManager
NetworkCapabilities
NetworkRequest
NetworkCallback
Network.bindSocket()
socketFactory
per-socket network binding

Design:

NetworkSelector

with support for:

AUTOMATIC
WIFI_ONLY
CELLULAR_ONLY
PREFER_WIFI
PREFER_CELLULAR

Provide implementation-grade pseudocode.

NETWORK HANDOVER

Research whether an active modem session could migrate between Wi-Fi and cellular.

Evaluate:

QUIC connection migration
MPTCP
custom UDP session protocol
application-level reconnection
relay-server session IDs

Seamless handover is not required for Beta 0.1 but the protocol should not prevent it.

CELLULAR VOICE RESEARCH

Perform dedicated deep research into sending actual modem waveforms over:

GSM voice
UMTS voice
VoLTE
VoNR

Research:

AMR-NB
AMR-WB
EVS
transcoding
VAD
DTX
comfort noise
AEC
AGC
noise suppression
packet loss concealment

Search specifically for documented experiments involving dial-up modems over cellular networks.

Find empirical reports and, where possible, recordings or test data.

Determine approximate practical limits for:

Bell 103
V.21
V.22
V.22bis
V.32
V.32bis
V.34

Do not claim a mode works without evidence.

V.150.1

Study V.150.1 deeply.

Determine whether modem relay is a better architecture than transporting raw modem audio.

Investigate existing implementations.

If source implementations exist, determine whether any can be reused.

V.152

Study V.152 deeply.

Determine how voice-band data is transported across packet networks.

Investigate:

G.711
RTP
jitter
latency
VAD disablement
comfort-noise disablement
packet loss
clock drift

Search for real implementation source code.

REMOTE GATEWAY

Research gateway options:

Linux + USB hardware modem
Linux + SpanDSP
Asterisk
FreeSWITCH
FXO hardware
PRI/ISDN
SIP trunk
ATA
software DSP modem

Find open-source components that could reduce development work.

Identify which gateway can be assembled fastest for Beta testing.

PSEUDOCODE REQUIREMENT

The final engineering specification must contain implementation-grade pseudocode for EVERY major subsystem.

At minimum:

main modem engine
network selector
Wi-Fi/cellular network binding
session manager
relay client
relay server
RTP transport
VBD transport
jitter buffer
clock-recovery system
packet loss handler
audio engine
RX modem DSP
TX modem DSP
NCO
FIR filters
IIR filters
Goertzel detector
AGC
carrier detector
symbol timing recovery
carrier recovery
FSK modem
PSK modem
QAM modem
scrambler
descrambler
adaptive equalizer
echo canceller
training sequence detector
Viterbi decoder
V.8 negotiation
rate fallback
call state machine
answer state machine
AT parser
AT dispatcher
S-register implementation
+++ escape detector
DTE interface
USB transport
Bluetooth transport
TCP transport
V.42
V.42bis
channel simulator
network simulator
test-vector runner
logging
diagnostics

Do not use vague pseudocode such as:

processSignal()
decodeModem()
handleNetwork()

Break every nontrivial operation into implementable steps.

ALGORITHMIC DETAIL

For DSP algorithms include:

mathematical equation
sample rate assumptions
loop coefficients
state variables
initialization
input/output types
failure detection
pseudocode
tests

For network algorithms include:

packet format
sequence numbering
timestamps
buffering
timeouts
reconnect behavior
loss behavior
ordering behavior
flow control

BOOK-BASED IMPLEMENTATION NOTES

Create a specific section called:

TEXTBOOK-DERIVED IMPLEMENTATION NOTES

Summarize practical implementation knowledge found in books that was not explicit in the standards.

Cite:

book
chapter
page where available

Examples might include:

PLL tuning
equalizer initialization
echo canceller convergence
QAM implementation
telephone channel modeling
carrier recovery
scrambler implementation
fixed-point considerations

OPEN-SOURCE IMPLEMENTATION NOTES

Create another section:

OPEN-SOURCE IMPLEMENTATION FINDINGS

For each useful project state:

what should be reused
what should be independently rewritten
what should only be used as a reference
why

Do not finish the research phase until enough sources have been found to make informed implementation decisions.

SOURCE QUALITY RULE

Use the following priority:

1. official standard
2. original chipset/vendor documentation
3. academic textbook
4. peer-reviewed paper/thesis
5. mature open-source implementation
6. university course material
7. experienced practitioner documentation
8. forums/anecdotal material

Lower-ranked sources may reveal useful facts, but verify important design decisions with stronger sources where possible.

FINAL OUTPUT

The resulting engineering specification must be detailed enough that separate coding agents can implement individual modules without inventing missing architecture.

Include:

1. Executive summary
2. Research methodology
3. Source inventory
4. Books and PDF references
5. Standards reviewed
6. Open-source projects reviewed
7. License analysis
8. Feasibility conclusions
9. Wi-Fi vs cellular-data vs cellular-voice comparison
10. Recommended architecture
11. Alternative architectures
12. Android network architecture
13. Modem-over-IP architecture
14. V.150.1 analysis
15. V.152 analysis
16. Cellular voice analysis
17. Gateway architecture
18. DSP architecture
19. Supported modem standards
20. AT command system
21. V.8
22. V.42/V.42bis
23. RTP/packet transport
24. Jitter buffer
25. Clock recovery
26. Network handover
27. CGNAT/relay design
28. Hardware requirements
29. Android audio architecture
30. Data structures
31. API contracts
32. Threading
33. Memory management
34. Detailed pseudocode
35. Channel simulator
36. Network simulator
37. Testing
38. Hardware interoperability
39. Beta 0.1 scope
40. Development milestones
41. Engineering unknowns
42. Required experiments
43. Open-source reuse plan
44. Textbook-derived implementation notes
45. Reference bibliography

The specification should favor proven existing implementations where reuse is legally and technically appropriate, while preserving clean modular boundaries so components can later be replaced with standards-compliant implementations.