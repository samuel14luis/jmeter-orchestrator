package com.performance.orchestrator.results;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resumen de metricas consolidado de una ejecucion. */
public record MetricsSummary(
        long samples,
        long errors,
        double errorPct,
        double tps,
        double avgMs,
        long minMs,
        long maxMs,
        long p90Ms,
        long p95Ms,
        long p99Ms,
        long durationSeconds) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", samples);
        m.put("errors", errors);
        m.put("errorPct", round(errorPct));
        m.put("tps", round(tps));
        m.put("avgMs", round(avgMs));
        m.put("minMs", minMs);
        m.put("maxMs", maxMs);
        m.put("p90", p90Ms);
        m.put("p95", p95Ms);
        m.put("p99", p99Ms);
        m.put("durationSeconds", durationSeconds);
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
