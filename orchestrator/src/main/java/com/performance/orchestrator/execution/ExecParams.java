package com.performance.orchestrator.execution;

import java.util.Map;

/**
 * Lectura tipada de los parametros efectivos (JSON) de una ejecucion.
 * Claves canonicas (seccion 6/8.1 del plan):
 *   threads, rampUp, duration, nodes, targetHost, targetProtocol, extraProps
 */
public final class ExecParams {

    public static final String THREADS = "threads";
    public static final String RAMP_UP = "rampUp";
    public static final String DURATION = "duration";
    public static final String NODES = "nodes";
    public static final String TARGET_HOST = "targetHost";
    public static final String TARGET_PROTOCOL = "targetProtocol";
    public static final String EXTRA_PROPS = "extraProps";
    // Rampa por RPS objetivo (plantilla rps-ramp.jmx). La presencia de peakRps
    // activa el "modo RPS": el orquestador reparte start/peak entre shards.
    public static final String START_RPS = "startRps";
    public static final String PEAK_RPS = "peakRps";
    public static final String RAMP_SECONDS = "rampSeconds";
    public static final String HOLD_SECONDS = "holdSeconds";

    private final Map<String, Object> params;

    public ExecParams(Map<String, Object> params) {
        this.params = params;
    }

    public int threads() {
        return asInt(THREADS, 10);
    }

    public int rampUp() {
        return asInt(RAMP_UP, 60);
    }

    public int duration() {
        return asInt(DURATION, 300);
    }

    public int nodes() {
        return asInt(NODES, 1);
    }

    public String targetHost() {
        return asString(TARGET_HOST, null);
    }

    public String targetProtocol() {
        return asString(TARGET_PROTOCOL, "https");
    }

    // ---- Rampa por RPS objetivo ----

    /** true si esta ejecucion usa rampa por RPS (hay peakRps definido y > 0). */
    public boolean isRpsMode() {
        Object v = params.get(PEAK_RPS);
        return v != null && asInt(PEAK_RPS, 0) > 0;
    }

    public int startRps() {
        return asInt(START_RPS, 0);
    }

    public int peakRps() {
        return asInt(PEAK_RPS, 0);
    }

    public int rampSeconds() {
        return asInt(RAMP_SECONDS, 30);
    }

    public int holdSeconds() {
        return asInt(HOLD_SECONDS, 60);
    }

    /** Propiedades -J adicionales ya formateadas como cadena "-Jk=v -Jk2=v2". */
    public String extraProps() {
        Object v = params.get(EXTRA_PROPS);
        if (v == null) {
            return "";
        }
        if (v instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append("-J").append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    public int asInt(String key, int def) {
        Object v = params.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String asString(String key, String def) {
        Object v = params.get(key);
        return v == null ? def : String.valueOf(v);
    }
}
