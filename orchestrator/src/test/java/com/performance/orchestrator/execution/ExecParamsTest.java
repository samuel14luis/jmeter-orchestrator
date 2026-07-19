package com.performance.orchestrator.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Test unitario puro (sin CDI) de la lectura tipada de parametros. */
class ExecParamsTest {

    @Test
    void appliesDefaultsWhenMissing() {
        ExecParams p = new ExecParams(new LinkedHashMap<>());
        assertEquals(10, p.threads());
        assertEquals(60, p.rampUp());
        assertEquals(300, p.duration());
        assertEquals(1, p.nodes());
        assertEquals("https", p.targetProtocol());
        assertEquals("", p.extraProps());
    }

    @Test
    void readsProvidedValuesAndFormatsExtraProps() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("threads", 1000);
        raw.put("nodes", 4);
        raw.put("targetHost", "httpbin.org");
        raw.put("extraProps", Map.of("csvFile", "users.csv"));

        ExecParams p = new ExecParams(raw);
        assertEquals(1000, p.threads());
        assertEquals(4, p.nodes());
        assertEquals("httpbin.org", p.targetHost());
        assertEquals("-JcsvFile=users.csv", p.extraProps());
    }

    @Test
    void parsesStringNumbers() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("threads", "250");
        assertEquals(250, new ExecParams(raw).threads());
    }
}
