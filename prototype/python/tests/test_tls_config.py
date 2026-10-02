import ssl
import unittest
from unittest import mock

from androidialup_relay.tls import create_client_ssl_context, create_server_ssl_context


class TlsConfigTests(unittest.TestCase):
    @mock.patch.object(ssl.SSLContext, "load_cert_chain")
    def test_server_context_requires_tls_1_3_and_does_not_require_client_cert(self, load_cert_chain):
        context = create_server_ssl_context("cert.pem", "key.pem")
        self.assertEqual(context.minimum_version, ssl.TLSVersion.TLSv1_3)
        self.assertEqual(context.verify_mode, ssl.CERT_NONE)
        load_cert_chain.assert_called_once_with(certfile="cert.pem", keyfile="key.pem")

    @mock.patch.object(ssl.SSLContext, "load_verify_locations")
    def test_client_context_verifies_certificates_and_hostname(self, load_verify_locations):
        context = create_client_ssl_context("ca.pem")
        self.assertEqual(context.minimum_version, ssl.TLSVersion.TLSv1_3)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(context.check_hostname)
        load_verify_locations.assert_called_once_with(cafile="ca.pem")


if __name__ == "__main__":
    unittest.main()
