-- =====================================================================
--  V1 - Esquema inicial (Microsoft SQL Server / T-SQL)
--  Nota: SQL Server no tiene JSONB; los campos JSON se guardan como
--  NVARCHAR(MAX) y se validan/consultan con las funciones ISJSON/JSON_VALUE.
-- =====================================================================

-- ---------------------------------------------------------------------
--  Script
-- ---------------------------------------------------------------------
CREATE TABLE script (
    id              BIGINT           IDENTITY(1,1) NOT NULL PRIMARY KEY,
    name            NVARCHAR(200)    NOT NULL,
    description     NVARCHAR(1000)   NULL,
    tags            NVARCHAR(500)    NULL,
    created_by      NVARCHAR(200)    NULL,
    created_at      DATETIME2(3)     NOT NULL CONSTRAINT DF_script_created_at DEFAULT SYSUTCDATETIME()
);
CREATE UNIQUE INDEX UX_script_name ON script(name);

-- ---------------------------------------------------------------------
--  ScriptVersion (inmutable: cada guardado crea version nueva)
-- ---------------------------------------------------------------------
CREATE TABLE script_version (
    id              BIGINT           IDENTITY(1,1) NOT NULL PRIMARY KEY,
    script_id       BIGINT           NOT NULL,
    version_number  INT              NOT NULL,
    blob_path       NVARCHAR(1000)   NOT NULL,
    checksum_sha256 CHAR(64)         NOT NULL,
    notes           NVARCHAR(1000)   NULL,
    created_by      NVARCHAR(200)    NULL,
    created_at      DATETIME2(3)     NOT NULL CONSTRAINT DF_scriptversion_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_scriptversion_script FOREIGN KEY (script_id) REFERENCES script(id)
);
CREATE UNIQUE INDEX UX_scriptversion_script_number ON script_version(script_id, version_number);

-- ---------------------------------------------------------------------
--  Preset
-- ---------------------------------------------------------------------
CREATE TABLE preset (
    id              BIGINT           IDENTITY(1,1) NOT NULL PRIMARY KEY,
    name            NVARCHAR(200)    NOT NULL,
    script_id       BIGINT           NOT NULL,
    test_type       NVARCHAR(20)     NOT NULL,   -- CARGA | ESTRES | PICO | RESISTENCIA
    parameters_json NVARCHAR(MAX)    NOT NULL CONSTRAINT DF_preset_params DEFAULT N'{}',
    target_env      NVARCHAR(60)     NULL,
    created_by      NVARCHAR(200)    NULL,
    created_at      DATETIME2(3)     NOT NULL CONSTRAINT DF_preset_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_preset_script FOREIGN KEY (script_id) REFERENCES script(id),
    CONSTRAINT CK_preset_params_json CHECK (ISJSON(parameters_json) = 1)
);
CREATE INDEX IX_preset_script ON preset(script_id);

-- ---------------------------------------------------------------------
--  Execution
-- ---------------------------------------------------------------------
CREATE TABLE execution (
    id                  BIGINT       IDENTITY(1,1) NOT NULL PRIMARY KEY,
    preset_id           BIGINT       NULL,
    script_version_id   BIGINT       NOT NULL,
    effective_params_json NVARCHAR(MAX) NOT NULL CONSTRAINT DF_execution_params DEFAULT N'{}',
    status              NVARCHAR(20) NOT NULL,   -- PENDING|RUNNING|AGGREGATING|COMPLETED|FAILED|CANCELLED
    nodes               INT          NOT NULL CONSTRAINT DF_execution_nodes DEFAULT 1,
    started_at          DATETIME2(3) NULL,
    finished_at         DATETIME2(3) NULL,
    summary_json        NVARCHAR(MAX) NULL,       -- tps, p90, p95, p99, errorPct, samples
    results_path        NVARCHAR(1000) NULL,
    launched_by         NVARCHAR(200) NULL,
    error_message       NVARCHAR(2000) NULL,
    created_at          DATETIME2(3) NOT NULL CONSTRAINT DF_execution_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_execution_preset  FOREIGN KEY (preset_id)         REFERENCES preset(id),
    CONSTRAINT FK_execution_version FOREIGN KEY (script_version_id) REFERENCES script_version(id),
    CONSTRAINT CK_execution_params_json CHECK (ISJSON(effective_params_json) = 1),
    CONSTRAINT CK_execution_summary_json CHECK (summary_json IS NULL OR ISJSON(summary_json) = 1)
);
CREATE INDEX IX_execution_status  ON execution(status);
CREATE INDEX IX_execution_version ON execution(script_version_id);
CREATE INDEX IX_execution_created ON execution(created_at);

-- ---------------------------------------------------------------------
--  ExecutionNode (un registro por pod/shard)
-- ---------------------------------------------------------------------
CREATE TABLE execution_node (
    id              BIGINT           IDENTITY(1,1) NOT NULL PRIMARY KEY,
    execution_id    BIGINT           NOT NULL,
    node_index      INT              NOT NULL,
    pod_name        NVARCHAR(253)    NULL,
    status          NVARCHAR(20)     NOT NULL,   -- PENDING|RUNNING|SUCCEEDED|FAILED
    exit_code       INT              NULL,
    jtl_path        NVARCHAR(1000)   NULL,
    log_path        NVARCHAR(1000)   NULL,
    updated_at      DATETIME2(3)     NOT NULL CONSTRAINT DF_execnode_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT FK_execnode_execution FOREIGN KEY (execution_id) REFERENCES execution(id)
);
CREATE UNIQUE INDEX UX_execnode_exec_index ON execution_node(execution_id, node_index);

-- ---------------------------------------------------------------------
--  Auditoria (banca): cada accion sensible queda registrada
-- ---------------------------------------------------------------------
CREATE TABLE audit_event (
    id           BIGINT          IDENTITY(1,1) NOT NULL PRIMARY KEY,
    at           DATETIME2(3)    NOT NULL CONSTRAINT DF_audit_at DEFAULT SYSUTCDATETIME(),
    actor        NVARCHAR(200)   NULL,
    action       NVARCHAR(60)    NOT NULL,     -- LAUNCH|CANCEL|RELAUNCH|UPLOAD|EDIT|PRETEST
    entity_type  NVARCHAR(60)    NOT NULL,
    entity_id    BIGINT          NULL,
    detail       NVARCHAR(MAX)   NULL
);
CREATE INDEX IX_audit_at ON audit_event(at);
