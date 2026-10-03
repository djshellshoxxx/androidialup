import socket
import threading
import unittest

from androidialup_host.bridge import bridge_socket_to_transport


class MemoryTransport:
    def __init__(self):
        self.inbound = bytearray()
        self.outbound = bytearray()
        self.closed = False
        self._condition = threading.Condition()

    def read(self, size):
        with self._condition:
            while not self.outbound and not self.closed:
                self._condition.wait(timeout=0.5)
            if self.outbound:
                take = min(size, len(self.outbound))
                data = bytes(self.outbound[:take])
                del self.outbound[:take]
                return data
            return b""

    def write(self, data):
        with self._condition:
            self.inbound.extend(data)
            self._condition.notify_all()
        return len(data)

    def inject_from_android(self, data):
        with self._condition:
            self.outbound.extend(data)
            self._condition.notify_all()

    def close(self):
        with self._condition:
            self.closed = True
            self._condition.notify_all()


class HostBridgeTest(unittest.TestCase):
    def test_forwards_bytes_bidirectionally_without_transforming(self):
        client, bridge_side = socket.socketpair()
        transport = MemoryTransport()
        thread = threading.Thread(
            target=bridge_socket_to_transport,
            args=(bridge_side, transport),
            daemon=True,
        )
        thread.start()
        try:
            payload_to_android = bytes(range(256)) * 8
            client.sendall(payload_to_android)
            client.shutdown(socket.SHUT_WR)

            transport.inject_from_android(b"CONNECT 9600\r\nhello\x00world")
            received = bytearray()
            while True:
                chunk = client.recv(4096)
                if not chunk:
                    break
                received.extend(chunk)

            thread.join(timeout=2)
            self.assertFalse(thread.is_alive())
            self.assertEqual(payload_to_android, bytes(transport.inbound))
            self.assertEqual(b"CONNECT 9600\r\nhello\x00world", bytes(received))
        finally:
            transport.close()
            client.close()
            bridge_side.close()

    def test_transport_eof_closes_socket_output(self):
        client, bridge_side = socket.socketpair()
        transport = MemoryTransport()
        transport.close()
        thread = threading.Thread(
            target=bridge_socket_to_transport,
            args=(bridge_side, transport),
            daemon=True,
        )
        thread.start()
        try:
            client.settimeout(2)
            self.assertEqual(b"", client.recv(1))
            thread.join(timeout=2)
            self.assertFalse(thread.is_alive())
        finally:
            client.close()
            bridge_side.close()


if __name__ == "__main__":
    unittest.main()
