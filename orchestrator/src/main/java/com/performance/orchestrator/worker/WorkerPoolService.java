package com.performance.orchestrator.worker;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.ExecutionNode;
import com.performance.orchestrator.domain.ExecutionStatus;
import com.performance.orchestrator.domain.NodeStatus;
import com.performance.orchestrator.execution.ExecParams;
import com.performance.orchestrator.execution.ExecutionEvents;
import com.performance.orchestrator.execution.ExecutionEvents.ExecutionEvent;
import com.performance.orchestrator.storage.StorageService;
import com.performance.orchestrator.worker.WorkerDtos.ClaimRequest;
import com.performance.orchestrator.worker.WorkerDtos.HeartbeatRequest;
import com.performance.orchestrator.worker.WorkerDtos.HeartbeatResponse;
import com.performance.orchestrator.worker.WorkerDtos.Phase;
import com.performance.orchestrator.worker.WorkerDtos.ShardAssignment;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Motor de ejecucion por worker-pool (Fase 7): las N replicas worker reclaman
 * shards al orquestador por pull, esperan un start-gate comun, ejecutan su parte
 * y suben los resultados por HTTP. No hay lider ni API de Kubernetes en runtime;
 * el unico punto de coordinacion es la base de datos.
 *
 * Ciclo de un shard: PENDING -(claim)-> CLAIMED -(start-gate + heartbeat RUNNING)->
 * RUNNING -(resultados)-> SUCCEEDED/FAILED. El reaper marca huerfanos los shards
 * cuyo latido envejece y cierra ejecuciones que nunca reunieron workers.
 */
@ApplicationScoped
public class WorkerPoolService {

    @Inject
    ExecutionEvents events;

    @Inject
    StorageService storage;

    /** Segundos de margen desde que se reclama el ultimo shard hasta el arranque comun. */
    @ConfigProperty(name = "orchestrator.pool.start-delay-seconds", defaultValue = "3")
    int startDelaySeconds;

    /** Un shard CLAIMED/RUNNING sin latido durante este tiempo se da por huerfano. */
    @ConfigProperty(name = "orchestrator.pool.heartbeat-timeout-seconds", defaultValue = "30")
    int heartbeatTimeoutSeconds;

    /** Una ejecucion PENDING que no reune todos sus workers en este tiempo se cierra como FAILED. */
    @ConfigProperty(name = "orchestrator.pool.pending-claim-timeout-seconds", defaultValue = "180")
    int pendingClaimTimeoutSeconds;

    // ---------------------------------------------------------------------
    //  Claim (pull): asigna atomicamente el siguiente shard libre
    // ---------------------------------------------------------------------

    private enum ClaimKind { CLAIMED, NO_WORK, LOST_RACE }

    private record ClaimOutcome(ClaimKind kind, ShardAssignment assignment) {
        static ClaimOutcome claimed(ShardAssignment a) { return new ClaimOutcome(ClaimKind.CLAIMED, a); }
        static final ClaimOutcome NO_WORK = new ClaimOutcome(ClaimKind.NO_WORK, null);
        static final ClaimOutcome LOST_RACE = new ClaimOutcome(ClaimKind.LOST_RACE, null);
    }

    public Optional<ShardAssignment> claim(ClaimRequest req) {
        if (req == null || req.workerId() == null || req.workerId().isBlank()) {
            throw ApiException.badRequest("workerId es obligatorio para reclamar un shard");
        }
        // Reintento acotado: un UPDATE guardado por status=PENDING garantiza que dos
        // workers no obtengan el mismo shard; si perdemos la carrera, reintentamos.
        for (int attempt = 0; attempt < 6; attempt++) {
            ClaimOutcome oc = QuarkusTransaction.requiringNew().call(() -> tryClaimOnce(req));
            switch (oc.kind()) {
                case CLAIMED -> {
                    return Optional.of(oc.assignment());
                }
                case NO_WORK -> {
                    return Optional.empty();
                }
                case LOST_RACE -> {
                    // reintentar
                }
            }
        }
        return Optional.empty();
    }

    private ClaimOutcome tryClaimOnce(ClaimRequest req) {
        ExecutionNode candidate = ExecutionNode.find(
                "status = ?1 and executionId in "
                        + "(select e.id from Execution e where e.status = ?2) "
                        + "order by executionId, nodeIndex",
                NodeStatus.PENDING, ExecutionStatus.PENDING).firstResult();
        if (candidate == null) {
            return ClaimOutcome.NO_WORK;
        }

        Instant now = Instant.now();
        int claimed = ExecutionNode.update(
                "status = ?1, workerId = ?2, heartbeatAt = ?3, updatedAt = ?3 "
                        + "where id = ?4 and status = ?5",
                NodeStatus.CLAIMED, req.workerId(), now, candidate.id, NodeStatus.PENDING);
        if (claimed == 0) {
            return ClaimOutcome.LOST_RACE;
        }

        Execution exec = Execution.findById(candidate.executionId);
        ExecParams ep = new ExecParams(exec.effectiveParams);

        // Reparto por shard. En modo RPS (rampa por Throughput Shaping Timer) el RPS
        // es la carga y se reparte entre shards (la suma da el pico); los hilos NO se
        // reparten: cada worker recibe el pool completo como techo. En modo hilos,
        // se reparten los hilos como siempre.
        int threads;
        int duration;
        String extra = ep.extraProps();
        if (ep.isRpsMode()) {
            threads = ep.threads();
            int peakShare = threadsForShard(ep.peakRps(), exec.nodes, candidate.nodeIndex);
            int startShare = ep.startRps() > 0 ? threadsForShard(ep.startRps(), exec.nodes, candidate.nodeIndex) : 0;
            duration = ep.rampSeconds() + ep.holdSeconds();
            extra = (extra + " -JstartRps=" + startShare + " -JpeakRps=" + peakShare
                    + " -JrampSeconds=" + ep.rampSeconds() + " -JholdSeconds=" + ep.holdSeconds()).trim();
        } else {
            threads = threadsForShard(ep.threads(), exec.nodes, candidate.nodeIndex);
            duration = ep.duration();
        }

        // Start-gate: si ya no quedan shards PENDING, fijamos el arranque comun.
        Instant startAt = exec.startAt;
        long pending = ExecutionNode.countByExecutionAndStatus(exec.id, NodeStatus.PENDING);
        if (pending == 0 && exec.startAt == null) {
            Instant gate = now.plusSeconds(startDelaySeconds);
            int flipped = Execution.update(
                    "startAt = ?1, status = ?2, startedAt = ?3 where id = ?4 and status = ?5",
                    gate, ExecutionStatus.RUNNING, now, exec.id, ExecutionStatus.PENDING);
            if (flipped == 1) {
                startAt = gate;
                events.publish(exec.id, new ExecutionEvent(exec.id, "RUNNING",
                        "Todos los shards reclamados; arranque en " + startDelaySeconds + "s", null));
            }
            // Si no ganamos el flip, otro worker lo fijo: el nuestro lo descubrira por heartbeat.
        }

        Log.infof("Shard reclamado: exec=%d idx=%d worker=%s threads=%d%s",
                exec.id, candidate.nodeIndex, req.workerId(), threads,
                ep.isRpsMode() ? " rpsMode(peak/shard=" + threadsForShard(ep.peakRps(), exec.nodes, candidate.nodeIndex) + ")" : "");
        ShardAssignment a = new ShardAssignment(
                exec.id, candidate.nodeIndex, exec.nodes, exec.scriptVersionId,
                threads, ep.rampUp(), duration, ep.targetHost(), ep.targetProtocol(),
                extra, startAt);
        return ClaimOutcome.claimed(a);
    }

    /** Reparto determinista: los primeros (total % count) shards reciben +1 hilo. */
    static int threadsForShard(int totalThreads, int shardCount, int shardIndex) {
        int base = totalThreads / shardCount;
        int remainder = totalThreads % shardCount;
        int threads = base + (shardIndex < remainder ? 1 : 0);
        return Math.max(1, threads);
    }

    // ---------------------------------------------------------------------
    //  Heartbeat: entrega el start-gate y detecta cancelacion
    // ---------------------------------------------------------------------

    public HeartbeatResponse heartbeat(long executionId, int shardIndex, HeartbeatRequest req) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Execution e = Execution.findById(executionId);
            if (e == null) {
                throw ApiException.notFound("Ejecucion no encontrada: " + executionId);
            }
            ExecutionNode n = ExecutionNode.findByExecutionAndIndex(executionId, shardIndex);
            if (n == null) {
                throw ApiException.notFound("Shard no encontrado: " + executionId + "/" + shardIndex);
            }
            boolean cancelled = e.status == ExecutionStatus.CANCELLED;
            if (!cancelled && !n.status.isTerminal()) {
                n.heartbeatAt = Instant.now();
                if (req != null && req.phase() == Phase.RUNNING && n.status == NodeStatus.CLAIMED) {
                    n.status = NodeStatus.RUNNING;
                    n.startedAt = Instant.now();
                }
                n.updatedAt = Instant.now();
            }
            return new HeartbeatResponse(e.startAt, cancelled);
        });
    }

    // ---------------------------------------------------------------------
    //  Resultados: el worker sube su JTL + log al terminar el shard
    // ---------------------------------------------------------------------

    public void submitResults(long executionId, int shardIndex, String workerId,
                              byte[] jtlBytes, byte[] logBytes, boolean failed) {
        QuarkusTransaction.requiringNew().run(() -> {
            Execution e = Execution.findById(executionId);
            if (e == null) {
                throw ApiException.notFound("Ejecucion no encontrada: " + executionId);
            }
            ExecutionNode n = ExecutionNode.findByExecutionAndIndex(executionId, shardIndex);
            if (n == null) {
                throw ApiException.notFound("Shard no encontrado: " + executionId + "/" + shardIndex);
            }
            if (n.status.isTerminal()) {
                Log.warnf("Resultados duplicados/tardios para shard ya terminal exec=%d idx=%d; ignorados",
                        executionId, shardIndex);
                return;
            }
            if (jtlBytes != null && jtlBytes.length > 0) {
                storage.store(n.jtlPath, jtlBytes);
            }
            if (logBytes != null && logBytes.length > 0) {
                storage.store(n.logPath, logBytes);
            }
            n.status = failed ? NodeStatus.FAILED : NodeStatus.SUCCEEDED;
            n.heartbeatAt = Instant.now();
            n.updatedAt = Instant.now();
            Log.infof("Resultados recibidos: exec=%d idx=%d worker=%s estado=%s",
                    executionId, shardIndex, workerId, n.status);

            publishProgress(executionId);

            // Cuando todos los shards terminan, pasar a AGGREGATING: el reconciler
            // programado hara la fusion de JTL y cerrara la ejecucion.
            if (ExecutionNode.countNonTerminal(executionId) == 0 && e.status == ExecutionStatus.RUNNING) {
                e.status = ExecutionStatus.AGGREGATING;
                events.publish(executionId, new ExecutionEvent(executionId, "AGGREGATING",
                        "Todos los shards entregados; agregando resultados", null));
            }
        });
    }

    // ---------------------------------------------------------------------
    //  Reaper: huerfanos y ejecuciones que nunca reunieron workers
    // ---------------------------------------------------------------------

    @Scheduled(every = "10s", concurrentExecution = ConcurrentExecution.SKIP, delayed = "10s")
    void reap() {
        try {
            reapOrphanShards();
            failStalePendingExecutions();
            publishRunningProgress();
        } catch (Exception ex) {
            Log.warnf(ex, "Error en el reaper del worker-pool");
        }
    }

    /** Marca FAILED los shards CLAIMED/RUNNING cuyo latido envejecio y cierra la ejecucion si procede. */
    private void reapOrphanShards() {
        QuarkusTransaction.requiringNew().run(() -> {
            Instant cutoff = Instant.now().minusSeconds(heartbeatTimeoutSeconds);
            List<ExecutionNode> stale = ExecutionNode.list("status in ?1 and heartbeatAt < ?2",
                    List.of(NodeStatus.CLAIMED, NodeStatus.RUNNING), cutoff);
            if (stale.isEmpty()) {
                return;
            }
            for (ExecutionNode n : stale) {
                n.status = NodeStatus.FAILED;
                n.updatedAt = Instant.now();
                Log.warnf("Shard huerfano (sin latido): exec=%d idx=%d worker=%s -> FAILED",
                        n.executionId, n.nodeIndex, n.workerId);
            }
            // Cerrar ejecuciones cuyos shards ya son todos terminales.
            stale.stream().map(n -> n.executionId).distinct().forEach(execId -> {
                Execution e = Execution.findById(execId);
                if (e != null && e.status == ExecutionStatus.RUNNING
                        && ExecutionNode.countNonTerminal(execId) == 0) {
                    e.status = ExecutionStatus.AGGREGATING;
                }
            });
        });
    }

    /** Cierra como FAILED las ejecuciones PENDING que nunca reunieron todos sus workers. */
    private void failStalePendingExecutions() {
        List<Long> toClose = QuarkusTransaction.requiringNew().call(() -> {
            Instant cutoff = Instant.now().minusSeconds(pendingClaimTimeoutSeconds);
            List<Execution> stuck = Execution.list("status = ?1 and createdAt < ?2",
                    ExecutionStatus.PENDING, cutoff);
            for (Execution e : stuck) {
                long claimedOrRunning = ExecutionNode.count(
                        "executionId = ?1 and status in ?2", e.id,
                        List.of(NodeStatus.CLAIMED, NodeStatus.RUNNING));
                e.status = ExecutionStatus.FAILED;
                e.finishedAt = Instant.now();
                e.errorMessage = "No se reunieron suficientes workers a tiempo ("
                        + claimedOrRunning + "/" + e.nodes + " shards reclamados)";
                Log.warnf("Ejecucion %d cerrada por falta de workers", e.id);
            }
            return stuck.stream().map(e -> e.id).toList();
        });
        for (Long id : toClose) {
            events.publish(id, new ExecutionEvent(id, "FAILED", "Sin workers suficientes", null));
            events.complete(id);
        }
    }

    /** Publica el progreso de las ejecuciones RUNNING para alimentar los tiles en vivo. */
    private void publishRunningProgress() {
        List<Long> running = QuarkusTransaction.requiringNew().call(() ->
                Execution.<Execution>list("status = ?1", ExecutionStatus.RUNNING)
                        .stream().map(e -> e.id).toList());
        for (Long id : running) {
            QuarkusTransaction.requiringNew().run(() -> publishProgress(id));
        }
    }

    /** Publica un evento SSE con el conteo de shards en el formato que consume la UI. */
    private void publishProgress(Long executionId) {
        long total = ExecutionNode.count("executionId", executionId);
        long ok = ExecutionNode.countByExecutionAndStatus(executionId, NodeStatus.SUCCEEDED);
        long failed = ExecutionNode.countByExecutionAndStatus(executionId, NodeStatus.FAILED)
                + ExecutionNode.countByExecutionAndStatus(executionId, NodeStatus.CANCELLED);
        long active = ExecutionNode.count("executionId = ?1 and status in ?2", executionId,
                List.of(NodeStatus.CLAIMED, NodeStatus.RUNNING));
        events.publish(executionId, new ExecutionEvent(executionId, "RUNNING",
                String.format("shards: %d/%d ok, %d fallo, %d activos", ok, total, failed, active),
                Map.of("succeeded", ok, "failed", failed, "active", active, "total", total)));
    }
}
