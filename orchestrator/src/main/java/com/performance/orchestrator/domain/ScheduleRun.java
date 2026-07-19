package com.performance.orchestrator.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Corrida de un {@link Schedule} (Fase 8): el veredicto consolidado y el detalle
 * por servicio de un disparo del chequeo.
 */
@Entity
@Table(name = "schedule_run")
public class ScheduleRun extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "schedule_id", nullable = false)
    public Long scheduleId;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    public Instant finishedAt;

    /** RUNNING mientras corre; luego OK | DEGRADED | FAILED (peor de los servicios). */
    @Column(name = "overall_status", nullable = false, length = 20)
    public String overallStatus = "RUNNING";

    /**
     * Detalle por servicio: {@code {serviceName, presetId, executionId, status,
     * p95, errorPct, samples, message}}.
     */
    @Convert(converter = JsonListConverter.class)
    @Column(name = "detail_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    public List<Map<String, Object>> detail = new ArrayList<>();

    public static List<ScheduleRun> listBySchedule(Long scheduleId) {
        return list("scheduleId = ?1 order by startedAt desc", scheduleId);
    }

    public static ScheduleRun latestForSchedule(Long scheduleId) {
        return find("scheduleId = ?1 order by startedAt desc", scheduleId).firstResult();
    }
}
