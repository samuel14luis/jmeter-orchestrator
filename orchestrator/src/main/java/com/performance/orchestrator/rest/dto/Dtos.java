package com.performance.orchestrator.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.ExecutionNode;
import com.performance.orchestrator.domain.Preset;
import com.performance.orchestrator.domain.Script;
import com.performance.orchestrator.domain.ScriptVersion;
import com.performance.orchestrator.domain.TestType;

/** Contenedor de DTOs (records) para la API REST. */
public final class Dtos {

    private Dtos() {
    }

    public record ScriptDto(Long id, String name, String description, String tags,
                            String createdBy, Instant createdAt, List<ScriptVersionDto> versions) {
        public static ScriptDto of(Script s, List<ScriptVersion> versions) {
            return new ScriptDto(s.id, s.name, s.description, s.tags, s.createdBy, s.createdAt,
                    versions == null ? null : versions.stream().map(ScriptVersionDto::of).toList());
        }
    }

    public record ScriptVersionDto(Long id, Long scriptId, Integer versionNumber,
                                   String checksumSha256, String notes, String createdBy, Instant createdAt) {
        public static ScriptVersionDto of(ScriptVersion v) {
            return new ScriptVersionDto(v.id, v.scriptId, v.versionNumber, v.checksumSha256,
                    v.notes, v.createdBy, v.createdAt);
        }
    }

    public record PresetRequest(String name, Long scriptId, TestType testType,
                                Map<String, Object> parameters, String targetEnv) {
    }

    public record PresetDto(Long id, String name, Long scriptId, TestType testType,
                            Map<String, Object> parameters, String targetEnv, String createdBy, Instant createdAt) {
        public static PresetDto of(Preset p) {
            return new PresetDto(p.id, p.name, p.scriptId, p.testType, p.parameters,
                    p.targetEnv, p.createdBy, p.createdAt);
        }
    }

    /** Lanzamiento ad hoc: version + parametros efectivos. */
    public record LaunchRequest(Long scriptVersionId, Map<String, Object> parameters) {
    }

    /** Overrides opcionales al lanzar desde un preset. */
    public record PresetLaunchRequest(Map<String, Object> overrides) {
    }

    public record ExecutionNodeDto(Long id, Integer nodeIndex, String podName, String status,
                                   Integer exitCode, String jtlPath, String logPath) {
        public static ExecutionNodeDto of(ExecutionNode n) {
            return new ExecutionNodeDto(n.id, n.nodeIndex, n.podName, n.status.name(),
                    n.exitCode, n.jtlPath, n.logPath);
        }
    }

    public record ExecutionDto(Long id, Long presetId, Long scriptVersionId, String status,
                               Integer nodes, Map<String, Object> parameters, Map<String, Object> summary,
                               String resultsPath, String launchedBy, String errorMessage,
                               Instant startedAt, Instant finishedAt, Instant createdAt,
                               List<ExecutionNodeDto> nodesDetail) {
        public static ExecutionDto of(Execution e, List<ExecutionNode> nodes) {
            return new ExecutionDto(e.id, e.presetId, e.scriptVersionId, e.status.name(), e.nodes,
                    e.effectiveParams, e.summary, e.resultsPath, e.launchedBy, e.errorMessage,
                    e.startedAt, e.finishedAt, e.createdAt,
                    nodes == null ? null : nodes.stream().map(ExecutionNodeDto::of).toList());
        }
    }
}
