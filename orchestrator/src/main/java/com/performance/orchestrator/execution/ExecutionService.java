package com.performance.orchestrator.execution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.AuditEvent;
import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.ExecutionNode;
import com.performance.orchestrator.domain.ExecutionStatus;
import com.performance.orchestrator.domain.NodeStatus;
import com.performance.orchestrator.domain.Preset;
import com.performance.orchestrator.domain.ScriptVersion;
import com.performance.orchestrator.execution.ExecutionEvents.ExecutionEvent;
import com.performance.orchestrator.k8s.KubernetesJobService;
import com.performance.orchestrator.k8s.LaunchSpec;
import com.performance.orchestrator.results.JtlAggregator;
import com.performance.orchestrator.results.MetricsSummary;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Motor de ejecuciones: valida guardrails, reparte la carga por sharding,
 * crea el Job de Kubernetes y (mediante reconciliacion periodica) sigue el
 * ciclo de vida PENDING -> RUNNING -> AGGREGATING -> COMPLETED/FAILED.
 *
 * Los limites transaccionales se manejan con QuarkusTransaction para que
 * funcionen tanto en el hilo de peticion como en el hilo del scheduler
 * (que no tiene scope de request) y sin sufrir el problema de self-invocation.
 */
@ApplicationScoped
public class ExecutionService {

    @Inject
    KubernetesJobService k8s;

    @Inject
    GuardrailsService guardrails;

    @Inject
    JtlAggregator aggregator;

    @Inject
    ExecutionEvents events;

    // ---------------------------------------------------------------------
    //  Consulta (invocada desde REST: hay session de request)
    // ---------------------------------------------------------------------

    public Execution get(Long id) {
        Execution e = Execution.findById(id);
        if (e == null) {
            throw ApiException.notFound("Ejecucion no encontrada: " + id);
        }
        return e;
    }

    public List<ExecutionNode> nodesOf(Long executionId) {
        return ExecutionNode.listByExecution(executionId);
    }

    public List<Execution> search(Long scriptVersionId, ExecutionStatus status, Instant from, Instant to) {
        StringBuilder q = new StringBuilder("1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (scriptVersionId != null) {
            q.append(" and scriptVersionId = :sv");
            params.put("sv", scriptVersionId);
        }
        if (status != null) {
            q.append(" and status = :st");
            params.put("st", status);
        }
        if (from != null) {
            q.append(" and createdAt >= :from");
            params.put("from", from);
        }
        if (to != null) {
            q.append(" and createdAt <= :to");
            params.put("to", to);
        }
        return Execution.find(q.toString(), Sort.by("createdAt").descending(), params).list();
    }

    // ---------------------------------------------------------------------
    //  Lanzamiento
    // ---------------------------------------------------------------------

    public Execution launchAdHoc(Long scriptVersionId, Map<String, Object> params, String user) {
        Long id = createRecord(scriptVersionId, null, params, user, "LAUNCH");
        startExecution(id);
        return getInTx(id);
    }

    public Execution launchFromPreset(Preset preset, Map<String, Object> overrides, String user) {
        Map<String, Object> effective = new LinkedHashMap<>(preset.parameters);
        if (overrides != null) {
            effective.putAll(overrides);
        }
        ScriptVersion latest = QuarkusTransaction.requiringNew().call(() ->
                ScriptVersion.find("scriptId = ?1 order by versionNumber desc", preset.scriptId).firstResult());
        if (latest == null) {
            throw ApiException.badRequest("El script del preset no tiene ninguna version");
        }
        Long id = createRecord(latest.id, preset.id, effective, user, "LAUNCH");
        startExecution(id);
        return getInTx(id);
    }

    public Execution relaunch(Long previousId, String user) {
        Execution prev = getInTx(previousId);
        Long id = createRecord(prev.scriptVersionId, prev.presetId,
                new LinkedHashMap<>(prev.effectiveParams), user, "RELAUNCH");
        startExecution(id);
        return getInTx(id);
    }

    private Execution getInTx(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> get(id));
    }

    private Long createRecord(Long scriptVersionId, Long presetId, Map<String, Object> params,
                              String user, String auditAction) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ScriptVersion version = ScriptVersion.findById(scriptVersionId);
            if (version == null) {
                throw ApiException.notFound("Version de script no encontrada: " + scriptVersionId);
            }

            ExecParams ep = new ExecParams(params);
            guardrails.validate(ep.threads(), ep.nodes(), ep.targetHost());

            Execution exec = new Execution();
            exec.scriptVersionId = scriptVersionId;
            exec.presetId = presetId;
            exec.effectiveParams = params;
            exec.status = ExecutionStatus.PENDING;
            exec.nodes = ep.nodes();
            exec.launchedBy = user;
            exec.resultsPath = null;
            exec.persist();

            String resultsDir = "executions/" + exec.id;
            for (int i = 0; i < ep.nodes(); i++) {
                ExecutionNode node = new ExecutionNode();
                node.executionId = exec.id;
                node.nodeIndex = i;
                node.status = NodeStatus.PENDING;
                node.jtlPath = resultsDir + "/node-" + i + ".jtl";
                node.logPath = resultsDir + "/node-" + i + ".log";
                node.persist();
            }
            exec.resultsPath = resultsDir;

            AuditEvent.record(user, auditAction, "Execution", exec.id,
                    "threads=" + ep.threads() + " nodes=" + ep.nodes() + " target=" + ep.targetHost());
            return exec.id;
        });
    }

    /** Crea el Job en Kubernetes y marca la ejecucion como RUNNING (o FAILED). */
    private void startExecution(Long executionId) {
        LaunchSpec spec = QuarkusTransaction.requiringNew().call(() -> {
            Execution exec = get(executionId);
            ScriptVersion version = ScriptVersion.findById(exec.scriptVersionId);
            ExecParams ep = new ExecParams(exec.effectiveParams);
            return new LaunchSpec(
                    exec.id, version.blobPath, exec.resultsPath, exec.nodes,
                    ep.threads(), ep.rampUp(), ep.duration(),
                    ep.targetHost(), ep.targetProtocol(), ep.extraProps());
        });

        try {
            k8s.createExecutionJob(spec);
            setStatus(executionId, ExecutionStatus.RUNNING, exec -> exec.startedAt = Instant.now());
            events.publish(executionId, new ExecutionEvent(executionId, "RUNNING",
                    "Job creado con " + spec.nodes() + " pods", null));
        } catch (Exception e) {
            Log.errorf(e, "Fallo creando el Job para la ejecucion %d", executionId);
            setStatus(executionId, ExecutionStatus.FAILED, exec -> {
                exec.errorMessage = "No se pudo crear el Job de Kubernetes: " + e.getMessage();
                exec.finishedAt = Instant.now();
            });
            events.publish(executionId, new ExecutionEvent(executionId, "FAILED", e.getMessage(), null));
            events.complete(executionId);
        }
    }

    // ---------------------------------------------------------------------
    //  Cancelacion
    // ---------------------------------------------------------------------

    public Execution cancel(Long executionId, String user) {
        // Marcamos CANCELLED (estado terminal) ANTES de borrar el Job, y comprobamos
        // el estado de forma atomica dentro de la transaccion. El reconciler solo
        // itera ejecuciones no terminales, asi que en cuanto queda CANCELLED deja de
        // tocarla: no puede haber una carrera en la que el poll la mueva a
        // AGGREGATING/COMPLETED y pise nuestra cancelacion.
        QuarkusTransaction.requiringNew().run(() -> {
            Execution e = Execution.findById(executionId);
            if (e == null) {
                throw ApiException.notFound("Ejecucion no encontrada: " + executionId);
            }
            if (e.status.isTerminal()) {
                throw ApiException.conflict("La ejecucion ya esta en estado terminal: " + e.status);
            }
            e.status = ExecutionStatus.CANCELLED;
            e.finishedAt = Instant.now();
            AuditEvent.record(user, "CANCEL", "Execution", executionId, null);
        });
        try {
            k8s.deleteExecutionJob(executionId);
        } catch (Exception e) {
            Log.warnf(e, "No se pudo borrar el Job al cancelar la ejecucion %d", executionId);
        }
        events.publish(executionId, new ExecutionEvent(executionId, "CANCELLED", "Cancelada por " + user, null));
        events.complete(executionId);
        return getInTx(executionId);
    }

    // ---------------------------------------------------------------------
    //  Utilidades transaccionales
    // ---------------------------------------------------------------------

    private void setStatus(Long id, ExecutionStatus status, java.util.function.Consumer<Execution> mutator) {
        QuarkusTransaction.requiringNew().run(() -> {
            Execution e = Execution.findById(id);
            if (e != null) {
                e.status = status;
                if (mutator != null) {
                    mutator.accept(e);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    //  Reconciliacion (invocada desde el scheduler)
    // ---------------------------------------------------------------------

    List<Execution> activeExecutions() {
        return QuarkusTransaction.requiringNew().call(() ->
                Execution.list("status in ?1",
                        List.of(ExecutionStatus.RUNNING, ExecutionStatus.AGGREGATING)));
    }

    /** Actualiza el estado de los nodos de forma agregada segun el Job. */
    void updateNodesCoarse(Long executionId, int succeeded, int failed) {
        QuarkusTransaction.requiringNew().run(() -> {
            List<ExecutionNode> nodes = ExecutionNode.listByExecution(executionId);
            for (int i = 0; i < nodes.size(); i++) {
                ExecutionNode n = nodes.get(i);
                if (i < succeeded) {
                    n.status = NodeStatus.SUCCEEDED;
                } else if (i < succeeded + failed) {
                    n.status = NodeStatus.FAILED;
                } else {
                    n.status = NodeStatus.RUNNING;
                }
                n.updatedAt = Instant.now();
            }
        });
    }

    void moveToAggregating(Long executionId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Execution e = Execution.findById(executionId);
            if (e != null && e.status == ExecutionStatus.RUNNING) {
                e.status = ExecutionStatus.AGGREGATING;
            }
        });
    }

    /** Ejecuta la fusion de JTL y cierra la ejecucion (COMPLETED/FAILED). */
    void aggregateAndClose(Long executionId) {
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            Execution exec = Execution.findById(executionId);
            if (exec == null || exec.status != ExecutionStatus.AGGREGATING) {
                return null;
            }
            List<ExecutionNode> nodes = ExecutionNode.listByExecution(executionId);
            List<String> jtlPaths = nodes.stream().map(n -> n.jtlPath).toList();
            String mergedPath = exec.resultsPath + "/merged.jtl";

            MetricsSummary summary = aggregator.aggregate(jtlPaths, mergedPath);
            exec.summary = summary.toMap();
            exec.finishedAt = Instant.now();
            exec.status = summary.samples() > 0 ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
            if (summary.samples() == 0) {
                exec.errorMessage = "No se recogieron muestras de ningun pod";
            }
            events.publish(executionId, new ExecutionEvent(executionId, exec.status.name(),
                    "Ejecucion cerrada", summary.toMap()));
            return executionId;
        });
        if (id != null) {
            events.complete(id);
        }
    }
}
