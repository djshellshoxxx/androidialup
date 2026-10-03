"""Pure Android Open Accessory control-transfer logic.

The USB backend is deliberately injected: tests use a fake device and the optional PyUSB adapter
lives separately, so the core prototype test suite does not require libusb or hardware.
"""

from __future__ import annotations

from dataclasses import dataclass, fields
from typing import Protocol

AOA_GET_PROTOCOL = 51
AOA_SEND_IDENT = 52
AOA_START = 53


class AoaProtocolError(RuntimeError):
    """The connected USB device cannot enter Android Open Accessory mode."""


class ControlDevice(Protocol):
    def control_in(self, request: int, value: int, index: int, length: int) -> bytes: ...

    def control_out(self, request: int, value: int, index: int, data: bytes = b"") -> int: ...


@dataclass(frozen=True)
class AccessoryIdentity:
    manufacturer: str
    model: str
    description: str
    version: str
    uri: str
    serial: str

    def __post_init__(self) -> None:
        for field in fields(self):
            value = getattr(self, field.name)
            if not isinstance(value, str):
                raise TypeError(f"{field.name} must be a string")
            if "\0" in value:
                raise ValueError(f"{field.name} must not contain NUL")

    @classmethod
    def default(cls) -> "AccessoryIdentity":
        return cls(
            manufacturer="CircuitDrift",
            model="AndroidDialup",
            description="AndroidDialup PC bridge",
            version="0.1",
            uri="https://github.com/djshellshoxxx/androidialup",
            serial="androidialup-host",
        )

    def encoded_fields(self) -> tuple[bytes, ...]:
        return tuple((getattr(self, field.name) + "\0").encode("utf-8") for field in fields(self))


def enter_accessory_mode(device: ControlDevice, identity: AccessoryIdentity) -> int:
    """Negotiate AOA and request the Android device to re-enumerate in accessory mode.

    Returns the AOA protocol version reported by Android. The caller must then rediscover the USB
    device after re-enumeration before opening bulk endpoints.
    """
    raw = bytes(device.control_in(AOA_GET_PROTOCOL, 0, 0, 2))
    if len(raw) != 2:
        raise AoaProtocolError("AOA GET_PROTOCOL must return a 2-byte version")
    protocol = int.from_bytes(raw, "little")
    if protocol < 1:
        raise AoaProtocolError(f"unsupported AOA protocol version {protocol}")

    for index, value in enumerate(identity.encoded_fields()):
        written = device.control_out(AOA_SEND_IDENT, 0, index, value)
        if written not in (None, len(value)):
            raise AoaProtocolError(
                f"AOA SEND_IDENT index {index} wrote {written!r}, expected {len(value)}"
            )

    device.control_out(AOA_START, 0, 0, b"")
    return protocol
