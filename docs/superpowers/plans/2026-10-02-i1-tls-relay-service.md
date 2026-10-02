# I1 TLS Relay Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the green ADUP protocol/RelaySession core behind a real TLS 1.3 TCP service and prove complete HELLO/AUTH/DIAL/BYTE_RELAY/HANGUP behavior over actual sockets.

**Architecture:** `RelayTcpServer` accepts TLS streams, feeds arbitrary read chunks into `FrameStreamDecoder`, passes complete frames to one `RelaySession` per connection, serializes returned frames through one writer, and closes on protocol/transport failure. `AsyncFramedConnection` is a small client-side transport primitive used by tests now and later by the Android/control implementation as a behavioral reference.

**Tech Stack:** Python 3 standard library: `asyncio`, `ssl`, `socket`, `unittest`; OpenSSL command-line only for ephemeral test certificate generation in CI/tests.

**Spec:** `docs/spec/S1_SPEC_FREEZE.md`, `docs/spec/S1_WIRE_PROTOCOL.md`, `docs/spec/S1_NETWORK_THREADING.md`, `docs/spec/S1_TEST_PLAN.md`

## Global Constraints

- TLS 1.3 minimum for relay control and BYTE_RELAY.
- One read owner and one serialized write owner per connection.
- `FrameStreamDecoder` remains the only stream reassembly/parser boundary.
- Each TCP connection owns exactly one `RelaySession`.
- Protocol violation closes only the affected connection.
- Maximum payload and stream-buffer bounds remain those already implemented.
- No plaintext fallback is enabled by production/default server helpers.
- Tests may use a generated self-signed certificate with a test trust store; certificate verification remains enabled client-side.

## Review Focus

- Split fixed header and payload across many socket reads must decode exactly once.
- Multiple ADUP frames in one socket read must remain ordered and produce ordered responses.
- Wrong/untrusted server certificate must fail the client TLS handshake.
- A malformed ADUP frame must terminate that client connection without killing the listening server or another valid client.
- Writer shutdown/EOF during active calls must not leak tasks or leave sessions reusable as connected.

---

### Task 1: TLS context helpers

**Files:**
- Create: `prototype/python/androidialup_relay/tls.py`
- Test: `prototype/python/tests/test_tls_config.py`

**Interfaces:**
- Produces: `create_server_ssl_context(certfile, keyfile) -> ssl.SSLContext`, `create_client_ssl_context(cafile, server_hostname_required=True) -> ssl.SSLContext`.

- [ ] Write failing tests asserting `minimum_version == ssl.TLSVersion.TLSv1_3`, hostname/certificate verification enabled for clients, and no client-auth requirement by default.
- [ ] Run focused test and verify RED.
- [ ] Implement the helpers using Python `ssl` only.
- [ ] Run focused/full suite and verify GREEN.
- [ ] Commit.

### Task 2: Async framed connection primitive

**Files:**
- Create: `prototype/python/androidialup_protocol/async_connection.py`
- Test: `prototype/python/tests/test_async_connection.py`

**Interfaces:**
- Consumes: `Frame`, `encode_frame`, `FrameStreamDecoder`.
- Produces: `AsyncFramedConnection(reader, writer, max_payload=...)`, `.send_frame(frame)`, `.recv_frame()`, `.close()`.

- [ ] Write failing localhost tests for fragmented reads, multiple frames per read, ordered send serialization, EOF, and malformed frame propagation.
- [ ] Run focused test and verify RED.
- [ ] Implement with one internal decoded-frame deque; `recv_frame()` reads until one complete frame or EOF.
- [ ] Run focused/full suite and verify GREEN.
- [ ] Commit.

### Task 3: Relay TLS server

**Files:**
- Create: `prototype/python/androidialup_relay/server.py`
- Modify: `prototype/python/androidialup_relay/__init__.py`
- Test: `prototype/python/tests/test_relay_server.py`

**Interfaces:**
- Consumes: `AsyncFramedConnection`, `RelaySession`, backend factory.
- Produces: `RelayTcpServer(host, port, ssl_context, session_factory=None)`, async `.start()`, `.close()`, `.address`, and per-client handler.

- [ ] Write failing TLS integration tests for real HELLO/AUTH/DIAL/data/hangup, fragmentation, two simultaneous clients, malformed-client isolation, and clean EOF.
- [ ] Run focused test and verify RED.
- [ ] Implement server with `asyncio.start_server(..., ssl=ssl_context)` and one task/session per client; responses preserve list order and call `drain()` through the framed writer.
- [ ] Run focused/full suite and verify GREEN.
- [ ] Commit.

### Task 4: Socket-level 1 MiB acceptance

**Files:**
- Create: `prototype/python/tests/test_e2e_tls_loopback.py`

**Interfaces:**
- Consumes Tasks 1-3 and existing protocol messages/session behavior.
- Produces no new public API.

- [ ] Write an end-to-end TLS test that generates/trusts an ephemeral certificate, opens the real relay server, completes HELLO/AUTH/DIAL, transfers deterministic 1 MiB binary data, refreshes FLOW_STATUS, exercises PING/PONG and HANGUP, and compares bytes exactly.
- [ ] Add a certificate-verification negative test using a different CA/self-signed cert.
- [ ] Run focused test and verify RED for first missing behavior.
- [ ] Implement minimal fixes only.
- [ ] Run full suite and require zero failures/errors.
- [ ] Commit.

### Task 5: CI and implementation status

**Files:**
- Modify: `.github/workflows/prototype-tests.yml` only if OpenSSL/test setup requires explicit package handling.
- Create: `docs/implementation/I1_TLS_RELAY_STATUS.md`

**Interfaces:**
- Produces GitHub-verifiable evidence and next-step list.

- [ ] Run GitHub Actions on the final commit.
- [ ] Record exact run ID, test count, TLS version assertion, socket-level 1 MiB result, and remaining Android/TCP-DTE work.
- [ ] Commit status note.

## Self-review

This plan intentionally does not implement Android network binding or AT parsing. It establishes a real network service around the already-frozen protocol core, which can be tested independently and then consumed by the Android implementation. TLS identity/provisioning beyond a standard CA/trust-store path remains configuration, not a new protocol decision.
