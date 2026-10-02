from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, IntEnum

from .at_parser import (
    AnswerCommand,
    AtCommand,
    AttentionCommand,
    DialCommand,
    EchoCommand,
    FactoryCommand,
    HangupCommand,
    IdentifyCommand,
    OnlineCommand,
    PlusCommand,
    QuietCommand,
    ResetCommand,
    SQueryCommand,
    SSetCommand,
    VerboseCommand,
)
from .profile import ModemMode, ModemProfile, NetworkPolicy


class ResultCode(IntEnum):
    OK = 0
    CONNECT = 1
    RING = 2
    NO_CARRIER = 3
    ERROR = 4
    NO_DIALTONE = 6
    BUSY = 7
    NO_ANSWER = 8


_RESULT_TEXT = {
    ResultCode.OK: "OK",
    ResultCode.CONNECT: "CONNECT",
    ResultCode.RING: "RING",
    ResultCode.NO_CARRIER: "NO CARRIER",
    ResultCode.ERROR: "ERROR",
    ResultCode.NO_DIALTONE: "NO DIALTONE",
    ResultCode.BUSY: "BUSY",
    ResultCode.NO_ANSWER: "NO ANSWER",
}


class AtEffectType(str, Enum):
    DIAL = "DIAL"
    HANGUP = "HANGUP"
    ANSWER = "ANSWER"
    RESUME_ONLINE = "RESUME_ONLINE"


@dataclass(frozen=True, slots=True)
class AtEffect:
    type: AtEffectType
    value: str | None = None


@dataclass(frozen=True, slots=True)
class AtContext:
    active_call: bool = False
    online_command: bool = False
    selected_network: str = "NONE"
    validated: bool = False
    metered: bool = False
    modem_state: str = "COMMAND"
    session_state: str = "IDLE"


@dataclass(frozen=True, slots=True)
class AtExecution:
    output_lines: tuple[str, ...]
    effects: tuple[AtEffect, ...]
    terminal_result: ResultCode | None


class AtEngine:
    def __init__(self, *, build_id: str = "dev") -> None:
        self.profile = ModemProfile.factory()
        self.build_id = build_id

    def format_result(self, result: ResultCode) -> str:
        if self.profile.verbose:
            return _RESULT_TEXT[result]
        return str(int(result))

    def _finish(
        self,
        info_lines: list[str],
        effects: list[AtEffect],
        terminal: ResultCode | None,
    ) -> AtExecution:
        if self.profile.quiet:
            return AtExecution((), tuple(effects), terminal)
        lines = list(info_lines)
        if terminal is not None:
            lines.append(self.format_result(terminal))
        return AtExecution(tuple(lines), tuple(effects), terminal)

    def execute(self, commands: tuple[AtCommand, ...], context: AtContext | None = None) -> AtExecution:
        context = context or AtContext()
        info: list[str] = []
        effects: list[AtEffect] = []
        terminal: ResultCode | None = ResultCode.OK

        for command in commands:
            if isinstance(command, AttentionCommand):
                continue

            if isinstance(command, EchoCommand):
                self.profile.echo = command.enabled
                continue

            if isinstance(command, QuietCommand):
                self.profile.quiet = command.enabled
                continue

            if isinstance(command, VerboseCommand):
                self.profile.verbose = command.enabled
                continue

            if isinstance(command, FactoryCommand):
                self.profile.restore_factory()
                continue

            if isinstance(command, ResetCommand):
                self.profile.restore_factory()
                if context.active_call:
                    effects.append(AtEffect(AtEffectType.HANGUP))
                continue

            if isinstance(command, HangupCommand):
                effects.append(AtEffect(AtEffectType.HANGUP))
                continue

            if isinstance(command, AnswerCommand):
                effects.append(AtEffect(AtEffectType.ANSWER))
                terminal = None
                break

            if isinstance(command, DialCommand):
                effects.append(AtEffect(AtEffectType.DIAL, command.target))
                terminal = None
                break

            if isinstance(command, OnlineCommand):
                if not context.active_call or not context.online_command:
                    terminal = ResultCode.ERROR
                    break
                effects.append(AtEffect(AtEffectType.RESUME_ONLINE))
                terminal = ResultCode.CONNECT
                break

            if isinstance(command, IdentifyCommand):
                pages = {
                    0: "AndroidDialup",
                    1: "Beta 0.1",
                    2: "protocol=1",
                    3: self.build_id,
                    4: "modes=AUTO,BYTE_RELAY,PCM_VBD_EXPERIMENTAL;net=AUTO,WIFI_ONLY,CELLULAR_ONLY,PREFER_WIFI,PREFER_CELLULAR",
                }
                info.append(pages[command.page])
                continue

            if isinstance(command, SQueryCommand):
                if command.register not in self.profile.s_registers:
                    terminal = ResultCode.ERROR
                    break
                info.append(str(self.profile.s_registers[command.register]))
                continue

            if isinstance(command, SSetCommand):
                if command.register not in self.profile.s_registers or not 0 <= command.value <= 255:
                    terminal = ResultCode.ERROR
                    break
                self.profile.s_registers[command.register] = command.value
                continue

            if isinstance(command, PlusCommand):
                if command.name == "MODE":
                    if command.operation == "query":
                        info.append(f"+MODE: {self.profile.mode.value}")
                        continue
                    if context.active_call:
                        terminal = ResultCode.ERROR
                        break
                    try:
                        self.profile.mode = ModemMode(str(command.value).upper())
                    except ValueError:
                        terminal = ResultCode.ERROR
                        break
                    continue

                if command.name == "NET":
                    if command.operation == "query":
                        info.append(
                            "+NET: "
                            f"policy={self.profile.network_policy.value},"
                            f"selected={context.selected_network},"
                            f"validated={1 if context.validated else 0},"
                            f"metered={1 if context.metered else 0}"
                        )
                        continue
                    try:
                        self.profile.network_policy = NetworkPolicy(str(command.value).upper())
                    except ValueError:
                        terminal = ResultCode.ERROR
                        break
                    continue

                if command.name == "DIAG":
                    if command.operation == "query":
                        info.append(
                            "+DIAG: "
                            f"modem={context.modem_state},"
                            f"session={context.session_state},"
                            f"extended={1 if self.profile.extended_diagnostics else 0}"
                        )
                        continue
                    if command.value not in {"0", "1"}:
                        terminal = ResultCode.ERROR
                        break
                    self.profile.extended_diagnostics = command.value == "1"
                    continue

                terminal = ResultCode.ERROR
                break

            terminal = ResultCode.ERROR
            break

        return self._finish(info, effects, terminal)
