"""Optional PyUSB/libusb backend for the PC-side Android Open Accessory bridge.

PyUSB is imported only by :func:`load_pyusb`; the core test suite therefore has no USB runtime
requirement. PyUSB is BSD-licensed. libusb is LGPL-2.1-or-later and remains an external runtime
component rather than vendored code.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

GOOGLE_VENDOR_ID = 0x18D1
ACCESSORY_PRODUCT_IDS = (0x2D00, 0x2D01)
USB_ENDPOINT_IN = 0x80
USB_TRANSFER_TYPE_MASK = 0x03
USB_TRANSFER_TYPE_BULK = 0x02


class PyUsbUnavailable(RuntimeError):
    pass


def load_pyusb():
    try:
        import usb.core as core  # type: ignore[import-not-found]
        import usb.util as util  # type: ignore[import-not-found]
    except ImportError as exc:
        raise PyUsbUnavailable(
            "PyUSB is required for hardware access; install it with 'python -m pip install pyusb' "
            "and install a libusb runtime/driver for your platform"
        ) from exc
    return core, util


class PyUsbControlDevice:
    """Adapter from PyUSB's ctrl_transfer API to the pure AOA control-device contract."""

    def __init__(self, device: Any, *, timeout_ms: int = 2000):
        self.device = device
        self.timeout_ms = timeout_ms

    def control_in(self, request: int, value: int, index: int, length: int) -> bytes:
        result = self.device.ctrl_transfer(
            0xC0, request, value, index, length, timeout=self.timeout_ms
        )
        return bytes(result)

    def control_out(self, request: int, value: int, index: int, data: bytes = b"") -> int:
        result = self.device.ctrl_transfer(
            0x40, request, value, index, data, timeout=self.timeout_ms
        )
        return int(result)


def find_accessory_device(core: Any):
    """Return the first AOAv1 accessory communication device, including the +ADB variant."""
    for product_id in ACCESSORY_PRODUCT_IDS:
        device = core.find(idVendor=GOOGLE_VENDOR_ID, idProduct=product_id)
        if device is not None:
            return device
    return None


@dataclass
class BulkTransport:
    in_endpoint: Any
    out_endpoint: Any
    timeout_ms: int = 0

    def read(self, size: int) -> bytes:
        timeout = self.timeout_ms or None
        return bytes(self.in_endpoint.read(size, timeout=timeout))

    def write(self, data: bytes) -> int:
        timeout = self.timeout_ms or None
        return int(self.out_endpoint.write(data, timeout=timeout))


def open_bulk_transport(device: Any, *, timeout_ms: int = 0) -> BulkTransport:
    """Configure an AOA device and select bulk IN/OUT endpoints from interface zero."""
    device.set_configuration()
    configuration = device.get_active_configuration()
    interface = configuration[(0, 0)]

    in_endpoint = None
    out_endpoint = None
    for endpoint in interface:
        if (int(endpoint.bmAttributes) & USB_TRANSFER_TYPE_MASK) != USB_TRANSFER_TYPE_BULK:
            continue
        if int(endpoint.bEndpointAddress) & USB_ENDPOINT_IN:
            if in_endpoint is None:
                in_endpoint = endpoint
        elif out_endpoint is None:
            out_endpoint = endpoint

    if in_endpoint is None or out_endpoint is None:
        raise RuntimeError("AOA interface does not expose both bulk IN and OUT endpoints")
    return BulkTransport(in_endpoint, out_endpoint, timeout_ms)
