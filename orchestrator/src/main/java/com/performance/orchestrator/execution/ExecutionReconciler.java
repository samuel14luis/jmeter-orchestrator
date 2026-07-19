package com.performance.orchestrator.execution;

import java.util.List;

import com.performance.orchestrator.domain.Execution;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Cierre periodico de ejecuciones en AGGREGATING (worker-pool, Fase 7). Cuando
 * todos los shards de una ejecucion se entregan (o vencen por el reaper), pasa a
 * AGGREGATING; este scheduler fusiona los JTL y la cierra COMPLETED/FAILED fuera
 * del hilo de peticion. El estado de verdad vive en BD, por lo que sobrevive a
 * reinicios del orquestador. El progreso RUNNING lo gobiernan los heartbeats y el
 * reaper del WorkerPoolService, no este componente.
 */
@ApplicationScoped
public class ExecutionReconciler {

    @Inject
    ExecutionService executions;

    @Scheduled(every = "5s", concurrentExecution = ConcurrentExecution.SKIP, delayed = "10s")
    void reconcile() {
        List<Execution> aggregating;
        try {
            aggregating = executions.aggregatingExecutions();
        } catch (Exception e) {
            Log.debugf("No se pudo leer ejecuciones en AGGREGATING: %s", e.getMessage());
            return;
        }
        for (Execution exec : aggregating) {
            try {
                executions.aggregateAndClose(exec.id);
            } catch (Exception e) {
                Log.warnf(e, "Error agregando la ejecucion %d", exec.id);
            }
        }
    }
}
