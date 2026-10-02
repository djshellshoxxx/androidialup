from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Callable, Protocol

from .at_engine import AtContext, AtEffectType, AtEngine, ResultCode
from .at_parser import AtLineParser, MAX_COMMAND_LINE, ParserError
from .escape import EscapeDetector


class ModemState(str, Enum):
    COMMAND = "COMMAND"
    DIALING = "DIALING"
    ONLINE_DATA = "ONLINE_DATA"
    ONLINE_COMMAND = "ONLINE_COMMAND"


@dataclass(frozen=True, slots=True)
class DteSignals:
    dsr: bool = True
    dcd: bool = False
    cts: bool = True
    ri: bool = False


@dataclass(frozen=True, slots=True)
class ModemSnapshot:
    state: ModemState
    signals: DteSignals
    terminal_reason: str | None


class SessionPort(Protocol):
    def dial(self, target: str) -> None: ...
    def write_data(self, data: bytes) -> None: ...
    def hangup(self, reason: str) -> None: ...
    def answer(self) -> None: ...


class ModemController:
    """Single-writer synchronous modem state owner for the I1 prototype."""

    def __init__(
        self,
        session_port: SessionPort,
        write_dte: Callable[[bytes], None],
        *,
        build_id: str = "dev",
        snapshot_listener: Callable[[ModemSnapshot], None] | None = None,
    ) -> None:
        self.session_port = session_port
        self.write_dte = write_dte
        self.engine = AtEngine(build_id=build_id)
        self.parser = AtLineParser()
        self.escape = EscapeDetector(last_forwarded_ms=0)
        self.state = ModemState.COMMAND
        self.signals = DteSignals()
        self.terminal_reason: str | None = None
        #: Optional structured detail for terminal_reason (S1_SPEC_FREEZE section 12).
        self.terminal_detail: str | None = None
        self.snapshot_listener = snapshot_listener
        self._line = bytearray()
        self._discard_line = False
        self._dial_terminal_emitted = False
        self._published = self.snapshot

    @property
    def snapshot(self) -> ModemSnapshot:
        return ModemSnapshot(self.state, self.signals, self.terminal_reason)

    def _set_dcd(self, value: bool) -> None:
        self.signals = DteSignals(
            dsr=self.signals.dsr,
            dcd=value,
            cts=self.signals.cts,
            ri=self.signals.ri,
        )

    def _publish(self) -> None:
        """Publish an immutable snapshot to observers when state or signals changed."""
        snapshot = self.snapshot
        previous = self._published
        if previous is not None and previous.state == snapshot.state and previous.signals == snapshot.signals:
            return
        self._published = snapshot
        if self.snapshot_listener is not None:
            self.snapshot_listener(snapshot)

    def _transition(self, state: ModemState, dcd: bool) -> None:
        self.state = state
        self._set_dcd(dcd)
        self._publish()

    def _emit_line(self, text: str) -> None:
        cr = self.engine.profile.s_registers[3]
        lf = self.engine.profile.s_registers[4]
        self.write_dte(text.encode("ascii", "replace") + bytes((cr, lf)))

    def _emit_result(self, result: ResultCode) -> None:
        if not self.engine.profile.quiet:
            self._emit_line(self.engine.format_result(result))

    def _context(self) -> AtContext:
        return AtContext(
            active_call=self.state in {ModemState.ONLINE_DATA, ModemState.ONLINE_COMMAND},
            online_command=self.state == ModemState.ONLINE_COMMAND,
            modem_state=self.state.value,
            session_state=("ESTABLISHED" if self.signals.dcd else "IDLE"),
        )

    def feed_dte(self, data: bytes, now_ms: int) -> None:
        raw = bytes(data)
        if self.state == ModemState.ONLINE_DATA:
            self._feed_online(raw, now_ms)
            return
        self._feed_command(raw)

    def _feed_command(self, data: bytes) -> None:
        terminator = self.engine.profile.s_registers[3]
        response_lf = self.engine.profile.s_registers[4]
        backspace = self.engine.profile.s_registers[5]

        for byte in data:
            if byte == response_lf and not self._line:
                continue
            if self.engine.profile.echo:
                self.write_dte(bytes((byte,)))

            if self._discard_line:
                if byte == terminator:
                    self._discard_line = False
                    self._line.clear()
                    self._emit_result(ResultCode.ERROR)
                continue

            if byte == backspace:
                if self._line:
                    self._line.pop()
                continue

            if byte != terminator:
                if len(self._line) >= MAX_COMMAND_LINE:
                    self._discard_line = True
                else:
                    self._line.append(byte)
                continue

            line = bytes(self._line)
            self._line.clear()
            if not line:
                continue
            self._execute_line(line)

    def _execute_line(self, line: bytes) -> None:
        try:
            commands = self.parser.parse(line)
        except ParserError:
            self._emit_result(ResultCode.ERROR)
            return

        execution = self.engine.execute(commands, self._context())
        for output in execution.output_lines:
            self._emit_line(output)

        for effect in execution.effects:
            if effect.type == AtEffectType.DIAL:
                self.terminal_reason = None
                self.terminal_detail = None
                self._dial_terminal_emitted = False
                self._transition(ModemState.DIALING, False)
                self.session_port.dial(effect.value or "")
            elif effect.type == AtEffectType.HANGUP:
                if self.state in {ModemState.DIALING, ModemState.ONLINE_DATA, ModemState.ONLINE_COMMAND}:
                    self.session_port.hangup("LOCAL_HANGUP")
                self._transition(ModemState.COMMAND, False)
            elif effect.type == AtEffectType.ANSWER:
                self.session_port.answer()
            elif effect.type == AtEffectType.RESUME_ONLINE:
                if self.signals.dcd:
                    self._transition(ModemState.ONLINE_DATA, True)

    def _feed_online(self, data: bytes, now_ms: int) -> None:
        escape_char = self.engine.profile.s_registers[2]
        guard_ms = self.engine.profile.s_registers[12] * 20
        forward = bytearray()
        for byte in data:
            action = self.escape.feed(byte, now_ms, escape_char, guard_ms)
            if action.forward:
                forward.extend(action.forward)
        if forward:
            # Preserve one DTE read as a bounded handoff rather than producing
            # one relay operation per octet. The ModeAdapter performs its own
            # protocol-sized chunking afterwards.
            self.session_port.write_data(bytes(forward))

    def on_timer(self, now_ms: int) -> None:
        if self.state != ModemState.ONLINE_DATA:
            return
        action = self.escape.timer(now_ms)
        if action.forward:
            self.session_port.write_data(action.forward)
        if action.escaped:
            self._transition(ModemState.ONLINE_COMMAND, self.signals.dcd)
            self._emit_result(ResultCode.OK)

    def on_dial_result(self, result: ResultCode, reason: str | None = None) -> None:
        if self.state != ModemState.DIALING or self._dial_terminal_emitted:
            return
        if result == ResultCode.CONNECT:
            self._transition(ModemState.ONLINE_DATA, True)
            self._dial_terminal_emitted = True
            self._emit_result(ResultCode.CONNECT)
            return

        if result not in {ResultCode.BUSY, ResultCode.NO_DIALTONE, ResultCode.NO_ANSWER, ResultCode.NO_CARRIER}:
            result = ResultCode.NO_CARRIER
        self._dial_terminal_emitted = True
        self.terminal_reason = reason or result.name
        self.terminal_detail = None
        self._transition(ModemState.COMMAND, False)
        self._emit_result(result)

    def on_remote_data(self, data: bytes) -> None:
        if self.state == ModemState.ONLINE_DATA and self.signals.dcd:
            self.write_dte(bytes(data))

    def on_remote_hangup(self, reason: str = "REMOTE_HANGUP", detail: str | None = None) -> None:
        """Remote/transport termination: DCD drops, then exactly one NO CARRIER."""
        if self.state not in {ModemState.DIALING, ModemState.ONLINE_DATA, ModemState.ONLINE_COMMAND}:
            return
        was_dialing = self.state == ModemState.DIALING
        self.terminal_reason = reason
        self.terminal_detail = detail
        self._transition(ModemState.COMMAND, False)
        if was_dialing:
            if not self._dial_terminal_emitted:
                self._dial_terminal_emitted = True
                self._emit_result(ResultCode.NO_CARRIER)
        else:
            self._emit_result(ResultCode.NO_CARRIER)

    def on_dte_disconnect(self) -> None:
        if self.state in {ModemState.DIALING, ModemState.ONLINE_DATA, ModemState.ONLINE_COMMAND}:
            self.session_port.hangup("DTE_DISCONNECTED")
        self._transition(ModemState.COMMAND, False)
        self._line.clear()
        self._discard_line = False
