from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class ModemMode(str, Enum):
    AUTO = "AUTO"
    BYTE_RELAY = "BYTE_RELAY"
    PCM_VBD_EXPERIMENTAL = "PCM_VBD_EXPERIMENTAL"


class NetworkPolicy(str, Enum):
    AUTO = "AUTO"
    WIFI_ONLY = "WIFI_ONLY"
    CELLULAR_ONLY = "CELLULAR_ONLY"
    PREFER_WIFI = "PREFER_WIFI"
    PREFER_CELLULAR = "PREFER_CELLULAR"


FACTORY_S_REGISTERS = {
    0: 0,
    2: 43,
    3: 13,
    4: 10,
    5: 8,
    7: 60,
    10: 14,
    12: 50,
}


@dataclass(slots=True)
class ModemProfile:
    echo: bool = True
    quiet: bool = False
    verbose: bool = True
    mode: ModemMode = ModemMode.AUTO
    network_policy: NetworkPolicy = NetworkPolicy.AUTO
    extended_diagnostics: bool = False
    s_registers: dict[int, int] = field(default_factory=lambda: dict(FACTORY_S_REGISTERS))

    @classmethod
    def factory(cls) -> "ModemProfile":
        return cls()

    def restore_factory(self) -> None:
        replacement = ModemProfile.factory()
        self.echo = replacement.echo
        self.quiet = replacement.quiet
        self.verbose = replacement.verbose
        self.mode = replacement.mode
        self.network_policy = replacement.network_policy
        self.extended_diagnostics = replacement.extended_diagnostics
        self.s_registers = dict(replacement.s_registers)
