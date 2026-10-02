# I1 Android Relay Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use test-driven development for every behavioral task.

**Goal:** Implement the Android-side ADUP v1 relay session as a deterministic pure-Java state machine plus a thin network-bound TLS transport adapter.

**Architecture:** `core-session` owns handshake, authentication envelope, call/session IDs, BYTE_RELAY sequencing, heartbeat and terminal-state semantics. It never opens sockets. `platform-android` owns selected-Network TCP creation, TLS wrapping, reader/writer threads and maps decoded frames/events into the pure session reducer.

**Tech Stack:** Java 17, JUnit 5, existing `core-protocol` and `core-network`, Android platform adapter compiled at API 36.

**Spec:** `docs/spec/S1_WIRE_PROTOCOL.md`, `docs/spec/S1_NETWORK_THREADING.md`, `docs/spec/S1_SPEC_FREEZE.md`.

## Global Constraints

- HELLO must be the first application frame after TLS establishment.
- No non-handshake application traffic before HELLO_ACK and AUTH_OK.
- Android generates a random 128-bit call ID per dial.
- Session ID is assigned by DIAL_ACCEPTED.
- BYTE_RELAY sequence is zero-based byte offset in each direction; duplicates, overlaps and gaps are protocol violations in Beta.
- Initial receive window is 256 KiB.
- DATA_BYTES payload is 1..32768 bytes.
- Active-call network change does not migrate the socket in I1.
- Every call terminates exactly once with one internal terminal reason.

## Task 1: Pure relay state reducer

Create `android/core-session` and tests covering HELLO/AUTH ordering, dial acceptance/progress, failures, remote termination, stale IDs and illegal frame ordering.

## Task 2: BYTE_RELAY sender/receiver

Add bounded sender chunking, sequence offsets, peer flow-window handling and strict contiguous receive validation.

## Task 3: Heartbeat state

Add PING/PONG nonce tracking and monotonic timeout event hooks without sleeping inside the reducer.

## Task 4: Android network-bound TLS stream

Use `BoundNetworkSockets.openTcp(selectedNetwork, ...)`, wrap only that connected socket in an `SSLSocket`, require TLS 1.3, validate hostname/certificate, and run exactly one reader and one serialized writer owner.

## Task 5: Session diagnostics

Expose immutable session state, call/session safe IDs, sequence counters, queue/window state, heartbeat age and terminal reason.

## Task 6: Integration harness

Connect the Android-side Java session implementation to the existing Python relay over TLS in an integration/device harness. CI may validate the pure Java reducer and Android compilation; real Wi-Fi/CELLULAR bearer binding remains a physical-device acceptance gate.
