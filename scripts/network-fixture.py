#!/usr/bin/env python3
"""Controlled fault/protocol endpoint, not an AI model or evidence of current prices."""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Per-path hit counter for the stateful "same URL good-then-bad" scenario. Keyed by the exact request path so a client
# can use a fresh suffix per run and each run independently observes valid-then-invalid on the SAME url.
_FLAKY_HITS = {}
_FLAKY_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == "/slow":
            time.sleep(5)
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "http://127.0.0.1:1/secret")
            self.end_headers()
            return
        if self.path.startswith("/templates-flaky"):
            with _FLAKY_LOCK:
                hit = _FLAKY_HITS.get(self.path, 0)
                _FLAKY_HITS[self.path] = hit + 1
            body = (ROOT / "server/templates.json").read_bytes() if hit == 0 else b'{"schemaVersion":99,"templates":[]}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except BrokenPipeError:
                pass
            return
        if self.path.startswith("/templates-slow"):
            # Valid catalog but delayed, to exercise "late refresh after switching source must not publish".
            time.sleep(5)
            body = (ROOT / "server/templates.json").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except BrokenPipeError:
                pass
            return
        # --- F5 documented search protocol fixture (Brave-compatible) ------------------------------------------
        if self.path.startswith("/search/web-slow"):
            # Same protocol, deliberately slow: production UI must cancel and never publish this late result.
            if self.headers.get("X-Subscription-Token") != "fixture-search-key":
                self.send_response(401); self.send_header("Content-Length", "0"); self.end_headers(); return
            time.sleep(3)
            body = json.dumps({"web": {"results": [{"title": "Slow source", "url": "https://fixture.subtrack.invalid/slow",
                "description": "Sample Pro costs USD 7.77 per month before tax."}]}}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers()
            try: self.wfile.write(body)
            except BrokenPipeError: pass
            return
        if self.path.startswith("/search/web") or self.path.startswith("/search/forbidden") or self.path.startswith("/search/quota"):
            # Controlled service: authentication header is REQUIRED and never echoed/logged.
            if self.headers.get("X-Subscription-Token") != "fixture-search-key":
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/search/forbidden"):
                self.send_response(403)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/search/quota"):
                self.send_response(429)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            body = json.dumps({
                "web": {
                    "results": [
                        {
                            "title": "Sample Pro plan pricing",
                            "url": "https://fixture.subtrack.invalid/sample-pro",
                            "description": "Sample Pro costs USD 9.99 per month before tax.",
                            "age": "1 day",
                        }
                    ]
                }
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except BrokenPipeError:
                pass
            return
        status = 401 if self.path == "/bad-key" else 429 if self.path == "/quota" else 200
        if self.path == "/templates":
            body = (ROOT / "server/templates.json").read_bytes()
        elif self.path == "/huge":
            body = b"x" * 1_100_000
        elif self.path == "/invalid":
            body = b'{"schemaVersion":99,"templates":[]}'
        else:
            body = b'{}'
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass

    def do_POST(self):
        size = int(self.headers.get("Content-Length", 0))
        if size > 65536:
            self.send_error(413)
            return
        raw = self.rfile.read(size)  # Deliberately do not log body or key.
        if self.path.startswith("/model/chat"):
            # Controlled OpenAI-compatible endpoint: model key must arrive as a Bearer token, and the material must be
            # the retrieval snippets (never ledger data). The body/headers are not logged.
            if self.headers.get("Authorization") != "Bearer fixture-model-key":
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            text = raw.decode("utf-8", "replace")
            if "fixture.subtrack.invalid" not in text or "9.99" not in text:
                self.send_response(400)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            source = "https://evil.example/forged" if self.path.startswith("/model/chat-bad-source") \
                else "https://fixture.subtrack.invalid/sample-pro"
            content = {
                "quotes": [{
                    "id": "model-1", "service": "Sample", "plan": "Pro", "region": "US", "currency": "USD",
                    "amountMinor": 999, "billingPeriod": "MONTHLY", "taxTerms": "before tax",
                    "discountTerms": "no promotion", "benefitTerms": "same benefits", "newUserOnly": False,
                    "sourceUrl": source, "evidence": "USD 9.99 per month",
                }]
            }
            body = json.dumps({"choices": [{"message": {"content": json.dumps(content)}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if self.path == "/ai-tool-blocked":
            # 模拟"不支持 tool calling 就回 403"的服务：带 tools 拒绝，去掉 tools 正常回答。
            if b'"tools"' in raw:
                body = json.dumps({"error": {"message": "this model does not support tool calling"}}).encode()
                self.send_response(403)
            else:
                body = json.dumps({"choices": [{"message": {
                    "role": "assistant", "content": "这个服务不支持工具调用；已改用普通对话回答。",
                }}]}).encode()
                self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        if self.path == "/slow-ai":
            time.sleep(5)
        content = {"service": "Sample", "plan": "Pro", "region": "US", "currency": "USD",
                   "billingPeriod": "MONTHLY", "amountMinor": 2000, "taxTerms": "before tax",
                   "sourceUrl": "https://example.com/price", "evidence": "USD 20 per month"}
        body = json.dumps({"choices": [{"message": {"content": json.dumps(content)}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


ThreadingHTTPServer(("127.0.0.1", 18080), Handler).serve_forever()
