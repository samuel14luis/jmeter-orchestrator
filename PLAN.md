# Plan de Proyecto — Orquestador de Pruebas JMeter

**Stack:** Quarkus 3 (LTS) · Java 21 · Microservicios sobre Kubernetes (minikube solo para validación local) · Apache JMeter 5.6.x · **Microsoft SQL Server**

---

## 0. Estado del proyecto (actualizado 2026-07-19)

Las Fases 0–4 están **implementadas y probadas end-to-end** en minikube: API completa
(scripts, versiones, validación, pre-test, presets, ejecuciones, SSE, cancelación,
relanzamiento) y ejecución distribuida real en 2 pods contra un SUT de demo. **Existe
además una UI web** (single-page en
`orchestrator/src/main/resources/META-INF/resources/index.html`) que cubre scripts —
incluido **editor in-app con versionado** —, presets, ejecuciones e historial, con
progreso SSE en vivo (barra de pods + tiles). El detalle operativo para retomar el
trabajo vive en [CLAUDE.md](CLAUDE.md).

### Pivote de arquitectura (decidido 2026-07-19; define las Fases 7 y 8)

El entorno productivo real es una **plataforma de microservicios**: se despliegan
servicios con N réplicas, y el orquestador no debe depender del API de Kubernetes en
runtime. Además, **en producción no puede existir consumo de métricas entre el
orquestador y Grafana/InfluxDB**: ese stack queda como demo local únicamente.
Decisiones cerradas con el usuario:

1. **Topología objetivo**: servicio `orchestrator` (1 réplica: UI + API + coordinación
   + scheduler) y servicio `worker` (N réplicas, escaladas según la prueba).
2. **Coordinación por pull, sin líder**: cada worker pregunta al orquestador si hay
   shard para él, lo reclama atómicamente, espera una señal de arranque común y
   ejecuta su parte. Se descartó explícitamente el patrón "la primera réplica se
   vuelve maestra y manda al resto": reintroduce el master/slave que este proyecto
   nació evitando (SPOF, elección de líder, direccionamiento pod-a-pod).
3. **Resultados por HTTP**: cada worker sube su JTL comprimido y su log al orquestador
   al terminar su shard. Sin volumen compartido entre microservicios.
4. **El motor de K8s Jobs (Fabric8) se retira** al completar el worker-pool: una sola
   arquitectura, y minikube valida exactamente la topología productiva.
5. **Nueva feature — monitoreo sintético programado** (Fase 8): Schedules con cron
   (p. ej. cada hora) que ejecutan un preset ligero por servicio y producen un reporte
   consolidado de estado, con aviso por webhook HTTP + historial en la UI.
6. **Rol de minikube**: estrictamente validar que N workers coordinan la ejecución
   completa de un script y que sus RPS suman el total esperado. No es entorno destino.

Desviaciones respecto al borrador original, **ya aplicadas** (el resto del documento
está actualizado para reflejarlas):

- **Base de datos: SQL Server, no PostgreSQL.** Sin JSONB: los campos JSON son
  `NVARCHAR(MAX)` + `CHECK (ISJSON()=1)`, mapeados a `Map<String,Object>` con un
  `AttributeConverter` (`JsonMapConverter`). Migraciones Flyway en T-SQL
  (`IDENTITY(1,1)`, `DATETIME2`).
- **Seguimiento de Jobs por reconciliación programada** (`ExecutionReconciler` con
  `@Scheduled`), no por informers de Fabric8: más simple y sobrevive reinicios del
  orquestador sin estado en memoria. Informers quedan como optimización futura si el
  polling se queda corto.
- **Identidad v1 por cabecera HTTP `X-User`** (OIDC llega en Fase 6). Ver §10.
- **Artefactos en PVC RWX** en el montaje local actual — transitorio: con la Fase 7
  los resultados viajan por HTTP y el almacén pasa a ser propio del orquestador (§13).

## 1. Resumen

Plataforma que centraliza la ejecución de pruebas de rendimiento con JMeter sobre una plataforma de microservicios. Permite subir, editar, versionar y pre-testear scripts `.jmx`; configurar pruebas de carga y estrés mediante parámetros reutilizables; distribuir la carga entre N réplicas worker que suman el RPS total; y conservar un historial completo de cada ejecución (parámetros, versión del script, shards, métricas y artefactos) para relanzarla en un clic. Además, ejecuta **chequeos sintéticos programados** (p. ej. cada hora) contra los servicios registrados y produce un reporte de estado consolidado con aviso por webhook cuando algo falla.

## 2. Objetivos

1. Ejecutar pruebas distribuidas en N réplicas worker sin depender del modo maestro/esclavo (RMI) de JMeter.
2. Gestionar scripts: subida, edición, validación, versionado y pre-test (dry run) antes del lanzamiento masivo.
3. Configurar pruebas de forma declarativa (carga, estrés, pico, resistencia) con presets reutilizables.
4. Registrar cada ejecución de forma auditable: quién, cuándo, con qué script/versión, con qué parámetros y con qué resultado.
5. Relanzar cualquier prueba anterior en menos de 30 segundos a partir de su preset o de su historial.
6. Ejecutar chequeos programados (cron) por servicio y publicar un reporte de estado consolidado (OK/DEGRADED/FAILED, p95, % error) con aviso por webhook cuando algo falle.

### No-objetivos (v1)

- Editor visual del árbol completo de JMeter (v1 edita el XML con validación; el editor gráfico por formularios llega en fases posteriores).
- Soporte multi-cluster o multi-región.
- Comparación automática contra baselines históricos (queda como consideración futura, pero el modelo de datos la habilita).

## 3. Historias de usuario

- Como ingeniero de performance, quiero subir un `.jmx` y validarlo al instante para detectar errores de estructura antes de gastar recursos del cluster.
- Como ingeniero de performance, quiero editar un script y guardar una nueva versión para corregirlo sin salir de la plataforma ni perder trazabilidad.
- Como ingeniero de performance, quiero ejecutar un pre-test (1 hilo, 30 s) para confirmar que el script y el target responden antes del lanzamiento completo.
- Como líder técnico, quiero definir presets de carga y estrés (hilos, rampa, duración, nodos, target) para estandarizar cómo se prueba cada API.
- Como miembro del squad, quiero relanzar la prueba del sprint pasado con un clic para comparar resultados tras un cambio.
- Como líder técnico, quiero ver el historial filtrable de ejecuciones con su resumen (TPS, p95, % error) para sustentar decisiones de capacity/HPA.
- Como responsable de seguridad, quiero que solo se pueda apuntar a hosts autorizados por entorno para evitar disparar carga contra producción por error.
- Como responsable del squad, quiero que cada hora corra un chequeo ligero contra todos mis servicios y me llegue un webhook si alguno falla, para enterarme antes que los usuarios.
- Como ingeniero de performance, quiero el histórico de chequeos por servicio para detectar degradaciones graduales (p95 subiendo semana a semana).

## 4. Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│  UI Web (single-page servida por el orquestador)             │
└───────────────┬──────────────────────────────────────────────┘
                │ REST + SSE
┌───────────────▼────────────────┐       ┌─────────────────────┐
│  ORCHESTRATOR (1 réplica)      │──────▶│ SQL Server          │
│  · API scripts/presets/execs   │       │ (metadatos, resumen)│
│  · Coordinador de shards (pull)│       └─────────────────────┘
│  · Scheduler de chequeos (cron)│       ┌─────────────────────┐
│  · Agregador de resultados     │──────▶│ Almacén PROPIO      │
│  · Webhook de alertas          │       │ (.jmx .jtl reportes)│
└───────────────▲────────────────┘       └─────────────────────┘
                │  pull: claim de shard + heartbeat
                │  push: POST resultados (jtl.gz + log)
┌───────────────┴────────────────┐
│  WORKER SERVICE (N réplicas)   │   Grafana/InfluxDB/Prometheus:
│  réplica-i: agente + jmeter -n │   SOLO demo local (minikube).
│  ejecuta el shard i de N       │   Prohibido en producción.
└────────────────────────────────┘
```

### Decisión clave: distribución por *sharding*, no RMI

El modo distribuido nativo de JMeter (maestro/esclavo por RMI) es frágil en Kubernetes: puertos dinámicos, serialización, y un maestro que se vuelve punto único de falla. En su lugar:

1. El orquestador divide la carga total entre N shards (ej.: 1 000 hilos / 4 workers → `-Jthreads=250` por worker).
2. Cada worker ejecuta `jmeter -n` de forma independiente y escribe su propio `.jtl`.
3. Al finalizar, cada worker entrega su `.jtl` al orquestador, que los fusiona, calcula el resumen y genera el reporte consolidado. **La suma de RPS de los shards = RPS total.**

Ventajas: workers homogéneos e independientes, escala lineal, la caída de un worker no tumba la prueba, cero RMI.

### Decisión clave: coordinación por *pull*, sin líder (Fase 7)

Los workers son réplicas de un Deployment normal: no hay "primera réplica que manda".
La coordinación va de los workers hacia el orquestador:

1. **Claim atómico**: el worker hace `POST /internal/shards/claim` con su `workerId`;
   el orquestador le asigna (en transacción de BD) el siguiente shard libre de una
   ejecución `PENDING`, o nada — y el worker vuelve a preguntar tras un intervalo.
2. **Start-gate**: cuando todos los shards están reclamados, el orquestador fija un
   `startAt` común; cada worker espera a ese instante para arrancar (~simultáneo).
3. **Heartbeat**: el worker late durante la corrida; sin latido en X segundos, su
   shard se considera huérfano (fallo parcial o reasignación).
4. **Entrega**: al terminar, el worker sube `node-i.jtl.gz` + log por HTTP y vuelve
   al bucle de claim.

Esto evita elección de líder, split-brain y direccionamiento pod-a-pod: el único punto
de coordinación es la BD del orquestador, que ya es el estado de verdad del sistema.

### Flujo de una ejecución (modelo worker-pool, Fase 7)

1. El usuario elige script (+versión) y un preset, o define parámetros ad hoc.
2. El orquestador valida parámetros y guardrails (host permitido, tope de hilos, shards ≤ réplicas worker disponibles).
3. Crea el registro `Execution` en `PENDING` con N shards libres en BD.
4. Los workers reclaman shards por pull; con todos reclamados, el orquestador fija el `startAt` común y pasa a `RUNNING`.
5. Durante la corrida, los heartbeats alimentan el progreso por SSE (shards ok/fallo/activos).
6. Cada worker sube su `.jtl.gz` + log por HTTP al terminar su shard.
7. Con todos los shards entregados (o vencidos por timeout), el agregador fusiona los JTL, calcula el resumen (TPS, p90/p95/p99, % error) y cierra como `COMPLETED` o `FAILED`.

> Hasta completar la Fase 7, el motor vigente es el de K8s Jobs *Indexed* (Fabric8):
> mismo sharding, pero los pods los crea un Job y los resultados van por PVC compartido.
> Se retira al validar el worker-pool en minikube.

## 5. Stack técnico

| Capa | Elección | Notas |
|---|---|---|
| Runtime | Quarkus 3 LTS + JDK 21 | Virtual threads (`@RunOnVirtualThread`) para las llamadas bloqueantes (BD, almacenamiento, agregación) |
| API | `quarkus-rest` + SmallRye OpenAPI | SSE para progreso en vivo |
| Persistencia | Hibernate ORM Panache + SQL Server + Flyway (T-SQL) | Metadatos y resúmenes; parámetros JSON en `NVARCHAR(MAX)` + `CHECK (ISJSON()=1)` |
| Ejecución distribuida | Worker service (N réplicas) con agente pull + `jmeter -n` | Motor K8s Jobs (Fabric8) vigente solo hasta completar la Fase 7 |
| Artefactos | Almacén propio del orquestador (volumen local) | Los workers entregan JTL/log por HTTP; sin volumen compartido entre servicios |
| Workers | Imagen propia: agente (claim/heartbeat/upload) + JMeter 5.6.x | Plugins comunes (Ultimate Thread Group, etc.) preinstalados |
| Métricas | Progreso en vivo por SSE · métricas finales desde los JTL agregados | Grafana/InfluxDB/Prometheus: **solo demo local**; prohibido en producción |
| Observabilidad propia | Micrometer + Prometheus, logs JSON con `io.quarkus.logging.Log` | Health checks para readiness/liveness |
| UI | Single-page vanilla servida por el orquestador (hecha) | Editor XML in-app (textarea); Monaco/SPA solo si hiciera falta más adelante |
| Scheduler | Quarkus `@Scheduled`/cron en la única réplica del orquestador | Sin elección de líder (1 réplica); dispara los chequeos de la Fase 8 |

## 6. Modelo de datos (borrador)

- **Script**: id, nombre, descripción, tags, createdBy, createdAt.
- **ScriptVersion**: id, scriptId, número, rutaBlob, checksumSHA256, notas, createdAt. *Inmutable: cada guardado crea versión nueva.*
- **Preset**: id, nombre, scriptId, tipoPrueba (`CARGA | ESTRES | PICO | RESISTENCIA`), parámetros JSON, targetEnv, createdBy.
- **Execution**: id, presetId (nullable), scriptVersionId, parámetrosEfectivos JSON, estado (`PENDING | RUNNING | AGGREGATING | COMPLETED | FAILED | CANCELLED`), nodos, startedAt, finishedAt, resumen JSON (tps, p90, p95, p99, errorPct, muestras), rutaResultados, lanzadaPor. *Pendiente de añadir:* `workerImage` (imagen+tag del worker usada), para que "relanzar la prueba del sprint pasado" sea comparable aunque la imagen por defecto haya cambiado.
- **ExecutionNode** (shard): id, executionId, índice, workerId (réplica que lo reclamó, Fase 7), estado, exitCode, heartbeatAt (Fase 7), rutaJtl, rutaLog.
- **Schedule** (Fase 8): id, nombre, cronExpr, enabled, webhookUrl, presets asociados (uno por servicio, con umbrales p95 máx / % error máx), createdBy, lastRunAt.
- **ScheduleRun** (Fase 8): id, scheduleId, startedAt, finishedAt, overallStatus (`OK | DEGRADED | FAILED`), detalle JSON por servicio (presetId, executionId, estado, p95, errorPct).

Parámetros JSON típicos: hilos totales, rampUp, duración, iteraciones, número de nodos, host/protocolo destino, propiedades `-J` adicionales, CSVs asociados. (En SQL Server viven en `NVARCHAR(MAX)` con `CHECK (ISJSON()=1)`; no hay índices sobre el JSON — si algún filtro del historial lo necesitara, promover ese campo a columna.)

## 7. API REST (borrador)

**Scripts**
- `POST /api/scripts` — subir `.jmx` (multipart); crea script + versión 1
- `GET /api/scripts` · `GET /api/scripts/{id}` — listar / detalle con versiones
- `POST /api/scripts/{id}/versions` — guardar edición como versión nueva
- `GET /api/scripts/{id}/versions/{v}/content` — obtener el XML
- `POST /api/scripts/{id}/versions/{v}/validate` — validación estática
- `POST /api/scripts/{id}/versions/{v}/pretest` — dry run

**Presets**
- CRUD `/api/presets`
- `POST /api/presets/{id}/launch` — lanzar en un clic

**Ejecuciones**
- `POST /api/executions` — lanzar ad hoc (scriptVersion + parámetros)
- `GET /api/executions?script=&estado=&desde=&hasta=` — historial filtrable
- `GET /api/executions/{id}` — detalle + resumen de métricas
- `GET /api/executions/{id}/report` — dashboard HTML consolidado
- `GET /api/executions/{id}/stream` — SSE de progreso
- `POST /api/executions/{id}/cancel` · `POST /api/executions/{id}/relaunch`

**Schedules y estado (Fase 8)**
- CRUD `/api/schedules` — cron + presets por servicio + umbrales + webhook
- `POST /api/schedules/{id}/run-now` — disparo manual
- `GET /api/schedules/{id}/runs` — histórico de corridas
- `GET /api/status` — última foto de salud de todos los servicios monitorizados

**API interna de workers (Fase 7; autenticada con token de servicio, nunca expuesta)**
- `POST /internal/shards/claim` — el worker pide shard; respuesta: spec (script, `-J`, índice, startAt) o vacío
- `POST /internal/shards/{executionId}/{idx}/heartbeat` — latido durante la corrida
- `POST /internal/shards/{executionId}/{idx}/results` — multipart `jtl.gz` + log al terminar

## 8. Funcionalidades clave

### 8.1 Gestión y edición de scripts
- Subida con validación: XML bien formado, raíz `jmeterTestPlan`, tope de tamaño.
- Versionado inmutable con checksum → toda ejecución referencia una versión exacta.
- Edición v1: editor de texto in-app sobre el XML, validando al guardar (**hecho**; Monaco como mejora futura).
- Edición v2: formularios para los campos frecuentes (HTTP Request Defaults, User Defined Variables, CSV Data Set) parseando el XML.
- **Convención de parametrización**: los scripts usan `${__P(threads,10)}`, `${__P(rampUp,60)}`, `${__P(duration,300)}`, `${__P(targetHost,...)}` para que el orquestador los controle vía `-J` sin tocar el XML. Se entrega una plantilla `.jmx` base.

### 8.2 Pre-test (dry run)
- Ejecuta el script en 1 shard con `-Jthreads=1 -Jduration=30` (o 1 iteración).
- Verifica: arranque sin errores de parseo, respuesta del target, aserciones OK, % de error bajo umbral.
- Devuelve veredicto + primeras muestras + log. Si falla, el lanzamiento masivo se bloquea (con opción de forzar para roles autorizados).

### 8.3 Tipos de prueba y presets
- **Carga**: hilos constantes durante una duración objetivo.
- **Estrés**: escalera de incrementos hasta observar degradación (vía Ultimate Thread Group o etapas encadenadas).
- **Pico**: subida y bajada súbitas.
- **Resistencia (soak)**: carga media sostenida por horas.

El preset guarda tipo + parámetros; el motor los traduce a propiedades `-J` por shard.

### 8.4 Ejecución distribuida
- Reparto de hilos por shard y partición de datos: `-Jpod.index` / `-Jpod.count` para que los CSV no dupliquen datos entre shards.
- Reparto del resto cuando hilos no es divisible: los primeros `total % shards` reciben +1 hilo (determinista); cada worker loguea sus hilos efectivos.
- Modelo worker-pool (Fase 7): claim atómico por pull, start-gate común, heartbeat y timeout de shard huérfano (ver §4).
- Reconciliación: el estado de verdad vive en BD. Si el orquestador se reinicia a mitad de una prueba, los workers siguen ejecutando y entregando; el orquestador retoma desde los shards registrados.

### 8.5 Historial y resultados
- Registro completo por ejecución + artefactos: JTL por shard, JTL fusionado, reporte HTML, logs.
- Resumen de métricas en BD para listar y filtrar sin abrir artefactos.
- Retención configurable (ej.: artefactos crudos 90 días; metadatos y resúmenes, indefinido). **Mecanismo (pendiente de implementar):** job de limpieza `@Scheduled` en el orquestador que borre del almacén los artefactos crudos vencidos; al ser un volumen propio de tamaño finito, llenarlo es un riesgo real (ver §12).
- El reporte HTML consolidado nativo de JMeter (`jmeter -g merged.jtl -o report/`) está **pendiente**; hoy se sirve un resumen HTML propio generado por el agregador.

### 8.6 Cancelación (contrato implementado)
- `POST /api/executions/{id}/cancel` solo aplica a ejecuciones no terminales (409 si ya terminó).
- Borra el Job de K8s con propagación `BACKGROUND` (los pods mueren de forma asíncrona); si el borrado falla se loguea y la cancelación continúa igualmente.
- La ejecución pasa a `CANCELLED` con `finishedAt`, se registra evento de auditoría y se cierra el stream SSE.
- Los `.jtl` parciales ya escritos en el almacén **se conservan** para inspección manual; no se agrega ni se genera reporte consolidado de una ejecución cancelada.
- El reconciler solo itera ejecuciones no terminales, así que una ejecución cancelada deja de reconciliarse. Para una ejecución `RUNNING`, un Job ausente se interpreta como fin de prueba (pasa a `AGGREGATING`), nunca como error — esto también cubre Jobs borrados por `ttlSecondsAfterFinished`.
- En el modelo worker-pool (Fase 7), cancelar además marca los shards no entregados como cancelados; los workers detectan la cancelación en su siguiente heartbeat y abortan su `jmeter -n`.

### 8.7 Monitoreo sintético programado (Fase 8)
- Un **Schedule** define: expresión cron (p. ej. `0 * * * *`), lista de presets ligeros
  (uno por servicio: 1-2 hilos, pocos segundos de duración), umbrales por preset
  (p95 máx, % error máx) y una URL de webhook opcional.
- En cada disparo, el scheduler (en la única réplica del orquestador — sin elección de
  líder) lanza las ejecuciones y construye un **ScheduleRun** con el veredicto por
  servicio: `OK | DEGRADED | FAILED` según umbrales.
- **Alertas**: si algún servicio degrada o falla, POST al webhook configurado (payload
  JSON: servicio, métricas, enlace al detalle) además del registro en el historial.
- **UI**: vista "Estado de servicios" con la última corrida (semáforo por servicio) e
  histórico para detectar degradaciones graduales.
- Los chequeos usan el mismo motor de ejecución (1 shard) y los mismos guardrails que
  las pruebas de carga; solo cambian los parámetros.

## 9. Requisitos priorizados

**P0 — sin esto no sirve** *(hecho sobre el motor de Jobs; se re-valida sobre worker-pool en Fase 7)*
- Subir script, lanzar en N shards, registrar la ejecución y ver el reporte consolidado.
- Parámetros básicos de prueba: hilos, rampa, duración, target, número de shards.
- Historial persistente de ejecuciones.

**P1 — siguiente entrega de valor**
- Migración al worker-pool por pull (Fase 7): desbloquea el despliegue en la plataforma real de microservicios.
- Monitoreo sintético programado con reporte de estado y webhook (Fase 8).

**P2 — futuro, pero condiciona el diseño**
- Comparación contra baseline, editor por formularios, integración con pipelines CI/CD.

## 10. Seguridad y guardrails (entorno bancario)

- Autenticación OIDC (Entra ID) + roles: administrador, ejecutor, lector.
  - **Estado v1 (interino):** la identidad se toma de la cabecera HTTP `X-User`, que es
    spoofeable. Aceptable únicamente mientras la API no se exponga fuera del
    cluster/red interna; OIDC es **bloqueante** para cualquier despliegue real y
    sustituye este mecanismo en Fase 6.
- **Lista blanca de hosts destino por entorno**: imposible apuntar a producción sin rol específico y confirmación explícita.
  - **Estado v1 (interino):** `allowed-hosts` en `application.properties` con
    comparación exacta del host contra la lista. No cubre puertos, IPs alternativas
    del mismo host ni subdominios, y aún no modela "entorno" (dev/qa/prod) ni roles.
    Endurecerla (modelo de entornos + normalización/resolución del target) es parte
    de Fase 6.
- Tope global de hilos/pods por ejecución y `ResourceQuota` en el namespace de workers.
- Credenciales de prueba vía Secrets de K8s / Key Vault; nunca embebidas en el `.jmx` (el validador lo advierte).
- Auditoría: cada lanzamiento, cancelación y edición queda asociado a un usuario.
- **API interna de workers** (`/internal/*`, Fase 7): autenticada con un token de
  servicio compartido (Secret de la plataforma), distinto de la identidad de usuarios;
  nunca expuesta fuera de la red interna del cluster.
- **Webhook de alertas** (Fase 8): URL configurable solo por administradores; el payload
  no incluye datos sensibles (solo servicio, métricas y enlace al detalle).

## 11. Fases

### Fase 0 — Fundaciones (≈1 semana) — HECHA (salvo CI)
- [x] Repositorio y scaffolding Quarkus (JDK 21) — **CI pendiente**
- [x] Imagen worker JMeter 5.6.3 + plugins + `entrypoint.sh`
- [x] SQL Server + Flyway (T-SQL); namespace, ServiceAccount y RBAC mínimos en el cluster

**Criterio de salida:** un Job JMeter lanzado a mano corre en el cluster y deja un `.jtl` en el almacén de artefactos. ✅

### Fase 1 — MVP mono-pod (≈2 semanas) — HECHA
- [x] Subida y listado de scripts (versión 1 automática)
- [x] Lanzar ejecución en 1 pod con hilos/rampa/duración/target
- [x] Estados vía reconciler `@Scheduled` + registro en BD + descarga de JTL y log

**Criterio:** desde la API se sube un script, se lanza y la ejecución queda registrada con su resultado. ✅

### Fase 2 — Distribución multi-pod (≈2 semanas) — HECHA con dos salvedades
- [x] Sharding de hilos por `pod.index` — **partición fina de CSV por pod pendiente**
- [x] Fusión de JTLs y resumen consolidado — **el reporte HTML nativo de JMeter (`jmeter -g`) sigue pendiente; hoy se sirve un resumen HTML propio**
- [x] Resumen de métricas en BD (TPS, percentiles, % error)

**Criterio:** una prueba multi-pod produce un único resultado consolidado correcto. ✅ (verificado con 2 pods en minikube)

### Fase 3 — Edición, validación y pre-test (≈2 semanas) — HECHA (editor = API; UI en Fase 5)
- [x] Versionado + validación al guardar (vía API; el editor in-app llegó con la UI en Fase 5)
- [x] Endpoint de pre-test con veredicto y bloqueo
- [x] Plantilla `.jmx` base y convención `__P()` documentadas

**Criterio:** editar → guardar versión → pre-test → lanzar, sin salir de la plataforma. ✅ (vía API)

### Fase 4 — Presets, historial y relanzamiento (≈1–2 semanas) — HECHA
- [x] CRUD de presets por tipo de prueba + lanzar/relanzar en un clic
- [x] Historial filtrable con resumen + cancelación de ejecuciones

**Criterio:** relanzar una prueba anterior toma menos de 30 segundos. ✅

### Fase 5 — UI y métricas en vivo (≈2–3 semanas) — mayormente HECHA
- [x] UI web (single-page, `index.html`): subida/validación/pre-test de scripts, CRUD y
  lanzamiento de presets, ejecuciones ad-hoc, historial filtrable con detalle,
  cancelar/relanzar, y **progreso SSE en vivo con barra de pods + tiles** (OK/fallo/activos/total)
- [x] Backend Listener → InfluxDB + Prometheus + dashboards Grafana (verificado en
  minikube; queda como **demo local únicamente** — prohibido en producción, ver §0)
- [x] Editor de scripts in-app: ver/editar el XML y guardar versión nueva sin salir de
  la UI, con validación al guardar (implementado y verificado end-to-end 2026-07-19)
- [ ] Resumen de métricas del **detalle** de ejecución como tiles (hoy JSON crudo)
- [ ] Confirmación en acciones destructivas (cancelar ejecución, borrar preset)

**Criterio:** un usuario no experto lanza una prueba guiado por la UI y ve métricas en
tiempo real. ✅ para el flujo básico; los tiles del detalle y las confirmaciones son el
fast-follow de usabilidad. (El deep-link a Grafana se descartó: sin Grafana en prod.)

### Fase 6 — Endurecimiento (continuo) — PENDIENTE
- [ ] OIDC + roles (sustituye `X-User`), lista blanca de targets por entorno, quotas
- [ ] Retención y limpieza de artefactos (job `@Scheduled`), backups de BD
- [ ] Prueba de resiliencia del propio orquestador (reinicio a mitad de ejecución → reconciliación)
- [ ] Registrar `workerImage` en `Execution` (reproducibilidad de relanzamientos)

### Fase 7 — Pivote a worker-pool por pull (plataforma de microservicios) — EN CURSO, P1
- [x] Endpoints internos en el orquestador (`/internal/*`): claim atómico de shards,
      heartbeat, recepción de resultados (multipart gzip), descarga de script por
      versionId, autenticados por token de servicio (`WorkerAuthFilter`)
- [x] Motor `WorkerPoolService`: shards en BD, claim optimista (guardado por
      `status=PENDING`), start-gate común, reparto de hilos server-side, timeout de
      shard huérfano y de ejecución sin workers (reaper `@Scheduled`), agregación al
      recibir el último shard, cancelación propagada vía heartbeat. Flag
      `orchestrator.engine=jobs|pool` (default pool); reconciler y cancel adaptados.
      Migración Flyway V2. **Verificado end-to-end simulando workers** (RPS suma
      3+3=6, reparto 10/3→4,3,3, cancelación por heartbeat).
- [ ] Agente worker (en la imagen del worker): bucle claim → espera del start-gate →
      `jmeter -n` → heartbeat → subida de `jtl.gz` + log → volver al bucle
- [ ] Manifiestos: Deployment `worker` (N réplicas) + Deployment `orchestrator`
      (1 réplica); retirar el RBAC de Jobs
- [ ] Retirar `KubernetesJobService`/Fabric8 y el `entrypoint.sh` basado en
      `JOB_COMPLETION_INDEX` cuando el pool esté validado
- [ ] **Validación en minikube (el objetivo declarado del entorno local)**: correr con
      1 shard y con 2+ shards contra `target-api` y verificar, con el resumen agregado
      del orquestador (no con Grafana), que los RPS por shard suman el total esperado

**Criterio:** una prueba de N shards corre completa sobre el worker-pool sin tocar el
API de Kubernetes en runtime, y la suma de RPS por shard ≈ RPS total del resumen.
El **backend del protocolo está hecho y verificado**; falta el agente worker real
(imagen), los manifiestos, retirar Fabric8 y la validación con carga real en minikube.

### Fase 8 — Monitoreo sintético programado (≈1–2 semanas, tras Fase 7) — PENDIENTE, P1
- [ ] Entidades `Schedule` y `ScheduleRun` + migración Flyway
- [ ] Scheduler cron en el orquestador (1 réplica ⇒ sin elección de líder) + `run-now`
- [ ] Umbrales por preset (p95 máx, % error máx) y veredicto `OK | DEGRADED | FAILED`
- [ ] Webhook HTTP en fallo/degradación (URL configurable, reintentos básicos)
- [ ] UI: vista "Estado de servicios" (semáforo por servicio + histórico de corridas)

**Criterio:** un Schedule horario corre solo; un servicio caído se ve en la UI y
dispara el webhook en esa misma corrida.

### Deuda de calidad transversal (añadida 2026-07-19)
Hoy solo existe un test unitario (`ExecParamsTest`). Por orden de retorno:
- [ ] Tests de integración `@QuarkusTest` + Testcontainers (SQL Server) para el ciclo scripts → presets → executions contra la API real
- [ ] Tests del protocolo worker-pool (Fase 7): claim concurrente (dos workers no obtienen el mismo shard), start-gate, timeout de heartbeat, entrega de resultados — sin cluster real, simulando workers por HTTP
- [ ] CI que ejecute `mvn package` + ambas suites en cada push

> No invertir en tests del motor Fabric8/Jobs (Kubernetes Mock Server): ese motor se
> retira en la Fase 7; el esfuerzo de testing va al protocolo que lo sustituye.

## 12. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| JTLs enormes en pruebas largas | Summariser/muestreo, compresión, subida en streaming, retención corta de crudos |
| Workers OOMKilled con muchos hilos | Dimensionar `-Xmx` vs hilos por pod; preferir más pods con menos hilos c/u |
| La carga afecta a otros equipos del cluster | Namespace dedicado, node pool propio, `ResourceQuota` |
| Prueba apuntando al entorno equivocado | Lista blanca por entorno + confirmación + roles |
| Datos CSV duplicados entre pods | Partición por `pod.index` / `pod.count` |
| Reinicio del orquestador durante una prueba | Estado en BD; con worker-pool (Fase 7) los workers siguen ejecutando y entregando. Hoy: reconciliación por labels de los Jobs |
| Resultados inconsistentes entre pods (relojes) | NTP del cluster + fusión por timestamp; validar en Fase 2 |
| El almacén de artefactos del orquestador se llena | Job de limpieza `@Scheduled` + retención corta de crudos + alerta de uso del volumen (pendiente, Fase 6) |
| ~~PVC RWX no compartible entre namespaces~~ | Obsoleto tras el pivote (Fase 7): los resultados viajan por HTTP y el volumen es propio del orquestador |
| Shard huérfano (worker muere a mitad de prueba) | Heartbeat + timeout: el shard se marca fallido (o se reasigna) y la ejecución cierra con fallo parcial en vez de colgarse |
| JTLs grandes subidos por HTTP | gzip obligatorio + summariser/muestreo en el worker + límite de tamaño configurable en el orquestador |
| Réplicas worker con versión desalineada | Handshake de versión en el claim: el orquestador rechaza workers incompatibles |
| Pool ocioso consume recursos entre pruebas | Escalar réplicas del worker service según necesidad (manual o HPA); el agente ocioso solo hace polling ligero |
| Scheduler en 1 réplica (pod caído ⇒ chequeo perdido) | Aceptado: la plataforma repone el pod y la siguiente corrida cron ejecuta; catch-up de la corrida perdida como opción futura |
| Cabecera `X-User` spoofeable (identidad v1) | No exponer la API fuera de la red interna hasta OIDC (Fase 6) |
| SSE detrás de ingress/LB (timeouts de conexiones largas) | Timeouts/keep-alive largos en el ingress + reconexión automática en el cliente cuando exista UI |

## 13. Decisiones abiertas

- ~~Almacén de artefactos~~ → **Resuelto (2026-07-19): almacén propio del orquestador; los workers entregan resultados por HTTP.** El PVC compartido queda obsoleto con la Fase 7.
- ~~Métricas en vivo~~ → **Resuelto (2026-07-19): en producción NO hay Grafana/InfluxDB.** Progreso en vivo = SSE; métricas finales = agregación de JTL. El stack Grafana/Influx queda como demo local opcional y el Backend Listener solo en scripts de demo.
- ~~Motor de ejecución~~ → **Resuelto (2026-07-19): worker-pool por pull (Fase 7); el motor de K8s Jobs se retira al validarlo en minikube.**
- ~~UI v1~~ → **Resuelto: single-page vanilla servida por el orquestador** (implementada, con editor in-app). Monaco/SPA solo si el editor lo exigiera más adelante.
- ~~Modelado del monitoreo~~ → **Resuelto (2026-07-19): un preset ligero por servicio, agrupados en Schedules; alertas por webhook HTTP + historial en UI** (email descartado por ahora).
- Sizing de referencia del worker (hilos máximos por réplica según CPU/RAM). → Sigue abierta; medir con cargas mayores.
- Intervalo de polling del claim y timeout de heartbeat (valores por defecto). → Definir en Fase 7 midiendo en minikube.
- Aviso del validador cuando un `.jmx` traiga Backend Listener (para que no llegue a prod). → Añadir en Fase 7.

## 14. Estructura de repositorio propuesta

```
jmeter-orchestrator/
├── orchestrator/                # Servicio Quarkus (JDK 21)
│   ├── src/main/java/.../rest/      # Recursos REST + SSE
│   ├── src/main/java/.../domain/    # Entidades Panache, servicios
│   ├── src/main/java/.../k8s/       # Fabric8/Jobs (se retira al completar la Fase 7)
│   ├── src/main/java/.../results/   # Fusión de JTL, reporte, resumen
│   ├── src/main/java/.../storage/   # Almacén local de artefactos del orquestador
│   └── src/main/resources/db/migration/   # Flyway
├── worker/                      # Dockerfile JMeter + agente pull (Fase 7; hoy entrypoint.sh de Jobs)
├── deploy/                      # Manifiestos (orquestador, workers; monitoring/ = SOLO demo local)
├── scripts-plantilla/           # .jmx base parametrizado con __P()
└── docs/                        # Este plan, convenciones, runbooks
```
