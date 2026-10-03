"""Bidirectional byte bridge between a host endpoint and the Android AOA transport."""

from __future__ import annotations

import socket
import threading
from typing import Protocol


class ByteTransport(Protocol):
    def read(self, size: int) -> bytes: ...
    def write(self, data: bytes) -> int | None: ...


def _write_all(transport: ByteTransport, data: bytes) -> None:
    view = memoryview(data)
    while view:
        written = transport.write(view.tobytes())
        if written is None:
            return
        if written <= 0:
            raise OSError("transport write made no progress")
        view = view[written:]


def bridge_socket_to_transport(
    endpoint: socket.socket,
    transport: ByteTransport,
    *,
    chunk_size: int = 64 * 1024,
) -> None:
    """Forward bytes both ways until both input directions reach EOF.

    A host-side write half-close stops PC->Android forwarding but deliberately leaves
    Android->PC alive. USB/transport EOF shuts down the socket's write half so the local client
    receives EOF after all already-read bytes have been delivered.
    """
    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")

    errors: list[BaseException] = []
    pc_done = threading.Event()
    usb_done = threading.Event()

    def pc_to_usb() -> None:
        try:
            while True:
                data = endpoint.recv(chunk_size)
                if not data:
                    return
                _write_all(transport, data)
        except (OSError, RuntimeError) as exc:
            errors.append(exc)
        finally:
            pc_done.set()

    def usb_to_pc() -> None:
        try:
            while True:
                data = bytes(transport.read(chunk_size))
                if not data:
                    try:
                        endpoint.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
                    return
                endpoint.sendall(data)
        except (OSError, RuntimeError) as exc:
            errors.append(exc)
            try:
                endpoint.shutdown(socket.SHUT_WR)
            except OSError:
                pass
        finally:
            usb_done.set()

    reader = threading.Thread(target=pc_to_usb, name="aoa-pc-to-usb", daemon=True)
    writer = threading.Thread(target=usb_to_pc, name="aoa-usb-to-pc", daemon=True)
    reader.start()
    writer.start()

    # USB EOF is authoritative for the modem endpoint. Once it has ended, unblock any host recv.
    usb_done.wait()
    if not pc_done.is_set():
        try:
            endpoint.shutdown(socket.SHUT_RD)
        except OSError:
            pass
    reader.join()
    writer.join()

    if errors:
        raise errors[0]
