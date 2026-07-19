-- =====================================================================
--  V3 - Monitoreo sintetico programado (Fase 8)
--  Schedules (cron) que ejecutan un preset ligero por servicio y producen un
--  reporte de estado consolidado (OK|DEGRADED|FAILED) con aviso por webhook.
-- =====================================================================

CREATE TABLE schedule (
    id            BIGINT         IDENTITY(1,1) NOT NULL PRIMARY KEY,
    name          NVARCHAR(200)  NOT NULL,
    cron_expr     NVARCHAR(120)  NOT NULL,          -- cron UNIX de 5 campos (ej. '0 * * * *')
    enabled       BIT            NOT NULL CONSTRAINT DF_schedule_enabled DEFAULT 1,
    webhook_url   NVARCHAR(1000) NULL,
    -- lista de servicios: [{presetId, serviceName, p95MaxMs, errorPctMax}, ...]
    services_json NVARCHAR(MAX)  NOT NULL CONSTRAINT DF_schedule_services DEFAULT N'[]',
    created_by    NVARCHAR(200)  NULL,
    created_at    DATETIME2(3)   NOT NULL CONSTRAINT DF_schedule_created_at DEFAULT SYSUTCDATETIME(),
    last_run_at   DATETIME2(3)   NULL,
    CONSTRAINT CK_schedule_services_json CHECK (ISJSON(services_json) = 1)
);
CREATE INDEX IX_schedule_enabled ON schedule(enabled);

CREATE TABLE schedule_run (
    id             BIGINT         IDENTITY(1,1) NOT NULL PRIMARY KEY,
    schedule_id    BIGINT         NOT NULL,
    started_at     DATETIME2(3)   NOT NULL CONSTRAINT DF_schedulerun_started DEFAULT SYSUTCDATETIME(),
    finished_at    DATETIME2(3)   NULL,
    overall_status NVARCHAR(20)   NOT NULL,          -- RUNNING|OK|DEGRADED|FAILED
    -- detalle por servicio: [{serviceName, presetId, executionId, status, p95, errorPct, samples, message}, ...]
    detail_json    NVARCHAR(MAX)  NOT NULL CONSTRAINT DF_schedulerun_detail DEFAULT N'[]',
    CONSTRAINT FK_schedulerun_schedule FOREIGN KEY (schedule_id) REFERENCES schedule(id),
    CONSTRAINT CK_schedulerun_detail_json CHECK (ISJSON(detail_json) = 1)
);
CREATE INDEX IX_schedulerun_schedule ON schedule_run(schedule_id, started_at DESC);
