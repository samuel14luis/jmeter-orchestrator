package com.performance.orchestrator.results;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.performance.orchestrator.storage.StorageService;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fusiona los .jtl por pod en un unico .jtl y calcula el resumen de metricas
 * (seccion 4 y 8.5 del plan). El JTL en formato CSV se parsea directamente en
 * Java para no depender del binario de JMeter en el orquestador; el reporte
 * HTML consolidado (jmeter -g) se puede generar aparte en un worker.
 */
@ApplicationScoped
public class JtlAggregator {

    @Inject
    StorageService storage;

    /** Fusiona los JTL indicados, guarda merged.jtl y devuelve el resumen. */
    public MetricsSummary aggregate(List<String> jtlPaths, String mergedPath) {
        List<Long> elapsedSamples = new ArrayList<>();
        long samples = 0;
        long errors = 0;
        long minTs = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;

        StringBuilder merged = new StringBuilder();
        boolean headerWritten = false;

        for (String path : jtlPaths) {
            if (!storage.exists(path)) {
                Log.warnf("JTL no encontrado, se omite: %s", path);
                continue;
            }
            byte[] data = storage.read(path);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null) {
                    continue;
                }
                JtlColumns cols = JtlColumns.parse(header);
                if (!headerWritten) {
                    merged.append(header).append('\n');
                    headerWritten = true;
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    merged.append(line).append('\n');
                    String[] f = line.split(",", -1);
                    if (f.length <= cols.maxIndex()) {
                        continue;
                    }
                    try {
                        long ts = Long.parseLong(f[cols.timeStamp].trim());
                        long elapsed = Long.parseLong(f[cols.elapsed].trim());
                        boolean success = Boolean.parseBoolean(f[cols.success].trim());
                        samples++;
                        if (!success) {
                            errors++;
                        }
                        elapsedSamples.add(elapsed);
                        minTs = Math.min(minTs, ts);
                        maxEnd = Math.max(maxEnd, ts + elapsed);
                    } catch (NumberFormatException ignore) {
                        // fila con formato inesperado: se conserva en el merge pero no cuenta
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Error leyendo JTL " + path, e);
            }
        }

        if (headerWritten) {
            storage.store(mergedPath, merged.toString().getBytes(StandardCharsets.UTF_8));
        }

        long durationSeconds = (samples == 0 || maxEnd <= minTs) ? 0 : Math.max(1, (maxEnd - minTs) / 1000);
        double tps = durationSeconds == 0 ? 0 : (double) samples / durationSeconds;
        double errorPct = samples == 0 ? 0 : (double) errors / samples * 100.0;

        elapsedSamples.sort(Long::compareTo);
        double avg = elapsedSamples.stream().mapToLong(Long::longValue).average().orElse(0);
        long min = elapsedSamples.isEmpty() ? 0 : elapsedSamples.get(0);
        long max = elapsedSamples.isEmpty() ? 0 : elapsedSamples.get(elapsedSamples.size() - 1);

        return new MetricsSummary(
                samples, errors, errorPct, tps, avg, min, max,
                percentile(elapsedSamples, 90),
                percentile(elapsedSamples, 95),
                percentile(elapsedSamples, 99),
                durationSeconds);
    }

    private static long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        rank = Math.max(0, Math.min(rank, sorted.size() - 1));
        return sorted.get(rank);
    }

    /** Indices de columnas relevantes del CSV de JTL, resueltos por cabecera. */
    private record JtlColumns(int timeStamp, int elapsed, int success) {
        static JtlColumns parse(String header) {
            String[] h = header.split(",", -1);
            int ts = -1, el = -1, ok = -1;
            for (int i = 0; i < h.length; i++) {
                switch (h[i].trim()) {
                    case "timeStamp" -> ts = i;
                    case "elapsed" -> el = i;
                    case "success" -> ok = i;
                    default -> { }
                }
            }
            // Valores por defecto del formato CSV estandar de JMeter si faltara la cabecera
            return new JtlColumns(ts < 0 ? 0 : ts, el < 0 ? 1 : el, ok < 0 ? 7 : ok);
        }

        int maxIndex() {
            return Math.max(timeStamp, Math.max(elapsed, success));
        }
    }
}
