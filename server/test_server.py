import base64
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from app import create_server


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.server = create_server(self.tmp.name + "/sync.sqlite", ("127.0.0.1", 0), "s" * 40, True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)
        _, self.a = self.call("/v1/register", {"name": "A"}, setup=True)
        _, pair = self.call("/v1/pairing", {}, self.a)
        _, self.b = self.call("/v1/pair", {"credential": pair["pairingCredential"], "name": "B"})

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.server.store.db.close()
        self.tmp.cleanup()

    def call(self, path, body=None, device=None, setup=False):
        headers = {"Content-Type": "application/json"}
        if device:
            headers["Authorization"] = "Bearer " + device["accessToken"]
        if setup:
            headers["X-Setup-Token"] = "s" * 40
        req = urllib.request.Request(self.url + path, None if body is None else json.dumps(body).encode(), headers)
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def get(self, path, device=None):
        headers = {}
        if device:
            headers["Authorization"] = "Bearer " + device["accessToken"]
        req = urllib.request.Request(self.url + path, None, headers)
        with urllib.request.urlopen(req, timeout=3) as r:
            return r.status, json.load(r)

    def op(self, n):
        return {"opId": "operation-000000-" + str(n), "envelope": {"version": 1, "keyEpoch": 1,
            "nonce": base64.b64encode(os.urandom(12)).decode(), "ciphertext": base64.b64encode(os.urandom(48)).decode()}}

    def test_pairing_single_use(self):
        _, pair = self.call("/v1/pairing", {}, self.a)
        self.assertEqual(201, self.call("/v1/pair", {"credential": pair["pairingCredential"]})[0])
        self.assertEqual(401, self.call("/v1/pair", {"credential": pair["pairingCredential"]})[0])

    def test_incremental_paging_and_retry_response_loss(self):
        op = self.op(1)
        self.assertEqual(200, self.call("/v1/ops", {"ops": [op, self.op(2)]}, self.a)[0])
        _, retry = self.call("/v1/ops", {"ops": [op]}, self.a)
        self.assertTrue(retry["accepted"][0]["duplicate"])
        _, first = self.call("/v1/changes?limit=1", device=self.b)
        self.assertTrue(first["hasMore"])
        _, again = self.call("/v1/changes?limit=1", device=self.b)
        self.assertEqual(first, again)
        _, last = self.call("/v1/changes?after=" + str(first["cursor"]), device=self.b)
        self.assertEqual(1, len(last["changes"]))
        self.assertFalse(last["hasMore"])

    def test_tenant_isolation(self):
        _, other = self.call("/v1/register", {"name": "other tenant"}, setup=True)
        self.call("/v1/ops", {"ops": [self.op(1)]}, self.a)
        self.assertEqual([], self.call("/v1/changes", device=other)[1]["changes"])
        self.assertEqual(404, self.call("/v1/revoke", {"deviceId": self.a["deviceId"]}, other)[0])

    def test_revoke_rejects_old_access(self):
        self.assertEqual(200, self.call("/v1/revoke", {"deviceId": self.b["deviceId"]}, self.a)[0])
        self.assertEqual(401, self.call("/v1/changes", device=self.b)[0])
        self.assertEqual(401, self.call("/v1/ops", {"ops": [self.op(1)]}, self.b)[0])

    def test_batch_atomicity_and_reused_id(self):
        op = self.op(1)
        self.call("/v1/ops", {"ops": [op]}, self.a)
        changed = self.op(1)
        self.assertEqual(409, self.call("/v1/ops", {"ops": [self.op(2), changed]}, self.a)[0])
        self.assertEqual(1, len(self.call("/v1/changes", device=self.b)[1]["changes"]))
        self.assertEqual(400, self.call("/v1/ops", {"ops": [self.op(3), {}]}, self.a)[0])
        self.assertEqual(1, len(self.call("/v1/changes", device=self.b)[1]["changes"]))

    def test_invalid_envelopes_and_plaintext(self):
        self.assertEqual(400, self.call("/v1/ops", {"ops": [{"opId": "operation-000000-1", "envelope": {"name": "private"}}]}, self.a)[0])
        bad = self.op(1); bad["envelope"]["nonce"] = "not base64"
        self.assertEqual(400, self.call("/v1/ops", {"ops": [bad]}, self.a)[0])

    def test_no_default_secret_or_anonymous_access(self):
        with self.assertRaises(ValueError):
            create_server(self.tmp.name + "/bad.sqlite", ("127.0.0.1", 0), "short")
        self.assertEqual(401, self.call("/v1/changes")[0])
        self.assertEqual(401, self.call("/v1/register", {})[0])

    def test_templates_real_http(self):
        code, templates = self.call("/v1/templates")
        self.assertEqual(200, code)
        self.assertEqual(1, templates["schemaVersion"])
        self.assertGreaterEqual(len({t["currency"] for t in templates["templates"]}), 3)
        self.assertTrue(all(t["referencePriceMinor"] is None for t in templates["templates"]))

    def test_recovery_credential_separate_from_ledger_key(self):
        code, recovered = self.call("/v1/recover", {"credential": self.a["recoveryCredential"], "name": "recover"})
        self.assertEqual(201, code)
        self.assertEqual(self.a["tenantId"], recovered["tenantId"])
        self.assertNotIn("dataKey", recovered)

    def test_tls_boundary(self):
        self.server.debug_http = False
        self.assertEqual(403, self.call("/v1/changes", device=self.a)[0])
        self.assertEqual(200, self.call("/healthz")[0])


    def test_epoch_allocator_seeds_above_legacy_ops(self):
        # P2: an allocator introduced later must never hand out an epoch used by already-accepted operations.
        old = self.op(950)
        old["envelope"]["keyEpoch"] = 5
        self.assertEqual(200, self.call("/v1/ops", {"ops": [old]}, self.a)[0])
        status, allocated = self.call("/v1/epoch", {}, self.a)
        self.assertEqual(200, status)
        self.assertGreater(allocated["epoch"], 5)

    def test_epoch_allocator_honours_caller_min_epoch_and_concurrency(self):
        status, first = self.call("/v1/epoch", {"minEpoch": 9}, self.a)
        self.assertEqual(200, status)
        self.assertGreater(first["epoch"], 9)
        status, second = self.call("/v1/epoch", {}, self.a)
        self.assertEqual(200, status)
        self.assertGreater(second["epoch"], first["epoch"])



    def test_reminder_role_metadata_versions_and_tenant_isolation(self):
        status, initial = self.get("/v1/role", self.a)
        self.assertEqual(200, status)
        self.assertEqual({"deviceId": None, "version": 0}, initial)
        device_b = self.b["deviceId"]
        status, setter = self.call("/v1/role", {"deviceId": device_b, "expectedVersion": 0}, self.a)
        self.assertEqual(200, status)
        self.assertEqual(device_b, setter["deviceId"])
        self.assertEqual(1, setter["version"])
        # stale compare-and-set must be rejected and must not overwrite
        self.assertEqual(409, self.call("/v1/role", {"deviceId": None, "expectedVersion": 0}, self.a)[0])
        status, current = self.get("/v1/role", self.a)
        self.assertEqual(200, status)
        self.assertEqual(device_b, current["deviceId"])
        # a device from another tenant may never be designated
        self.assertEqual(400, self.call("/v1/role", {"deviceId": "not-a-member", "expectedVersion": 1}, self.a)[0])



if __name__ == "__main__":
    unittest.main(verbosity=2)

class EpochAllocationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.server = create_server(self.tmp.name + "/sync.sqlite", ("127.0.0.1", 0), "s" * 40, True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.server.store.db.close()
        self.tmp.cleanup()

    def call(self, path, body, token=None, setup=False):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if setup:
            headers["X-Setup-Token"] = "s" * 40
        req = urllib.request.Request(self.url + path, json.dumps(body).encode(), headers)
        with urllib.request.urlopen(req, timeout=3) as r:
            return r.status, json.load(r)

    def test_concurrent_rotations_receive_distinct_epochs(self):
        _, registered = self.call("/v1/register", {"name": "A"}, setup=True)
        token = registered["accessToken"]
        _, first = self.call("/v1/epoch", {}, token)
        _, second = self.call("/v1/epoch", {}, token)
        self.assertEqual(2, first["epoch"])
        self.assertEqual(3, second["epoch"])
        self.assertNotEqual(first["epoch"], second["epoch"])
