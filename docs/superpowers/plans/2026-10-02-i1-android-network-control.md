# I1 Android Network Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use test-driven development for every behavioral task.

**Goal:** Build the Android-side network-selection/control foundation required by I1, beginning with a device-independent scoring engine and then adapting Android `ConnectivityManager`/`Network` objects to it.

**Architecture:** Keep policy/scoring/hysteresis pure and deterministic in a JVM module with no Android dependencies. The Android adapter owns `ConnectivityManager`, normalizes callbacks into immutable candidates, delegates selection to the core engine, and creates sockets through the selected Android `Network` rather than process-wide binding.

**Tech Stack:** Java 17 core module, Gradle 8.x, Android Java/Kotlin adapter in the subsequent task, JUnit 5.

**Spec:** `docs/spec/S1_NETWORK_THREADING.md` and `docs/spec/S1_SPEC_FREEZE.md`.

## Global Constraints

- Policies: AUTOMATIC, WIFI_ONLY, CELLULAR_ONLY, PREFER_WIFI, PREFER_CELLULAR.
- Candidate must have INTERNET and validated Internet unless developer override is enabled.
- Score starts at 1000; known RTT subtracts min(RTT ms, 800), unknown RTT subtracts 250.
- Known loss subtracts `1000 * clamp(loss_fraction, 0, 0.5)`.
- Metered subtracts 25; roaming subtracts 50.
- Preferred transport receives +150 for PREFER_WIFI/PREFER_CELLULAR.
- Idle handover requires competitor score >= selected +100 unless selected becomes ineligible.
- No automatic socket migration during an active Beta call.
- Socket binding must use the selected Android `Network`; process-wide binding is prohibited.

## Review Focus

- invalid/NaN/out-of-range RTT/loss inputs are normalized deterministically;
- selected network becoming ineligible bypasses hysteresis;
- exact 100-point boundary changes selection;
- active-call selection never migrates merely because a better candidate appears;
- deterministic tie-breaks do not depend on map iteration order.

### Task 1: Pure network selection engine

**Files:**
- Create: `android/settings.gradle`
- Create: `android/build.gradle`
- Create: `android/core-network/build.gradle`
- Create: `android/core-network/src/main/java/io/circuitdrift/androidialup/network/NetworkPolicy.java`
- Create: `android/core-network/src/main/java/io/circuitdrift/androidialup/network/NetworkTransport.java`
- Create: `android/core-network/src/main/java/io/circuitdrift/androidialup/network/NetworkCandidate.java`
- Create: `android/core-network/src/main/java/io/circuitdrift/androidialup/network/NetworkSelectionEngine.java`
- Create: `android/core-network/src/test/java/io/circuitdrift/androidialup/network/NetworkSelectionEngineTest.java`

**Produces:** deterministic eligibility, scoring, tie-break, hysteresis and active-call selection behavior.

- [ ] Write tests for all five policies, validation filtering, scoring, tie breaks, hysteresis boundary, selected loss, active-call no-migration and malformed measurement normalization.
- [ ] Run tests and confirm RED before implementation.
- [ ] Implement the minimal pure Java engine.
- [ ] Run focused and full Java tests to GREEN.

### Task 2: Android ConnectivityManager adapter

**Files:**
- Create Android app/core adapter module and `AndroidNetworkManager`.
- Add unit-testable candidate-normalization helpers.
- Add Android instrumentation contract tests where platform APIs are required.

**Produces:** one owner for callbacks and selected `Network`, with immutable snapshots.

### Task 3: Per-Network socket factory

**Produces:** TCP through `network.socketFactory.createSocket()` and UDP/native binding through `network.bindSocket()`, plus selected-network DNS resolution where supported.

### Task 4: Diagnostics snapshot

**Produces:** policy, selected transport/handle, validation/metering/roaming state, scores, probe metrics, reason for last selection change, and active-call migration suppression state.

### Task 5: Device acceptance harness

**Produces:** developer screen/CLI hooks for WIFI_ONLY and CELLULAR_ONLY selection and explicit evidence that relay sockets use the selected Android `Network`.
