package com.performance.orchestrator.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.ScriptVersion;
import com.performance.orchestrator.storage.StorageService;
import com.performance.orchestrator.worker.WorkerDtos.ClaimRequest;
import com.performance.orchestrator.worker.WorkerDtos.HeartbeatRequest;
import com.performance.orchestrator.worker.WorkerDtos.HeartbeatResponse;
import com.performance.orchestrator.worker.WorkerDtos.ShardAssignment;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * API interna del protocolo worker-pool (Fase 7). Guardada por {@link WorkerAuthFilter}
 * (token de servicio). Los workers: reclaman un shard, laten mientras esperan y
 * ejecutan, y suben su JTL + log al terminar. Tambien descargan el script por HTTP,
 * de modo que no hace falta volumen compartido entre orquestador y workers.
 */
@Path("/internal")
public class WorkerApiResource {

    @Inject
    WorkerPoolService pool;

    @Inject
    StorageService storage;

    /** El worker pide trabajo. 200 con la asignacion, o 204 si no hay shards libres. */
    @POST
    @Path("/shards/claim")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response claim(ClaimRequest req) {
        return pool.claim(req)
                .<Response>map(a -> Response.ok(a).build())
                .orElseGet(() -> Response.noContent().build());
    }

    /** Latido durante espera/ejecucion; devuelve el start-gate y si hay que abortar. */
    @POST
    @Path("/shards/{execId}/{idx}/heartbeat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HeartbeatResponse heartbeat(@PathParam("execId") long execId,
                                       @PathParam("idx") int idx,
                                       HeartbeatRequest req) {
        return pool.heartbeat(execId, idx, req);
    }

    public static class ResultsForm {
        /** JTL del shard, comprimido con gzip (node-i.jtl.gz). */
        @RestForm("jtl")
        public FileUpload jtl;
        /** Log del shard (opcional, texto plano). */
        @RestForm("log")
        public FileUpload log;
        /** "true" si el shard fallo (jmeter devolvio error). */
        @RestForm("failed")
        public String failed;
    }

    /** El worker entrega su JTL (gzip) + log al terminar el shard. */
    @POST
    @Path("/shards/{execId}/{idx}/results")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response results(@PathParam("execId") long execId,
                            @PathParam("idx") int idx,
                            @HeaderParam("X-Worker-Id") String workerId,
                            ResultsForm form) {
        if (form == null || form.jtl == null) {
            throw ApiException.badRequest("Falta el archivo JTL (campo 'jtl')");
        }
        byte[] jtl = gunzipIfNeeded(readUpload(form.jtl));
        byte[] log = form.log != null ? readUpload(form.log) : null;
        boolean failed = form.failed != null && Boolean.parseBoolean(form.failed.trim());
        pool.submitResults(execId, idx, workerId, jtl, log, failed);
        return Response.ok(Map.of("stored", true)).build();
    }

    /** Descarga del contenido .jmx por id de version (el worker lo ejecuta con jmeter -n). */
    @GET
    @Path("/script-versions/{id}/content")
    @Produces(MediaType.APPLICATION_XML)
    public Response scriptContent(@PathParam("id") Long versionId) {
        ScriptVersion v = ScriptVersion.findById(versionId);
        if (v == null) {
            throw ApiException.notFound("Version de script no encontrada: " + versionId);
        }
        return Response.ok(storage.read(v.blobPath)).build();
    }

    // -------- helpers --------

    private static byte[] readUpload(FileUpload file) {
        try {
            return Files.readAllBytes(file.uploadedFile());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo subido", e);
        }
    }

    /** Descomprime si el contenido tiene cabecera gzip; si no, lo devuelve tal cual. */
    private static byte[] gunzipIfNeeded(byte[] data) {
        if (data == null || data.length < 2
                || (data[0] & 0xff) != 0x1f || (data[1] & 0xff) != 0x8b) {
            return data; // no es gzip
        }
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo descomprimir el JTL", e);
        }
    }
}
