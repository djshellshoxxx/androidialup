# I1 Protocol, Relay, and Loopback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the first executable I1 slice from protocol-v1 payloads through a deterministic loopback call, so a client can negotiate, dial `loopback`, exchange arbitrary binary bytes, apply flow control, heartbeat, and cleanly hang up.

**Architecture:** Keep the existing `Frame`/`FrameStreamDecoder` as the transport envelope. Add typed payload codecs with strict bounds, then a single-connection relay session state machine that speaks those messages, and a gateway abstraction with a deterministic loopback backend. The first integration target is an in-process end-to-end test using the same frame/payload codecs that the later Android client and network relay will use.

**Tech Stack:** Python 3 standard library, `unittest`, dataclasses/enums/struct; no third-party runtime dependencies in this prototype slice.

**Spec:** `docs/spec/S1_SPEC_FREEZE.md`, `docs/spec/S1_WIRE_PROTOCOL.md`, `docs/spec/S1_GATEWAY_BACKEND.md`, `docs/spec/S1_TEST_PLAN.md`

## Global Constraints

- Protocol magic is `ADUP`; version is `1`; all multi-byte integers use network byte order.
- Maximum frame payload is 1 MiB; `DATA_BYTES` application payload is 1..32768 octets.
- HELLO must complete before authentication; authentication must complete before dialing.
- BYTE_RELAY uses byte-offset `stream_seq`; duplicates and overlaps are protocol violations.
- Initial receive window is 256 KiB; no user bytes may be silently dropped.
- Loopback backend supports at least ECHO, SINK, PATTERN, and DELAYED modes, with ECHO required for this slice.
- Control/session code is deterministic and bounded; no queue or parser may grow without limit.
- No third-party runtime dependency is introduced in this prototype slice.

## Review Focus

- Malformed length-prefixed UTF-8 fields must fail before allocating or slicing outside payload bounds.
- State-machine messages received out of order must produce a protocol failure rather than advancing state.
- `DATA_BYTES` sequence gaps, overlaps, duplicates, empty data, and >32768-byte chunks must be rejected deterministically.
- Flow-window exhaustion must stop forwarding without dropping accepted bytes.
- Repeated/late hangup and terminal events must be idempotent and produce one terminal call result.

---

### Task 1: Typed protocol payload codecs

**Files:**
- Create: `prototype/python/androidialup_protocol/messages.py`
- Modify: `prototype/python/androidialup_protocol/__init__.py`
- Test: `prototype/python/tests/test_messages.py`

**Interfaces:**
- Consumes: `FrameKind`, `ProtocolError` from `androidialup_protocol.frame`.
- Produces: dataclasses/enums plus `encode_payload(message) -> bytes` and `decode_payload(kind: FrameKind, payload: bytes)`.

- [ ] **Step 1: Write failing tests** for HELLO/HELLO_ACK, AUTH envelope, DIAL_REQUEST/DIAL_ACCEPTED/DIAL_FAILED, CALL_PROGRESS/CALL_TERMINATED, DATA_BYTES, FLOW_STATUS, PING/PONG, HANGUP_REQUEST/HANGUP_ACK. Assert round trips and exact bounds from S1.
- [ ] **Step 2: Run focused tests** with `python -m unittest tests.test_messages -v`; expected initial failure because `messages` does not exist.
- [ ] **Step 3: Implement minimal strict codecs** using explicit length-prefixed UTF-8/list/map helpers, enum validation, bounded strings/lists, and exact trailing-byte rejection.
- [ ] **Step 4: Run focused and full tests**: `python -m unittest tests.test_messages -v` then `python -m unittest discover -s tests -v`; expected PASS.
- [ ] **Step 5: Commit** payload codec and tests.

### Task 2: BYTE_RELAY sequencing and flow control

**Files:**
- Create: `prototype/python/androidialup_protocol/byte_relay.py`
- Test: `prototype/python/tests/test_byte_relay.py`

**Interfaces:**
- Consumes: `DataBytes`, `FlowStatus` from Task 1.
- Produces: `ByteRelayReceiver.accept(message: DataBytes) -> bytes`, `ByteRelaySender.build(data: bytes) -> list[DataBytes]`, `ByteRelaySender.update_flow(status: FlowStatus) -> None`.

- [ ] **Step 1: Write failing tests** for contiguous offsets, duplicate/overlap/gap rejection, empty/>32768 rejection, 32768-byte chunking, 256-KiB default peer window, and zero-window stop behavior.
- [ ] **Step 2: Run focused tests** and verify RED.
- [ ] **Step 3: Implement receiver/sender** with u64 byte-offset accounting, bounded local pending bytes, and no silent drop semantics.
- [ ] **Step 4: Run focused and full tests** and verify GREEN.
- [ ] **Step 5: Commit** BYTE_RELAY sequencing/flow-control code and tests.

### Task 3: Loopback gateway backend

**Files:**
- Create: `prototype/python/androidialup_gateway/__init__.py`
- Create: `prototype/python/androidialup_gateway/backend.py`
- Create: `prototype/python/androidialup_gateway/loopback.py`
- Test: `prototype/python/tests/test_loopback_backend.py`

**Interfaces:**
- Produces: `BackendEvent`, `BackendState`, `LoopbackBackend.open()`, `.dial(target, options)`, `.write(data)`, `.hangup(reason)`, `.poll_events()`.

- [ ] **Step 1: Write failing tests** for open->dial->progress->connected, ECHO byte-for-byte return including NUL/0xFF, SINK behavior, BUSY/NO_ANSWER/NO_DIALTONE simulation, and idempotent hangup.
- [ ] **Step 2: Run focused tests** and verify RED.
- [ ] **Step 3: Implement deterministic backend** with no threads for ECHO/SINK in the unit path; represent delayed behavior via scheduled timestamps rather than sleeps.
- [ ] **Step 4: Run focused and full tests** and verify GREEN.
- [ ] **Step 5: Commit** loopback backend and tests.

### Task 4: Relay connection state machine

**Files:**
- Create: `prototype/python/androidialup_relay/__init__.py`
- Create: `prototype/python/androidialup_relay/session.py`
- Test: `prototype/python/tests/test_relay_session.py`

**Interfaces:**
- Consumes: frame/message codecs, ByteRelay sender/receiver, gateway backend events.
- Produces: `RelaySession.handle_frame(frame: Frame) -> list[Frame]`, `RelaySession.poll() -> list[Frame]`, explicit session states.

- [ ] **Step 1: Write failing tests** for required ordering HELLO->AUTH->DIAL, out-of-order rejection, call/session ID validation, DIAL_ACCEPTED/progress generation, DATA echo, FLOW_STATUS handling, PING/PONG, HANGUP, duplicate dial idempotence, and one terminal event.
- [ ] **Step 2: Run focused tests** and verify RED.
- [ ] **Step 3: Implement single-writer deterministic reducer-style relay session**; no sockets yet, only frames in/frames out plus backend event polling.
- [ ] **Step 4: Run focused and full tests** and verify GREEN.
- [ ] **Step 5: Commit** relay state machine and tests.

### Task 5: End-to-end loopback protocol acceptance

**Files:**
- Create: `prototype/python/tests/test_e2e_loopback.py`
- Optionally modify only implementation files from Tasks 1-4 for bugs exposed by the test.

**Interfaces:**
- Consumes all prior tasks.
- Produces no new public API; proves the slice works as one system.

- [ ] **Step 1: Write failing integration test** that performs HELLO, AUTH, `DIAL_REQUEST(target="loopback")`, waits for CONNECTED, transfers deterministic pseudorandom binary data in both directions, exercises zero-window/reopen flow control, PING/PONG, HANGUP, and verifies exact echoed bytes and terminal state.
- [ ] **Step 2: Run focused integration test** and verify RED for the first missing behavior.
- [ ] **Step 3: Implement only minimal corrections** required for the end-to-end flow.
- [ ] **Step 4: Run `python -m unittest discover -s tests -v`** and require all tests PASS with zero failures/errors.
- [ ] **Step 5: Commit** integration test and any fixes.

### Task 6: Verification and implementation note

**Files:**
- Create: `docs/implementation/I1_PROTOCOL_RELAY_LOOPBACK_STATUS.md`

**Interfaces:**
- Consumes test output and commit state.
- Produces a concise record of implemented behavior, known omissions, and exact test command/result.

- [ ] **Step 1: Run full prototype suite** from `prototype/python` with `python -m unittest discover -s tests -v`.
- [ ] **Step 2: Record counts and covered I1 acceptance items**; explicitly list what remains for network TLS service, Android AT/DTE, Android NetworkSelector, and real Wi-Fi/cellular tests.
- [ ] **Step 3: Commit** verification note.

## Self-review

Spec coverage: this plan covers the protocol/control loopback slice only; Android AT/DTE, Android network selection, TLS socket service, diagnostics UI, and serial-modem hardware remain separate I1/I5 plans. That separation is intentional because this slice is independently testable and establishes the shared message/state contract first.

Type consistency: all later tasks consume the `Frame`, typed messages, BYTE_RELAY helpers, and backend interfaces defined in earlier tasks; no duplicate wire representation is introduced.

Review-focus coverage: malformed payload parsing belongs to Task 1; sequence/window failures to Task 2; terminal/idempotence failures to Tasks 3-4; integrated order and flow behavior to Task 5.
