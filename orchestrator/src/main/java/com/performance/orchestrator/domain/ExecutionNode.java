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

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    public static List<ExecutionNode> listByExecution(Long executionId) {
        return list("executionId = ?1 order by nodeIndex", executionId);
    }

    public static ExecutionNode findByExecutionAndIndex(Long executionId, int index) {
        return find("executionId = ?1 and nodeIndex = ?2", executionId, index).firstResult();
    }
}
