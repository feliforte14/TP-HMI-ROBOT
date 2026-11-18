"""API HTTP local de demo. Persistencia SQLite, sesiones y conciliación ROS 2."""
import argparse
import copy
import hmac
import json
import math
import os
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from backend.engine import ACTIONS, Engine


class Gateway:
    def __init__(self, database=":memory:", clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.state = None
        self.received = 0.0
        self.session = None
        self.dispatch = None
        self.transport = "ROS 2 / DDS"
        self.db = sqlite3.connect(database, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS events(cursor INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE, body TEXT NOT NULL)")
        self.db.commit()

    def get_command(self, cid):
        with self.lock:
            row = self.db.execute("SELECT body FROM commands WHERE id=?", (cid,)).fetchone()
            if not row:
                return None
            c = json.loads(row[0])
            if c["status"] == "ACCEPTED" and self.clock() - c["updatedAt"] > 5:
                c.update(status="UNKNOWN", reason="Sin confirmación; conciliando. No reenviar con otro ID.")
            return c

    def put_command(self, c):
        self.db.execute("INSERT OR REPLACE INTO commands VALUES (?, ?)", (c["id"], json.dumps(c)))
        self.db.commit()

    def receive(self, data):
        with self.lock:
            if data.get("source") != "SIMULATION" or not isinstance(data.get("version"), int):
                return
            self.state = copy.deepcopy(data)
            self.received = self.clock()
            for c in data.get("commands", {}).values():
                old = self.get_command(c["id"])
                if old:
                    old.update(c)
                    self.put_command(old)
            for e in data.get("events", []):
                # Mantener cursor original para eventos sin cambios; cada cambio debe sincronizarse.
                row = self.db.execute("SELECT body FROM events WHERE id=?", (e["id"],)).fetchone()
                body = json.dumps(e, sort_keys=True)
                if not row or row[0] != body:
                    self.db.execute("DELETE FROM events WHERE id=?", (e["id"],))
                    self.db.execute("INSERT INTO events(id,body) VALUES (?,?)", (e["id"], body))
            self.db.commit()

    def events(self, after=0):
        with self.lock:
            rows = self.db.execute("SELECT cursor,body FROM events WHERE cursor>? ORDER BY cursor LIMIT 200", (after,)).fetchall()
            return {"items": [json.loads(row[1]) for row in rows], "cursor": rows[-1][0] if rows else after}

    def snapshot(self):
        with self.lock:
            d = copy.deepcopy(self.state) if self.state else {}
            d["hasTelemetry"] = self.state is not None
            d["transport"] = self.transport
            d["fresh"] = self.state is not None and self.clock() - self.received <= 5
            d["receivedAt"] = self.received
            d["ageSeconds"] = max(0, self.clock() - self.received) if self.state else None
            return d

    def acquire(self, client):
        if not isinstance(client, str) or not 1 <= len(client) <= 80:
            raise ValueError("clientId inválido")
        with self.lock:
            if self.session and self.session["expiresAt"] > self.clock() and self.session["clientId"] != client:
                raise ValueError("Otro operador tiene el control; esperar a que venza su sesión")
            if not self.session or self.session["clientId"] != client or self.session["expiresAt"] <= self.clock():
                self.session = {"id": secrets.token_urlsafe(24), "clientId": client}
            self.session["expiresAt"] = self.clock() + 60
            return copy.deepcopy(self.session)

    def reconcile(self):
        """Mismo ID y TTL original: consultar/reentregar sin una segunda ejecución."""
        with self.lock:
            if not self.dispatch or not self.snapshot()["fresh"]:
                return
            for row in self.db.execute("SELECT body FROM commands").fetchall():
                c = json.loads(row[0])
                if c["status"] in ("ACCEPTED", "UNKNOWN") and self.clock() - c["updatedAt"] > 1:
                    self.dispatch(c)

    def submit(self, c):
        with self.lock:
            cid = c.get("id")
            if not isinstance(cid, str) or not 1 <= len(cid) <= 80:
                raise ValueError("ID de comando inválido")
            if not self.session or c.get("sessionId") != self.session["id"] or self.session["expiresAt"] <= self.clock():
                raise ValueError("Sesión sin autoridad de control")
            existing = self.get_command(cid)
            if existing:
                if existing["action"] != c.get("action") or existing.get("eventId", "") != c.get("eventId", ""):
                    raise ValueError("ID reutilizado con otra orden")
                return existing
            if c.get("action") not in ACTIONS:
                raise ValueError("Acción no permitida")
            if not self.snapshot()["fresh"]:
                raise ValueError("Telemetría ausente o vencida")
            if c.get("expectedVersion") != self.state["version"]:
                raise ValueError("Versión desactualizada")
            pending = self.db.execute("SELECT id FROM commands").fetchall()
            if any(self.get_command(r[0])["status"] in ("ACCEPTED", "UNKNOWN") for r in pending):
                raise ValueError("Hay una orden pendiente de conciliación")
            if not self.dispatch:
                raise ValueError("Transporte no disponible")
            packet = {"id": cid, "action": c["action"], "eventId": str(c.get("eventId", ""))[:80],
                      "note": str(c.get("note", ""))[:300], "expectedVersion": c["expectedVersion"],
                      "expiresAt": self.clock() + 3}
            result = {**packet, "status": "ACCEPTED", "reason": "", "updatedAt": self.clock(), "source": "SIMULATION"}
            self.put_command(result)
            try:
                self.dispatch(packet)
            except Exception:
                result.update(status="UNKNOWN", reason="Fallo de transporte; consultar el mismo ID")
                self.put_command(result)
            return result


def handler_for(gateway, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # No registrar token ni cuerpos de solicitudes.

        def respond(self, code, value):
            raw = json.dumps(value, allow_nan=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self):
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                self.respond(401, {"error": "Token inválido"})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            p = urlparse(self.path)
            if p.path == "/v1/state":
                self.respond(200, gateway.snapshot())
            elif p.path == "/v1/events":
                try:
                    after = int(parse_qs(p.query).get("after", ["0"])[0])
                    if after < 0:
                        raise ValueError()
                    self.respond(200, gateway.events(after))
                except ValueError:
                    self.respond(400, {"error": "Cursor inválido"})
            elif p.path.startswith("/v1/commands/"):
                c = gateway.get_command(p.path.rsplit("/", 1)[-1])
                self.respond(200 if c else 404, c or {"error": "Comando desconocido"})
            else:
                self.respond(404, {"error": "Ruta inexistente"})

        def do_POST(self):
            if not self.authorized():
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size < 1 or size > 8192:
                    raise ValueError("Cuerpo inválido o demasiado grande")
                body = json.loads(self.rfile.read(size), parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Número inválido")))
                if not isinstance(body, dict):
                    raise ValueError("Se espera un objeto JSON")
                if self.path == "/v1/control-session":
                    self.respond(200, gateway.acquire(body.get("clientId")))
                elif self.path == "/v1/commands":
                    self.respond(202, gateway.submit(body))
                else:
                    self.respond(404, {"error": "Ruta inexistente"})
            except (ValueError, TypeError) as exc:
                self.respond(400, {"error": str(exc)})
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--demo", action="store_true", help="Motor local SIN ROS/DDS; solo prueba HTTP")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--database", default="gateway.sqlite3")
    args = parser.parse_args()
    token = os.environ.get("RONDAGO_TOKEN")
    if not token or len(token) < 16:
        parser.error("Definir RONDAGO_TOKEN con al menos 16 caracteres")
    g = Gateway(args.database)
    stop = threading.Event()
    if args.demo:
        g.transport = "HTTP LOCAL · SIN DDS"
        import queue
        engine = Engine(args.database + ".engine")
        packets = queue.Queue()
        g.dispatch = packets.put
        def run():
            while not stop.wait(0.25):
                while not packets.empty():
                    engine.command(packets.get())
                # El reloj de progreso es 1 Hz, independiente del HTTP.
                if int(time.monotonic()) != getattr(run, "last", None):
                    run.last = int(time.monotonic())
                    engine.tick()
                    g.reconcile()
                g.receive(engine.snapshot())
        thread = threading.Thread(target=run, daemon=True)
    else:
        from backend.ros_nodes import GatewayNode
        import rclpy
        rclpy.init()
        node = GatewayNode(g)
        thread = threading.Thread(target=lambda: rclpy.spin(node), daemon=True)
    thread.start()
    server = ThreadingHTTPServer((args.host, args.port), handler_for(g, token))
    print(f"RondaGo SIMULATION: http://{args.host}:{args.port} · {'DEMO HTTP SIN DDS' if args.demo else 'ROS 2 / DDS'}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        if not args.demo:
            rclpy.shutdown()
        thread.join(timeout=3)


if __name__ == "__main__":
    main()
