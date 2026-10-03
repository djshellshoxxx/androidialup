"""Command-line PC bridge for the AndroidDialup AOA DTE."""

from __future__ import annotations

import argparse
import socket
import sys
import time
from typing import Sequence

from .aoa import AccessoryIdentity, enter_accessory_mode
from .bridge import bridge_socket_to_transport
from .pyusb_backend import (
    PyUsbControlDevice,
    PyUsbUnavailable,
    find_accessory_device,
    load_pyusb,
    open_bulk_transport,
)


def parse_usb_id(value: str) -> tuple[int, int]:
    try:
        vendor_text, product_text = value.split(":", 1)
        vendor = int(vendor_text, 16)
        product = int(product_text, 16)
    except (ValueError, AttributeError) as exc:
        raise argparse.ArgumentTypeError("USB device must be VID:PID in hexadecimal") from exc
    if not (0 <= vendor <= 0xFFFF and 0 <= product <= 0xFFFF):
        raise argparse.ArgumentTypeError("USB VID and PID must fit 16 bits")
    return vendor, product


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="androidialup-aoa-bridge",
        description="Expose AndroidDialup's Android Open Accessory DTE as localhost TCP.",
    )
    parser.add_argument(
        "--device",
        type=parse_usb_id,
        metavar="VID:PID",
        help="initial Android USB VID:PID if it is not already in AOA mode",
    )
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, default=2323)
    parser.add_argument("--usb-timeout-ms", type=int, default=0)
    parser.add_argument("--reenumeration-timeout", type=float, default=12.0)
    parser.add_argument("--serial", default="androidialup-host")
    return parser


def _wait_for_accessory(core, timeout_seconds: float):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        device = find_accessory_device(core)
        if device is not None:
            return device
        time.sleep(0.2)
    return None


def _obtain_accessory(core, args):
    accessory = find_accessory_device(core)
    if accessory is not None:
        return accessory

    if args.device is None:
        raise RuntimeError(
            "no Android device is already in AOA mode; pass --device VID:PID for the initial USB device"
        )

    vendor, product = args.device
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
        serial=args.serial,
    )
    protocol = enter_accessory_mode(PyUsbControlDevice(initial), identity)
    print(f"AOA protocol {protocol}; waiting for Android USB re-enumeration...", file=sys.stderr)
    accessory = _wait_for_accessory(core, args.reenumeration_timeout)
    if accessory is None:
        raise RuntimeError("Android did not re-enumerate in AOA accessory mode before timeout")
    return accessory


def run(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not (1 <= args.listen_port <= 65535):
        raise SystemExit("--listen-port must be 1..65535")
    if args.usb_timeout_ms < 0:
        raise SystemExit("--usb-timeout-ms must be >= 0")
    if args.reenumeration_timeout <= 0:
        raise SystemExit("--reenumeration-timeout must be positive")

    try:
        core, _util = load_pyusb()
        device = _obtain_accessory(core, args)
        transport = open_bulk_transport(device, timeout_ms=args.usb_timeout_ms)
    except (PyUsbUnavailable, RuntimeError, OSError) as exc:
        print(f"androidialup-aoa-bridge: {exc}", file=sys.stderr)
        return 2

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.listen_host, args.listen_port))
        server.listen(1)
        address = server.getsockname()
        print(f"AndroidDialup USB DTE listening on {address[0]}:{address[1]}", file=sys.stderr)
        client, peer = server.accept()
        with client:
            print(f"DTE client connected from {peer[0]}:{peer[1]}", file=sys.stderr)
            try:
                bridge_socket_to_transport(client, transport)
            except (OSError, RuntimeError) as exc:
                print(f"bridge ended: {exc}", file=sys.stderr)
                return 3
    return 0


def main() -> None:
    raise SystemExit(run())


if __name__ == "__main__":
    main()
