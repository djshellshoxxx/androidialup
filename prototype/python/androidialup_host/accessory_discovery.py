"""AOA accessory discovery and mode-switch orchestration.

This module owns only device lookup, host-side AOA negotiation, and USB
re-enumeration polling. Argument parsing, TCP listening and byte forwarding
belong to the CLI and bridge modules. There is no eager import of PyUSB:
the optional libusb backend is loaded only when the CLI runs.
"""
from __future__ import annotations

import sys
import time
from typing import Any, Protocol

from .aoa import AccessoryIdentity, enter_accessory_mode
from .pyusb_backend import PyUsbControlDevice, find_accessory_device


class UsbFinder(Protocol):
    """Minimal injected USB device finder, supplied by PyUSB or a test fake."""

    def find(self, **criteria: Any) -> Any: ...


class DiscoveryOptions(Protocol):
    """Subset of CLI options required to locate an AOA USB accessory."""

    device: tuple[int, int] | None
    reenumeration_timeout: float
    serial: str


def wait_for_accessory(core: UsbFinder, timeout_seconds: float) -> Any | None:
    """Poll for re-enumeration, returning None when the deadline passes.

    Uses monotonic time rather than wall time. Discovery happens only until the
    caller-supplied timeout and never takes ownership of the USB device.
    """
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        device = find_accessory_device(core)
        if device is not None:
            return device
        time.sleep(0.2)
    return None


def obtain_accessory(core: UsbFinder, options: DiscoveryOptions) -> Any:
    """Find an existing AOA accessory or switch a selected device into AOA mode.

    The returned object is owned by the caller, which opens and closes the bulk
    transport. Errors intentionally preserve the CLI's existing diagnostics.
    """
    accessory = find_accessory_device(core)
    if accessory is not None:
        return accessory

    if options.device is None:
        raise RuntimeError(
            "no Android device is already in AOA mode; pass --device VID:PID for the initial USB device"
        )

    vendor, product = options.device
    initial = core.find(idVendor=vendor, idProduct=product)
    if initial is None:
        raise RuntimeError(f"USB device {vendor:04x}:{product:04x} was not found")

    identity = AccessoryIdentity.default()
    identity = AccessoryIdentity(
        manufacturer=identity.manufacturer,
        model=identity.model,
        description=identity.description,
        version=identity.version,
        uri=identity.uri,
        serial=options.serial,
    )
    protocol = enter_accessory_mode(PyUsbControlDevice(initial), identity)
    print(f"AOA protocol {protocol}; waiting for Android USB re-enumeration...", file=sys.stderr)
    accessory = wait_for_accessory(core, options.reenumeration_timeout)
    if accessory is None:
        raise RuntimeError("Android did not re-enumerate in AOA accessory mode before timeout")
    return accessory
