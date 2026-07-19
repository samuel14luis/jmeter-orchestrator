package com.performance.orchestrator.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.performance.orchestrator.domain.Schedule;
import com.performance.orchestrator.domain.ScheduleRun;

/** DTOs de la API de chequeos sinteticos programados (Fase 8). */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    /** Alta/edicion. `services` = lista de {presetId, serviceName?, p95MaxMs?, errorPctMax?}. */
    public record ScheduleRequest(String name, String cronExpr, String webhookUrl,
                                  Boolean enabled, List<Map<String, Object>> services) {
    }

    public record ScheduleDto(Long id, String name, String cronExpr, boolean enabled,
                              String webhookUrl, List<Map<String, Object>> services,
                              String createdBy, Instant createdAt, Instant lastRunAt,
                              ScheduleRunDto lastRun) {
        public static ScheduleDto of(Schedule s, ScheduleRun lastRun) {
            return new ScheduleDto(s.id, s.name, s.cronExpr, s.enabled, s.webhookUrl, s.services,
                    s.createdBy, s.createdAt, s.lastRunAt,
                    lastRun == null ? null : ScheduleRunDto.of(lastRun));
        }
    }

    public record ScheduleRunDto(Long id, Long scheduleId, Instant startedAt, Instant finishedAt,
                                 String overallStatus, List<Map<String, Object>> detail) {
        public static ScheduleRunDto of(ScheduleRun r) {
            return new ScheduleRunDto(r.id, r.scheduleId, r.startedAt, r.finishedAt,
                    r.overallStatus, r.detail);
        }
    }

    /** Fila de la vista de estado: el ultimo veredicto de un schedule. */
    public record StatusRow(Long scheduleId, String scheduleName, String overallStatus,
                            Instant at, List<Map<String, Object>> services) {
        public static StatusRow of(Schedule s, ScheduleRun lastRun) {
            return new StatusRow(s.id, s.name,
                    lastRun == null ? "UNKNOWN" : lastRun.overallStatus,
                    lastRun == null ? null : lastRun.startedAt,
                    lastRun == null ? List.of() : lastRun.detail);
        }
    }
}
