#!/usr/bin/env python3
"""Demo de telemetría SIMULADA. No controla hardware.

Ubuntu 24.04 + ROS 2 Jazzy:
  source /opt/ros/jazzy/setup.bash
  export RMW_IMPLEMENTATION=rmw_fastrtps_cpp
  python3 simulator/rondago_sim.py

En otra terminal Ubuntu con el mismo entorno:
  ros2 topic echo /rondago/sim/state

Publica JSON en std_msgs/msg/String a 1 Hz (contrato provisional).
Una ronda automática: base -> recepción -> pasillo A -> pasillo B ->
portón de carga -> base. Cada etapa dura cinco publicaciones.
Al finalizar conserva el estado final; Ctrl+C detiene el proceso.
No implementa comandos, sensores, alertas persistentes ni conexión Android.
"""
import json
from datetime import datetime, timezone
from uuid import uuid4

import rclpy
from rclpy.node import Node
from rclpy.executors import ExternalShutdownException
from std_msgs.msg import String


def sample(tick, round_id):
    """Estado determinista de demostración; tick empieza en cero."""
    if tick < 0:
        raise ValueError("tick debe ser no negativo")
    route = ("base", "recepcion", "pasillo_a", "pasillo_b", "porton_carga", "base")
    stage = min(tick // 5, 5)
    return {
        "schemaVersion": 1,
        "source": "SIMULATION",
        "robotId": "GO2-SIM-01",
        "roundId": round_id,
        "sequence": tick,
        "state": "IDLE" if stage == 0 else ("COMPLETED" if stage == 5 else "PATROLLING"),
        "sector": route[stage],
        "verifiedCheckpoints": min(stage, 4),
        "totalCheckpoints": 4,
        "batteryPercent": 95 - min(tick, 25) * 0.2,
    }


class RondaGoSimulator(Node):
    def __init__(self):
        super().__init__("rondago_simulator")
        self.publisher = self.create_publisher(String, "/rondago/sim/state", 10)
        self.tick = 0
        self.round_id = str(uuid4())
        self.timer = self.create_timer(1.0, self.publish_state)
        self.get_logger().info("SIMULACION: sin hardware; ronda automatica de demostracion")

    def publish_state(self):
        payload = sample(self.tick, self.round_id)
        payload["observedAt"] = datetime.now(timezone.utc).isoformat()
        message = String()
        message.data = json.dumps(payload, ensure_ascii=False)
        self.publisher.publish(message)
        self.get_logger().info(message.data)
        self.tick += 1


def main():
    rclpy.init()
    node = RondaGoSimulator()
    try:
        rclpy.spin(node)
    except (KeyboardInterrupt, ExternalShutdownException):
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
