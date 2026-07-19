package com.performance.orchestrator.worker;

import java.time.Instant;

/**
 * DTOs del protocolo pull entre las replicas worker y el orquestador (Fase 7).
 * Todo el trafico va por la API interna {@code /internal/*}, autenticada con un
 * token de servicio (nunca expuesta fuera de la red del cluster).
 */
public final class WorkerDtos {

    private WorkerDtos() {
    }

    /** El worker pide trabajo. workerVersion permite rechazar imagenes incompatibles. */
    public record ClaimRequest(String workerId, String workerVersion) {
    }

    /**
     * Asignacion de un shard a un worker. Contiene todo lo necesario para ejecutar
     * el shard sin volumen compartido: el worker descarga el script por HTTP con
     * {@code scriptVersionId} y arranca cuando llega {@code startAt}.
     *
     * @param threads hilos ya resueltos para ESTE shard (reparto hecho en el servidor)
     * @param startAt start-gate comun; null si aun faltan shards por reclamar
     */
    public record ShardAssignment(
            long executionId,
            int shardIndex,
            int shardCount,
            long scriptVersionId,
            int threads,
            int rampUp,
            int duration,
            String targetHost,
            String targetProtocol,
            String extraProps,
            Instant startAt) {
    }

    /** Fase que reporta el worker en su latido. */
    public enum Phase {
        WAITING, // reclamado, esperando el start-gate
        RUNNING  // ejecutando jmeter -n
    }

    public record HeartbeatRequest(String workerId, Phase phase) {
    }

    /**
     * Respuesta al latido: el worker aprende el start-gate y si debe abortar.
     *
     * @param startAt   instante de arranque comun (null si aun no fijado)
     * @param cancelled la ejecucion fue cancelada: el worker debe abortar jmeter
     */
    public record HeartbeatResponse(Instant startAt, boolean cancelled) {
    }
}
