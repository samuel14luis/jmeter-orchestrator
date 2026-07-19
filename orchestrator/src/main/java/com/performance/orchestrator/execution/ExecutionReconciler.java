package com.performance.orchestrator.execution;

import java.util.List;

import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.ExecutionStatus;
import com.performance.orchestrator.execution.ExecutionEvents.ExecutionEvent;
import com.performance.orchestrator.k8s.KubernetesJobService;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reconciliacion periodica del estado real de los Jobs de Kubernetes contra la
 * base de datos (seccion 8.4 del plan). Reconstruye el progreso a partir de las
 * labels de executionId, por lo que sobrevive a reinicios del orquestador.
 */
@ApplicationScoped
public class ExecutionReconciler {

    @Inject
    ExecutionService executions;

    @Inject
    KubernetesJobService k8s;

    @Inject
    ExecutionEvents events;

    @Scheduled(every = "5s", concurrentExecution = ConcurrentExecution.SKIP, delayed = "10s")
    void reconcile() {
        List<Execution> active;
        try {
            active = executions.activeExecutions();
        } catch (Exception e) {
            Log.debugf("No se pudo leer ejecuciones activas: %s", e.getMessage());
            return;
        }
        for (Execution exec : active) {
            try {
                reconcileOne(exec);
            } catch (Exception e) {
                Log.warnf(e, "Error reconciliando ejecucion %d", exec.id);
            }
        }
    }

    private void reconcileOne(Execution exec) {
        if (exec.status == ExecutionStatus.AGGREGATING) {
            executions.aggregateAndClose(exec.id);
            return;
        }
        // status == RUNNING
        Job job = k8s.getJob(exec.id);
        if (job == null) {
            // El Job pudo ser borrado por TTL tras finalizar: intentamos cerrar por artefactos.
            Log.infof("Job de la ejecucion %d no encontrado; se pasa a AGGREGATING", exec.id);
            executions.moveToAggregating(exec.id);
            return;
        }

        JobStatus status = job.getStatus();
        int succeeded = status != null && status.getSucceeded() != null ? status.getSucceeded() : 0;
        int failed = status != null && status.getFailed() != null ? status.getFailed() : 0;
        int active = status != null && status.getActive() != null ? status.getActive() : 0;
        int total = exec.nodes;

        executions.updateNodesCoarse(exec.id, succeeded, failed);
        events.publish(exec.id, new ExecutionEvent(exec.id, "RUNNING",
                String.format("pods: %d/%d ok, %d fallo, %d activos", succeeded, total, failed, active),
                java.util.Map.of("succeeded", succeeded, "failed", failed, "active", active, "total", total)));

        boolean finished = (succeeded + failed) >= total && active == 0;
        if (finished) {
            Log.infof("Ejecucion %d finalizada en el cluster (ok=%d, fallo=%d); agregando resultados",
                    exec.id, succeeded, failed);
            executions.moveToAggregating(exec.id);
        }
    }
}
