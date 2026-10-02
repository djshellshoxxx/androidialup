# S1 AT Command and DTE Specification

Status: normative for Beta 0.1.

## 1. DTE byte model

DTE stream is 8-bit clean in online-data mode. Command mode is ASCII-compatible and line-oriented.

Default command terminator: CR (`0x0D`). LF following CR is ignored in command mode.

Maximum command line length: 512 bytes excluding terminator. Overlength command returns `ERROR` and discards until next terminator.

## 2. Command recognition

Commands begin with `AT` or `at`. Case-insensitive parsing applies to command tokens, not payload values where case may matter.

Whitespace outside quoted/project-specific values is ignored between tokens.

A bare `AT` returns `OK`.

Unknown command returns `ERROR` and SHALL NOT partially apply later tokens on the same line.

## 3. Command transaction rule

A command line is parsed completely before execution. If syntax validation fails, no command on that line mutates state.

If syntax is valid, commands execute left-to-right. A runtime failure stops execution and emits one final result code.

## 4. Required Beta commands

```text
AT                  attention
ATE0 / ATE1         echo off/on
ATQ0 / ATQ1         result output enabled/quiet
ATV0 / ATV1         numeric/text result codes
ATZ                  reset active profile to stored/default Beta profile
AT&F                 factory defaults
ATH / ATH0           hang up
ATA                  answer incoming call
ATD<target>          dial
ATO                  return from online-command to online-data
ATI                  identification
ATI0..ATI4           fixed identification pages
ATS<n>?              read S-register
ATS<n>=<value>       set S-register
AT+MODE?             query mode
AT+MODE=<value>      set preferred mode
AT+NET?              query network policy/current selection summary
AT+NET=<value>       set network policy
AT+DIAG?             concise current diagnostic snapshot
AT+DIAG=<0|1>        disable/enable extended diagnostic result text
```

## 5. Result codes

Text mode:

```text
OK
CONNECT
RING
NO CARRIER
ERROR
NO DIALTONE
BUSY
NO ANSWER
```

Numeric mode:

```text
0
1
2
3
4
6
7
8
```

Extended diagnostic text never replaces the base result code. When enabled it appears as project-prefixed informational lines before the terminal result:

```text
+ADIAG: BACKEND_BUSY,gateway=gw-01
BUSY
```

## 6. Dial syntax

Beta accepts:

```text
ATD<target>
ATDT<target>
ATDP<target>
```

`T` and `P` are accepted for compatibility but do not alter the IP relay semantics. The target string is passed to the gateway policy after validation.

Target maximum: 256 UTF-8 bytes after parsing.

Semicolon voice-return syntax is not supported in Beta and returns `ERROR`.

## 7. Dial behavior

On accepted `ATD`:

- parser produces no immediate `OK`;
- state becomes DIALING;
- DTE input during DIALING is interpreted as command-control input only where explicitly supported;
- successful call emits `CONNECT` and enters ONLINE_DATA;
- failure emits exactly one of BUSY, NO DIALTONE, NO ANSWER, or NO CARRIER and returns to COMMAND.

`ATH` during DIALING cancels the dial, waits for cleanup up to the disconnect grace period, emits `OK`, and returns to COMMAND.

## 7a. DTMF dialing and sending

The dial target MAY contain DTMF-dialable characters `0-9`, `A-D`, `*`, `#`,
and the pause character `,` (comma), whose dwell is `S8` seconds per comma.
These are passed to the gateway, which generates the tones into the call audio
path where the backend supports it (`S1_GATEWAY_BACKEND.md`). Over BYTE_RELAY
the digits are forwarded as dial-target characters only; no audio is produced.

To send DTMF during an established call:

```text
AT+DTMF=<digits>     // digits from 0-9 A-D * # , with optional per-digit timing
```

`AT+DTMF` is valid only in ONLINE_COMMAND or COMMAND with an active call; it
returns `OK` once the gateway accepts the sequence, or `ERROR` if the backend
cannot generate DTMF. Default tone/gap timing is `S11` milliseconds (default
`S11=95`). It never alters modem state.

## 7b. Call-progress and tone reporting

Beyond the terminal result code, when extended diagnostics are enabled
(`AT+DIAG=1`) the modem MAY emit informational call-progress lines before the
terminal result, one per detected event, without replacing the base result:

```text
+ACPROG: DIAL_TONE
+ACPROG: RINGBACK
+ACPROG: BUSY
+ACPROG: REORDER        // fast busy / reorder
+ACPROG: SIT,<code>     // special information tone, when classified
+ACPROG: VOICE          // voice/answer detected, no carrier
+ACPROG: CARRIER,<rate> // answering carrier detected
+ADTMF: <digit>         // DTMF digit detected inbound during the call
```

These are reported only when the active backend performs call-progress or
DTMF detection; they are advisory and do not change the single terminal result
code (`CONNECT`, `BUSY`, `NO DIALTONE`, `NO ANSWER`, `NO CARRIER`, `ERROR`).
On a successful connect the modem emits `CONNECT` and, where the backend
reports a negotiated line rate, `CONNECT <rate>`.

## 8. Online mode

ONLINE_DATA forwards bytes transparently to the active ModeAdapter except escape-sequence candidates.

ONLINE_COMMAND retains the active call but interprets bytes as AT commands.

`ATO` from ONLINE_COMMAND returns to ONLINE_DATA and emits `CONNECT`.

`ATH` from ONLINE_COMMAND terminates the active call and emits `OK` after local hangup initiation.

## 9. Escape sequence

Default escape character: `+` (`S2=43`). If `S2 > 127`, escape detection is disabled.

Guard time uses `S12` in 1/50-second units for compatibility-style behavior.

Default `S12=50`, giving 1 second pre/post guard time.

Algorithm:

```text
on online byte b at time now:
  if not candidate:
    if b == escape_char and now-last_forwarded_data >= guard:
      hold b
      candidate_count=1
      candidate_last=now
    else:
      forward b
      last_forwarded_data=now

  else:
    if b == escape_char and candidate_count < 3:
      if now-candidate_last <= guard:
        hold b
        candidate_count++
        candidate_last=now
        if candidate_count == 3:
          arm post_guard_deadline = now+guard
      else:
        flush held bytes
        restart normal handling of b
    else:
      flush held bytes
      forward b
      cancel candidate

on post_guard_deadline:
  if candidate_count == 3 and no later online data arrived:
    discard held escape chars
    transition ONLINE_COMMAND
    emit OK
  else:
    flush held bytes
```

Held escape bytes count against a small fixed buffer of exactly three bytes.

## 10. S-registers required in Beta

```text
S0   auto-answer rings; default 0
S2   escape character; default 43
S3   command terminator; default 13
S4   response formatting LF; default 10
S5   backspace; default 8
S7   wait-for-carrier/dial timeout seconds; default 60
S10  lost-carrier disconnect delay in tenths; default 14, advisory for future PHY modes
S12  escape guard time in 1/50 seconds; default 50
```

Values outside supported range return ERROR.

Beta may store more internal settings, but unsupported S-register numbers SHALL return ERROR rather than fabricated values.

## 11. Project mode command

```text
AT+MODE?
+MODE: BYTE_RELAY
OK
```

Set values:

```text
AUTO
BYTE_RELAY
PCM_VBD_EXPERIMENTAL
```

Changing mode while ONLINE_DATA or ONLINE_COMMAND returns ERROR.

## 12. Network command

Accepted values:

```text
AUTO
WIFI_ONLY
CELLULAR_ONLY
PREFER_WIFI
PREFER_CELLULAR
```

Query example:

```text
+NET: policy=PREFER_WIFI,selected=WIFI,validated=1,metered=0
OK
```

Changing policy during an active call affects future calls only in Beta 0.1. It does not migrate the current socket.

## 13. Identification

Minimum responses:

```text
ATI0  -> AndroidDialup
ATI1  -> Beta 0.1
ATI2  -> protocol=1
ATI3  -> build identifier
ATI4  -> supported mode/network summary
```

Exact build string is generated at build time.

## 14. DTE transport priorities

I1 required transport: TCP loopback/LAN test transport.

I2 target transport: USB-based PC-facing modem endpoint.

Bluetooth SPP is optional and only supported where Android/device APIs permit reliable implementation.

All DTE transports expose the same byte and signal interface.

## 15. DTE signals

Logical defaults:

```text
DSR = application ready and DTE transport established
DCD = active modem call connected
CTS = application can accept DTE bytes
RI  = incoming call currently ringing
```

On DTE connection start before a call: DSR=1, DCD=0, CTS=1, RI=0.

On call connect: DCD=1.

On terminal call loss: DCD=0 before `NO CARRIER` is emitted.

If physical DTE transport cannot represent signals, state is still maintained internally and exposed through diagnostics.

## 16. Profiles and reset

Beta has one effective runtime profile.

`AT&F` resets all modem settings to compiled factory defaults.

`ATZ` resets active state/settings to Beta's stored/default profile and hangs up an active call if one exists.

Persistent profile-write commands are out of scope for Beta.

## 17. Parser test requirements

Tests SHALL cover:

- mixed case AT tokens;
- multiple valid commands on one line;
- syntax error atomicity;
- overlength lines;
- result text/numeric modes;
- quiet mode;
- S-register bounds;
- escape guard timing boundaries;
- `ATH` during DIALING and ONLINE_COMMAND;
- failed dial mapping;
- transparent binary online data including NUL and 0xFF.
