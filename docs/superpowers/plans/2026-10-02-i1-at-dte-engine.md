# I1 AT Engine and TCP DTE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the modem-facing control layer that accepts DTE bytes, implements the frozen Beta AT/V.250-style command behavior, preserves binary online data, handles timed `+++` escape/ATO/ATH, and exposes a TCP DTE test endpoint.

**Architecture:** Split parsing from execution. `AtLineParser` converts a complete command line into immutable commands with syntax validation before mutation; `AtEngine` owns profile/settings and result formatting; `EscapeDetector` handles online-data guard timing without interpreting arbitrary binary bytes; `ModemController` owns COMMAND/DIALING/ONLINE_DATA/ONLINE_COMMAND transitions and delegates dial/data/hangup effects through a small session interface. `TcpDteServer` is only a byte transport and never owns modem state.

**Tech Stack:** Python 3 standard library, `asyncio`, `unittest`, dataclasses/enums; no third-party runtime dependencies.

**Spec:** `docs/spec/S1_AT_DTE.md`, `docs/spec/S1_SPEC_FREEZE.md`, `docs/spec/S1_NETWORK_THREADING.md`, `docs/spec/S1_TEST_PLAN.md`

## Global Constraints

- Command mode is ASCII-compatible and line-oriented; online-data mode is 8-bit clean.
- Default terminator CR=13; LF after CR ignored; max command line 512 bytes excluding terminator.
- A line is syntax-validated completely before any command on it mutates state.
- Minimum result codes: OK/CONNECT/RING/NO CARRIER/ERROR/NO DIALTONE/BUSY/NO ANSWER with V/Q behavior.
- Required S-registers: S0, S2, S3, S4, S5, S7, S10, S12 with frozen defaults.
- `+++` uses S2 and S12 guard-time semantics; it is never a substring search.
- DSR/DCD/CTS/RI logical signal state exists even when TCP cannot physically expose pins.
- Only `ModemController` changes top-level modem state in this prototype slice.

## Review Focus

- A syntax error late in a multi-command line must not partially apply earlier valid settings.
- Online binary data containing NUL, CR, `+`, and 0xFF must remain byte-for-byte intact unless it satisfies the exact timed escape sequence.
- Guard-time boundary values must be deterministic at exactly pre/post guard expiration.
- Quiet/numeric modes must not accidentally suppress or reformat data-mode bytes.
- DTE disconnect during DIALING/ONLINE_DATA must produce one hangup effect and leave DCD low.

---

### Task 1: AT command parser and immutable command model

**Files:**
- Create: `prototype/python/androidialup_modem/__init__.py`
- Create: `prototype/python/androidialup_modem/at_parser.py`
- Test: `prototype/python/tests/test_at_parser.py`

**Interfaces:**
- Produces: `AtLineParser.parse(line: bytes) -> tuple[AtCommand, ...]`, command dataclasses/enums for E/Q/V/Z/&F/H/A/D/O/I/S/+MODE/+NET/+DIAG.

- [ ] Write failing tests for mixed case, bare AT, chained basic commands, S-register query/set, + commands, dial T/P compatibility, unknown command, semicolon rejection, and syntax-error atomicity inputs.
- [ ] Run focused test and verify RED.
- [ ] Implement parser with no state mutation and a 512-byte line limit.
- [ ] Run focused/full suite and verify GREEN.
- [ ] Commit.

### Task 2: Profile, result formatting, and AT execution

**Files:**
- Create: `prototype/python/androidialup_modem/profile.py`
- Create: `prototype/python/androidialup_modem/at_engine.py`
- Test: `prototype/python/tests/test_at_engine.py`

**Interfaces:**
- Consumes parsed commands.
- Produces: `AtEngine.execute(commands, context) -> AtExecution` containing output lines and requested effects; `ModemProfile` with S-registers, echo, quiet, verbose, mode, network policy, diagnostics flag.

- [ ] Write failing tests for factory defaults, E/Q/V, S-register bounds, ATI0..4, +MODE/+NET/+DIAG queries/sets, ATZ/&F, text/numeric result formatting, and full-line validation before mutation.
- [ ] Run focused RED.
- [ ] Implement minimal execution and effect model; DIAL/HANGUP/ANSWER/ATO become effects rather than socket operations.
- [ ] Run focused/full GREEN.
- [ ] Commit.

### Task 3: Timed escape detector

**Files:**
- Create: `prototype/python/androidialup_modem/escape.py`
- Test: `prototype/python/tests/test_escape.py`

**Interfaces:**
- Produces: `EscapeDetector.feed(byte, now_ms, escape_char, guard_ms) -> EscapeAction`, `.timer(now_ms) -> EscapeAction`; actions forward bytes, hold, or signal escape.

- [ ] Write failing tests for exact pre-guard, three-plus candidate, post-guard, too-fast pre-guard, extra plus, intervening byte, S2>127 disabled, and binary plus bytes.
- [ ] Verify RED.
- [ ] Implement exactly-three-byte bounded candidate state.
- [ ] Verify focused/full GREEN.
- [ ] Commit.

### Task 4: Modem controller/reducer

**Files:**
- Create: `prototype/python/androidialup_modem/controller.py`
- Test: `prototype/python/tests/test_modem_controller.py`

**Interfaces:**
- Consumes AT engine, EscapeDetector, and abstract `SessionPort` callbacks/effects.
- Produces: `ModemController.feed_dte(data, now_ms)`, `.on_dial_result(...)`, `.on_remote_data(...)`, `.on_remote_hangup(...)`, immutable state/signal snapshots.

- [ ] Write failing tests for COMMAND -> DIALING -> ONLINE_DATA, BUSY/NO_DIALTONE/NO_ANSWER/NO_CARRIER mappings, binary online forwarding, escape -> ONLINE_COMMAND, ATO resume, ATH hangup, DCD timing, and one terminal result.
- [ ] Verify RED.
- [ ] Implement serialized synchronous reducer semantics for the prototype.
- [ ] Verify focused/full GREEN.
- [ ] Commit.

### Task 5: TCP DTE transport

**Files:**
- Create: `prototype/python/androidialup_modem/tcp_dte.py`
- Test: `prototype/python/tests/test_tcp_dte.py`

**Interfaces:**
- Consumes `ModemController`.
- Produces: `TcpDteServer(host, port, controller_factory)`, one controller per client connection, raw byte read/write path.

- [ ] Write failing localhost tests for `AT\r -> OK`, fragmented command bytes, multiple commands per read, binary online data, client EOF cleanup, and independent simultaneous clients.
- [ ] Verify RED.
- [ ] Implement with `asyncio.start_server`; no AT parsing in transport layer.
- [ ] Verify focused/full GREEN.
- [ ] Commit.

### Task 6: AT-to-relay adapter and local end-to-end modem test

**Files:**
- Create: `prototype/python/androidialup_modem/relay_port.py`
- Create: `prototype/python/tests/test_e2e_tcp_dte_relay.py`

**Interfaces:**
- Consumes live TLS relay from prior plan plus ModemController.
- Produces an async `RelaySessionPort` adapter mapping DIAL/data/hangup to ADUP frames and relay events back to controller callbacks.

- [ ] Write failing end-to-end test: terminal TCP client sends `AT`, `ATDloopback`, observes CONNECT, transfers pseudorandom binary bytes, performs timed `+++`, receives OK, `ATO` receives CONNECT, `ATH` receives OK and DCD becomes false in snapshot.
- [ ] Verify RED.
- [ ] Implement minimal adapter/effect executor needed for the test.
- [ ] Run full suite and require zero failures/errors.
- [ ] Commit.

### Task 7: Status checkpoint

**Files:**
- Create: `docs/implementation/I1_AT_DTE_STATUS.md`

- [ ] Record final GitHub Actions run/test count and proven modem semantics.
- [ ] List remaining Android-specific NetworkSelector and platform integration work.
- [ ] Commit.

## Self-review

The parser and controller remain separate so syntax validation can be proven independently from state mutation. The TCP DTE implementation is intentionally a test transport; USB remains I2. Task 6 is the first prototype where a normal terminal-style TCP client exercises modem semantics through the real TLS relay and loopback gateway.
