package com.performance.orchestrator.domain;

/** Ciclo de vida de una ejecucion (seccion 6 del plan). */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    AGGREGATING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
