package com.performance.orchestrator.domain;

/** Estado de un pod/shard concreto dentro de una ejecucion. */
public enum NodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
