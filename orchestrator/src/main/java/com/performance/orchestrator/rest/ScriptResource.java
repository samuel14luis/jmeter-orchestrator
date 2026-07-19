package com.performance.orchestrator.rest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.ScriptVersion;
import com.performance.orchestrator.execution.ExecutionService;
import com.performance.orchestrator.rest.dto.Dtos.ExecutionDto;
import com.performance.orchestrator.rest.dto.Dtos.ScriptDto;
import com.performance.orchestrator.rest.dto.Dtos.ScriptVersionDto;
import com.performance.orchestrator.scripts.ScriptService;
import com.performance.orchestrator.scripts.ScriptValidationResult;

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

@Path("/api/scripts")
@Produces(MediaType.APPLICATION_JSON)
public class ScriptResource {

    @Inject
    ScriptService scripts;

    @Inject
    ExecutionService executions;

    // -------- listado / detalle --------

    @GET
    public List<ScriptDto> list() {
        return scripts.listScripts().stream().map(s -> ScriptDto.of(s, null)).toList();
    }

    @GET
    @Path("/{id}")
    public ScriptDto detail(@PathParam("id") Long id) {
        return ScriptDto.of(scripts.getScript(id), scripts.listVersions(id));
    }

    // -------- subida (multipart) --------

    public static class UploadForm {
        @RestForm("name")
        public String name;
        @RestForm("description")
        public String description;
        @RestForm("tags")
        public String tags;
        @RestForm("file")
        public FileUpload file;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(UploadForm form, @HeaderParam("X-User") String user) {
        if (form.file == null) {
            throw ApiException.badRequest("Falta el archivo .jmx (campo 'file')");
        }
        String name = form.name != null ? form.name : form.file.fileName();
        byte[] content = readUpload(form.file);
        ScriptVersion v = scripts.createScript(name, form.description, form.tags, userOr(user), content);
        return Response.status(Response.Status.CREATED).entity(ScriptVersionDto.of(v)).build();
    }

    // -------- versiones --------

    @GET
    @Path("/{id}/versions")
    public List<ScriptVersionDto> versions(@PathParam("id") Long id) {
        return scripts.listVersions(id).stream().map(ScriptVersionDto::of).toList();
    }

    @POST
    @Path("/{id}/versions")
    @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN})
    public Response saveVersion(@PathParam("id") Long id, String xmlContent,
                                @QueryParam("notes") String notes, @HeaderParam("X-User") String user) {
        byte[] content = xmlContent == null ? new byte[0] : xmlContent.getBytes(StandardCharsets.UTF_8);
        ScriptVersion v = scripts.saveNewVersion(id, content, notes, userOr(user));
        return Response.status(Response.Status.CREATED).entity(ScriptVersionDto.of(v)).build();
    }

    @GET
    @Path("/{id}/versions/{v}/content")
    @Produces(MediaType.APPLICATION_XML)
    public Response content(@PathParam("id") Long id, @PathParam("v") int version) {
        byte[] content = scripts.getVersionContent(id, version);
        return Response.ok(content).build();
    }

    @POST
    @Path("/{id}/versions/{v}/validate")
    public ScriptValidationResult validate(@PathParam("id") Long id, @PathParam("v") int version) {
        return scripts.validate(scripts.getVersionContent(id, version));
    }

    // -------- pre-test (dry run) --------

    @POST
    @Path("/{id}/versions/{v}/pretest")
    public ExecutionDto pretest(@PathParam("id") Long id, @PathParam("v") int version,
                                @HeaderParam("X-User") String user) {
        ScriptVersion sv = scripts.getVersion(id, version);
        // 1 pod, 1 hilo, 30 s (seccion 8.2 del plan)
        Map<String, Object> params = Map.of(
                "threads", 1, "rampUp", 1, "duration", 30, "nodes", 1, "pretest", true);
        Execution exec = executions.launchAdHoc(sv.id, params, userOr(user));
        return ExecutionDto.of(exec, executions.nodesOf(exec.id));
    }

    // -------- helpers --------

    private static byte[] readUpload(FileUpload file) {
        try {
            return Files.readAllBytes(file.uploadedFile());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo subido", e);
        }
    }

    private static String userOr(String user) {
        return user == null || user.isBlank() ? "anonymous" : user;
    }
}
