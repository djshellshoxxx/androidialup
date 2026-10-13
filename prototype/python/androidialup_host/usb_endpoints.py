"""USB descriptor-only selection of the AOA data endpoints.

No PyUSB import, libusb runtime, device open/configuration, or I/O is needed to
test this module. The caller owns USB configuration and endpoint lifetimes.
"""
from __future__ import annotations

from typing import Any, Iterable

USB_ENDPOINT_IN = 0x80
USB_TRANSFER_TYPE_MASK = 0x03
USB_TRANSFER_TYPE_BULK = 0x02


def select_bulk_endpoints(interface: Iterable[Any]) -> tuple[Any, Any]:
    """Choose the first bulk IN and OUT endpoint from a configured interface.

    Order, direction masking, transfer-type checks and error text intentionally
    match the original `pyusb_backend.open_bulk_transport` implementation.
    Endpoint descriptors are not mutated or opened by this function.
    """
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
    return in_endpoint, out_endpoint
