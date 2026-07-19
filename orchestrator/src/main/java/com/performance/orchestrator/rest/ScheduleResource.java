package com.performance.orchestrator.rest;

import java.util.List;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.Schedule;
import com.performance.orchestrator.domain.ScheduleRun;
import com.performance.orchestrator.rest.dto.ScheduleDtos.ScheduleDto;
import com.performance.orchestrator.rest.dto.ScheduleDtos.ScheduleRequest;
import com.performance.orchestrator.rest.dto.ScheduleDtos.ScheduleRunDto;
import com.performance.orchestrator.schedule.ScheduleService;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * API de chequeos sinteticos programados (Fase 8): CRUD de schedules, disparo
 * manual, histórico de corridas y foto de estado de todos los servicios.
 */
@Path("/api/schedules")
@Produces(MediaType.APPLICATION_JSON)
public class ScheduleResource {

    @Inject
    ScheduleService schedules;

    @GET
    public List<ScheduleDto> list() {
        return schedules.list().stream()
                .map(s -> ScheduleDto.of(s, ScheduleRun.latestForSchedule(s.id)))
                .toList();
    }

    @GET
    @Path("/{id}")
    public ScheduleDto get(@PathParam("id") Long id) {
        Schedule s = schedules.get(id);
        return ScheduleDto.of(s, ScheduleRun.latestForSchedule(id));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(ScheduleRequest req, @HeaderParam("X-User") String user) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("El nombre del schedule es obligatorio");
        }
        Schedule s = schedules.create(req.name(), req.cronExpr(), req.webhookUrl(),
                req.enabled(), req.services(), userOr(user));
        return Response.status(Response.Status.CREATED).entity(ScheduleDto.of(s, null)).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public ScheduleDto update(@PathParam("id") Long id, ScheduleRequest req) {
        Schedule s = schedules.update(id, req.name(), req.cronExpr(), req.webhookUrl(),
                req.enabled(), req.services());
        return ScheduleDto.of(s, ScheduleRun.latestForSchedule(id));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        schedules.delete(id);
        return Response.noContent().build();
    }

    /** Dispara el chequeo ya mismo; devuelve la corrida creada (en estado RUNNING). */
    @POST
    @Path("/{id}/run-now")
    public Response runNow(@PathParam("id") Long id) {
        Long runId = schedules.triggerNow(id);
        return Response.status(Response.Status.ACCEPTED)
                .entity(ScheduleRunDto.of(ScheduleRun.findById(runId))).build();
    }

    @GET
    @Path("/{id}/runs")
    public List<ScheduleRunDto> runs(@PathParam("id") Long id) {
        return schedules.runsOf(id).stream().map(ScheduleRunDto::of).toList();
    }

    private static String userOr(String user) {
        return user == null || user.isBlank() ? "anonymous" : user;
    }
}
