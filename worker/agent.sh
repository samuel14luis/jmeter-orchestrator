#!/usr/bin/env bash
# =====================================================================
#  Agente worker del motor worker-pool por pull (Fase 7 del plan).
#  Bucle: claim -> descarga script -> espera el start-gate -> jmeter -n
#         -> heartbeat mientras corre -> sube jtl.gz + log -> vuelve a claim.
#  No usa RMI ni el API de Kubernetes. Toda la coordinacion es HTTP contra
#  el orquestador (/internal/*), autenticada con un token de servicio.
# =====================================================================
set -uo pipefail   # NO usamos -e: un fallo transitorio no debe tumbar la replica

ORCHESTRATOR_URL="${ORCHESTRATOR_URL:?ORCHESTRATOR_URL es obligatorio}"
WORKER_TOKEN="${WORKER_TOKEN:?WORKER_TOKEN es obligatorio}"
WORKER_ID="${WORKER_ID:-$(hostname)}"
WORKER_VERSION="${WORKER_VERSION:-5.6.3}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"        # s entre claims cuando no hay trabajo
HEARTBEAT_INTERVAL="${HEARTBEAT_INTERVAL:-10}"  # s entre latidos
WORK_DIR="${WORK_DIR:-/tmp/worker}"

AUTH=(-H "X-Worker-Token: ${WORKER_TOKEN}")
JSON=(-H "Content-Type: application/json")

log() { echo "[agent $(date -u +%H:%M:%S) ${WORKER_ID}] $*"; }

# --- descarga el .jmx de una version por la API interna ---
download_script() {
  local version_id="$1" dest="$2"
  curl -fsS "${AUTH[@]}" "${ORCHESTRATOR_URL}/internal/script-versions/${version_id}/content" -o "$dest"
}

# --- heartbeat; imprime la respuesta JSON {startAt, cancelled} en stdout ---
heartbeat() {
  local exec_id="$1" idx="$2" phase="$3"
  curl -fsS "${AUTH[@]}" "${JSON[@]}" \
    "${ORCHESTRATOR_URL}/internal/shards/${exec_id}/${idx}/heartbeat" \
    -d "{\"workerId\":\"${WORKER_ID}\",\"phase\":\"${phase}\"}" 2>/dev/null
}

# --- espera hasta el instante ISO-8601 indicado (o arranca ya si es pasado) ---
wait_until() {
  local iso="$1"
  [ -z "$iso" ] || [ "$iso" = "null" ] && return 0
  local target now diff
  target=$(date -d "$iso" +%s 2>/dev/null) || return 0
  now=$(date -u +%s)
  diff=$(( target - now ))
  if [ "$diff" -gt 0 ]; then
    log "start-gate en ${diff}s (${iso})"
    sleep "$diff"
  fi
}

# --- ejecuta un shard de principio a fin ---
run_shard() {
  local assignment="$1"
  local exec_id idx count vid threads ramp dur host proto extra start_at
  exec_id=$(echo "$assignment" | jq -r '.executionId')
  idx=$(echo "$assignment" | jq -r '.shardIndex')
  count=$(echo "$assignment" | jq -r '.shardCount')
  vid=$(echo "$assignment" | jq -r '.scriptVersionId')
  threads=$(echo "$assignment" | jq -r '.threads')
  ramp=$(echo "$assignment" | jq -r '.rampUp')
  dur=$(echo "$assignment" | jq -r '.duration')
  host=$(echo "$assignment" | jq -r '.targetHost // ""')
  proto=$(echo "$assignment" | jq -r '.targetProtocol // "https"')
  extra=$(echo "$assignment" | jq -r '.extraProps // ""')
  start_at=$(echo "$assignment" | jq -r '.startAt // ""')

  local dir="${WORK_DIR}/exec-${exec_id}-${idx}"
  mkdir -p "$dir"
  local script="${dir}/script.jmx" jtl="${dir}/node-${idx}.jtl" jlog="${dir}/node-${idx}.log"

  log "shard reclamado exec=${exec_id} idx=${idx}/${count} threads=${threads} target=${host}"

  if ! download_script "$vid" "$script"; then
    log "ERROR: no se pudo descargar el script v${vid}; abandono el shard"
    return 1
  fi

  # --- start-gate: si aun no hay startAt, latir (WAITING) hasta conocerlo ---
  while [ -z "$start_at" ] || [ "$start_at" = "null" ]; do
    local hb; hb=$(heartbeat "$exec_id" "$idx" "WAITING")
    if [ -z "$hb" ]; then sleep 2; continue; fi
    if [ "$(echo "$hb" | jq -r '.cancelled')" = "true" ]; then
      log "cancelada antes de arrancar; abandono"
      return 0
    fi
    start_at=$(echo "$hb" | jq -r '.startAt // ""')
    [ -z "$start_at" ] || [ "$start_at" = "null" ] && sleep 2
  done
  wait_until "$start_at"

  # --- lanzar jmeter -n en segundo plano ---
  log "arrancando jmeter -n (threads=${threads})"
  # shellcheck disable=SC2086
  jmeter -n -t "$script" -l "$jtl" -j "$jlog" \
    -Jthreads="$threads" -JrampUp="$ramp" -Jduration="$dur" \
    -Jpod.index="$idx" -Jpod.count="$count" \
    -JtargetHost="$host" -JtargetProtocol="$proto" \
    $extra &
  local jmeter_pid=$!

  # --- heartbeat (RUNNING) mientras corre; abortar si el orquestador cancela ---
  local failed=false
  heartbeat "$exec_id" "$idx" "RUNNING" >/dev/null
  while kill -0 "$jmeter_pid" 2>/dev/null; do
    sleep "$HEARTBEAT_INTERVAL"
    local hb; hb=$(heartbeat "$exec_id" "$idx" "RUNNING")
    if [ -n "$hb" ] && [ "$(echo "$hb" | jq -r '.cancelled')" = "true" ]; then
      log "cancelacion recibida; matando jmeter"
      kill "$jmeter_pid" 2>/dev/null
      failed=true
      break
    fi
  done
  wait "$jmeter_pid" 2>/dev/null
  local rc=$?
  if [ "$failed" != "true" ] && [ "$rc" -ne 0 ]; then
    log "jmeter termino con codigo ${rc}"
    failed=true
  fi

  # --- subir resultados (jtl.gz + log) ---
  [ -f "$jtl" ] || : > "$jtl"
  gzip -c "$jtl" > "${jtl}.gz"
  log "subiendo resultados (failed=${failed})"
  curl -fsS "${AUTH[@]}" -H "X-Worker-Id: ${WORKER_ID}" \
    -F "jtl=@${jtl}.gz" \
    -F "log=@${jlog}" \
    -F "failed=${failed}" \
    "${ORCHESTRATOR_URL}/internal/shards/${exec_id}/${idx}/results" >/dev/null \
    && log "resultados entregados" \
    || log "ERROR subiendo resultados"

  rm -rf "$dir"
}

log "agente iniciado; orquestador=${ORCHESTRATOR_URL} version=${WORKER_VERSION}"
while true; do
  resp=$(curl -sS -w $'\n%{http_code}' "${AUTH[@]}" "${JSON[@]}" \
    "${ORCHESTRATOR_URL}/internal/shards/claim" \
    -d "{\"workerId\":\"${WORKER_ID}\",\"workerVersion\":\"${WORKER_VERSION}\"}" 2>/dev/null)
  code=$(echo "$resp" | tail -n1)
  body=$(echo "$resp" | sed '$d')

  case "$code" in
    200) run_shard "$body" ;;
    204) sleep "$POLL_INTERVAL" ;;   # no hay trabajo
    401) log "ERROR: token de worker rechazado (401); revisar WORKER_TOKEN"; sleep "$POLL_INTERVAL" ;;
    "")  log "orquestador inalcanzable; reintento en ${POLL_INTERVAL}s"; sleep "$POLL_INTERVAL" ;;
    *)   log "claim devolvio HTTP ${code}; reintento"; sleep "$POLL_INTERVAL" ;;
  esac
done
