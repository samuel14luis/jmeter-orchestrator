-- =====================================================================
--  V2 - Motor worker-pool por pull (Fase 7)
--  Anade el estado de coordinacion de shards para el modelo en el que N
--  replicas worker reclaman shards al orquestador (sin K8s Jobs en runtime).
--    · execution.start_at        : start-gate comun (se fija cuando todos los
--                                   shards estan reclamados)
--    · execution_node.worker_id  : replica que reclamo el shard
--    · execution_node.heartbeat_at : ultimo latido (para detectar huerfanos)
--    · execution_node.started_at : instante en que el shard empezo a ejecutar
--  Los estados nuevos de node (CLAIMED, CANCELLED) caben en el NVARCHAR(20)
--  existente y no hay CHECK que alterar.
-- =====================================================================

ALTER TABLE execution ADD start_at DATETIME2(3) NULL;

ALTER TABLE execution_node ADD
    worker_id    NVARCHAR(200) NULL,
    heartbeat_at DATETIME2(3)  NULL,
    started_at   DATETIME2(3)  NULL;

-- El claim busca shards PENDING; el reaper busca CLAIMED/RUNNING con latido viejo.
CREATE INDEX IX_execnode_status ON execution_node(status);
