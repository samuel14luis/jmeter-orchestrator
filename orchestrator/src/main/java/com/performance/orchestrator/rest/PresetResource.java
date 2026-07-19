package com.performance.orchestrator.rest;

import java.util.List;

import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.Preset;
import com.performance.orchestrator.execution.ExecutionService;
import com.performance.orchestrator.preset.PresetService;
import com.performance.orchestrator.rest.dto.Dtos.ExecutionDto;
import com.performance.orchestrator.rest.dto.Dtos.PresetDto;
import com.performance.orchestrator.rest.dto.Dtos.PresetLaunchRequest;
import com.performance.orchestrator.rest.dto.Dtos.PresetRequest;

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

@Path("/api/presets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PresetResource {

    @Inject
    PresetService presets;

    @Inject
    ExecutionService executions;

    @GET
    public List<PresetDto> list() {
        return presets.list().stream().map(PresetDto::of).toList();
    }

    @GET
    @Path("/{id}")
    public PresetDto get(@PathParam("id") Long id) {
        return PresetDto.of(presets.get(id));
    }

    @POST
    public Response create(PresetRequest req, @HeaderParam("X-User") String user) {
        Preset p = presets.create(req.name(), req.scriptId(), req.testType(),
                req.parameters(), req.targetEnv(), userOr(user));
        return Response.status(Response.Status.CREATED).entity(PresetDto.of(p)).build();
    }

    @PUT
    @Path("/{id}")
    public PresetDto update(@PathParam("id") Long id, PresetRequest req) {
        return PresetDto.of(presets.update(id, req.name(), req.testType(), req.parameters(), req.targetEnv()));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        presets.delete(id);
        return Response.noContent().build();
    }

    /** Lanzar en un clic (seccion 7 del plan). */
    @POST
    @Path("/{id}/launch")
    public Response launch(@PathParam("id") Long id, PresetLaunchRequest req, @HeaderParam("X-User") String user) {
        Preset preset = presets.get(id);
        Execution exec = executions.launchFromPreset(preset,
                req == null ? null : req.overrides(), userOr(user));
        return Response.status(Response.Status.ACCEPTED)
                .entity(ExecutionDto.of(exec, executions.nodesOf(exec.id))).build();
    }

    private static String userOr(String user) {
        return user == null || user.isBlank() ? "anonymous" : user;
    }
}
