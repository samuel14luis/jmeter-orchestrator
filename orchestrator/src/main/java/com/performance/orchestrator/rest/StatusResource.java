package com.performance.orchestrator.rest;

import java.util.List;

import com.performance.orchestrator.domain.Schedule;
import com.performance.orchestrator.domain.ScheduleRun;
import com.performance.orchestrator.rest.dto.ScheduleDtos.StatusRow;
import com.performance.orchestrator.schedule.ScheduleService;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Foto de salud (Fase 8): el último veredicto de cada schedule, servicio a servicio.
 * Alimenta la vista "Estado de servicios" de la UI.
 */
@Path("/api/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {

    @Inject
    ScheduleService schedules;

    @GET
    public List<StatusRow> status() {
        return schedules.list().stream()
                .map(s -> StatusRow.of(s, ScheduleRun.latestForSchedule(s.id)))
                .toList();
    }
}
