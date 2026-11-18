"""Prueba real HTTP -> gateway ROS -> DDS -> controlador -> DDS -> HTTP.
Ejecutar con ROS 2 Jazzy cargado. No sustituye pruebas en hardware.
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import uuid
from urllib.request import Request, urlopen


def main():
    token = "integration-" + uuid.uuid4().hex
    env = dict(os.environ, RONDAGO_TOKEN=token, RMW_IMPLEMENTATION="rmw_fastrtps_cpp", ROS_DOMAIN_ID="77", ROS_LOCALHOST_ONLY="1")
    port = 18080
    def request(path, body=None):
        req = Request(f"http://127.0.0.1:{port}/v1/{path}", headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}, data=json.dumps(body).encode() if body else None)
        with urlopen(req, timeout=3) as r: return json.load(r)
    def wait(predicate, seconds=35):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = predicate()
                if value: return value
            except (OSError, ValueError): pass
            time.sleep(0.3)
        raise AssertionError("Tiempo agotado en prueba ROS/DDS")
    with tempfile.TemporaryDirectory() as tmp:
        simulator = subprocess.Popen([sys.executable, "-m", "backend.ros_nodes", "--database", tmp+"/sim.db"], env=env)
        gateway = subprocess.Popen([sys.executable, "-m", "backend.gateway", "--port", str(port), "--database", tmp+"/gw.db"], env=env)
        try:
            wait(lambda: request("state").get("fresh"))
            session = request("control-session", {"clientId": "integration"})
            def command(action, **fields):
                state = request("state")
                c = dict(id=str(uuid.uuid4()), action=action, expectedVersion=state["version"], sessionId=session["id"], **fields)
                result = request("commands", c)
                assert result["status"] == "ACCEPTED", result
                final = wait(lambda: (lambda r: r if r["status"] in ("EXECUTED", "REJECTED") else None)(request("commands/"+c["id"])))
                assert final["status"] == "EXECUTED", final
                return c
            c = command("START")
            # Reentrega con mismo ID no inicia otra ronda.
            assert request("commands", c)["status"] == "EXECUTED"
            wait(lambda: request("state").get("verified", 0) >= 1)
            command("DEMO_PRESENCE")
            state = request("state"); assert state["status"] == "PAUSED"
            event = next(e for e in state["events"] if e["type"] == "PRESENCE")
            command("ANNOUNCE", eventId=event["id"])
            command("RESOLVE", eventId=event["id"], note="Sector revisado en prueba automática")
            command("RESUME")
            command("DEMO_LOW_BATTERY")
            command("RETURN")
            wait(lambda: request("state").get("status") == "RETURNED")
            assert len(request("events")["items"]) >= 2
            simulator.terminate(); simulator.wait(timeout=10)
            wait(lambda: not request("state").get("fresh"), seconds=12)
            print("PASS: DDS real, inicio, idempotencia, presencia, resolución, batería, regreso y telemetría vencida", flush=True)
        finally:
            for p in (simulator, gateway):
                if p.poll() is None: p.terminate()
                try: p.wait(timeout=10)
                except subprocess.TimeoutExpired: p.kill(); p.wait()


if __name__ == "__main__": main()
