# CLAUDE.md — JMeter Orchestrator

Guía para retomar el trabajo en este repositorio. Orquestador de pruebas de
rendimiento JMeter sobre una plataforma de microservicios. Implementa [PLAN.md](PLAN.md)
**con dos salvedades clave respecto al borrador original**: la base de datos es
**Microsoft SQL Server** (no PostgreSQL) y la ejecución distribuida es por
**worker-pool por pull** (no K8s Jobs/RMI).

## Stack
Quarkus 3.15.1 LTS · Java 21 · SQL Server · JMeter 5.6.x · Flyway (T-SQL) ·
Hibernate ORM Panache. **Sin dependencia de Kubernetes en runtime** (Fabric8 retirado
en la Fase 7).

## Estado actual (2026-07-19) — Fases 0–7 HECHAS

- **Repo git propio** en la carpeta del proyecto (rama `main`, con commits). Ya NO es
  el home `C:\Users\User`. Commitear solo lo del proyecto; nunca `git add` del home.
- **Compila y testea**: `mvn -DskipTests package` OK; `ExecParamsTest` 3/3.
- **Motor de ejecución: worker-pool por pull (Fase 7), único motor.** El orquestador
  NO usa el API de Kubernetes. Flujo: `POST /api/executions` crea la ejecución en
  `PENDING` con N shards en BD; las réplicas worker reclaman shards por pull, esperan
  un start-gate común, corren `jmeter -n`, laten y suben su `jtl.gz`+log por HTTP; el
  orquestador fusiona y cierra (`AGGREGATING` → `COMPLETED/FAILED`).
- **UI web** servida en `/` (`orchestrator/src/main/resources/META-INF/resources/index.html`):
  scripts (subida, **editor in-app con versionado**, validación, pre-test), presets
  (CRUD + lanzar), ejecuciones (ad-hoc, historial filtrable, detalle, cancelar,
  relanzar) y **progreso SSE en vivo con barra de shards + tiles**.
- **Validado end-to-end en minikube con carga JMeter real**: `nodes=2` contra
  `target-api` → COMPLETED, 0 errores, y la **suma de RPS es exacta** (muestras de
  node-0 + node-1 = muestras del resumen agregado). Sin Grafana/InfluxDB.
- **Constraints del entorno productivo** (decididos con el usuario, ver PLAN §0):
  1) el orquestador NO debe consumir métricas de Grafana/InfluxDB (ese stack es
  demo local); 2) despliegue como microservicios (orchestrator 1 réplica + worker
  N réplicas), sin API de K8s en runtime.
- **Fix histórico vigente** (application.properties): los `Instant` se mapeaban a
  `datetimeoffset` por defecto en Hibernate 6 y chocaban con Flyway (`DATETIME2`).
  Se fuerza `hibernate.type.preferred_instant_jdbc_type=TIMESTAMP`. No revertir.
- **Ruido cosmético**: Hibernate avisa post-boot de `nvarchar`/`char` vs `varchar`
  (Flyway usa `NVARCHAR`/`CHAR(64)` a propósito). No bloquea nada.

## Layout
```
orchestrator/     Servicio Quarkus (código Java + pom.xml + application.properties)
  src/main/java/com/performance/orchestrator/
    domain/       Entidades Panache, enums, JsonMapConverter (JSON->NVARCHAR(MAX))
    scripts/      Subida, versionado, validación de .jmx (ScriptValidator)
    preset/       PresetService (CRUD)
    execution/    ExecutionService (QuarkusTransaction), GuardrailsService,
                  ExecutionReconciler (@Scheduled: cierra AGGREGATING), ExecutionEvents
                  (SSE), ExecParams
    worker/       Motor worker-pool: WorkerPoolService (claim/start-gate/heartbeat/
                  resultados/reaper), WorkerApiResource (/internal/*), WorkerAuthFilter
                  (token), WorkerDtos
    results/      JtlAggregator (fusión JTL + percentiles), MetricsSummary
    storage/      StorageService + LocalStorageService (volumen local del orquestador)
    rest/         ScriptResource, PresetResource, ExecutionResource (+SSE), dto/Dtos
    common/       ApiException(+Mapper), Checksums
  src/main/resources/
    META-INF/resources/index.html          UI web (single-page)
    application.properties
    db/migration/V1__initial_schema.sql    (T-SQL: IDENTITY, NVARCHAR, DATETIME2, ISJSON)
    db/migration/V2__worker_pool.sql       (start_at, worker_id, heartbeat_at, started_at)
worker/           Dockerfile (JMeter 5.6.3 + plugins + jq) + agent.sh (agente pull)
deploy/pool/      Topología worker-pool: 00-namespace, 10-orchestrator, 20-worker, README
deploy/monitoring/ y deploy/target-api/   SOLO demo local (Grafana/InfluxDB/Prometheus, SUT)
scripts-plantilla/ base.jmx parametrizado con __P()  (OJO: golpea '/'; usar /api/fast en el SUT)
```

## Decisiones de diseño importantes (no romper)
- **SQL Server sin JSONB**: campos JSON = `NVARCHAR(MAX)` + `CHECK (ISJSON()=1)`,
  mapeados con `JsonMapConverter` (AttributeConverter) a `Map<String,Object>`.
- **Ids**: `IDENTITY(1,1)` + `GenerationType.IDENTITY`.
- **Distribución por sharding vía pull, sin RMI ni Jobs**: `WorkerPoolService` asigna
  cada shard con un UPDATE optimista guardado por `status=PENDING` (dos workers nunca
  obtienen el mismo shard). El reparto de hilos se resuelve en el servidor (base + 1
  para los primeros `total % shards`). Start-gate común cuando todos los shards están
  reclamados. La coordinación es la BD; no hay líder.
- **Resultados por HTTP, sin volumen compartido**: el worker sube `jtl.gz`+log a
  `/internal/shards/{id}/{idx}/results`; el orquestador los guarda en SU almacén y el
  `JtlAggregator` los fusiona. El worker descarga el `.jmx` por HTTP (por versionId).
- **API interna `/internal/*`** protegida por token de servicio (`WorkerAuthFilter`,
  cabecera `X-Worker-Token`), distinta de la identidad de usuarios (`X-User`).
- **Transacciones**: `ExecutionService`/`WorkerPoolService` usan `QuarkusTransaction`
  explícito (no `@Transactional`) para evitar self-invocation y funcionar en el hilo
  del scheduler (sin request scope). No revertir a `@Transactional` en esas clases.
- **Identidad de usuario**: v1 la toma de la cabecera HTTP `X-User` (OIDC es Fase 6).

## Cómo arrancar en local (solo API/UI, sin cluster)

Sirve para todo salvo ejecutar carga real: scripts, editor/versionado, validación,
pre-test, presets, historial y la UI. Una ejecución lanzada sin workers queda
`PENDING` y el reaper la marca `FAILED` tras `pending-claim-timeout` (180s).

### 1. Docker + SQL Server
```bash
docker info   # debe responder
docker run -d --name mssql-jmeter -e "ACCEPT_EULA=Y" \
  -e "MSSQL_SA_PASSWORD=Your_strong_Passw0rd" -p 1433:1433 \
  mcr.microsoft.com/mssql/server:2022-latest
# esperar ~20-30s y crear la BD (sqlcmd en 2022 está en mssql-tools18 y requiere -C):
docker exec -i mssql-jmeter /opt/mssql-tools18/bin/sqlcmd -C \
  -S localhost -U sa -P 'Your_strong_Passw0rd' \
  -Q "IF DB_ID('jmeter_orchestrator') IS NULL CREATE DATABASE jmeter_orchestrator"
```
> En Git Bash, prefija los `docker exec ... /opt/...` con `MSYS_NO_PATHCONV=1` para que
> no convierta la ruta `/opt/...` a ruta Windows.

### 2. Quarkus dev
```bash
cd orchestrator && mvn quarkus:dev
```
Defaults ya apuntan a `jdbc:sqlserver://localhost:1433;databaseName=jmeter_orchestrator`
(sa / Your_strong_Passw0rd). Flyway migra al arrancar. UI: http://localhost:8080 ·
Swagger: /q/swagger-ui · Health: /q/health.

### 3. Probar el protocolo pull sin cluster (opcional)
Se puede ejercer el ciclo completo simulando workers con curl contra `/internal/*`
(claim → heartbeat → results con un `jtl.gz` de prueba), o corriendo `worker/agent.sh`
con `ORCHESTRATOR_URL=http://localhost:8080` y un `jmeter` real en el PATH.

## Ejecución REAL end-to-end en minikube (topología pool)

Todo en el namespace `jmeter-load` (orchestrator 1 réplica + worker N réplicas). Sin
PVC compartido y sin RBAC de Jobs. La BD sigue en el HOST (SQL Server en Docker),
alcanzada vía `host.minikube.internal:1433`. El SUT `target-api` vive en el ns `sut`
(imagen `target-api:0.1.0`; endpoints `/api/fast|slow|cpu|flaky|mem`; `/` da 404).

```bash
MK="/c/Program Files/Kubernetes/Minikube/minikube.exe"
"$MK" start
# 1. imágenes (con el motor pool / el agente): mvn package + build/load en minikube
cd orchestrator && mvn -DskipTests package && cd ..
"$MK" image build -t jmeter-orchestrator:0.1.0 -f orchestrator/src/main/docker/Dockerfile.jvm ./orchestrator
docker build -t jmeter-worker:5.6.3 ./worker && "$MK" image load jmeter-worker:5.6.3
# 2. SUT + pool
kubectl apply -f deploy/target-api/target-api.yaml
kubectl apply -f deploy/pool/00-namespace.yaml -f deploy/pool/10-orchestrator.yaml -f deploy/pool/20-worker.yaml
# 3. port-forward (proceso vivo; desde el prompt usar '! kubectl ...')
kubectl port-forward -n jmeter-load svc/orchestrator 18080:80
# 4. subir script + lanzar (nodes <= réplicas de jmeter-worker). Para muestras reales,
#    usar un .jmx que pegue a /api/fast (base.jmx golpea '/', que da 404 en el SUT).
curl -F name=demo -F file=@scripts-plantilla/base.jmx -H "X-User: ana" http://localhost:18080/api/scripts
curl -X POST http://localhost:18080/api/executions -H "Content-Type: application/json" -H "X-User: ana" \
  -d '{"scriptVersionId":<VID>,"parameters":{"threads":20,"nodes":2,"rampUp":2,"duration":20,"targetProtocol":"http","targetHost":"target-api.sut.svc.cluster.local"}}'
# 5. verificar la suma de RPS (sin Grafana): resumen agregado + muestras por shard
ORCH=$(kubectl get pods -n jmeter-load -l app.kubernetes.io/name=orchestrator -o name | head -1)
kubectl exec -n jmeter-load "$ORCH" -- sh -c 'D=/artifacts/executions/<EID>; for f in node-0.jtl node-1.jtl merged.jtl; do echo "$f: $(($(wc -l < "$D/$f")-1))"; done'
```
Notas: `target-api.sut.svc.cluster.local` está en `allowed-hosts`. El monitoring
(Grafana/InfluxDB/Prometheus) es **demo opcional** y NO debe usarse en producción.
Escalar el pool: `kubectl scale -n jmeter-load deploy/jmeter-worker --replicas=N`.

## Comandos útiles
```bash
cd orchestrator
mvn -DskipTests package      # build + augmentation Quarkus (fast-jar en target/quarkus-app)
mvn -Dtest=ExecParamsTest test
mvn quarkus:dev              # modo dev con hot reload
```

## Pendiente / fast-follow
- **Fase 8 (P1, siguiente)**: monitoreo sintético programado — Schedules cron por
  servicio, veredicto OK/DEGRADED/FAILED contra umbrales, webhook + vista de estado.
- **Fase 6 (endurecimiento)**: OIDC + roles (sustituye `X-User`), lista blanca de
  targets por entorno, retención/limpieza de artefactos, `workerImage` en `Execution`.
- Otros: dashboard HTML nativo de JMeter (`jmeter -g merged.jtl`, hoy resumen propio),
  partición fina de CSV por shard, comparación contra baseline, tiles de métricas en
  el detalle de ejecución, confirmación en acciones destructivas, tests de integración
  (Testcontainers) y del protocolo pull, CI.
