"""Command-line PC bridge for the AndroidDialup AOA DTE."""

from __future__ import annotations

import argparse
import socket
import sys
from typing import Sequence

from .accessory_discovery import obtain_accessory as _obtain_accessory, wait_for_accessory as _wait_for_accessory
from .bridge import bridge_socket_to_transport
from .pyusb_backend import (
    PyUsbUnavailable,
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
