#!/usr/bin/env python3
"""Host-side HTTPS fixture contract test; synthetic APK bytes, no Android installation."""
import base64
import hashlib
import importlib.util
import json
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

spec = importlib.util.spec_from_file_location("update_fixture", Path(__file__).with_name("update-https-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class UpdateFixtureContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="subtrack-update-https-")
        cls.root = Path(cls.tmp.name)
        cls.keys = cls.root / "keys"
        fixture.initialize(cls.keys)
        cls.good = cls.root / "good.apk"; cls.good.write_bytes(b"synthetic-good-apk")
        cls.wrong = cls.root / "wrong.apk"; cls.wrong.write_bytes(b"synthetic-wrong-signer-apk")
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), fixture.BaseHTTPRequestHandler)
        port = cls.server.server_address[1]
        cls.server.RequestHandlerClass = fixture.make_handler(cls.keys, cls.good, cls.wrong, 20, port)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cls.keys / "tls-server.pem", cls.keys / "tls-server-key.pem")
        cls.server.socket = tls.wrap_socket(cls.server.socket, server_side=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()
        cls.base = f"https://127.0.0.1:{port}"
        cls.context = ssl.create_default_context(cafile=str(cls.keys / "tls-ca.pem"))
        der = base64.b64decode((cls.keys / "manifest-test-public.b64").read_text())
        cls.public = cls.root / "manifest-public.pem"
        cls.public.write_bytes(fixture.openssl("pkey", "-pubin", "-inform", "DER", "-outform", "PEM", input_data=der))

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(); cls.tmp.cleanup()

    def fetch(self, path):
        with urllib.request.urlopen(self.base + path, context=self.context, timeout=3) as response:
            return response.read()

    def verify(self, envelope):
        body = base64.b64decode(envelope["payload"], validate=True)
        signature = self.root / "signature.bin"; signature.write_bytes(base64.b64decode(envelope["signature"], validate=True))
        result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(self.public), "-signature", str(signature)],
                                input=body, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return result.returncode == 0, json.loads(body)

    def test_https_trust_is_explicit_and_hostname_checked(self):
        with self.assertRaises(urllib.error.URLError):
            urllib.request.urlopen(self.base + "/manifest/valid", timeout=3)
        self.assertTrue(self.verify(json.loads(self.fetch("/manifest/valid")))[0])
        with socket.create_connection(self.server.server_address, timeout=3) as connection:
            with self.assertRaises(ssl.SSLCertVerificationError):
                self.context.wrap_socket(connection, server_hostname="wrong.example")

    def test_bad_signatures_tampered_manifests_and_apks(self):
        for mode in ("bad-signature", "tampered-manifest"):
            self.assertFalse(self.verify(json.loads(self.fetch("/manifest/" + mode)))[0])
        verified, payload = self.verify(json.loads(self.fetch("/manifest/tampered-apk")))
        self.assertTrue(verified)
        tampered = self.fetch("/apk/tampered")
        self.assertEqual(payload["sizeBytes"], len(tampered))
        self.assertNotEqual(payload["sha256"], hashlib.sha256(tampered).hexdigest())

    def test_wrong_signer_path_has_its_own_correct_hash(self):
        verified, payload = self.verify(json.loads(self.fetch("/manifest/wrong-signer")))
        self.assertTrue(verified)
        self.assertEqual(payload["sha256"], hashlib.sha256(self.fetch("/apk/wrong")).hexdigest())
        self.assertIn("Isolated test", payload["releaseNotes"])

    def test_slow_download_can_time_out_without_an_empty_success(self):
        with self.assertRaises((socket.timeout, TimeoutError, urllib.error.URLError)):
            urllib.request.urlopen(self.base + "/apk/slow", context=self.context, timeout=0.2).read()


if __name__ == "__main__":
    unittest.main(verbosity=2)
