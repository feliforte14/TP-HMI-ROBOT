#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source /opt/ros/jazzy/setup.bash
export RMW_IMPLEMENTATION=rmw_fastrtps_cpp
export ROS_DOMAIN_ID="${ROS_DOMAIN_ID:-42}"
: "${RONDAGO_TOKEN:?Definí RONDAGO_TOKEN con al menos 16 caracteres}"
python3 -m backend.ros_nodes &
simulator_pid=$!
trap 'kill "$simulator_pid" 2>/dev/null || true' EXIT
python3 -m backend.gateway --host "${RONDAGO_HOST:-127.0.0.1}"
