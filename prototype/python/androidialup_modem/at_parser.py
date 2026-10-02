from __future__ import annotations

from dataclasses import dataclass

MAX_COMMAND_LINE = 512
MAX_DIAL_TARGET_UTF8 = 256


class ParserError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class AttentionCommand:
    pass


@dataclass(frozen=True, slots=True)
class EchoCommand:
    enabled: bool


@dataclass(frozen=True, slots=True)
class QuietCommand:
    enabled: bool


@dataclass(frozen=True, slots=True)
class VerboseCommand:
    enabled: bool


@dataclass(frozen=True, slots=True)
class ResetCommand:
    pass


@dataclass(frozen=True, slots=True)
class FactoryCommand:
    pass


@dataclass(frozen=True, slots=True)
class HangupCommand:
    pass


@dataclass(frozen=True, slots=True)
class AnswerCommand:
    pass


@dataclass(frozen=True, slots=True)
class DialCommand:
    target: str


@dataclass(frozen=True, slots=True)
class OnlineCommand:
    pass


@dataclass(frozen=True, slots=True)
class IdentifyCommand:
    page: int = 0


@dataclass(frozen=True, slots=True)
class SQueryCommand:
    register: int


@dataclass(frozen=True, slots=True)
class SSetCommand:
    register: int
    value: int


@dataclass(frozen=True, slots=True)
class PlusCommand:
    name: str
    operation: str
    value: str | None


AtCommand = (
    AttentionCommand
    | EchoCommand
    | QuietCommand
    | VerboseCommand
    | ResetCommand
    | FactoryCommand
    | HangupCommand
    | AnswerCommand
    | DialCommand
    | OnlineCommand
    | IdentifyCommand
    | SQueryCommand
    | SSetCommand
    | PlusCommand
)


class AtLineParser:
    def parse(self, line: bytes) -> tuple[AtCommand, ...]:
        raw = bytes(line)
        if len(raw) > MAX_COMMAND_LINE:
            raise ParserError("AT command line exceeds 512 bytes")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ParserError("AT command line is not valid UTF-8") from exc

        if len(text) < 2 or text[:2].upper() != "AT":
            raise ParserError("command line must begin with AT")

        i = 2
        commands: list[AtCommand] = []

        def skip_ws(pos: int) -> int:
            while pos < len(text) and text[pos].isspace():
                pos += 1
            return pos

        while True:
            i = skip_ws(i)
            if i >= len(text):
                break

            token = text[i].upper()

            if token in {"E", "Q", "V"}:
                i += 1
                i = skip_ws(i)
                if i >= len(text) or text[i] not in "01":
                    raise ParserError(f"{token} requires 0 or 1")
                enabled = text[i] == "1"
                i += 1
                if token == "E":
                    commands.append(EchoCommand(enabled))
                elif token == "Q":
                    commands.append(QuietCommand(enabled))
                else:
                    commands.append(VerboseCommand(enabled))
                continue

            if token == "Z":
                commands.append(ResetCommand())
                i += 1
                continue

            if token == "&":
                if i + 1 >= len(text) or text[i + 1].upper() != "F":
                    raise ParserError("unknown & command")
                commands.append(FactoryCommand())
                i += 2
                continue

            if token == "H":
                i += 1
                if i < len(text) and text[i].isdigit():
                    if text[i] != "0":
                        raise ParserError("only H0 is supported")
                    i += 1
                commands.append(HangupCommand())
                continue

            if token == "A":
                commands.append(AnswerCommand())
                i += 1
                continue

            if token == "O":
                commands.append(OnlineCommand())
                i += 1
                continue

            if token == "I":
                i += 1
                page = 0
                if i < len(text) and text[i].isdigit():
                    page = int(text[i])
                    if page > 4:
                        raise ParserError("ATI page must be 0..4")
                    i += 1
                commands.append(IdentifyCommand(page))
                continue

            if token == "S":
                i += 1
                start = i
                while i < len(text) and text[i].isdigit():
                    i += 1
                if start == i:
                    raise ParserError("S command requires a register number")
                register = int(text[start:i])
                i = skip_ws(i)
                if i >= len(text):
                    raise ParserError("S command requires ? or =value")
                if text[i] == "?":
                    commands.append(SQueryCommand(register))
                    i += 1
                    continue
                if text[i] == "=":
                    i += 1
                    i = skip_ws(i)
                    start = i
                    while i < len(text) and text[i].isdigit():
                        i += 1
                    if start == i:
                        raise ParserError("S register set requires a numeric value")
                    commands.append(SSetCommand(register, int(text[start:i])))
                    continue
                raise ParserError("S command requires ? or =value")

            if token == "D":
                i += 1
                i = skip_ws(i)
                if i < len(text) and text[i].upper() in {"T", "P"}:
                    i += 1
                target = text[i:].strip()
                if not target:
                    raise ParserError("D command requires a target")
                if ";" in target:
                    raise ParserError("dial semicolon syntax is not supported")
                if len(target.encode("utf-8")) > MAX_DIAL_TARGET_UTF8:
                    raise ParserError("dial target exceeds 256 UTF-8 bytes")
                commands.append(DialCommand(target))
                i = len(text)
                continue

            if token == "+":
                i += 1
                name_start = i
                while i < len(text) and (text[i].isalpha() or text[i] == "_"):
                    i += 1
                name = text[name_start:i].upper()
                if name not in {"MODE", "NET", "DIAG"}:
                    raise ParserError(f"unsupported extended command +{name}")
                i = skip_ws(i)
                if i >= len(text):
                    raise ParserError(f"+{name} requires ? or =value")
                if text[i] == "?":
                    i += 1
                    if skip_ws(i) != len(text):
                        raise ParserError("extended query must terminate the command line")
                    commands.append(PlusCommand(name, "query", None))
                    i = len(text)
                    continue
                if text[i] == "=":
                    value = text[i + 1 :].strip()
                    if not value:
                        raise ParserError(f"+{name} set requires a value")
                    commands.append(PlusCommand(name, "set", value))
                    i = len(text)
                    continue
                raise ParserError(f"+{name} requires ? or =value")

            raise ParserError(f"unknown command token {text[i]!r}")

        if not commands:
            return (AttentionCommand(),)
        return tuple(commands)
