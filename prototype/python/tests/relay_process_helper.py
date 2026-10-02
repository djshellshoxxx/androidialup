"""Run a RelayTcpServer as a separate OS process for failure-injection tests.

Usage: relay_process_helper.py CERT KEY ENDPOINT_ID_HEX DEVICE_SECRET_HEX

Prints ``PORT <n>`` once listening, then serves until killed. Tests send it
SIGKILL (relay process death) or SIGSTOP (relay hung, sockets left open).
"""

from __future__ import annotations

import asyncio
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from androidialup_relay.auth import InMemoryDeviceCredentialStore  # noqa: E402
from androidialup_relay.server import RelayTcpServer  # noqa: E402
from androidialup_relay.tls import create_server_ssl_context  # noqa: E402


async def main(cert: str, key: str, endpoint_hex: str, secret_hex: str) -> None:
    server = RelayTcpServer(
        "127.0.0.1",
        0,
        create_server_ssl_context(cert, key),
        credential_store=InMemoryDeviceCredentialStore({bytes.fromhex(endpoint_hex): bytes.fromhex(secret_hex)}),
    )
    await server.start()
    print(f"PORT {server.address[1]}", flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    asyncio.run(main(*sys.argv[1:5]))
