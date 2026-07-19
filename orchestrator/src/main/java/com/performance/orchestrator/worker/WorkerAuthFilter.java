package com.performance.orchestrator.worker;

import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Guarda la API interna de workers ({@code /internal/*}, Fase 7) con un token de
 * servicio compartido. Es una identidad de servicio distinta de la de usuarios
 * (cabecera {@code X-User}); la API interna nunca debe exponerse fuera de la red
 * del cluster.
 */
@Provider
public class WorkerAuthFilter implements ContainerRequestFilter {

    public static final String TOKEN_HEADER = "X-Worker-Token";

    @ConfigProperty(name = "orchestrator.worker.token", defaultValue = "dev-worker-token")
    String expectedToken;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path == null) {
            return;
        }
        // getPath() devuelve la ruta relativa sin barra inicial (p.ej. "internal/shards/claim").
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!normalized.startsWith("internal")) {
            return; // solo protegemos la API interna
        }
        String provided = ctx.getHeaderString(TOKEN_HEADER);
        if (provided == null || !constantTimeEquals(provided, expectedToken)) {
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "token de worker invalido o ausente", "status", 401))
                    .build());
        }
    }

    /** Comparacion en tiempo constante para no filtrar el token por timing. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
