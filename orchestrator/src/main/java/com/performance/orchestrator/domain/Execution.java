package com.performance.orchestrator.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "execution")
public class Execution extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "preset_id")
    public Long presetId;

    @Column(name = "script_version_id", nullable = false)
    public Long scriptVersionId;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "effective_params_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    public Map<String, Object> effectiveParams = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public ExecutionStatus status = ExecutionStatus.PENDING;

    @Column(name = "nodes", nullable = false)
    public Integer nodes = 1;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "finished_at")
    public Instant finishedAt;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "summary_json", columnDefinition = "NVARCHAR(MAX)")
    public Map<String, Object> summary;

    @Column(name = "results_path")
    public String resultsPath;

    @Column(name = "launched_by")
    public String launchedBy;

    @Column(name = "error_message", length = 2000)
    public String errorMessage;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
