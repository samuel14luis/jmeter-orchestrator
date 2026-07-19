package com.performance.orchestrator.execution;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.performance.orchestrator.common.ApiException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Guardrails de seguridad (seccion 10 del plan): tope de hilos/pods y lista
 * blanca de hosts destino. Evita disparar carga contra hosts no autorizados.
 */
@ApplicationScoped
public class GuardrailsService {

    @ConfigProperty(name = "orchestrator.guardrails.max-threads", defaultValue = "5000")
    int maxThreads;

    @ConfigProperty(name = "orchestrator.guardrails.max-pods", defaultValue = "20")
    int maxPods;

    @ConfigProperty(name = "orchestrator.guardrails.allowed-hosts")
    Optional<List<String>> allowedHosts;

    public void validate(int totalThreads, int nodes, String targetHost) {
        if (nodes < 1) {
            throw ApiException.badRequest("El numero de nodos debe ser >= 1");
        }
        if (nodes > maxPods) {
            throw ApiException.badRequest("Nodos (" + nodes + ") supera el tope permitido (" + maxPods + ")");
        }
        if (totalThreads < 1) {
            throw ApiException.badRequest("Los hilos totales deben ser >= 1");
        }
        if (totalThreads > maxThreads) {
            throw ApiException.badRequest("Hilos (" + totalThreads + ") supera el tope permitido (" + maxThreads + ")");
        }
        if (targetHost != null && !targetHost.isBlank()) {
            List<String> hosts = allowedHosts.orElse(List.of());
            if (!hosts.contains(targetHost)) {
                throw ApiException.forbidden(
                        "Host destino '" + targetHost + "' no esta en la lista blanca autorizada");
            }
        }
    }

    public boolean isHostAllowed(String host) {
        return host != null && allowedHosts.orElse(List.of()).contains(host);
    }
}
