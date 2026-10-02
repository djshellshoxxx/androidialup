# I1 Relay Liveness, Deadlines and Failure Injection: Status

Status: PASS for the Python reference prototype (`prototype/python`).
Suite: 199 tests, green on CPython 3.11, 3.12 and 3.13
(`cd prototype/python && python3 -m unittest discover -s tests -v`).

## Scope

This work implements S1_WIRE_PROTOCOL section 9 (heartbeat), the
S1_SPEC_FREEZE section 7 deadlines that apply to the relay link, and the
S1_TEST_PLAN sections 8 and 9 failure-injection cases for the relay and
gateway path.

## What was built

| Area | Module | Behaviour |
|---|---|---|
| Sans-IO core | `androidialup_protocol/liveness.py` | `TimeoutConfig` holds the S1 section 7 defaults, all injectable. `LinkFailure` only accepts S1 section 12 taxonomy reasons. `HeartbeatMonitor` covers PING scheduling, one outstanding nonce, failure detection and PONG validation. `ManualClock` is for tests. Time always comes from the caller as monotonic seconds. |
| Bounded close | `androidialup_protocol/async_connection.py` | `close(grace=3.0)` is bounded by the clean-disconnect grace and then aborts. `abort()` drops the connection at once. |
| Relay server | `androidialup_relay/server.py` | Each connection runs on one single-writer loop. It enforces the auth deadline, sends PINGs, detects heartbeat failure and polls the backend while the client is idle. Every connection gets exactly one `close_records` entry, and its backend lease is released. |
| Relay session | `androidialup_relay/session.py` | A backend crash mid-call becomes `CALL_TERMINATED(BACKEND_UNAVAILABLE, GATEWAY, "BACKEND_FAILED")`, and the control session stays up. A backend crash at dial time becomes `DIAL_FAILED(GATEWAY_UNAVAILABLE)`. `abort()` releases the lease. `heartbeat_seconds` can be configured. |
| Client port | `androidialup_modem/relay_port.py` | Answers relay PINGs and sends its own PING when idle. Enforces the connect, relay-auth and DIAL-ack deadlines. A link failure is reported once, as `on_remote_hangup(reason, detail)`. DIAL_FAILED is mapped to the DTE result plus the taxonomy reason. |
| Modem controller | `androidialup_modem/controller.py` | Keeps `terminal_reason` and `terminal_detail`. Every state or DCD change goes through one transition that publishes an immutable `ModemSnapshot` to an optional listener. |

### Taxonomy mapping implemented

| Failure | Detected by | Terminal reason | Detail | DTE |
|---|---|---|---|---|
| No PONG within heartbeat failure interval | client and relay | `NETWORK_LOST` | `HEARTBEAT_TIMEOUT` | NO CARRIER |
| TLS/TCP closed (FIN, close_notify) | client and relay | `NETWORK_LOST` | `TRANSPORT_CLOSED` | NO CARRIER |
| TLS/TCP reset or TLS error | client and relay | `NETWORK_LOST` | `TRANSPORT_ERROR` | NO CARRIER |
| Wrong-nonce or unsolicited PONG, malformed frame | client and relay | `PROTOCOL_VIOLATION` | parser message | NO CARRIER |
| Relay auth not completed in 8 s | relay (AUTH_FAIL `AUTH_TIMEOUT`, then close) and client | `AUTH_FAILURE` | `AUTH_TIMEOUT` | (no call yet) |
| TCP+TLS connect not completed in 10 s | client | `CONNECT_TIMEOUT` | `CONNECT_TIMEOUT` | (no call yet) |
| No DIAL_ACCEPTED/DIAL_FAILED in 5 s | client | `RELAY_UNAVAILABLE` | `DIAL_ACK_TIMEOUT` | NO CARRIER |
| Gateway/backend crash mid-call | relay, then `CALL_TERMINATED` to client | `BACKEND_UNAVAILABLE` | `BACKEND_FAILED` | NO CARRIER |
| Relay server shut down | relay | `RELAY_UNAVAILABLE` | `RELAY_SHUTDOWN` | client sees NETWORK_LOST, NO CARRIER |
| PING cannot be queued (tx queue full) | client | `QUEUE_OVERFLOW` | `RELAY_TX_QUEUE` | NO CARRIER |
| DIAL_FAILED BUSY / NO_DIALTONE / NO_ANSWER | client | `BACKEND_BUSY` / `BACKEND_NO_DIALTONE` / `BACKEND_NO_ANSWER` | none | BUSY / NO DIALTONE / NO ANSWER |
| DIAL_FAILED NO_ROUTE / GATEWAY_UNAVAILABLE / AUTHORIZATION_DENIED / UNSUPPORTED_MODE / TIMEOUT / INTERNAL_ERROR | client | `NO_ROUTE` / `BACKEND_UNAVAILABLE` / `AUTH_FAILURE` / `LOCAL_CONFIG` / `BACKEND_NO_ANSWER` / `INTERNAL_ERROR` | none | NO CARRIER |

## What is proven (tests)

- `tests/test_liveness.py` (15) proves the following with a manual clock:
  - the section 7 defaults;
  - taxonomy enforcement;
  - the exact PING and failure boundaries;
  - a single outstanding PING;
  - a single TIMEOUT;
  - other traffic does not answer a PING;
  - wrong-nonce and unsolicited PONGs are rejected.
- `tests/test_relay_liveness.py` (10) runs over real TLS against `RelayTcpServer`. It proves:
  - HELLO_ACK advertises the interval;
  - idle PINGs carry zero IDs and `request_id` 0;
  - answered PINGs keep the link;
  - a missing PONG closes the connection as `NETWORK_LOST/HEARTBEAT_TIMEOUT` after interval plus failure, and releases the backend;
  - wrong-nonce and unsolicited PONGs give `PROTOCOL_VIOLATION`;
  - client traffic defers the relay's PING;
  - no PING is sent before auth;
  - a silent client is closed at the auth deadline without any frame;
  - a stalled auth gets `AUTH_FAIL("AUTH_TIMEOUT")` and is then closed.
- `tests/test_relay_session_failures.py` (6) proves the session handles a backend crash during poll, write and dial, that `abort()` releases the lease, and that the heartbeat value can be configured.
- `tests/test_relay_port_liveness.py` (12) runs over real TLS. It proves:
  - the client answers relay PINGs and pings the relay itself when idle;
  - the negotiated interval is the default;
  - a missing PONG during a call gives exactly one `NETWORK_LOST/HEARTBEAT_TIMEOUT`;
  - a wrong-nonce PONG gives `PROTOCOL_VIOLATION`;
  - an idle link failure is silent until the next dial, which then fails with the same reason;
  - an unacknowledged dial gives `RELAY_UNAVAILABLE/DIAL_ACK_TIMEOUT` and the deadline is cleared by DIAL_ACCEPTED;
  - the auth deadline, the connect deadline, and AUTH_FAIL in place of a challenge are handled;
  - a full queue gives `QUEUE_OVERFLOW`.
- `tests/test_controller_termination.py` (7) proves:
  - exactly one NO CARRIER;
  - DCD low is published before NO CARRIER;
  - exactly one COMMAND snapshot;
  - stale CONNECT after a failure is ignored;
  - taxonomy reasons are kept for dial failures.
- `tests/test_async_connection_close.py` (2) proves the bounded close and the abort.
- `tests/test_e2e_failure_injection.py` (5) is the acceptance test. It runs end to end: DTE TCP, modem, TLS, relay, loopback.

  | Case | Injection | Asserted terminal reason |
  |---|---|---|
  | relay process killed mid-call | separate OS process (`tests/relay_process_helper.py`), SIGKILL | `NETWORK_LOST`, detail `TRANSPORT_CLOSED` or `TRANSPORT_ERROR` (see decision 9) |
  | relay stops responding | separate OS process, SIGSTOP | `NETWORK_LOST/HEARTBEAT_TIMEOUT`, no sooner than the failure interval |
  | TLS socket closed mid-call | relay-side `close()` (close_notify plus FIN) | `NETWORK_LOST`. Relay records one terminal reason and the backend ends CLOSED. |
  | relay task killed mid-call | `RelayTcpServer.close()` cancels the connection task | client `NETWORK_LOST`, relay `RELAY_UNAVAILABLE/RELAY_SHUTDOWN`, backend CLOSED |
  | gateway/backend failure mid-call | backend raises on poll (process death) | `BACKEND_UNAVAILABLE/BACKEND_FAILED`. The relay link survives and the next `ATDloopback` gives CONNECT. |

  Every case moves 16 KiB of binary data first. It then asserts:
  - one `NO CARRIER`;
  - DCD low is published before it;
  - exactly one `(COMMAND, DCD=0)` snapshot;
  - after waiting past every deadline, the next DTE line answers a fresh `AT` with `OK`, which shows no extra result codes were queued.

The suite was stable across repeated runs, including three concurrent full-suite runs on a 4-core host. Production intervals stay at the S1 defaults. Tests inject sub-second intervals: 50 ms interval and 150 to 200 ms failure.

## Decisions

1. **What counts as "idle", and who PINGs.** S1 section 9 says the relay PINGs
   "while idle" and that "either peer may send a PING", but it does not define idle.
   Here, idle means *no frame received from the peer* for the interval, and
   both sides run the same rule independently. Only inbound traffic proves the
   peer is alive. This is the rule in OpenSSH `ServerAliveInterval`: "if no data
   has been received from the server, ssh will send a message through the
   encrypted channel to request a response". The QUIC idle timer works the same
   way: it is restarted "when a packet from its peer is received and processed
   successfully" (RFC 9000 section 10.1). If both sides happen to PING in the
   same interval, that is allowed and costs one small frame. Sources: OpenSSH
   ssh_config(5), as quoted at docs.rackspace.com/docs/how-to-keep-ssh-sesions-alive;
   RFC 9000 section 10.1, as quoted in golang.org/x/net/quic/idle.go and the IETF quicwg
   mail archive (rfc-editor.org could not be reached from this environment).
2. **Failure is measured from the PING, and only a matching PONG answers it.**
   S1 section 9 says "No PONG within the S1 failure interval causes a transport failure".
   So other inbound traffic does not clear an outstanding PING. Worst-case
   detection is therefore interval plus failure (40 s with the defaults). The
   Android `RelaySessionMachine` makes the same choice (commit 242479f).
   SSH's `ServerAliveCountMax` behaves the same way: unanswered probes count
   even while other data arrives.
3. **Unsolicited and wrong-nonce PONGs are protocol violations**, and the connection is closed.
   This follows S1 section 15 ("malformed ... protocol violations and close the
   affected connection") and matches the Android machine.
4. **Heartbeat starts only after AUTH_OK on both sides.** Before that point, the
   8 s relay-authentication deadline covers liveness. This matches Android ("no
   PING before AUTH_OK").
5. **Heartbeat timeout uses reason `NETWORK_LOST` with detail `HEARTBEAT_TIMEOUT`.**
   The S1 section 12 taxonomy has no heartbeat entry, and every disconnect "SHALL have
   exactly one terminal reason code plus optional structured detail".
   S1_TEST_PLAN section 8 asks for "NO CARRIER and NETWORK_LOST/transport diagnostic"
   when the relay connection is killed, and S1_NETWORK_THREADING says lost
   connectivity fails the call with NETWORK_LOST. **Divergence to reconcile:** the
   Android `RelaySessionMachine.TransportFailed` carries the string
   `"HEARTBEAT_TIMEOUT"`. Android should map that to terminal reason `NETWORK_LOST`
   with detail `HEARTBEAT_TIMEOUT`. `android/**` was out of scope for this task.
6. **A relay death or TLS close mid-call is `NETWORK_LOST`, not `RELAY_UNAVAILABLE`.**
   The client cannot tell a relay crash from a path failure, and S1_TEST_PLAN section 8
   gives "kill relay connection" the NETWORK_LOST result.
   `RELAY_UNAVAILABLE` is kept for a relay that is reachable but not working: an
   unacknowledged DIAL_REQUEST, or the relay's own shutdown record.
7. **A gateway/backend crash mid-call is
   `CALL_TERMINATED(BACKEND_UNAVAILABLE, source=GATEWAY, diagnostic_code=BACKEND_FAILED)`,
   and the relay connection stays open.** S1_GATEWAY_BACKEND section 14 says a gateway crash
   "causes active Android calls to receive terminal transport failure/NO CARRIER".
   S1_WIRE_PROTOCOL section 10 says the "Socket remains reusable for later calls unless
   transport-level failure requires close". `source=GATEWAY` names the failure
   domain, consistent with the existing backend-HANGUP mapping. A crash at dial
   time is `DIAL_FAILED(GATEWAY_UNAVAILABLE, retryable)`, as S1_GATEWAY_BACKEND section 13
   requires ("DIAL fails with GATEWAY_UNAVAILABLE").
8. **Deadline ownership (S1 section 7).**
   - *Relay authentication, 8 s:* both sides enforce it. The relay measures from
     the accepted TLS connection. If HELLO_ACK has been sent, it sends
     `AUTH_FAIL("AUTH_TIMEOUT")` and flushes before closing, per S1 section 4 "On
     failure relay sends AUTH_FAIL then closes". Before HELLO_ACK it only closes,
     because section 3 allows no other frame before HELLO_ACK. The client measures
     HELLO to AUTH_OK. Both use `AUTH_FAILURE/AUTH_TIMEOUT`. A timeout reason does
     not reveal whether the endpoint is known, so the section 4.1 rule of giving the
     same reason for an unknown endpoint and a bad proof still holds.
   - *DIAL_REQUEST acknowledgement, 5 s:* the client enforces it. The relay
     answers DIAL_REQUEST synchronously in this prototype, so its side of the
     deadline is always met. On expiry the client fails the call with
     `RELAY_UNAVAILABLE/DIAL_ACK_TIMEOUT` and **closes the link**. Without a
     session_id it cannot send HANGUP_REQUEST, and a late DIAL_ACCEPTED would
     otherwise leave a call open at the relay ("stale events do not resurrect
     call", S1_TEST_PLAN section 3).
   - *TCP/TLS connect, 10 s:* the client enforces it, as `CONNECT_TIMEOUT`.
   - *Clean disconnect grace, 3 s:* this bounds every orderly TLS close.
     After a heartbeat timeout or transport error the connection is aborted
     straight away, because a dead peer never sends close_notify. CPython 3.12+
     would otherwise wait on the TLS shutdown, and `Server.wait_closed()`
     would wait on live connections. Both were found by running the suite on 3.12.
9. **The detail for a lost TCP connection is not deterministic, but the reason is.**
   When a process dies, the kernel sends FIN, or RST if unread bytes (such as an
   in-flight PING) are queued. So the e2e tests require the reason `NETWORK_LOST`
   exactly and accept either `TRANSPORT_CLOSED` or `TRANSPORT_ERROR` as the detail.
   This was seen once under parallel load.
10. **DTE result for every link or transport failure is NO CARRIER.** ITU-T V.250
    result code 3 "NO CARRIER" covers a terminated connection or a failed
    attempt (V.250, as summarised in 3GPP TP-030037 / 27.007 section 4.3). S1_AT_DTE section 7
    lists NO CARRIER as the generic failure result. S1_SPEC_FREEZE section 9 keeps the
    project reason in diagnostics, not on the DTE stream. DIAL_FAILED `TIMEOUT` maps
    to NO CARRIER with `BACKEND_NO_ANSWER`, following the hardware modem's
    S7 wait-for-carrier expiry, which also reports NO CARRIER.
11. **PING frames are connection-scoped.** A relay or client PING uses zero call
    and session IDs and `request_id` 0, since it is an unsolicited event under S1 section 6. A PONG echoes
    the PING's IDs and request_id. Android's client PING also uses zero IDs.
12. **Interval negotiation.** The relay advertises
    `ceil(heartbeat_interval)` whole seconds, and never 0, because 0 disables
    heartbeat on Android. The client uses the HELLO_ACK value unless an explicit
    `heartbeat_interval` is injected (for tests or deployment tuning). The heartbeat
    failure interval is local configuration, since HELLO_ACK does not carry it.
13. **Monotonic time only.** All deadlines take a `Clock`
    (`time.monotonic` by default). Event-loop waits use asyncio's monotonic
    timer, and `time.time` is never used (S1 section 7: "All deadlines use monotonic time").

## What remains

- **Android parity:**
  - map `TransportFailed("HEARTBEAT_TIMEOUT")` to `NETWORK_LOST` with that detail;
  - add the relay-auth (8 s) and DIAL-ack (5 s) deadlines;
  - add the dial-failure taxonomy mapping in `core-session`/`core-modem`.
- **Backend dial setup (60 s)** is in `TimeoutConfig` but not enforced. The
  loopback backend connects synchronously. The serial-modem backend (I5)
  should apply it as `DIAL_FAILED(TIMEOUT)`.
- **Diagnostics:** `AT+DIAG?` does not yet print `terminal_reason`/`terminal_detail`,
  and the relay RTT from PING/PONG timing is not yet exposed (S1_TEST_PLAN section 12).
- **ATH while DIALING before DIAL_ACCEPTED** still sends nothing to the relay,
  because there is no session_id yet. This is pre-existing. If the relay never
  acknowledges, the 5 s deadline closes the link correctly. If it acknowledges
  late, an orphan call can remain at the relay until the link closes.
- **No reconnect policy.** As S1 requires for Beta, every transport loss ends the call.
- **Not yet injected** from S1_TEST_PLAN sections 8 and 9: bearer loss on Android (needs a
  device), and serial write stall (needs the serial backend).
