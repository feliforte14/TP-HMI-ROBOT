import json
import os
import tempfile
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer
from backend.engine import Engine
from backend.gateway import Gateway, handler_for


class Clock:
    def __init__(self): self.t = 1000.0
    def __call__(self): return self.t
    def advance(self, seconds=1): self.t += seconds


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.e = Engine(clock=self.clock)
        self.n = 0
    def send(self, action, **kwargs):
        self.n += 1
        return self.e.command(dict(id=str(self.n), action=action, expectedVersion=self.e.data["version"], expiresAt=self.clock()+3, **kwargs))
    def ticks(self, n):
        for _ in range(n): self.clock.advance(); self.e.tick()
    def test_normal_round_and_four_unique_checkpoints(self):
        self.assertEqual(self.send("START")["status"], "EXECUTED")
        self.ticks(20)
        self.assertEqual(self.e.data["verified"], 4)
        self.ticks(20)
        self.assertEqual(self.e.data["verified"], 4)
        self.assertEqual(self.e.data["status"], "COMPLETED")
        self.assertEqual(self.e.data["sector"], "Base")
        self.assertEqual(self.e.data["rounds"][0]["status"], "COMPLETED")
    def test_pause_freezes_progress(self):
        self.send("START"); self.send("PAUSE"); self.ticks(10)
        self.assertEqual(self.e.data["verified"], 0)
        self.send("RESUME"); self.ticks(5)
        self.assertEqual(self.e.data["verified"], 1)
    def test_obstacle_requires_resolution(self):
        self.send("START"); self.send("DEMO_OBSTACLE")
        self.assertEqual(self.send("RESUME")["status"], "REJECTED")
        event = self.e.data["events"][-1]
        self.send("ACK", eventId=event["id"])
        self.assertEqual(self.send("RESUME")["status"], "REJECTED")
        self.send("RESOLVE", eventId=event["id"], note="Paso verificado")
        self.assertEqual(self.send("RESUME")["status"], "EXECUTED")
    def test_presence_speaker_and_hold(self):
        self.send("START"); self.send("DEMO_PRESENCE")
        event = self.e.data["events"][-1]
        self.assertEqual(self.send("ANNOUNCE", eventId=event["id"])["status"], "EXECUTED")
        self.assertEqual(event["status"], "ACKNOWLEDGED")
        self.assertEqual(self.send("HOLD")["status"], "EXECUTED")
    def test_missing_navigation(self):
        self.send("START"); self.ticks(5); self.e.data["capabilities"] = []
        self.assertEqual(self.send("RETURN")["status"], "REJECTED")
    def test_access_deduplicated_and_resolution_guard(self):
        self.send("DEMO_ACCESS"); self.send("DEMO_ACCESS")
        self.assertEqual(len(self.e.data["events"]), 1)
        event = self.e.data["events"][0]
        self.assertEqual(self.send("RESOLVE", eventId=event["id"], note="cerrado")["status"], "REJECTED")
        self.send("DEMO_CLOSE_ACCESS")
        self.assertEqual(self.send("RESOLVE", eventId=event["id"], note="cerrado")["status"], "EXECUTED")
        self.send("DEMO_ACCESS")
        self.assertEqual(len(self.e.data["events"]), 2)
    def test_noise_hysteresis(self):
        self.send("DEMO_NOISE"); self.ticks(3)
        self.assertFalse(self.e.unresolved("NOISE"))
        self.ticks(1); self.assertTrue(self.e.unresolved("NOISE"))
        self.send("DEMO_QUIET"); self.ticks(5)
        self.assertTrue(self.e.unresolved("NOISE"))
        self.ticks(1); self.assertFalse(self.e.unresolved("NOISE"))
    def test_low_battery_return_recharge(self):
        self.send("START"); self.ticks(5); self.send("DEMO_LOW_BATTERY")
        self.assertEqual(self.e.data["status"], "PAUSED")
        self.assertEqual(self.send("START")["status"], "REJECTED")
        self.send("RETURN"); self.ticks(5)
        self.assertEqual(self.e.data["status"], "RETURNED")
        self.send("DEMO_RECHARGE")
        self.assertEqual(self.send("START")["status"], "EXECUTED")
    def test_idempotence(self):
        c = dict(id="same", action="START", expectedVersion=0, expiresAt=self.clock()+3)
        a = self.e.command(c)
        self.assertEqual(self.e.command(c), a)
        self.assertEqual(len(self.e.data["rounds"]), 1)
    def test_expired_order(self):
        c = dict(id="old", action="START", expectedVersion=0, expiresAt=self.clock()-1)
        self.assertEqual(self.e.command(c)["status"], "REJECTED")
    def test_stale_version(self):
        self.send("DEMO_ACCESS")
        c = dict(id="old", action="START", expectedVersion=0, expiresAt=self.clock()+3)
        self.assertEqual(self.e.command(c)["status"], "REJECTED")
    def test_restart_persists_result_and_pauses(self):
        with tempfile.TemporaryDirectory() as path:
            f = os.path.join(path, "engine.db")
            e = Engine(f, self.clock)
            c = dict(id="same", action="START", expectedVersion=0, expiresAt=self.clock()+3)
            result = e.command(c)
            e.db.close()
            e = Engine(f, self.clock)
            self.assertEqual(e.data["status"], "PAUSED")
            self.assertEqual(e.command(c), result)
            e.db.close()


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock(); self.e = Engine(clock=self.clock); self.g = Gateway(clock=self.clock)
        self.g.receive(self.e.snapshot()); self.session = self.g.acquire("operator-a"); self.sent = []
        self.g.dispatch = self.sent.append
    def order(self, cid="c1", action="START"):
        return dict(id=cid, action=action, sessionId=self.session["id"], expectedVersion=self.e.data["version"])
    def test_acceptance_distinct_from_execution(self):
        result = self.g.submit(self.order())
        self.assertEqual(result["status"], "ACCEPTED")
        self.assertEqual(self.e.data["status"], "IDLE")
        self.e.command(self.sent[0]); self.g.receive(self.e.snapshot())
        self.assertEqual(self.g.get_command("c1")["status"], "EXECUTED")
    def test_duplicate_does_not_dispatch_twice(self):
        self.g.submit(self.order()); self.g.submit(self.order())
        self.assertEqual(len(self.sent), 1)
    def test_unknown_blocks_other_commands_until_reconciled(self):
        self.g.submit(self.order()); self.clock.advance(6); self.g.receive(self.e.snapshot())
        self.assertEqual(self.g.get_command("c1")["status"], "UNKNOWN")
        with self.assertRaises(ValueError): self.g.submit(self.order("c2"))
        self.e.command(self.sent[0]); self.g.receive(self.e.snapshot())
        self.assertEqual(self.g.get_command("c1")["status"], "REJECTED")  # TTL vencido
    def test_second_operator_and_expired_session(self):
        with self.assertRaises(ValueError): self.g.acquire("operator-b")
        c = self.order(); c["sessionId"] = "invalid"
        with self.assertRaises(ValueError): self.g.submit(c)
        self.clock.advance(61)
        with self.assertRaises(ValueError): self.g.submit(self.order())
        self.assertEqual(self.g.acquire("operator-b")["clientId"], "operator-b")
    def test_stale_telemetry(self):
        self.clock.advance(6)
        self.assertFalse(self.g.snapshot()["fresh"])
        with self.assertRaises(ValueError): self.g.submit(self.order())
    def test_event_cursor_updates_no_duplicates(self):
        self.e.command(dict(id="a", action="DEMO_ACCESS", expectedVersion=0, expiresAt=self.clock()+3))
        self.g.receive(self.e.snapshot()); page = self.g.events()
        self.assertEqual(len(page["items"]), 1)
        self.g.receive(self.e.snapshot()); self.assertEqual(self.g.events(page["cursor"])["items"], [])
        event = self.e.data["events"][0]; event["status"] = "ACKNOWLEDGED"
        self.g.receive(self.e.snapshot()); updated = self.g.events(page["cursor"])
        self.assertEqual(updated["items"][0]["status"], "ACKNOWLEDGED")
        self.assertEqual(len(self.g.events()["items"]), 1)
    def test_journal_survives_gateway_restart(self):
        with tempfile.TemporaryDirectory() as path:
            f = os.path.join(path, "gateway.db")
            g = Gateway(f, self.clock); g.receive(self.e.snapshot()); s = g.acquire("a"); g.dispatch = self.sent.append
            c = self.order(); c["sessionId"] = s["id"]; g.submit(c); g.db.close()
            g = Gateway(f, self.clock); self.clock.advance(6)
            self.assertEqual(g.get_command("c1")["status"], "UNKNOWN")
            self.assertFalse(g.snapshot()["fresh"])
            g.db.close()


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.g = Gateway(); self.g.receive(Engine().snapshot()); self.g.dispatch = lambda _: None
        self.token = "test-token-12345678"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(self.g, self.token))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
    def tearDown(self): self.server.shutdown(); self.server.server_close(); self.thread.join()
    def request(self, path, body=None, token=True):
        headers = {"Content-Type": "application/json"}
        if token: headers["Authorization"] = "Bearer " + self.token
        req = Request(self.url + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        with urlopen(req, timeout=3) as response: return json.load(response)
    def test_unauthorized(self):
        with self.assertRaises(HTTPError) as caught: self.request("/v1/state", token=False)
        self.assertEqual(caught.exception.code, 401)
    def test_real_http_flow(self):
        state = self.request("/v1/state"); self.assertTrue(state["fresh"])
        session = self.request("/v1/control-session", {"clientId": "phone"})
        result = self.request("/v1/commands", {"id": "http1", "action": "START", "sessionId": session["id"], "expectedVersion": 0})
        self.assertEqual(result["status"], "ACCEPTED")
        self.assertEqual(self.request("/v1/commands/http1")["status"], "ACCEPTED")
    def test_invalid_cursor_and_non_object(self):
        with self.assertRaises(HTTPError): self.request("/v1/events?after=-1")
        with self.assertRaises(HTTPError): self.request("/v1/commands", [])

if __name__ == "__main__": unittest.main()
