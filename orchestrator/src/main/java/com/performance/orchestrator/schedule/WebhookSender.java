package com.performance.orchestrator.schedule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Envia el aviso de un chequeo degradado/fallido a un webhook HTTP configurable
 * (Fase 8). El payload no incluye datos sensibles (solo servicio, metricas y el
 * enlace al detalle). Un fallo de envio se loguea pero nunca rompe la corrida.
 */
@ApplicationScoped
public class WebhookSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** POST del payload JSON al webhook. Devuelve true si el envio fue 2xx. */
    public boolean send(String url, Map<String, Object> payload) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String body = MAPPER.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = res.statusCode() >= 200 && res.statusCode() < 300;
            if (ok) {
                Log.infof("Webhook enviado a %s (HTTP %d)", url, res.statusCode());
            } else {
                Log.warnf("Webhook a %s respondio HTTP %d", url, res.statusCode());
            }
            return ok;
        } catch (Exception e) {
            Log.warnf("No se pudo enviar el webhook a %s: %s", url, e.getMessage());
            return false;
        }
    }
}
