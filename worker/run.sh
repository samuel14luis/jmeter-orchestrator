#!/usr/bin/env bash
# Dispatcher del worker: elige el modo segun el entorno.
#  · worker-pool por pull (Fase 7): si ORCHESTRATOR_URL esta definido -> agent.sh
#  · K8s Jobs (legado): si no, el entrypoint clasico basado en JOB_COMPLETION_INDEX
set -uo pipefail
if [ -n "${ORCHESTRATOR_URL:-}" ]; then
  exec /agent.sh
else
  exec /entrypoint.sh
fi
