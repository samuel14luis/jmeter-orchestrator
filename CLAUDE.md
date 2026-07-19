# CLAUDE.md — JMeter Orchestrator

Guía para retomar el trabajo en este repositorio. Orquestador de pruebas de
rendimiento JMeter sobre Kubernetes. Implementa [PLAN.md](PLAN.md) **con una
salvedad clave: la base de datos es Microsoft SQL Server, no PostgreSQL**.

## Stack
Quarkus 3.15.1 LTS · Java 21 · SQL Server · Fabric8 (Kubernetes) · JMeter 5.6.x ·
Flyway (T-SQL) · Hibernate ORM Panache.

## Estado actual (2026-07-18)
- **Código completo y compila**: `mvn -DskipTests package` pasa (augmentation OK)
  y el test unitario `ExecParamsTest` pasa (3/3).
- **Smoke test en runtime: HECHO y OK** (SQL Server 2022 en Docker + `quarkus:dev`).
  Toda la API que no depende de K8s funciona contra SQL Server:
  - `POST /api/scripts` (multipart) → crea script + versión 1, calcula checksum
    SHA-256 y guarda el blob. ✅
  - `GET /api/scripts`, `GET /api/scripts/{id}/versions` ✅
  - `POST /api/scripts/{id}/versions/{v}/validate` → `{"valid":true}` ✅
  - `GET /api/executions` (historial) ✅
  - `POST /api/executions` → queda `FAILED` con "No se pudo crear el Job de
    Kubernetes" (esperado: no hay cluster K8s en local; el resto del motor sí corre
    y persiste la fila). ✅
  - Health `/q/health` = UP (DB UP).
- **Fix aplicado durante el smoke test** (application.properties): los campos
  `Instant` se mapeaban a `datetimeoffset` (TIMESTAMP_UTC) por defecto en Hibernate 6
  y chocaban con la migración Flyway (`DATETIME2`). Añadido
  `quarkus.hibernate-orm.unsupported-properties."hibernate.type.preferred_instant_jdbc_type"=TIMESTAMP`
  para forzar `DATETIME2`. Resuelto el error de validación de esa columna.
- **Ruido de validación restante (no-fatal, cosmético)**: Hibernate hace una
  validación post-boot y avisa de `nvarchar`/`char` vs `varchar` esperado (Flyway usa
  `NVARCHAR`/`CHAR(64)` a propósito). No bloquea el arranque ni la operación. Se podría
  silenciar del todo desactivando el validador post-boot si molesta.
- **Docker**: arranca OK (engine 29.6.1). El bloqueo previo del engine ya no aplica.
- No hay commits hechos (el repo git raíz es `C:\Users\User`, todo el home).
  No commitear sin que el usuario lo pida.

## Ejecución REAL end-to-end en minikube: HECHA y OK (2026-07-18)
Se lanzó carga real a través del orquestador y se visualizó en Grafana.

**Topología (todo en minikube, driver docker, single-node):**
- ns `sut`: `target-api` (Quarkus+Micrometer, imagen `target-api:0.1.0`). Endpoints
  de demo en `DemoResource`: `/api/fast`, `/api/slow` (latencia 50-500ms),
  `/api/cpu` (quema CPU), `/api/flaky` (~10% error 500), `/api/mem` (heap/GC).
  Service ClusterIP :80 -> pod :8080. OJO: `/` da 404 (no usar en el .jmx).
- ns `monitoring`: Prometheus (:9090, raspa target-api vía anotaciones pod),
  Grafana (NodePort 30030, admin/admin), InfluxDB 1.8 (:8086, db `jmeter`, sin auth).
  Dashboards: `target-api` (uid, Prometheus: JVM/CPU/RAM del servidor) y
  `jmeter-load` (uid, InfluxDB: RPS/response time/error % del cliente).
- ns `jmeter-workers`: PVC RWX `jmeter-artifacts` (SC `standard`/hostpath),
  ResourceQuota `workers-quota` (exige requests+limits en TODO pod del ns),
  Role `worker-job-manager`, y el **orquestador desplegado AQUÍ mismo** (no en
  `jmeter-orchestrator`) para compartir el PVC en single-node. Los Jobs de JMeter
  también salen en este ns.

**Imágenes construidas en minikube** (`minikube image build`, binario en
`C:\Program Files\Kubernetes\Minikube\minikube.exe`):
- `jmeter-worker:5.6.3`  (desde `worker/Dockerfile`)
- `jmeter-orchestrator:0.1.0` (desde `orchestrator/src/main/docker/Dockerfile.jvm`,
  creado en esta sesión; requiere `mvn -DskipTests package` antes para el fast-jar).

**Manifiesto de despliegue local:** `deploy/local-minikube-orchestrator.yaml`
(SA + RoleBinding al Role existente + Deployment + Service en `jmeter-workers`).
La BD sigue en el HOST (SQL Server en Docker); el pod la alcanza vía
`host.minikube.internal:1433` (verificado alcanzable, sin bloqueo de firewall).

**Guardrails:** añadido `target-api.sut.svc.cluster.local` a `allowed-hosts` en
`application.properties` (la validación es `contains` exacto).

**Scripts de prueba:** `scripts-plantilla/demo-sut.jmx` golpea la mezcla
fast/slow/cpu/flaky (parametrizado con `__P`) y lleva un **Backend Listener
InfluxDB** (-> `influxdb.monitoring.svc.cluster.local:8086/write?db=jmeter`,
application=`orchestrator-demo`) para alimentar el dashboard cliente.

**Resultado observado:** ~290 RPS agregados, CPU del target al 100% del límite,
heap ~21MB, ~2.8% error (por /flaky). InfluxDB y Prometheus ambos con datos.

### Cómo reproducir el lanzamiento real (con minikube ya levantado)
```bash
MK="/c/Program Files/Kubernetes/Minikube/minikube.exe"
# 1. (una vez) construir imágenes en minikube
"$MK" image build -t jmeter-worker:5.6.3 ./worker
cd orchestrator && mvn -DskipTests package && cd ..
"$MK" image build -t jmeter-orchestrator:0.1.0 -f src/main/docker/Dockerfile.jvm ./orchestrator
# 2. (una vez) infra: PVC/quota + RBAC role + orquestador
kubectl apply -f deploy/03-storage-quota.yaml
kubectl apply -f deploy/02-rbac.yaml   # crea el Role (ignora el error del ns jmeter-orchestrator)
kubectl apply -f deploy/local-minikube-orchestrator.yaml
# 3. port-forwards (procesos vivos; usar '! kubectl ...' desde el prompt)
kubectl port-forward -n jmeter-workers svc/orchestrator 18080:80
kubectl port-forward -n monitoring    svc/grafana       3000:3000
# 4. subir script + lanzar
curl -F name=demo-sut-live -F file=@scripts-plantilla/demo-sut.jmx -H "X-User: ana" http://localhost:18080/api/scripts
curl -X POST http://localhost:18080/api/executions -H "Content-Type: application/json" -H "X-User: ana" \
  -d '{"scriptVersionId":<VID>,"parameters":{"threads":80,"rampUp":20,"duration":300,"nodes":2,"targetProtocol":"http","targetHost":"target-api.sut.svc.cluster.local"}}'
# 5. Grafana http://localhost:3000 (admin/admin): dashboards "API objetivo" y "JMeter - Carga (cliente)"
```

**Fast-follow pendiente:** habilitar histograma de percentiles en target-api
(`quarkus.micrometer.binder.http-server.max-route-tags-*` / distribution
percentiles-histogram) para que salga la p95 server-side en Prometheus; hoy la
p95 sale por el lado cliente (InfluxDB).

## Layout
```
orchestrator/     Servicio Quarkus (código Java + pom.xml + application.properties)
  src/main/java/com/performance/orchestrator/
    domain/       Entidades Panache, enums, JsonMapConverter (JSON->NVARCHAR(MAX))
    scripts/      Subida, versionado, validación de .jmx (ScriptValidator)
    preset/       PresetService (CRUD)
    execution/    Motor: ExecutionService (QuarkusTransaction), GuardrailsService,
                  ExecutionReconciler (@Scheduled), ExecutionEvents (SSE), ExecParams
    k8s/          KubernetesJobService (Fabric8, Jobs Indexed), LaunchSpec
    results/      JtlAggregator (fusión JTL + percentiles), MetricsSummary
    storage/      StorageService + LocalStorageService (PVC RWX / dir local)
    rest/         ScriptResource, PresetResource, ExecutionResource (+SSE), dto/Dtos
    common/       ApiException(+Mapper), Checksums
  src/main/resources/
    application.properties
    db/migration/V1__initial_schema.sql   (T-SQL: IDENTITY, NVARCHAR, DATETIME2, ISJSON)
worker/           Dockerfile (JMeter 5.6.3 + plugins) + entrypoint.sh (sharding)
deploy/           Manifiestos K8s (namespaces, RBAC, PVC RWX, quota, deployment, mssql dev)
scripts-plantilla/ base.jmx parametrizado con __P()
```

## Decisiones de diseño importantes (no romper)
- **SQL Server sin JSONB**: campos JSON = `NVARCHAR(MAX)` + `CHECK (ISJSON()=1)`,
  mapeados con `JsonMapConverter` (AttributeConverter) a `Map<String,Object>`.
- **Ids**: `IDENTITY(1,1)` + `GenerationType.IDENTITY`.
- **Distribución por sharding, sin RMI**: cada ejecución = un único Job *Indexed*
  de K8s (`completions=parallelism=N`). El reparto de hilos lo hace
  `worker/entrypoint.sh` usando `JOB_COMPLETION_INDEX`.
- **Transacciones**: `ExecutionService` usa `QuarkusTransaction` explícito (no
  `@Transactional`) para evitar self-invocation y funcionar en el hilo del
  scheduler (sin request scope). No revertir a `@Transactional` en esa clase.
- **Identidad de usuario**: v1 la toma de la cabecera HTTP `X-User` (OIDC es Fase 6).

## Cómo arrancar en local (pasos al retomar)

### 1. Verificar Docker
```bash
docker info   # debe responder sin error
```
Si no: abrir Docker Desktop, esperar a que el icono esté "running".

### 2. Levantar SQL Server + crear la base
```bash
docker run -d --name mssql-jmeter \
  -e "ACCEPT_EULA=Y" -e "MSSQL_SA_PASSWORD=Your_strong_Passw0rd" \
  -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest

# esperar ~20-30s a que arranque, luego crear la BD:
docker exec -i mssql-jmeter /opt/mssql-tools18/bin/sqlcmd -C \
  -S localhost -U sa -P 'Your_strong_Passw0rd' \
  -Q "IF DB_ID('jmeter_orchestrator') IS NULL CREATE DATABASE jmeter_orchestrator"
```
Nota: en la imagen 2022 la herramienta está en `/opt/mssql-tools18/bin/sqlcmd` y
requiere `-C` (trust cert). Si fuese imagen antigua: `/opt/mssql-tools/bin/sqlcmd`
sin `-C`.

### 3. Arrancar Quarkus dev
```bash
cd orchestrator
mvn quarkus:dev
```
Los defaults de `application.properties` ya apuntan a
`jdbc:sqlserver://localhost:1433;databaseName=jmeter_orchestrator` con usuario
`sa` y pass `Your_strong_Passw0rd`. Flyway migra al arrancar. Sobrescribir con
env `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` si hace falta.

### 4. Smoke test (con la app arriba en :8080)
```bash
# subir el script de plantilla (crea script + versión 1)
curl -F name=demo -F file=@scripts-plantilla/base.jmx \
  -H "X-User: ana" http://localhost:8080/api/scripts

# validar la versión
curl -X POST http://localhost:8080/api/scripts/1/versions/1/validate

# historial (vacío al inicio)
curl http://localhost:8080/api/executions
```
Swagger UI: http://localhost:8080/q/swagger-ui · Health: /q/health

> Ojo: **lanzar una ejecución** (`POST /api/executions`) requiere un cluster
> Kubernetes accesible (crea Jobs vía Fabric8). Sin cluster, la creación del Job
> falla y la ejecución queda en FAILED — el resto de la API (scripts, presets,
> validación, historial) funciona solo con SQL Server.

## Comandos útiles
```bash
cd orchestrator
mvn -DskipTests package      # build completo + augmentation Quarkus
mvn -Dtest=ExecParamsTest test
mvn quarkus:dev              # modo dev con hot reload
```

## Pendiente / fast-follow (documentado en README)
OIDC + roles reales · dashboard HTML nativo de JMeter (`jmeter -g merged.jtl`,
hoy se sirve un resumen HTML) · UI web · Backend Listener -> InfluxDB/Grafana en
vivo · partición fina de CSV por pod · comparación contra baseline. El PVC de
artefactos debe compartirse entre los namespaces `jmeter-orchestrator` y
`jmeter-workers` (misma Azure File share) o mover ambos al mismo namespace.
