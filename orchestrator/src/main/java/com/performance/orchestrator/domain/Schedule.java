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
 * Chequeo sintetico programado (Fase 8): un cron que, en cada disparo, ejecuta un
 * preset ligero por servicio y produce un reporte de estado consolidado.
 */
@Entity
@Table(name = "schedule")
public class Schedule extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "name", nullable = false)
    public String name;

    /** Cron UNIX de 5 campos (ej. "0 * * * *" = cada hora en punto). */
    @Column(name = "cron_expr", nullable = false, length = 120)
    public String cronExpr;

    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    @Column(name = "webhook_url", length = 1000)
    public String webhookUrl;

    /**
     * Servicios a chequear: cada elemento {@code {presetId, serviceName, p95MaxMs,
     * errorPctMax}}. Se guarda como JSON en NVARCHAR(MAX).
     */
    @Convert(converter = JsonListConverter.class)
    @Column(name = "services_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    public List<Map<String, Object>> services = new ArrayList<>();

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "last_run_at")
    public Instant lastRunAt;
}
