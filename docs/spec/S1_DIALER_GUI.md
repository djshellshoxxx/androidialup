# S1 Developer Dialer GUI and Call-Progress Log

Status: normative for Beta 0.1 (developer/diagnostic surface).

This specifies the on-device developer dialer screen and the call-progress
log that the Android app presents on top of the AT/modem core
(`S1_AT_DTE.md`) and the relay session (`S1_WIRE_PROTOCOL.md`). It is a
diagnostic/operator surface, not the computer-facing DTE transport, which
remains USB/Bluetooth/TCP per `PROJECT_PLAN.md` phases I1/I2.

## 1. Scope and non-goals

The dialer places one authenticated call at a time to a single operator-chosen
destination, drives DTMF where the call path supports it, applies a per-call
timeout, and records a structured call-progress log.

Non-goals, explicitly out of scope for Beta 0.1:

- No unattended sweep of a number range. The GUI SHALL NOT offer a control
  that enumerates a numeric range and dials its members automatically, and
  SHALL NOT auto-advance from one destination to the next without an explicit
  per-call operator action.
- No carrier/modem survey or discovery output. The log is the record of calls
  the operator placed, not a scan report.

These bounds follow the architecture: every call is one authenticated relay
session to one target terminated by a gateway, and the product's purpose is to
reach a known endpoint, not to discover unknown ones.

## 2. Destination entry

```text
DestinationInput {
  target: utf8 1..256 bytes      // passed verbatim to gateway policy
  dial_method: AUTO | TONE | PULSE   // maps to ATD / ATDT / ATDP
  mode: BYTE_RELAY | (future modes shown disabled)
  per_call_timeout_ms: u32 default 60000  // bounds DIALING before NO ANSWER
}
```

The target is validated to the AT dial rules before dialing; an invalid
target is rejected in the GUI without starting a call. The per-call timeout is
surfaced as the modem connect timeout and, on expiry, yields the standard
`NO ANSWER` termination.

## 3. Confirmed test-list mode (operator lines only)

For checking the operator's own lines, the GUI MAY accept a short explicit
list of individual destinations (not a range expression). This mode:

- requires the operator to confirm an authorization attestation once, stating
  the listed numbers are theirs or are authorized test lines;
- dials exactly one entry per explicit operator action and never auto-advances;
- places each entry as an independent authenticated call with its own timeout;
- produces the same per-call log as a single call.

A list that is empty, or any entry failing dial-target validation, blocks the
mode. The attestation text and the operator's acknowledgement are recorded in
the log header.

## 4. Live call state

The dialer reflects the modem state machine (`S1_AT_DTE.md`): COMMAND,
DIALING, ONLINE_DATA, ONLINE_COMMAND. It shows DCD and the current result
code, and offers Cancel (maps to `ATH` during DIALING) and Hang Up (maps to
`ATH`). It never fabricates state; it renders the reducer's snapshot.

## 5. Call-progress log

Each call appends one record:

```text
CallLogRecord {
  started_at_monotonic_ms
  target_redacted            // target shown per display policy
  dial_method
  progress[]: { phase, detail?, at_ms }   // ROUTING..CONNECTED per wire proto
  tones[]: { tone, at_ms }   // DIAL_TONE|RINGBACK|BUSY|REORDER|SIT|VOICE|CARRIER
  dtmf_detected[]: { digit, at_ms }
  negotiated: { rate?, protocol? }  // from onConnected, when the backend reports it
  outcome: CONNECT | BUSY | NO_DIALTONE | NO_ANSWER | NO_CARRIER | ERROR | CANCELLED
  internal_reason                 // S1 taxonomy reason
  ended_at_monotonic_ms
}
```

The `tones`, `dtmf_detected`, and `negotiated` fields are populated only when
the active backend reports them (see `S1_GATEWAY_BACKEND.md`); over a pure
BYTE_RELAY loopback they stay empty except for simulated progress. The log is
viewable on-device and exportable as newline-delimited JSON. No credential,
proof, nonce, or raw payload byte appears in the log.

## 6. Wiring

```text
DialerActivity
  -> ModemController (core-modem)         // AT execution, state, result codes
  -> RelaySessionMachine (core-session)   // dial/session, call-progress events
  -> AndroidNetworkManager (platform)     // selected network, diagnostics
```

The GUI calls only the public entry points of those components on their single
owner thread; it performs no socket or protocol work itself. Call-progress
events, tone reports, and DTMF reports arrive as session actions and are
appended to the current `CallLogRecord`.
