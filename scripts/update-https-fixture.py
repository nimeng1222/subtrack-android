#!/usr/bin/env python3
"""Isolated loopback-only E4/N3 fixture; all keys are ephemeral test keys.

init --dir DIR creates separate TLS CA/server and manifest signing material.
serve --dir DIR --apk APK --version-code N --wrong-signer-apk APK starts HTTPS.
No keys, credentials, request headers, or bodies are logged.
"""
import argparse
import base64
import hashlib
import json
import os
import ssl
import subprocess
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def openssl(*args, input_data=None):
    return subprocess.run(["openssl", *map(str, args)], input=input_data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=True).stdout


def initialize(directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    os.chmod(directory, 0o700)
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
            "-subj", "/CN=SubTrack isolated TLS test CA", "-addext", "basicConstraints=critical,CA:TRUE",
            "-keyout", directory / "tls-ca-key.pem", "-out", directory / "tls-ca.pem")
    openssl("req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
            "-keyout", directory / "tls-server-key.pem", "-out", directory / "tls-server.csr")
    ext = directory / "tls-ext.cnf"
    ext.write_text("subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2\n"
                   "extendedKeyUsage=serverAuth\nbasicConstraints=critical,CA:FALSE\n")
    openssl("x509", "-req", "-in", directory / "tls-server.csr", "-CA", directory / "tls-ca.pem",
            "-CAkey", directory / "tls-ca-key.pem", "-CAcreateserial", "-days", "2",
            "-extfile", ext, "-out", directory / "tls-server.pem")
    for name in ("manifest-test", "manifest-wrong"):
        openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", directory / f"{name}-key.pem")
    public_der = openssl("pkey", "-in", directory / "manifest-test-key.pem", "-pubout", "-outform", "DER")
    (directory / "manifest-test-public.b64").write_text(base64.b64encode(public_der).decode("ascii"))
    for path in directory.glob("*-key.pem"):
        os.chmod(path, 0o600)
    print("Isolated TEST material created; TLS CA and update-signing key are distinct.")
    print("TEST CA:", directory / "tls-ca.pem")
    print("TEST manifest public key:", directory / "manifest-test-public.b64")


def make_handler(directory, apk, wrong_apk, version_code, port):
    good = apk.read_bytes()
    wrong = wrong_apk.read_bytes()
    if not good or not wrong or max(len(good), len(wrong)) > 64 * 1024 * 1024:
        raise ValueError("fixture APK size invalid")
    events = []
    events_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def reply(self, status, body, kind="application/json"):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass

        def do_GET(self):
            name = self.path.split("?", 1)[0]
            if name == "/test-events":
                with events_lock:
                    return self.reply(200, json.dumps(events).encode())
            if name.startswith(("/manifest/", "/apk/")):
                with events_lock:
                    events.append(name)
            if name.startswith("/manifest/"):
                mode = name.rsplit("/", 1)[-1]
                if mode not in {"valid", "slow", "slow-apk", "bad-signature", "tampered-manifest", "tampered-apk", "wrong-signer"}:
                    return self.reply(404, b"{}")
                if mode == "slow":
                    time.sleep(6)
                which = "wrong" if mode == "wrong-signer" else "slow" if mode == "slow-apk" else "tampered" if mode == "tampered-apk" else "good"
                expected = wrong if mode == "wrong-signer" else good
                payload = json.dumps({"packageName": "com.subtrack", "channel": "direct", "versionName": "TEST ONLY",
                    "versionCode": version_code, "minSdk": 26,
                    "downloadUrl": f"https://127.0.0.1:{port}/apk/{which}", "sizeBytes": len(expected),
                    "sha256": hashlib.sha256(expected).hexdigest(), "releaseNotes": "Isolated test; not a release",
                    "publishedAt": "2026-10-29T00:00:00Z"}, separators=(",", ":")).encode()
                key = directory / ("manifest-wrong-key.pem" if mode == "bad-signature" else "manifest-test-key.pem")
                sig = openssl("dgst", "-sha256", "-sign", key, input_data=payload)
                if mode == "tampered-manifest":
                    payload = payload.replace(b"TEST ONLY", b"TAMPERED ")
                return self.reply(200, json.dumps({"payload": base64.b64encode(payload).decode(),
                    "signature": base64.b64encode(sig).decode()}).encode())
            if name.startswith("/apk/"):
                which = name.rsplit("/", 1)[-1]
                if which == "slow":
                    time.sleep(6)
                if which == "tampered":
                    changed = bytearray(good); changed[len(changed) // 2] ^= 1
                    return self.reply(200, changed, "application/vnd.android.package-archive")
                if which in {"good", "slow", "wrong"}:
                    return self.reply(200, wrong if which == "wrong" else good, "application/vnd.android.package-archive")
            return self.reply(404, b"{}")

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    init = sub.add_parser("init"); init.add_argument("--dir", type=Path, required=True)
    serve = sub.add_parser("serve"); serve.add_argument("--dir", type=Path, required=True)
    serve.add_argument("--apk", type=Path, required=True); serve.add_argument("--wrong-signer-apk", type=Path, required=True)
    serve.add_argument("--version-code", type=int, required=True); serve.add_argument("--port", type=int, default=18443)
    args = parser.parse_args()
    if args.cmd == "init":
        initialize(args.dir)
        return
    if args.version_code <= 0:
        parser.error("version-code must be positive")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(args.dir, args.apk, args.wrong_signer_apk, args.version_code, args.port))
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(args.dir / "tls-server.pem", args.dir / "tls-server-key.pem")
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    print(f"Isolated TEST HTTPS fixture bound to 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
