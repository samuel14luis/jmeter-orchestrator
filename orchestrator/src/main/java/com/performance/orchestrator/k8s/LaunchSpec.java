package com.performance.orchestrator.k8s;

/**
 * Parametros efectivos y resueltos que necesita el Job de Kubernetes para
 * lanzar una ejecucion distribuida por sharding.
 *
 * @param executionId    id de la ejecucion (usado como label para reconciliacion)
 * @param scriptPath     ruta del .jmx en el almacen compartido
 * @param resultsDir     directorio de resultados en el almacen compartido
 * @param nodes          numero de pods (shards)
 * @param totalThreads   hilos totales a repartir entre los pods
 * @param rampUpSeconds  rampa en segundos
 * @param durationSeconds duracion en segundos
 * @param targetHost     host destino (ya validado contra la lista blanca)
 * @param targetProtocol http | https
 * @param extraProps     propiedades -J adicionales, separadas por espacio (ej. "-Jfoo=bar")
 */
public record LaunchSpec(
        long executionId,
        String scriptPath,
        String resultsDir,
        int nodes,
        int totalThreads,
        int rampUpSeconds,
        int durationSeconds,
        String targetHost,
        String targetProtocol,
        String extraProps) {
}
