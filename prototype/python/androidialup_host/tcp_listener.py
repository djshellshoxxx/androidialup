"""TCP listener lifecycle for a single PC-side Android Open Accessory DTE client.

Only the socket server and accepted-client lifetime belong here. USB discovery,
CLI argument handling, and bidirectional byte forwarding remain independently
owned and injected via their narrow contracts.

This is deliberately a *single-client* listener: changing that semantics is a
separate feature, not part of a behavior-preserving extraction.
"""
from __future__ import annotations

import socket
import sys
from typing import Callable

from .bridge import ByteTransport


def serve_one_client(
    host: str,
    port: int,
    transport: ByteTransport,
    bridge: Callable[[socket.socket, ByteTransport], None],
) -> int:
    """Accept exactly one TCP client and delegate byte forwarding to `bridge`.

    Returns 0 on ordinary bridge completion, or 3 when bridging raises OSError
    or RuntimeError. Bind/listen/accept failures continue propagating as before.
    Both accepted and listening sockets are always released by context managers.
    The USB transport is owned by the caller and is never closed by this helper.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(1)
        address = server.getsockname()
        print(f"AndroidDialup USB DTE listening on {address[0]}:{address[1]}", file=sys.stderr)
        client, peer = server.accept()
        with client:
            print(f"DTE client connected from {peer[0]}:{peer[1]}", file=sys.stderr)
            try:
                bridge(client, transport)
            except (OSError, RuntimeError) as exc:
                print(f"bridge ended: {exc}", file=sys.stderr)
                return 3
    return 0
