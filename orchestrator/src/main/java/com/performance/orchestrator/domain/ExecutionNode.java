package com.performance.orchestrator.domain;

import java.time.Instant;
import java.util.List;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "execution_node")
public class ExecutionNode extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "execution_id", nullable = false)
    public Long executionId;

    @Column(name = "node_index", nullable = false)
    public Integer nodeIndex;

    @Column(name = "pod_name", length = 253)
    public String podName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public NodeStatus status = NodeStatus.PENDING;

    @Column(name = "exit_code")
    public Integer exitCode;

    @Column(name = "jtl_path")
    public String jtlPath;

    @Column(name = "log_path")
    public String logPath;

    // ---- Motor worker-pool (Fase 7) ----

    /** Replica worker que reclamo este shard (null mientras esta PENDING). */
    @Column(name = "worker_id", length = 200)
    public String workerId;

    /** Ultimo latido recibido del worker; el reaper marca huerfano si envejece. */
    @Column(name = "heartbeat_at")
    public Instant heartbeatAt;

    /** Instante en que el shard empezo a ejecutar jmeter -n. */
    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    public static List<ExecutionNode> listByExecution(Long executionId) {
        return list("executionId = ?1 order by nodeIndex", executionId);
    }

    public static ExecutionNode findByExecutionAndIndex(Long executionId, int index) {
        return find("executionId = ?1 and nodeIndex = ?2", executionId, index).firstResult();
    }

    public static long countByExecutionAndStatus(Long executionId, NodeStatus status) {
        return count("executionId = ?1 and status = ?2", executionId, status);
    }

    /** Numero de shards que aun no han terminado (no estan en estado terminal). */
    public static long countNonTerminal(Long executionId) {
        return count("executionId = ?1 and status in ?2", executionId,
                List.of(NodeStatus.PENDING, NodeStatus.CLAIMED, NodeStatus.RUNNING));
    }
}
