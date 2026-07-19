# JMeter Orchestrator

Plataforma para centralizar la ejecucion de pruebas de rendimiento con Apache
JMeter sobre Kubernetes. Implementa el [PLAN.md](PLAN.md) con una diferencia
respecto al borrador original: **la base de datos es Microsoft SQL Server**
(no PostgreSQL).

> Stack: **Quarkus 3.15 LTS · Java 21 · SQL Server · Fabric8 (K8s) · JMeter 5.6.x**

## Por que SQL Server en lugar de PostgreSQL

- Extension `quarkus-jdbc-mssql` + driver oficial de Microsoft.
- Flyway con dialecto T-SQL (`flyway-sqlserver`), migracion en
  `orchestrator/src/main/resources/db/migration/V1__initial_schema.sql`.
- SQL Server **no tiene el tipo `JSONB`**; los campos JSON del plan
  (parametros, resumen de metricas) se almacenan como `NVARCHAR(MAX)` con
  `CHECK (ISJSON(col) = 1)` y se mapean con un `AttributeConverter`
  (`JsonMapConverter`) a `Map<String,Object>`.
- Ids con `IDENTITY(1,1)` y `GenerationType.IDENTITY`.

## Distribucion por sharding (sin RMI)

Cada ejecucion es **un unico Job "Indexed" de Kubernetes** con
`completions = parallelism = N`. Cada pod recibe su `JOB_COMPLETION_INDEX` y se
comporta como un shard independiente (`jmeter -n`). El reparto de hilos lo hace
`worker/entrypoint.sh`. Al terminar, el orquestador fusiona los `.jtl` y calcula
el resumen (TPS, p90/p95/p99, % error). No se usa el modo maestro/esclavo RMI.

## Estructura

```
orchestrator/        Servicio Quarkus (JDK 21)
  domain/            Entidades Panache, enums, JSON converter
  scripts/           Subida, versionado y validacion de .jmx
  preset/            CRUD de presets
  execution/         Motor de ejecuciones, guardrails, SSE, reconciliacion
  k8s/               Fabric8: creacion/observacion/borrado de Jobs
  results/           Fusion de JTL + resumen de metricas
  storage/           Almacen de artefactos (PVC RWX / local)
  rest/              Recursos REST + DTOs + SSE
worker/              Dockerfile JMeter + entrypoint.sh (sharding)
deploy/              Manifiestos K8s (namespaces, RBAC, PVC, quota, despliegue)
scripts-plantilla/   base.jmx parametrizado con __P()
```

## Endpoints principales

| Metodo | Ruta | Descripcion |
|---|---|---|
| POST | `/api/scripts` | Subir `.jmx` (multipart) -> crea script + version 1 |
| GET  | `/api/scripts` · `/api/scripts/{id}` | Listar / detalle con versiones |
| POST | `/api/scripts/{id}/versions` | Guardar edicion como version nueva (body XML) |
| GET  | `/api/scripts/{id}/versions/{v}/content` | Obtener el XML |
| POST | `/api/scripts/{id}/versions/{v}/validate` | Validacion estatica |
| POST | `/api/scripts/{id}/versions/{v}/pretest` | Dry run (1 pod, 1 hilo, 30 s) |
| GET/POST/PUT/DELETE | `/api/presets` | CRUD de presets |
| POST | `/api/presets/{id}/launch` | Lanzar en un clic |
| POST | `/api/executions` | Lanzar ad hoc (scriptVersion + parametros) |
| GET  | `/api/executions?scriptVersion=&estado=&desde=&hasta=` | Historial filtrable |
| GET  | `/api/executions/{id}` | Detalle + resumen |
| GET  | `/api/executions/{id}/report` | Reporte HTML consolidado |
| GET  | `/api/executions/{id}/stream` | SSE de progreso |
| POST | `/api/executions/{id}/cancel` · `/relaunch` | Cancelar / relanzar |

Swagger UI en `/q/swagger-ui`, health en `/q/health`, metricas en `/q/metrics`.

## Ejecutar en local (dev)

Necesitas un SQL Server accesible. Rapido con Docker:

```bash
docker run -e "ACCEPT_EULA=Y" -e "MSSQL_SA_PASSWORD=Your_strong_Passw0rd" \
  -p 1433:1433 -d mcr.microsoft.com/mssql/server:2022-latest
# crea la base
docker exec -it <container> /opt/mssql-tools/bin/sqlcmd -S localhost \
  -U sa -P 'Your_strong_Passw0rd' -Q "CREATE DATABASE jmeter_orchestrator"
```

```bash
cd orchestrator
mvn quarkus:dev
```

Variables de entorno relevantes: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`K8S_WORKER_NAMESPACE`, `WORKER_IMAGE`, `STORAGE_BASE_DIR`. Ver
`application.properties`.

## Construir la imagen worker

```bash
cd worker
docker build -t jmeter-worker:5.6.3 .
```

## Desplegar en Kubernetes

```bash
kubectl apply -f deploy/
```

Ajusta `storageClassName` del PVC (`deploy/03-storage-quota.yaml`) a una clase
**RWX** disponible (p.ej. `azurefile` en AKS) y el `Secret orchestrator-db`.

## Ejemplos

Subir un script:

```bash
curl -F name=demo -F file=@scripts-plantilla/base.jmx \
  -H "X-User: ana" http://localhost:8080/api/scripts
```

Lanzar 1000 hilos en 4 pods contra `httpbin.org`:

```bash
curl -X POST http://localhost:8080/api/executions \
  -H 'Content-Type: application/json' -H 'X-User: ana' \
  -d '{"scriptVersionId":1,"parameters":{"threads":1000,"nodes":4,"rampUp":60,"duration":300,"targetHost":"httpbin.org","targetProtocol":"https"}}'
```

## Estado de implementacion (frente al plan)

Implementado: modelo de datos completo (SQL Server), subida/versionado/validacion
de scripts, presets CRUD + launch, motor de ejecuciones con sharding, creacion y
reconciliacion de Jobs (Fabric8), guardrails (tope hilos/pods + lista blanca de
hosts), fusion de JTL + resumen de metricas, SSE de progreso, pre-test,
historial filtrable, cancelacion y relanzamiento, auditoria, imagen worker y
manifiestos de despliegue.

Pendiente / fast-follow: OIDC + roles (hoy la identidad se toma de la cabecera
`X-User`), generacion del dashboard HTML de JMeter (`jmeter -g`) — hoy se sirve un
resumen; UI web; backend listener -> InfluxDB/Grafana en vivo; particion fina de
CSV; comparacion contra baseline. El PVC de artefactos debe compartirse entre los
namespaces `jmeter-orchestrator` y `jmeter-workers` (misma Azure File share) o
mover ambos al mismo namespace.
