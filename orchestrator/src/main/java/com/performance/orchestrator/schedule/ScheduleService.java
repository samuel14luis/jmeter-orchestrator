package com.performance.orchestrator.schedule;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.Execution;
import com.performance.orchestrator.domain.ExecutionStatus;
import com.performance.orchestrator.domain.Preset;
import com.performance.orchestrator.domain.Schedule;
import com.performance.orchestrator.domain.ScheduleRun;
import com.performance.orchestrator.domain.ServiceStatus;
import com.performance.orchestrator.execution.ExecutionService;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Monitoreo sintetico programado (Fase 8). Cada Schedule tiene un cron y una lista
 * de servicios (un preset ligero por servicio con umbrales p95/error%). En cada
 * disparo se ejecuta el preset de cada servicio (1 shard), se compara su resumen
 * contra los umbrales y se produce un veredicto consolidado OK|DEGRADED|FAILED,
 * con aviso por webhook si algo degrada o falla.
 *
 * El scheduler corre en la unica replica del orquestador, por lo que no necesita
 * eleccion de lider: un tick @Scheduled comprueba que schedules estan "vencidos".
 */
@ApplicationScoped
public class ScheduleService {

    private static final CronParser CRON =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    @Inject
    ExecutionService executions;

    @Inject
    WebhookSender webhook;

    @Inject
    ManagedExecutor executor;

    /** Tope de espera (s) a que un chequeo termine antes de darlo por FAILED. */
    @ConfigProperty(name = "orchestrator.schedule.check-timeout-seconds", defaultValue = "90")
    int checkTimeoutSeconds;

    // ---------------------------------------------------------------------
    //  CRUD
    // ---------------------------------------------------------------------

    public List<Schedule> list() {
        return Schedule.listAll(Sort.by("name"));
    }

    public Schedule get(Long id) {
        Schedule s = Schedule.findById(id);
        if (s == null) {
            throw ApiException.notFound("Schedule no encontrado: " + id);
        }
        return s;
    }

    public Schedule create(String name, String cronExpr, String webhookUrl, Boolean enabled,
                           List<Map<String, Object>> services, String user) {
        validateCron(cronExpr);
        validateServices(services);
        return QuarkusTransaction.requiringNew().call(() -> {
            Schedule s = new Schedule();
            s.name = name;
            s.cronExpr = cronExpr;
            s.webhookUrl = webhookUrl;
            s.enabled = enabled == null || enabled;
            s.services = services != null ? services : new ArrayList<>();
            s.createdBy = user;
            s.persist();
            return s;
        });
    }

    public Schedule update(Long id, String name, String cronExpr, String webhookUrl,
                           Boolean enabled, List<Map<String, Object>> services) {
        if (cronExpr != null) {
            validateCron(cronExpr);
        }
        if (services != null) {
            validateServices(services);
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            Schedule s = Schedule.findById(id);
            if (s == null) {
                throw ApiException.notFound("Schedule no encontrado: " + id);
            }
            if (name != null) {
                s.name = name;
            }
            if (cronExpr != null) {
                s.cronExpr = cronExpr;
            }
            if (webhookUrl != null) {
                s.webhookUrl = webhookUrl.isBlank() ? null : webhookUrl;
            }
            if (enabled != null) {
                s.enabled = enabled;
            }
            if (services != null) {
                s.services = services;
            }
            return s;
        });
    }

    public void delete(Long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            ScheduleRun.delete("scheduleId", id);
            if (!Schedule.deleteById(id)) {
                throw ApiException.notFound("Schedule no encontrado: " + id);
            }
        });
    }

    public List<ScheduleRun> runsOf(Long scheduleId) {
        get(scheduleId);
        return ScheduleRun.listBySchedule(scheduleId);
    }

    // ---------------------------------------------------------------------
    //  Disparo manual (run-now) y tick cron
    // ---------------------------------------------------------------------

    /** Crea la corrida (RUNNING) y lanza el chequeo en segundo plano; devuelve su id. */
    public Long triggerNow(Long scheduleId) {
        get(scheduleId); // valida existencia
        Long runId = createRun(scheduleId);
        executor.execute(() -> executeRun(runId, scheduleId));
        return runId;
    }

    @Scheduled(every = "60s", concurrentExecution = ConcurrentExecution.SKIP, delayed = "20s")
    void tick() {
        List<Long> due;
        try {
            due = QuarkusTransaction.requiringNew().call(() -> {
                Instant now = Instant.now();
                List<Long> ids = new ArrayList<>();
                for (Schedule s : Schedule.<Schedule>list("enabled = ?1", true)) {
                    if (isDue(s, now)) {
                        s.lastRunAt = now; // "claim" para no re-disparar en el siguiente tick
                        ids.add(s.id);
                    }
                }
                return ids;
            });
        } catch (Exception e) {
            Log.warnf("No se pudieron evaluar los schedules: %s", e.getMessage());
            return;
        }
        for (Long id : due) {
            Long runId = createRun(id);
            executor.execute(() -> executeRun(runId, id));
        }
    }

    private boolean isDue(Schedule s, Instant now) {
        try {
            ExecutionTime et = ExecutionTime.forCron(CRON.parse(s.cronExpr));
            Instant from = s.lastRunAt != null ? s.lastRunAt : s.createdAt;
            Optional<ZonedDateTime> next = et.nextExecution(from.atZone(ZoneOffset.UTC));
            return next.isPresent() && !next.get().toInstant().isAfter(now);
        } catch (Exception e) {
            Log.warnf("Cron invalido en el schedule %d ('%s'): %s", s.id, s.cronExpr, e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------------
    //  Ejecucion de una corrida
    // ---------------------------------------------------------------------

    private Long createRun(Long scheduleId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ScheduleRun r = new ScheduleRun();
            r.scheduleId = scheduleId;
            r.startedAt = Instant.now();
            r.overallStatus = "RUNNING";
            r.persist();
            return r.id;
        });
    }

    /** Ejecuta el chequeo de cada servicio, consolida el veredicto y avisa por webhook. */
    void executeRun(Long runId, Long scheduleId) {
        Schedule sch = QuarkusTransaction.requiringNew().call(() -> Schedule.findById(scheduleId));
        if (sch == null) {
            return;
        }
        List<Map<String, Object>> services = sch.services != null ? sch.services : List.of();
        List<Map<String, Object>> detail = new ArrayList<>();
        ServiceStatus overall = ServiceStatus.OK;

        for (Map<String, Object> svc : services) {
            Map<String, Object> r = new LinkedHashMap<>();
            long presetId = asLong(svc.get("presetId"), -1);
            String svcName = asString(svc.get("serviceName"), "preset#" + presetId);
            double p95Max = asDouble(svc.get("p95MaxMs"), Double.MAX_VALUE);
            double errMax = asDouble(svc.get("errorPctMax"), 100.0);
            r.put("serviceName", svcName);
            r.put("presetId", presetId);
            try {
                ServiceStatus vs = checkService(presetId, p95Max, errMax, r);
                r.put("status", vs.name());
                overall = ServiceStatus.worst(overall, vs);
            } catch (Exception e) {
                r.put("status", ServiceStatus.FAILED.name());
                r.put("message", "error interno: " + e.getMessage());
                overall = ServiceStatus.FAILED;
                Log.warnf(e, "Fallo chequeando el servicio %s (preset %d)", svcName, presetId);
            }
            detail.add(r);
        }

        final ServiceStatus finalOverall = overall;
        QuarkusTransaction.requiringNew().run(() -> {
            ScheduleRun run = ScheduleRun.findById(runId);
            if (run != null) {
                run.overallStatus = finalOverall.name();
                run.detail = detail;
                run.finishedAt = Instant.now();
            }
        });
        Log.infof("Schedule %d corrida %d -> %s (%d servicios)", scheduleId, runId, finalOverall, detail.size());

        if (finalOverall != ServiceStatus.OK && sch.webhookUrl != null && !sch.webhookUrl.isBlank()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schedule", sch.name);
            payload.put("scheduleId", scheduleId);
            payload.put("runId", runId);
            payload.put("overallStatus", finalOverall.name());
            payload.put("at", Instant.now().toString());
            payload.put("services", detail);
            webhook.send(sch.webhookUrl, payload);
        }
    }

    /** Lanza el preset del servicio (1 shard), espera y calcula el veredicto. */
    private ServiceStatus checkService(long presetId, double p95Max, double errMax, Map<String, Object> r) {
        Preset preset = QuarkusTransaction.requiringNew().call(() -> Preset.findById(presetId));
        if (preset == null) {
            r.put("message", "preset no existe");
            return ServiceStatus.FAILED;
        }
        // Un chequeo es siempre de un solo shard; el resto de parametros los define el preset.
        Execution exec = executions.launchFromPreset(preset, Map.of("nodes", 1), "scheduler");
        r.put("executionId", exec.id);

        Execution done = pollUntilTerminal(exec.id);
        return verdict(done, p95Max, errMax, r);
    }

    private Execution pollUntilTerminal(Long executionId) {
        long deadline = System.currentTimeMillis() + checkTimeoutSeconds * 1000L;
        Execution e = readExecution(executionId);
        while (e != null && !e.status.isTerminal() && System.currentTimeMillis() < deadline) {
            sleep(2000);
            e = readExecution(executionId);
        }
        return e;
    }

    private Execution readExecution(Long executionId) {
        return QuarkusTransaction.requiringNew().call(() -> Execution.findById(executionId));
    }

    private ServiceStatus verdict(Execution exec, double p95Max, double errMax, Map<String, Object> r) {
        if (exec == null || exec.status != ExecutionStatus.COMPLETED) {
            r.put("message", "ejecucion " + (exec == null ? "desaparecida" : exec.status)
                    + " (no completo en " + checkTimeoutSeconds + "s)");
            return ServiceStatus.FAILED;
        }
        Map<String, Object> summary = exec.summary != null ? exec.summary : Map.of();
        long samples = asLong(summary.get("samples"), 0);
        double errorPct = asDouble(summary.get("errorPct"), 0);
        double p95 = asDouble(summary.get("p95"), 0);
        r.put("samples", samples);
        r.put("errorPct", errorPct);
        r.put("p95", p95);
        if (samples == 0) {
            r.put("message", "sin muestras (target inalcanzable)");
            return ServiceStatus.FAILED;
        }
        if (errorPct > errMax) {
            r.put("message", "error% " + errorPct + " > umbral " + errMax);
            return ServiceStatus.FAILED;
        }
        if (p95 > p95Max) {
            r.put("message", "p95 " + p95 + "ms > umbral " + p95Max + "ms");
            return ServiceStatus.DEGRADED;
        }
        return ServiceStatus.OK;
    }

    // ---------------------------------------------------------------------
    //  Validacion y helpers
    // ---------------------------------------------------------------------

    private void validateCron(String cronExpr) {
        if (cronExpr == null || cronExpr.isBlank()) {
            throw ApiException.badRequest("cronExpr es obligatorio");
        }
        try {
            CRON.parse(cronExpr);
        } catch (Exception e) {
            throw ApiException.badRequest("Cron UNIX invalido ('" + cronExpr + "'): " + e.getMessage());
        }
    }

    private void validateServices(List<Map<String, Object>> services) {
        if (services == null || services.isEmpty()) {
            throw ApiException.badRequest("Debe indicar al menos un servicio (preset)");
        }
        for (Map<String, Object> svc : services) {
            if (asLong(svc.get("presetId"), -1) < 0) {
                throw ApiException.badRequest("Cada servicio requiere un presetId valido");
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long asLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? def : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? def : Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String asString(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }
}
