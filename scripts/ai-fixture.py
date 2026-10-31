#!/usr/bin/env python3
"""本地 mock 模型服务：只为验证 AI 助手的工具调用链路，不是真实模型。

行为：
- POST /v1/chat/completions：第一轮返回一个 fetch_page 工具调用；收到工具结果后
  用其中的价格整理成中文回答。
- GET /plans：一份"官网定价页"的 HTML，供抓取工具读取。
- GET /health：健康检查。

只在 127.0.0.1 监听，不记录请求体。
"""
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PRICE = "17.99"
BASE = "http://127.0.0.1:18082"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def _json(self, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if urlparse(self.path).path == "/health":
            self._json({"ok": True})
            return
        if urlparse(self.path).path == "/v1/models":
            # OpenAI 兼容的模型列表：让"从服务获取模型列表"能走真实 HTTP 路径验证。
            self._json({
                "object": "list",
                "data": [
                    {"id": "mock-fast", "object": "model"},
                    {"id": "mock-balanced", "object": "model"},
                    {"id": "mock-deep", "object": "model"},
                ],
            })
            return
        if urlparse(self.path).path == "/plans":
            html = f"""<!doctype html><html><head><title>Standard plan</title>
            <style>.x{{color:#000}}</style><script>track()</script></head>
            <body><h1>Netflix Standard</h1>
            <p>United States pricing</p>
            <p>US$ {PRICE} / month</p>
            <p>Prices exclude taxes.</p>
            <script>alert(1)</script></body></html>"""
            body = html.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if urlparse(self.path).path != "/v1/chat/completions":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length) or b"{}")
        messages = request.get("messages", [])
        tool_payload = None
        for message in reversed(messages):
            if message.get("role") == "tool":
                tool_payload = message.get("content")
                break
            if message.get("role") == "user":
                break
        if tool_payload is None:
            self._json({"choices": [{"message": {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_mock_1",
                    "type": "function",
                    "function": {"name": "fetch_page", "arguments": json.dumps({"url": f"{BASE}/plans"})},
                }],
            }}]})
            return
        price = PRICE
        match = re.search(r"US\$\s*([0-9.]+)", tool_payload or "")
        if match:
            price = match.group(1)
        answer = (
            f"我打开了官网定价页核对：Netflix 标准版在美国区是每月 ${price}（未含税）。\n"
            "你台账里目前没有 Netflix；如果要记，按这个价格新建一条月付即可。\n"
            "来源已列在下面。"
        )
        self._json({"choices": [{"message": {"role": "assistant", "content": answer}}]})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18082), Handler).serve_forever()
