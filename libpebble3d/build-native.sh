#!/bin/sh
# Runs inside the builder container (mounts: /dist jars, /work this dir, /out).
set -e

CP=$(find /dist/libs -name '*.jar' | sort | tr '\n' ':')

# Trace reachability metadata on the JVM against live system + session buses;
# hardware-only paths are covered by the hand-written configs in /work.
dbus-uuidgen > /etc/machine-id
mkdir -p /run/dbus
dbus-daemon --system --fork
(/usr/libexec/bluetooth/bluetoothd --nodetach 2>/dev/null \
    || /usr/sbin/bluetoothd --nodetach) &
# Session bus: exercises the org.rockwork export + notification monitor paths.
export DBUS_SESSION_BUS_ADDRESS=$(dbus-daemon --session --fork --print-address)
sleep 1

AGENT_DIR=/tmp/agent-config
mkdir -p "$AGENT_DIR"
echo "tracing (45s)..."
# preferIPv4Stack: docker containers have no IPv6 route; the JVM tries IPv6 first
# and the HTTPS trace request would hang instead of exercising TLS.
timeout 45 env LIBPEBBLE3D_AUTOCONNECT=1 LIBPEBBLE3D_TRACE_HTTP=1 \
    java -Djava.net.preferIPv4Stack=true \
    -agentlib:native-image-agent=config-output-dir="$AGENT_DIR" \
    -cp "$CP" io.rebble.libpebblecommon.Daemon > /tmp/trace-run.log 2>&1 || true
if [ ! -s "$AGENT_DIR/reachability-metadata.json" ]; then
    echo "error: tracing produced no metadata; trace log tail:" >&2
    tail -20 /tmp/trace-run.log >&2
    exit 1
fi

# Docker Desktop caps this VM near 7.75GB and peak RSS sits right at it. The default
# all-cores parallelism holds one method graph per thread and oversubscribes the CPU, so the
# builder GC-thrashes and trivial methods blow past native-image's 300s per-method wall-clock
# limit. Capping worker threads cuts peak memory and contention, trading build time for reliability.
native-image \
  -H:ConfigurationFileDirectories="$AGENT_DIR" \
  -H:ReflectionConfigurationFiles=/work/reflect-config.json \
  -H:ResourceConfigurationFiles=/work/resource-config.json \
  -H:JNIConfigurationFiles=/work/jni-config.json \
  -H:DynamicProxyConfigurationFiles=/work/proxy-config.json \
  -H:NumberOfThreads="${NI_THREADS:-4}" \
  -J-XX:MaxRAMPercentage=75 \
  -cp "$CP" \
  -o /out/libpebble3d \
  --no-fallback \
  --enable-url-protocols=http,https \
  -H:+ReportExceptionStackTraces \
  "$@" \
  io.rebble.libpebblecommon.Daemon

file /out/libpebble3d
