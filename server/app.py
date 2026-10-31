"""Opaque incremental operation service. Never accepts ledger plaintext or a data key.

Android sync UI is intentionally gated until its transactional engine is complete.
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

MAX_BODY = 1_048_576


class Store:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        os.chmod(path, 0o600)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
        PRAGMA journal_mode=WAL;
        PRAGMA foreign_keys=ON;
        CREATE TABLE IF NOT EXISTS tenant(id TEXT PRIMARY KEY, recovery_hash TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS device(id TEXT PRIMARY KEY, tenant TEXT NOT NULL REFERENCES tenant(id),
          name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS pairing(hash TEXT PRIMARY KEY, tenant TEXT NOT NULL, expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS epoch(tenant TEXT PRIMARY KEY, value INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS role(tenant TEXT PRIMARY KEY, deviceId TEXT, version INTEGER NOT NULL, updatedAt INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS op(seq INTEGER PRIMARY KEY AUTOINCREMENT, tenant TEXT NOT NULL,
          id TEXT NOT NULL, device TEXT NOT NULL, envelope TEXT NOT NULL, UNIQUE(tenant,id));
        PRAGMA user_version=1;
        """)

    @staticmethod
    def digest(token):
        return hashlib.sha256(token.encode()).hexdigest()

    def add_device(self, tenant, name):
        token = secrets.token_urlsafe(32)
        device = secrets.token_hex(16)
        self.db.execute("INSERT INTO device(id,tenant,name,token_hash) VALUES(?,?,?,?)",
                        (device, tenant, str(name)[:80], self.digest(token)))
        return {"deviceId": device, "tenantId": tenant, "accessToken": token}

    def authenticate(self, header):
        if not header.startswith("Bearer ") or len(header) > 200:
            raise ApiError(401, "unauthorized")
        row = self.db.execute("SELECT * FROM device WHERE token_hash=? AND revoked=0",
                              (self.digest(header[7:]),)).fetchone()
        if row is None:
            raise ApiError(401, "unauthorized or revoked")
        return dict(row)


class ApiError(Exception):
    def __init__(self, code, message):
        self.code, self.message = code, message


def validate_envelope(op):
    if not isinstance(op, dict) or set(op) != {"opId", "envelope"}:
        raise ApiError(400, "invalid operation")
    if not isinstance(op["opId"], str) or not 16 <= len(op["opId"]) <= 100:
        raise ApiError(400, "invalid operation ID")
    e = op["envelope"]
    if not isinstance(e, dict) or set(e) != {"version", "keyEpoch", "nonce", "ciphertext"}:
        raise ApiError(400, "opaque AES-GCM envelope required")
    if e["version"] != 1 or not isinstance(e["keyEpoch"], int) or e["keyEpoch"] < 1:
        raise ApiError(400, "unsupported envelope")
    try:
        nonce = base64.b64decode(e["nonce"], validate=True)
        cipher = base64.b64decode(e["ciphertext"], validate=True)
        if len(nonce) != 12 or not 16 <= len(cipher) <= 65_536:
            raise ValueError()
    except (ValueError, TypeError):
        raise ApiError(400, "invalid ciphertext")
    return json.dumps(e, sort_keys=True, separators=(",", ":"))


class Handler(BaseHTTPRequestHandler):
    server_version = "SubTrack/1"
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_):
        pass  # Never log authorization headers, recovery material, request bodies or query strings.

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.handle_api()

    def do_POST(self):
        self.handle_api()

    def handle_api(self):
        try:
            parsed = urlsplit(self.path)
            path = parsed.path
            if self.command == "GET" and path == "/healthz":
                return self.reply(200, {"ok": True, "schemaVersion": 1})
            if self.command == "GET" and path == "/v1/templates":
                return self.reply(200, json.loads(Path(__file__).with_name("templates.json").read_text()))
            # TLS terminates in the supplied Caddy service. Direct HTTP is loopback-only by default.
            if not self.server.debug_http and self.headers.get("X-Forwarded-Proto") != "https":
                raise ApiError(403, "TLS proxy required")
            size = int(self.headers.get("Content-Length", "0"))
            if size < 0 or size > MAX_BODY or self.headers.get("Transfer-Encoding"):
                raise ApiError(413, "body too large or unsupported transfer")
            body = json.loads(self.rfile.read(size)) if size else {}
            store = self.server.store
            with store.lock:
                now = time.monotonic()
                bucket = self.server.requests
                for ip in list(bucket):
                    if now - bucket[ip][0] > 60:
                        del bucket[ip]
                ip = self.client_address[0]
                start, count = bucket.get(ip, (now, 0))
                if count >= 120:
                    raise ApiError(429, "rate limited; wait one minute")
                if ip not in bucket and len(bucket) >= 4096:
                    raise ApiError(429, "service busy")
                bucket[ip] = (start, count + 1)
                with store.db:
                    code, result = self.dispatch(store, path, parse_qs(parsed.query), body)
            self.reply(code, result)
        except ApiError as e:
            self.reply(e.code, {"error": e.message})
        except (ValueError, KeyError, TypeError):
            self.reply(400, {"error": "invalid request"})
        except Exception:
            self.reply(500, {"error": "storage or internal error; transaction rolled back"})

    def dispatch(self, s, path, query, body):
        if self.command == "POST" and path == "/v1/register":
            supplied = self.headers.get("X-Setup-Token", "")
            if not hmac.compare_digest(supplied, self.server.setup_token):
                raise ApiError(401, "initialization credential required")
            tenant = secrets.token_hex(16)
            recovery = secrets.token_urlsafe(32)
            s.db.execute("INSERT INTO tenant VALUES(?,?)", (tenant, s.digest(recovery)))
            result = s.add_device(tenant, body.get("name", "first device"))
            result["recoveryCredential"] = recovery
            return 201, result
        if self.command == "POST" and path in ("/v1/pair", "/v1/recover"):
            token = body.get("credential", "")
            if not isinstance(token, str) or len(token) > 200:
                raise ApiError(400, "invalid credential")
            if path == "/v1/pair":
                row = s.db.execute("SELECT tenant FROM pairing WHERE hash=? AND expires>?",
                                   (s.digest(token), int(time.time()))).fetchone()
                if row:
                    s.db.execute("DELETE FROM pairing WHERE hash=?", (s.digest(token),))
            else:
                row = s.db.execute("SELECT id AS tenant FROM tenant WHERE recovery_hash=?", (s.digest(token),)).fetchone()
            if row is None:
                raise ApiError(401, "invalid or expired credential")
            return 201, s.add_device(row["tenant"], body.get("name", "paired device"))
        d = s.authenticate(self.headers.get("Authorization", ""))
        tenant = d["tenant"]
        if path == "/v1/role" and self.command == "GET":
            row = s.db.execute("SELECT deviceId, version FROM role WHERE tenant=?", (tenant,)).fetchone()
            return 200, {"deviceId": row["deviceId"] if row else None, "version": row["version"] if row else 0}
        if path == "/v1/role" and self.command == "POST":
            # P5: tenant-scoped reminder-device role with compare-and-set concurrency. No keys or ledger data here.
            device = body.get("deviceId")
            expected = body.get("expectedVersion", 0)
            if isinstance(expected, bool) or not isinstance(expected, int) or expected < 0:
                raise ApiError(400, "invalid expectedVersion")
            if device is not None and (not isinstance(device, str) or not device or len(device) > 128):
                raise ApiError(400, "invalid deviceId")
            row = s.db.execute("SELECT deviceId, version FROM role WHERE tenant=?", (tenant,)).fetchone()
            current = row["version"] if row else 0
            if expected != current:
                return 409, {"deviceId": row["deviceId"] if row else None, "version": current}
            if device is not None:
                owned = s.db.execute("SELECT 1 FROM device WHERE id=? AND tenant=? AND revoked=0", (device, tenant)).fetchone()
                if owned is None:
                    raise ApiError(400, "designated device is not an active member")
            version = current + 1
            s.db.execute(
                "INSERT INTO role(tenant,deviceId,version,updatedAt) VALUES(?,?,?,?) "
                "ON CONFLICT(tenant) DO UPDATE SET deviceId=?, version=?, updatedAt=?",
                (tenant, device, version, int(time.time()), device, version, int(time.time())),
            )
            return 200, {"deviceId": device, "version": version}
        if self.command == "POST" and path == "/v1/epoch":
            # C/G4/P2: cross-device rotation must never allocate the same epoch on two devices, and an allocator introduced
            # later must seed above epochs that were already accepted before it existed. Lower bound = max(allocator row,
            # max keyEpoch of stored operations, caller's own highest committed epoch). Allocation is transactional and
            # survives restarts because it is persisted.
            min_epoch = body.get("minEpoch", 0)
            if isinstance(min_epoch, bool) or not isinstance(min_epoch, int) or not (0 <= min_epoch <= 1_000_000):
                raise ApiError(400, "invalid minEpoch")
            row = s.db.execute("SELECT value FROM epoch WHERE tenant=?", (tenant,)).fetchone()
            legacy = s.db.execute(
                "SELECT MAX(CAST(json_extract(envelope,'$.keyEpoch') AS INTEGER)) FROM op WHERE tenant=?", (tenant,)
            ).fetchone()[0] or 0
            lower = max(1, row["value"] if row else 0, legacy, min_epoch)
            value = lower + 1
            s.db.execute(
                "INSERT INTO epoch(tenant,value) VALUES(?,?) ON CONFLICT(tenant) DO UPDATE SET value=?",
                (tenant, value, value),
            )
            return 200, {"epoch": value}
        if self.command == "POST" and path == "/v1/pairing":
            code = secrets.token_urlsafe(32)
            s.db.execute("DELETE FROM pairing WHERE expires<=?", (int(time.time()),))
            s.db.execute("INSERT INTO pairing VALUES(?,?,?)", (s.digest(code), tenant, int(time.time()) + 300))
            return 201, {"pairingCredential": code, "expiresIn": 300}
        if self.command == "GET" and path == "/v1/devices":
            return 200, {"devices": [dict(r) for r in s.db.execute(
                "SELECT id,name,revoked FROM device WHERE tenant=?", (tenant,))]}
        if self.command == "POST" and path == "/v1/revoke":
            result = s.db.execute("UPDATE device SET revoked=1 WHERE tenant=? AND id=?", (tenant, body["deviceId"]))
            if result.rowcount != 1:
                raise ApiError(404, "device not found")
            s.db.execute("DELETE FROM pairing WHERE tenant=?", (tenant,))
            return 200, {"revoked": body["deviceId"], "keyRotationRequired": True}
        if self.command == "POST" and path == "/v1/ops":
            ops = body["ops"]
            if not isinstance(ops, list) or not 1 <= len(ops) <= 100:
                raise ApiError(400, "batch size must be 1..100")
            # Validate all before writing; a rejected batch cannot be partially committed.
            validated = [(op["opId"], validate_envelope(op)) for op in ops]
            accepted = []
            for opid, envelope in validated:
                old = s.db.execute("SELECT seq,envelope FROM op WHERE tenant=? AND id=?", (tenant, opid)).fetchone()
                if old:
                    if old["envelope"] != envelope:
                        raise ApiError(409, "operation ID reused with different payload")
                    accepted.append({"opId": opid, "seq": old["seq"], "duplicate": True})
                else:
                    cur = s.db.execute("INSERT INTO op(tenant,id,device,envelope) VALUES(?,?,?,?)",
                                       (tenant, opid, d["id"], envelope))
                    accepted.append({"opId": opid, "seq": cur.lastrowid, "duplicate": False})
            return 200, {"accepted": accepted}
        if self.command == "GET" and path == "/v1/changes":
            cursor = int(query.get("after", ["0"])[0])
            limit = int(query.get("limit", ["100"])[0])
            if cursor < 0 or not 1 <= limit <= 100:
                raise ApiError(400, "invalid page")
            rows = list(s.db.execute("SELECT seq,id,device,envelope FROM op WHERE tenant=? AND seq>? ORDER BY seq LIMIT ?",
                                     (tenant, cursor, limit + 1)))
            page = rows[:limit]
            return 200, {"changes": [{"seq": r["seq"], "opId": r["id"], "deviceId": r["device"],
                                      "envelope": json.loads(r["envelope"])} for r in page],
                         "cursor": page[-1]["seq"] if page else cursor, "hasMore": len(rows) > limit}
        raise ApiError(404, "not found")


def create_server(path, address=("127.0.0.1", 8080), setup_token=None, debug_http=False):
    token = setup_token or os.environ.get("SUBTRACK_SETUP_TOKEN", "")
    if len(token) < 32:
        raise ValueError("SUBTRACK_SETUP_TOKEN must contain at least 32 random characters; no default credential")
    server = ThreadingHTTPServer(address, Handler)
    server.store = Store(path)
    server.setup_token = token
    server.debug_http = debug_http
    server.requests = {}
    server.timeout = 15
    return server


if __name__ == "__main__":
    create_server(os.environ.get("SUBTRACK_DATA", "./data/sync.sqlite"),
                  (os.environ.get("SUBTRACK_BIND", "127.0.0.1"), int(os.environ.get("PORT", "8080"))),
                  debug_http=os.environ.get("SUBTRACK_DEBUG_HTTP") == "1").serve_forever()
