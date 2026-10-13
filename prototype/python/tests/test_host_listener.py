"""Lifecycle/ownership contracts for the modular one-client TCP DTE listener."""
from __future__ import annotations

import io
import unittest
from contextlib import redirect_stderr
from unittest.mock import MagicMock, patch

from androidialup_host.tcp_listener import serve_one_client


class TcpListenerTest(unittest.TestCase):
    def _server(self):
        server = MagicMock()
        server.__enter__.return_value = server
        client = MagicMock()
        client.__enter__.return_value = client
        server.getsockname.return_value = ("127.0.0.1", 3333)
        server.accept.return_value = (client, ("127.0.0.1", 4444))
        return server, client

    def test_one_connection_is_bridged_and_sockets_close(self):
        server, client = self._server()
        bridge = MagicMock()
        transport = object()
        output = io.StringIO()
        with patch("socket.socket", return_value=server) as factory, redirect_stderr(output):
            result = serve_one_client("127.0.0.1", 3333, transport, bridge)
        self.assertEqual(0, result)
        factory.assert_called_once()
        server.setsockopt.assert_called_once()
        server.bind.assert_called_once_with(("127.0.0.1", 3333))
        server.listen.assert_called_once_with(1)
        server.accept.assert_called_once_with()
        bridge.assert_called_once_with(client, transport)
        client.__exit__.assert_called_once()
        server.__exit__.assert_called_once()
        self.assertIn("AndroidDialup USB DTE listening on 127.0.0.1:3333", output.getvalue())
        self.assertIn("DTE client connected from 127.0.0.1:4444", output.getvalue())

    def test_bridge_exception_has_same_exit_code_and_closes_sockets(self):
        server, client = self._server()
        output = io.StringIO()
        bridge = MagicMock(side_effect=RuntimeError("USB disconnected"))
        with patch("socket.socket", return_value=server), redirect_stderr(output):
            result = serve_one_client("127.0.0.1", 3333, object(), bridge)
        self.assertEqual(3, result)
        self.assertIn("bridge ended: USB disconnected", output.getvalue())
        client.__exit__.assert_called_once()
        server.__exit__.assert_called_once()

    def test_bind_error_is_not_mistaken_for_bridge_failure(self):
        server, _ = self._server()
        server.bind.side_effect = OSError("address already in use")
        with patch("socket.socket", return_value=server):
            with self.assertRaisesRegex(OSError, "address already in use"):
                serve_one_client("127.0.0.1", 3333, object(), MagicMock())
        server.__exit__.assert_called_once()


if __name__ == "__main__":
    unittest.main()
