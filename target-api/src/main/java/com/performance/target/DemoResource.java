package com.performance.target;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * API objetivo (System Under Test) para las pruebas de performance.
 *
 * Endpoints pensados para generar distintos perfiles de carga. Micrometer
 * instrumenta automaticamente cada uno via {@code http_server_requests_seconds}
 * (rps + histograma de latencia) y ademas expone metricas de JVM/CPU/RAM.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class DemoResource {

    /** Respuesta inmediata: mide el throughput maximo sin trabajo. */
    @GET
    @Path("/fast")
    public Map<String, Object> fast() {
        return Map.of("ok", true, "endpoint", "fast");
    }

    /**
     * Latencia artificial. {@code min}/{@code max} en ms (por defecto 50-500).
     * Sirve para ver percentiles de response time reaccionando a la carga.
     */
    @GET
    @Path("/slow")
    public Map<String, Object> slow(@QueryParam("min") Integer min, @QueryParam("max") Integer max) {
        int lo = min == null ? 50 : min;
        int hi = max == null ? 500 : Math.max(max, lo + 1);
        int delay = ThreadLocalRandom.current().nextInt(lo, hi);
        sleep(delay);
        return Map.of("ok", true, "endpoint", "slow", "delayMs", delay);
    }

    /**
     * Quema CPU con un bucle de computo. {@code iters} controla la intensidad
     * (por defecto 200000). Util para ver subir process_cpu_usage / system.
     */
    @GET
    @Path("/cpu")
    public Map<String, Object> cpu(@QueryParam("iters") Integer iters) {
        int n = iters == null ? 200_000 : iters;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += Math.sqrt(i) * Math.sin(i);
        }
        return Map.of("ok", true, "endpoint", "cpu", "iters", n, "acc", acc);
    }

    /**
     * Falla de forma aleatoria. {@code rate} es la probabilidad de error 0..1
     * (por defecto 0.1 = 10%). Sirve para ver el % de error en el dashboard.
     */
    @GET
    @Path("/flaky")
    public Response flaky(@QueryParam("rate") Double rate) {
        double p = rate == null ? 0.1 : rate;
        if (ThreadLocalRandom.current().nextDouble() < p) {
            throw new WebApplicationException("fallo simulado", 500);
        }
        return Response.ok(Map.of("ok", true, "endpoint", "flaky")).build();
    }

    /**
     * Asigna memoria transitoria ({@code kb} kilobytes, por defecto 1024) para
     * mover el uso de heap y forzar GC bajo carga.
     */
    @GET
    @Path("/mem")
    public Map<String, Object> mem(@QueryParam("kb") Integer kb) {
        int size = (kb == null ? 1024 : kb) * 1024;
        byte[] blob = new byte[size];
        ThreadLocalRandom.current().nextBytes(blob);
        // "usar" el blob para que no lo elimine el JIT
        long sum = 0;
        for (int i = 0; i < blob.length; i += 4096) {
            sum += blob[i];
        }
        return Map.of("ok", true, "endpoint", "mem", "allocatedKb", size / 1024, "checksum", sum);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
