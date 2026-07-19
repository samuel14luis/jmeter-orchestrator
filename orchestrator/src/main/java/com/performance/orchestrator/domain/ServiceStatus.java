package com.performance.orchestrator.domain;

/**
 * Veredicto de salud de un servicio en un chequeo sintetico (Fase 8):
 *   OK       - dentro de todos los umbrales
 *   DEGRADED - responde pero fuera del umbral de latencia (p95)
 *   FAILED   - caido / no ejecutable / demasiados errores
 */
public enum ServiceStatus {
    OK,
    DEGRADED,
    FAILED;

    /** Devuelve el peor de dos veredictos (FAILED > DEGRADED > OK). */
    public static ServiceStatus worst(ServiceStatus a, ServiceStatus b) {
        if (a == FAILED || b == FAILED) {
            return FAILED;
        }
        if (a == DEGRADED || b == DEGRADED) {
            return DEGRADED;
        }
        return OK;
    }
}
