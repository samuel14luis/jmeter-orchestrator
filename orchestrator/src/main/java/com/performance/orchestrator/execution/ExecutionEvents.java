package com.performance.orchestrator.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bus en memoria de eventos de progreso por ejecucion, publicado por SSE
 * (seccion 4 del plan). Cada ejecucion tiene su propio BroadcastProcessor.
 */
@ApplicationScoped
public class ExecutionEvents {

    private final Map<Long, BroadcastProcessor<ExecutionEvent>> byExecution = new ConcurrentHashMap<>();

    public Multi<ExecutionEvent> stream(long executionId) {
        return processor(executionId);
    }

    public void publish(long executionId, ExecutionEvent event) {
        processor(executionId).onNext(event);
    }

    public void complete(long executionId) {
        BroadcastProcessor<ExecutionEvent> p = byExecution.remove(executionId);
        if (p != null) {
            p.onComplete();
        }
    }

    private BroadcastProcessor<ExecutionEvent> processor(long executionId) {
        return byExecution.computeIfAbsent(executionId, k -> BroadcastProcessor.create());
    }

    /** Evento de progreso enviado por SSE. */
    public record ExecutionEvent(long executionId, String status, String message, Object data) {
    }
}
