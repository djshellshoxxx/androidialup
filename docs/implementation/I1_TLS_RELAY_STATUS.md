# I1 TLS Relay Status

Status: GREEN checkpoint

## Implemented

The I1 relay prototype now has a real network transport around the previously green ADUP protocol/session core:

- TLS 1.3 minimum server context.
- Certificate and hostname verification enabled client-side.
- Async framed stream connection using the existing bounded `FrameStreamDecoder`.
- Correct handling of split headers/payloads and multiple frames per TCP read.
- Serialized frame writes per connection.
- One independent `RelaySession` per TLS client.
- Protocol-violation isolation: malformed clients close without stopping the listener.
- Independent simultaneous client sessions.
- Clean client EOF handling.
- Full HELLO -> AUTH -> DIAL -> CONNECTED -> DATA -> HANGUP over real TLS sockets.
- Negative certificate trust test.

## GitHub verification

GitHub Actions run: `37048340860`

Commit: `6fb933eaa24c5958a89d7f5cf85109cd03b7e65a`

Command:

```text
python -m unittest discover -s tests -v
```

Result:

```text
Ran 61 tests in 1.260s
OK
```

The suite explicitly confirmed the negotiated SSL version is `TLSv1.3`.

## Socket-level acceptance proven

A real TLS client/server test transferred a deterministic 1 MiB binary stream through:

```text
AsyncFramedConnection
  -> TLS 1.3 TCP socket
  -> RelayTcpServer
  -> RelaySession
  -> LoopbackBackend
  -> RelaySession
  -> TLS 1.3 TCP socket
  -> AsyncFramedConnection
```

The returned stream matched byte-for-byte. The same test exercised heartbeat and clean hangup.

A separate test trusted a different self-signed certificate and verified the connection failed with certificate verification rather than silently accepting an untrusted relay.

## Remaining I1 work

- AT/V.250-style parser and result-code engine.
- timed `+++` escape / ATO / ATH semantics.
- modem reducer/state snapshots and logical DTE signals.
- TCP DTE test endpoint.
- AT-to-live-relay adapter.
- Android application shell.
- Android NetworkSelector and per-Network socket binding.
- Wi-Fi and cellular packet-data device acceptance.
- live diagnostics/metrics and forced network-loss tests.

The relay transport itself is no longer an in-process-only proof.
