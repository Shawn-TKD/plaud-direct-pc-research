import unittest

from plaud_pc.bootstrap import (
    create_portable_envelope,
    create_portable_secret_envelope,
    decrypt_portable_envelope,
    decrypt_portable_secret_envelope,
)


class BootstrapTests(unittest.TestCase):
    def test_portable_named_secret_round_trip(self):
        password = "correct horse battery staple portable"
        envelope = create_portable_secret_envelope("SILICONFLOW_API_KEY", "test-key", password)
        self.assertEqual(
            decrypt_portable_secret_envelope(envelope, password),
            {"name": "SILICONFLOW_API_KEY", "value": "test-key"},
        )

    def test_portable_envelope_round_trip(self):
        material = {
            "version": 1,
            "sn": "test-sn",
            "signature": "signature",
            "bindToken": "bind-token",
            "rsaPublicKey": "public-key",
            "rsaPrivateKey": "private-key",
        }
        password = "correct horse battery staple portable"
        envelope = create_portable_envelope(material, password)
        self.assertEqual(decrypt_portable_envelope(envelope, password), material)

    def test_portable_envelope_rejects_wrong_password(self):
        material = {
            "version": 1,
            "sn": "test-sn",
            "signature": "signature",
            "bindToken": "bind-token",
            "rsaPublicKey": "public-key",
            "rsaPrivateKey": "private-key",
        }
        envelope = create_portable_envelope(material, "correct horse battery staple portable")
        with self.assertRaises(Exception):
            decrypt_portable_envelope(envelope, "this is the wrong portable password")
