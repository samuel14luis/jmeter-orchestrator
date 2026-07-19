package com.performance.orchestrator.domain;

/**
 * Estado de un pod/shard concreto dentro de una ejecucion.
 *
 * Ciclo en el motor worker-pool (Fase 7):
 *   PENDING -> CLAIMED (una replica lo reclamo, espera el start-gate)
 *           -> RUNNING (ejecutando jmeter -n)
 *           -> SUCCEEDED | FAILED | CANCELLED
 * En el motor de K8s Jobs se usan PENDING/RUNNING/SUCCEEDED/FAILED.
 */
public enum NodeStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
