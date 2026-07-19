#!/usr/bin/env bash
# =====================================================================
#  Entrypoint del worker JMeter (shard independiente, sin RMI).
#  Reparte los hilos totales entre los pods usando el indice del Job
#  "Indexed" de Kubernetes (JOB_COMPLETION_INDEX).
# =====================================================================
set -euo pipefail

IDX="${JOB_COMPLETION_INDEX:-0}"
POD_COUNT="${POD_COUNT:-1}"
THREADS_TOTAL="${THREADS_TOTAL:-10}"
RAMP_UP="${RAMP_UP:-60}"
DURATION="${DURATION:-300}"
TARGET_HOST="${TARGET_HOST:-}"
TARGET_PROTOCOL="${TARGET_PROTOCOL:-https}"
EXTRA_PROPS="${EXTRA_PROPS:-}"
SCRIPT_PATH="${SCRIPT_PATH:?SCRIPT_PATH es obligatorio}"
RESULTS_DIR="${RESULTS_DIR:?RESULTS_DIR es obligatorio}"

# Reparto de hilos: base + 1 para los primeros (THREADS_TOTAL % POD_COUNT) pods.
BASE=$(( THREADS_TOTAL / POD_COUNT ))
REMAINDER=$(( THREADS_TOTAL % POD_COUNT ))
THREADS=$BASE
if [ "$IDX" -lt "$REMAINDER" ]; then
  THREADS=$(( BASE + 1 ))
fi
if [ "$THREADS" -lt 1 ]; then
  THREADS=1
fi

mkdir -p "$RESULTS_DIR"
JTL="$RESULTS_DIR/node-$IDX.jtl"
LOG="$RESULTS_DIR/node-$IDX.log"

echo "[worker] pod=$IDX/$POD_COUNT threads=$THREADS rampUp=$RAMP_UP duration=$DURATION target=$TARGET_HOST"

# shellcheck disable=SC2086
exec jmeter -n \
  -t "$SCRIPT_PATH" \
  -l "$JTL" \
  -j "$LOG" \
  -Jthreads="$THREADS" \
  -JrampUp="$RAMP_UP" \
  -Jduration="$DURATION" \
  -Jpod.index="$IDX" \
  -Jpod.count="$POD_COUNT" \
  -JtargetHost="$TARGET_HOST" \
  -JtargetProtocol="$TARGET_PROTOCOL" \
  $EXTRA_PROPS
