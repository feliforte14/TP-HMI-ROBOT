"""Máquina de estados de demostración. Nunca controla hardware."""
import copy
import json
import sqlite3
import threading
import time
import uuid

ROUTE = ["Base", "Recepción", "Pasillo A", "Pasillo B", "Portón de carga"]
ACTIONS = {"START", "PAUSE", "RESUME", "CANCEL", "RETURN", "STOP", "ACK", "RESOLVE", "ANNOUNCE", "HOLD",
           "DEMO_PRESENCE", "DEMO_OBSTACLE", "DEMO_ACCESS", "DEMO_CLOSE_ACCESS", "DEMO_NOISE", "DEMO_QUIET", "DEMO_LOW_BATTERY", "DEMO_RECHARGE"}


class Engine:
    def __init__(self, database=":memory:", clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.db = sqlite3.connect(database, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS snapshot (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
        row = self.db.execute("SELECT body FROM snapshot WHERE id=1").fetchone()
        self.data = json.loads(row[0]) if row else {
            "source": "SIMULATION", "robotId": "GO2-SIM-01", "roundId": "", "version": 0,
            "status": "IDLE", "sector": "Base", "verified": 0, "total": 4, "battery": 95.0,
            "accessOpen": False, "noise": 45.0, "events": [], "commands": {}, "rounds": [],
            "capabilities": ["navigation", "speaker"], "step": 0, "noiseHighSince": None,
            "noiseLowSince": None, "speakerUntil": 0.0,
        }
        # Un reinicio del controlador no reanuda una ronda por sí solo.
        if self.data["status"] in ("PATROLLING", "RETURNING"):
            self.data["status"] = "PAUSED"
            self.data["version"] += 1
            self.event("RESTART", "Controlador reiniciado; ronda pausada")
        self.save()

    def save(self):
        for r in self.data["rounds"]:
            if r["id"] == self.data["roundId"]:
                r.update(status=self.data["status"], verified=self.data["verified"])
        self.db.execute("INSERT OR REPLACE INTO snapshot VALUES (1, ?)", (json.dumps(self.data),))
        self.db.commit()

    def event(self, kind, message):
        for e in self.data["events"]:
            if e["type"] == kind and e["roundId"] == self.data["roundId"] and e["status"] != "RESOLVED":
                return e
        e = {"id": str(uuid.uuid4()), "roundId": self.data["roundId"], "type": kind,
             "message": message, "sector": self.data["sector"], "status": "PENDING",
             "observedAt": self.clock(), "source": "SIMULATION"}
        self.data["events"].append(e)
        return e

    def unresolved(self, kind):
        return any(e["type"] == kind and e["status"] != "RESOLVED" and
                   e["roundId"] == self.data["roundId"] for e in self.data["events"])

    def finish(self, status):
        d = self.data
        d["status"] = status
        for r in d["rounds"]:
            if r["id"] == d["roundId"]:
                r.update(status=status, endedAt=self.clock(), verified=d["verified"])

    def snapshot(self):
        with self.lock:
            result = copy.deepcopy(self.data)
            result["observedAt"] = self.clock()
            return result

    def command(self, c):
        with self.lock:
            cid = c.get("id")
            if not isinstance(cid, str) or not 1 <= len(cid) <= 80:
                raise ValueError("Identificador inválido")
            if cid in self.data["commands"]:
                return copy.deepcopy(self.data["commands"][cid])
            result = {"id": cid, "action": c.get("action", ""), "eventId": c.get("eventId", ""),
                      "roundId": self.data["roundId"], "status": "REJECTED", "reason": "",
                      "updatedAt": self.clock(), "source": "SIMULATION"}
            try:
                if c.get("action") not in ACTIONS:
                    raise ValueError("Acción no permitida")
                if not isinstance(c.get("expiresAt"), (int, float)) or self.clock() > c["expiresAt"]:
                    raise ValueError("Orden vencida")
                if c.get("expectedVersion") != self.data["version"]:
                    raise ValueError("Estado cambió: actualizar antes de enviar")
                if c.get("eventId") and not any(e["id"] == c["eventId"] and e["status"] != "RESOLVED" for e in self.data["events"]):
                    raise ValueError("Alerta inexistente o resuelta")
                self.apply(c)
                if c.get("eventId") and c["action"] in ("HOLD", "RETURN"):
                    for event in self.data["events"]:
                        if event["id"] == c["eventId"]:
                            event["status"] = "ACKNOWLEDGED"
                self.data["version"] += 1
                result.update(status="EXECUTED", roundId=self.data["roundId"])
            except ValueError as exc:
                result["reason"] = str(exc)
            self.data["commands"][cid] = result
            self.save()  # Estado y resultado en una sola transacción durable.
            return copy.deepcopy(result)

    def apply(self, c):
        d, a = self.data, c["action"]
        active = d["status"] in ("PATROLLING", "PAUSED", "RETURNING")
        event = next((e for e in d["events"] if e["id"] == c.get("eventId")), None)
        if a == "START":
            if active or d["battery"] < 20 or d["sector"] != "Base":
                raise ValueError("Inicio requiere base, batería >=20% y ninguna ronda activa")
            if "navigation" not in d["capabilities"]:
                raise ValueError("Navegación no disponible")
            d.update(roundId=str(uuid.uuid4()), status="PATROLLING", verified=0, step=0)
            d["rounds"].append({"id": d["roundId"], "startedAt": self.clock(), "endedAt": None,
                                "status": "PATROLLING", "verified": 0, "source": "SIMULATION"})
        elif a in ("PAUSE", "HOLD", "STOP"):
            if not active:
                raise ValueError("No hay ronda activa")
            d["status"] = "PAUSED"
        elif a == "RESUME":
            if d["status"] != "PAUSED" or self.unresolved("OBSTACLE") or self.unresolved("PRESENCE") or d["battery"] < 20:
                raise ValueError("Reanudar requiere pausa, batería suficiente e incidentes resueltos")
            d["status"] = "PATROLLING"
        elif a == "CANCEL":
            if not active:
                raise ValueError("No hay ronda activa")
            self.finish("CANCELLED")
        elif a == "RETURN":
            if "navigation" not in d["capabilities"] or d["sector"] == "Base":
                raise ValueError("Regreso no disponible")
            d.update(status="RETURNING", step=0)
        elif a in ("ACK", "RESOLVE", "ANNOUNCE"):
            if not event or event["status"] == "RESOLVED":
                raise ValueError("Alerta inexistente o resuelta")
            if a == "RESOLVE":
                if not isinstance(c.get("note"), str) or len(c["note"].strip()) < 3:
                    raise ValueError("Indicar motivo de resolución")
                if event["type"] == "ACCESS" and d["accessOpen"]:
                    raise ValueError("Cerrar primero el acceso simulado")
                if event["type"] == "NOISE" and d["noise"] >= 65:
                    raise ValueError("Reducir primero el ruido simulado")
                if event["type"] == "BATTERY" and d["battery"] < 20:
                    raise ValueError("Recargar primero en base")
                event.update(status="RESOLVED", note=c["note"].strip()[:300])
            else:
                if a == "ANNOUNCE":
                    if event["type"] != "PRESENCE" or "speaker" not in d["capabilities"]:
                        raise ValueError("Aviso no disponible")
                    d["speakerUntil"] = self.clock() + 3
                event["status"] = "ACKNOWLEDGED"
        elif a.startswith("DEMO_"):
            if a == "DEMO_PRESENCE":
                self.event("PRESENCE", "Presencia sintética: revisar sector")
                if active:
                    d["status"] = "PAUSED"
            elif a == "DEMO_OBSTACLE":
                self.event("OBSTACLE", "Obstáculo sintético en recorrido")
                if active:
                    d["status"] = "PAUSED"
            elif a == "DEMO_ACCESS":
                d["accessOpen"] = True
                self.event("ACCESS", "Portón de carga abierto (simulado)")
            elif a == "DEMO_CLOSE_ACCESS":
                d["accessOpen"] = False
            elif a == "DEMO_NOISE":
                d["noise"] = 78.0
            elif a == "DEMO_QUIET":
                d["noise"] = 45.0
            elif a == "DEMO_LOW_BATTERY":
                d["battery"] = 15.0
                self.event("BATTERY", "Batería baja simulada: solicitar regreso")
                if d["status"] == "PATROLLING":
                    d["status"] = "PAUSED"
            elif a == "DEMO_RECHARGE":
                if d["sector"] != "Base" or active:
                    raise ValueError("Recargar solo en base sin ronda activa")
                d["battery"] = 95.0

    def tick(self):
        with self.lock:
            d, now = self.data, self.clock()
            changed = False
            if now >= d["speakerUntil"] and d["noise"] > 70:
                d["noiseLowSince"] = None
                if d["noiseHighSince"] is None:
                    d["noiseHighSince"] = now
                if now - d["noiseHighSince"] >= 3 and not self.unresolved("NOISE"):
                    self.event("NOISE", "Ruido sintético >70 dB(A) durante 3 s")
                    changed = True
            elif d["noise"] < 65:
                d["noiseHighSince"] = None
                if d["noiseLowSince"] is None:
                    d["noiseLowSince"] = now
                if now - d["noiseLowSince"] >= 5:
                    for e in d["events"]:
                        if e["type"] == "NOISE" and e["status"] != "RESOLVED":
                            e.update(status="RESOLVED", note="Ruido bajo 65 durante 5 s")
                            changed = True
            else:
                d["noiseHighSince"] = None
                d["noiseLowSince"] = None
            if d["status"] in ("PATROLLING", "RETURNING"):
                d["step"] += 1
                if d["step"] >= 5:
                    d["step"] = 0
                    if d["status"] == "RETURNING" or d["verified"] == 4:
                        d["sector"] = "Base"
                        self.finish("RETURNED" if d["status"] == "RETURNING" else "COMPLETED")
                    else:
                        d["verified"] += 1
                        d["sector"] = ROUTE[d["verified"]]
                        d["battery"] = max(0, d["battery"] - 1)
                    changed = True
            if changed:
                d["version"] += 1
            self.save()
