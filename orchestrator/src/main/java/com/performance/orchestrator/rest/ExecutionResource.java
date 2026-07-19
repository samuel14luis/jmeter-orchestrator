package com.performance.orchestrator.rest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.jboss.resteasy.reactive.RestStreamElementType;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.ExecutionStatus;
import com.performance.orchestrator.execution.ExecutionEvents;
import com.performance.orchestrator.execution.ExecutionEvents.ExecutionEvent;
import com.performance.orchestrator.execution.ExecutionService;
import com.performance.orchestrator.rest.dto.Dtos.ExecutionDto;
import com.performance.orchestrator.rest.dto.Dtos.LaunchRequest;
import com.performance.orchestrator.storage.StorageService;

import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/executions")
@Produces(MediaType.APPLICATION_JSON)
public class ExecutionResource {

    @Inject
    ExecutionService executions;

    @Inject
    ExecutionEvents events;

    @Inject
    StorageService storage;

    /** Lanzar ad hoc: scriptVersion + parametros. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response launch(LaunchRequest req, @HeaderParam("X-User") String user) {
        if (req == null || req.scriptVersionId() == null) {
            throw ApiException.badRequest("scriptVersionId es obligatorio");
        }
        Execution exec = executions.launchAdHoc(req.scriptVersionId(), req.parameters(), userOr(user));
        return Response.status(Response.Status.ACCEPTED)
                .entity(ExecutionDto.of(exec, executions.nodesOf(exec.id))).build();
    }

    /** Historial filtrable. */
    @GET
    public List<ExecutionDto> search(@QueryParam("scriptVersion") Long scriptVersion,
                                     @QueryParam("estado") String estado,
                                     @QueryParam("desde") String desde,
                                     @QueryParam("hasta") String hasta) {
        ExecutionStatus status = estado == null || estado.isBlank() ? null
                : ExecutionStatus.valueOf(estado.toUpperCase());
        Instant from = parseInstant(desde);
        Instant to = parseInstant(hasta);
        return executions.search(scriptVersion, status, from, to).stream()
                .map(e -> ExecutionDto.of(e, null)).toList();
    }

    @GET
    @Path("/{id}")
    public ExecutionDto get(@PathParam("id") Long id) {
        Execution e = executions.get(id);
        return ExecutionDto.of(e, executions.nodesOf(id));
    }

    /** SSE de progreso en vivo. */
    @GET
    @Path("/{id}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ExecutionEvent> stream(@PathParam("id") Long id) {
        executions.get(id); // valida existencia
        return events.stream(id);
    }

    /** Reporte HTML consolidado (o resumen minimo si aun no hay reporte JMeter). */
    @GET
    @Path("/{id}/report")
    @Produces(MediaType.TEXT_HTML)
    public Response report(@PathParam("id") Long id) {
        Execution e = executions.get(id);
        String reportPath = e.resultsPath + "/report/index.html";
        if (storage.exists(reportPath)) {
            return Response.ok(storage.read(reportPath)).build();
        }
        return Response.ok(simpleReport(e).getBytes(StandardCharsets.UTF_8)).build();
    }

    @POST
    @Path("/{id}/cancel")
    public ExecutionDto cancel(@PathParam("id") Long id, @HeaderParam("X-User") String user) {
        Execution e = executions.cancel(id, userOr(user));
        return ExecutionDto.of(e, executions.nodesOf(id));
    }

    @POST
    @Path("/{id}/relaunch")
    public Response relaunch(@PathParam("id") Long id, @HeaderParam("X-User") String user) {
        Execution e = executions.relaunch(id, userOr(user));
        return Response.status(Response.Status.ACCEPTED)
                .entity(ExecutionDto.of(e, executions.nodesOf(e.id))).build();
    }

    // -------- helpers --------

    private static String simpleReport(Execution e) {
        String summary = e.summary == null ? "(sin resumen todavia)" : e.summary.toString();
        return """
                <!DOCTYPE html><html lang="es"><head><meta charset="utf-8">
                <title>Ejecucion %d</title>
                <style>body{font-family:sans-serif;margin:2rem}table{border-collapse:collapse}
                td,th{border:1px solid #ccc;padding:.4rem .8rem}</style></head><body>
                <h1>Ejecucion #%d</h1>
                <p>Estado: <b>%s</b> · Nodos: %d · Lanzada por: %s</p>
                <h2>Resumen de metricas</h2>
                <pre>%s</pre>
                <p><i>El dashboard HTML completo de JMeter (jmeter -g merged.jtl) se genera aparte
                y quedara disponible en esta misma URL cuando exista.</i></p>
                </body></html>
                """.formatted(e.id, e.id, e.status, e.nodes,
                e.launchedBy == null ? "-" : e.launchedBy, summary);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private static String userOr(String user) {
        return user == null || user.isBlank() ? "anonymous" : user;
    }
}
