"""Dos procesos ROS 2 independientes con DDS. Mensajes JSON del TP, no Unitree."""
import argparse
import json
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, ReliabilityPolicy, DurabilityPolicy
from rclpy.executors import ExternalShutdownException
from std_msgs.msg import String
from backend.engine import Engine

QOS = QoSProfile(depth=10, reliability=ReliabilityPolicy.RELIABLE, durability=DurabilityPolicy.VOLATILE)
STATE_TOPIC = "/rondago/sim/state"
COMMAND_TOPIC = "/rondago/sim/commands"


class GatewayNode(Node):
    def __init__(self, gateway):
        super().__init__("rondago_gateway")
        self.gateway = gateway
        self.publisher = self.create_publisher(String, COMMAND_TOPIC, QOS)
        self.subscriber = self.create_subscription(String, STATE_TOPIC, self.receive, QOS)
        gateway.dispatch = self.send
        self.reconcile_timer = self.create_timer(1.0, gateway.reconcile)

    def send(self, packet):
        self.publisher.publish(String(data=json.dumps(packet)))

    def receive(self, msg):
        try:
            self.gateway.receive(json.loads(msg.data))
        except (ValueError, TypeError, KeyError):
            self.get_logger().warning("Telemetría inválida descartada")


class SimulatorNode(Node):
    def __init__(self, database):
        super().__init__("rondago_controller_sim")
        self.engine = Engine(database)
        self.publisher = self.create_publisher(String, STATE_TOPIC, QOS)
        self.subscriber = self.create_subscription(String, COMMAND_TOPIC, self.command, QOS)
        self.timer = self.create_timer(1.0, self.tick)
        self.get_logger().info("SIMULACION. Sin conexión ni movimiento de hardware real.")

    def command(self, msg):
        try:
            self.engine.command(json.loads(msg.data))
            self.publish()
        except (ValueError, TypeError, KeyError):
            self.get_logger().warning("Comando inválido descartado")

    def publish(self):
        self.publisher.publish(String(data=json.dumps(self.engine.snapshot())))

    def tick(self):
        self.engine.tick()
        self.publish()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--database", default="simulator.sqlite3")
    args = parser.parse_args()
    rclpy.init()
    node = SimulatorNode(args.database)
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
