#!/usr/bin/env bash
# Parallel airicraft sim-server fleet — N independent server processes, each
# with its own run dir (sim/run/sim-inst-i), MC port and control port.
# Launches the same classpath Gradle's runServer uses, without Gradle.
#
#   sim_fleet.sh start [N]   # N instances on ctrl 8777+i, mc 25575+i
#   sim_fleet.sh stop        # kill the fleet
#   sim_fleet.sh status      # per-instance /v1/status probe
#
# Env overrides: XMX (per-instance heap, default 3G), CTRL0, MC0, JAVA_HOME.
set -u
SIM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARGF="$SIM_DIR/build/loom-cache/argFiles/runServer"
CFG="$SIM_DIR/.gradle/loom-cache/launch.cfg"
JAVA="${JAVA_HOME:-$HOME/jdks/jdk21}/bin/java"
CTRL0="${CTRL0:-8777}"
MC0="${MC0:-25575}"
XMX="${XMX:-3G}"
CMD="${1:-start}"
N="${2:-6}"

if [ ! -f "$ARGF" ] || [ ! -f "$CFG" ]; then
  echo "missing loom launch files — run './gradlew :sim:build' once first" >&2
  exit 1
fi

case "$CMD" in
stop)
  pkill -f "airicraft.sim.port=" && echo "fleet stopped" || echo "nothing running"
  exit 0
  ;;
status)
  i=0
  while curl -sf "http://127.0.0.1:$((CTRL0+i))/v1/status" -o /tmp/fleet-st-$i.json 2>/dev/null; do
    echo "inst $i (ctrl $((CTRL0+i))): $(head -c 200 /tmp/fleet-st-$i.json)"
    i=$((i+1))
  done
  [ "$i" -eq 0 ] && echo "no instances answering on ctrl $CTRL0+"
  exit 0
  ;;
esac

for i in $(seq 0 $((N-1))); do
  cport=$((CTRL0+i)); mport=$((MC0+i))
  rd="$SIM_DIR/run/sim-inst-$i"
  mkdir -p "$rd"
  echo "eula=true" > "$rd/eula.txt"
  sed "s/^server-port=.*/server-port=$mport/" \
      "$SIM_DIR/run/sim-server/server.properties" > "$rd/server.properties"
  echo "[fleet] inst $i: ctrl=$cport mc=$mport dir=$rd"
  (cd "$rd" && setsid "$JAVA" \
    -Dfabric.dli.config="$CFG" -Dfabric.dli.env=server \
    -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer \
    @"$ARGF" -Xmx"$XMX" -Dfile.encoding=UTF-8 \
    -Dairicraft.sim.port="$cport" \
    -Duser.country -Duser.language=en -Duser.variant \
    net.fabricmc.devlaunchinjector.Main nogui --nogui \
    > "$rd/server.log" 2>&1 < /dev/null &)
done

for i in $(seq 0 $((N-1))); do
  cport=$((CTRL0+i))
  ok=""
  for _t in $(seq 1 90); do
    curl -sf "http://127.0.0.1:$cport/v1/status" >/dev/null 2>&1 && { ok=1; break; }
    sleep 2
  done
  echo "inst $i ctrl=$cport: ${ok:+up}${ok:-TIMEOUT (see run/sim-inst-$i/server.log)}"
done
