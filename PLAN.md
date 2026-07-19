# Plan de Proyecto — Orquestador de Pruebas JMeter

**Stack:** Quarkus 3 (LTS) · Java 21 · Kubernetes (minikube en dev, AKS como destino) · Apache JMeter 5.6.x · **Microsoft SQL Server**

---

## 0. Estado del proyecto (actualizado 2026-07-19)

Las Fases 0–4 están **implementadas y probadas end-to-end** en minikube: API completa
(scripts, versiones, validación, pre-test, presets, ejecuciones, SSE, cancelación,
relanzamiento), ejecución distribuida real en 2 pods contra un SUT de demo y métricas
en vivo en Grafana (InfluxDB + Prometheus). **Existe además una UI web** (single-page en
`orchestrator/src/main/resources/META-INF/resources/index.html`) que cubre scripts,
presets, ejecuciones e historial, con progreso SSE en vivo (barra de pods + tiles).
Pendientes principales: endurecer la UI (editor de scripts in-app, deep-link a Grafana),
OIDC, reporte HTML nativo de JMeter (`jmeter -g`), partición fina de CSV por pod. El
detalle operativo para retomar el trabajo vive en [CLAUDE.md](CLAUDE.md).

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
- **Artefactos en PVC RWX** (no Azure Blob) en el entorno local; ver §13.

## 1. Resumen

Plataforma que centraliza la ejecución de pruebas de rendimiento con JMeter sobre Kubernetes. Permite subir, editar, versionar y pre-testear scripts `.jmx`; configurar pruebas de carga y estrés mediante parámetros reutilizables; distribuir la carga entre varios pods; y conservar un historial completo de cada ejecución (parámetros, versión del script, nodos, métricas y artefactos) para relanzarla en un clic.

## 2. Objetivos

1. Ejecutar pruebas distribuidas en N pods sin depender del modo maestro/esclavo (RMI) de JMeter.
2. Gestionar scripts: subida, edición, validación, versionado y pre-test (dry run) antes del lanzamiento masivo.
3. Configurar pruebas de forma declarativa (carga, estrés, pico, resistencia) con presets reutilizables.
4. Registrar cada ejecución de forma auditable: quién, cuándo, con qué script/versión, con qué parámetros y con qué resultado.
5. Relanzar cualquier prueba anterior en menos de 30 segundos a partir de su preset o de su historial.

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

## 4. Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│  UI Web (Qute v1 / SPA v2)                                   │
└───────────────┬──────────────────────────────────────────────┘
                │ REST + SSE
┌───────────────▼───────────────┐        ┌─────────────────────┐
│  ORQUESTADOR (Quarkus/JDK21)  │───────▶│ SQL Server          │
│  · API scripts/presets/execs  │        │ (metadatos, resumen)│
│  · Motor de ejecuciones       │        └─────────────────────┘
│  · Cliente Kubernetes (Fabric8│        ┌─────────────────────┐
│    Jobs + reconciler)         │───────▶│ Almacén artefactos  │
│  · Agregador de resultados    │        │ Azure Blob o PVC RWX│
└───────────────┬───────────────┘        │ (.jmx .jtl reportes)│
                │ crea Jobs / observa    └─────────▲───────────┘
┌───────────────▼───────────────┐                  │ suben .jtl
│  WORKERS JMETER (K8s Jobs)    │──────────────────┘
│  pod-0 … pod-N  (jmeter -n)   │
│  Backend Listener ────────────────▶ InfluxDB/Prometheus ▶ Grafana
└───────────────────────────────┘
```

### Decisión clave: distribución por *sharding*, no RMI

El modo distribuido nativo de JMeter (maestro/esclavo por RMI) es frágil en Kubernetes: puertos dinámicos, serialización, y un maestro que se vuelve punto único de falla. En su lugar:

1. El orquestador divide la carga total entre N pods (ej.: 1 000 hilos / 4 pods → `-Jthreads=250` por pod).
2. Cada pod ejecuta `jmeter -n` de forma independiente y escribe su propio `.jtl`.
3. Al finalizar, el orquestador fusiona los `.jtl` y genera el dashboard HTML consolidado (`jmeter -g merged.jtl -o report/`).

Ventajas: pods homogéneos e independientes, escala lineal, la caída de un pod no tumba la prueba, cero RMI.

### Flujo de una ejecución

1. El usuario elige script (+versión) y un preset, o define parámetros ad hoc.
2. El orquestador valida parámetros y guardrails (host destino permitido, tope de hilos/pods).
3. Crea el registro `Execution` en estado `PENDING` y N Jobs de Kubernetes etiquetados con el `executionId`.
4. Observa los Jobs/pods vía reconciliación programada (`@Scheduled`) y publica el progreso por SSE.
5. Los workers suben su `.jtl` y log al almacén de artefactos al terminar.
6. El agregador fusiona resultados, genera el reporte HTML, calcula el resumen (TPS, p90/p95/p99, % error) y cierra la ejecución como `COMPLETED` o `FAILED`.

## 5. Stack técnico

| Capa | Elección | Notas |
|---|---|---|
| Runtime | Quarkus 3 LTS + JDK 21 | Virtual threads (`@RunOnVirtualThread`) para las llamadas bloqueantes al API de K8s |
| API | `quarkus-rest` + SmallRye OpenAPI | SSE para progreso en vivo |
| Persistencia | Hibernate ORM Panache + SQL Server + Flyway (T-SQL) | Metadatos y resúmenes; parámetros JSON en `NVARCHAR(MAX)` + `CHECK (ISJSON()=1)` |
| Kubernetes | Fabric8 (`quarkus-kubernetes-client`) | Creación de Jobs *Indexed*; estado por reconciliación `@Scheduled` (no informers) |
| Artefactos | PVC RWX (decidido para local) · Azure Blob/Files en AKS | `.jmx`, `.jtl`, reportes HTML, logs |
| Workers | Imagen propia `jmeter:5.6.x` | Plugins Manager + plugins comunes (Ultimate Thread Group, etc.) preinstalados |
| Métricas en vivo | Backend Listener → InfluxDB (cliente) + Prometheus (servidor) | Dashboards Grafana: "JMeter - Carga" (InfluxDB) y "API objetivo" (Prometheus) |
| Observabilidad propia | Micrometer + Prometheus, logs JSON con `io.quarkus.logging.Log` | Health checks para readiness/liveness |
| UI | Qute server-side (v1) → SPA con editor Monaco (v2) | El editor XML es el driver de la SPA |

## 6. Modelo de datos (borrador)

- **Script**: id, nombre, descripción, tags, createdBy, createdAt.
- **ScriptVersion**: id, scriptId, número, rutaBlob, checksumSHA256, notas, createdAt. *Inmutable: cada guardado crea versión nueva.*
- **Preset**: id, nombre, scriptId, tipoPrueba (`CARGA | ESTRES | PICO | RESISTENCIA`), parámetros JSON, targetEnv, createdBy.
- **Execution**: id, presetId (nullable), scriptVersionId, parámetrosEfectivos JSON, estado (`PENDING | RUNNING | AGGREGATING | COMPLETED | FAILED | CANCELLED`), nodos, startedAt, finishedAt, resumen JSON (tps, p90, p95, p99, errorPct, muestras), rutaResultados, lanzadaPor. *Pendiente de añadir:* `workerImage` (imagen+tag del worker usada), para que "relanzar la prueba del sprint pasado" sea comparable aunque la imagen por defecto haya cambiado.
- **ExecutionNode**: id, executionId, índice, podName, estado, exitCode, rutaJtl, rutaLog.

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

## 8. Funcionalidades clave

### 8.1 Gestión y edición de scripts
- Subida con validación: XML bien formado, raíz `jmeterTestPlan`, tope de tamaño.
- Versionado inmutable con checksum → toda ejecución referencia una versión exacta.
- Edición v1: editor de texto (Monaco) sobre el XML, validando al guardar.
- Edición v2: formularios para los campos frecuentes (HTTP Request Defaults, User Defined Variables, CSV Data Set) parseando el XML.
- **Convención de parametrización**: los scripts usan `${__P(threads,10)}`, `${__P(rampUp,60)}`, `${__P(duration,300)}`, `${__P(targetHost,...)}` para que el orquestador los controle vía `-J` sin tocar el XML. Se entrega una plantilla `.jmx` base.

### 8.2 Pre-test (dry run)
- Ejecuta el script en 1 pod con `-Jthreads=1 -Jduration=30` (o 1 iteración).
- Verifica: arranque sin errores de parseo, respuesta del target, aserciones OK, % de error bajo umbral.
- Devuelve veredicto + primeras muestras + log. Si falla, el lanzamiento masivo se bloquea (con opción de forzar para roles autorizados).

### 8.3 Tipos de prueba y presets
- **Carga**: hilos constantes durante una duración objetivo.
- **Estrés**: escalera de incrementos hasta observar degradación (vía Ultimate Thread Group o etapas encadenadas).
- **Pico**: subida y bajada súbitas.
- **Resistencia (soak)**: carga media sostenida por horas.

El preset guarda tipo + parámetros; el motor los traduce a propiedades `-J` por pod.

### 8.4 Ejecución distribuida
- Reparto de hilos por pod y partición de datos: `-Jpod.index` / `-Jpod.count` para que los CSV no dupliquen datos entre pods.
- Reparto del resto cuando hilos no es divisible entre pods: los primeros `total % pods` pods reciben +1 hilo (determinista, implementado en `entrypoint.sh`); cada worker loguea sus hilos efectivos.
- Jobs con `ttlSecondsAfterFinished`, `activeDeadlineSeconds` = duración + margen, y requests/limits explícitos.
- Reconciliación: si el orquestador se reinicia a mitad de una prueba, reconstruye el estado desde los Jobs vivos usando las labels de `executionId`.

### 8.5 Historial y resultados
- Registro completo por ejecución + artefactos: JTL por pod, JTL fusionado, reporte HTML, logs.
- Resumen de métricas en BD para listar y filtrar sin abrir artefactos.
- Retención configurable (ej.: artefactos crudos 90 días; metadatos y resúmenes, indefinido). **Mecanismo (pendiente de implementar):** job de limpieza `@Scheduled` en el orquestador que borre del PVC los artefactos crudos vencidos; con PVC (a diferencia de Blob) llenar el volumen es un riesgo real (ver §12).
- El reporte HTML consolidado nativo de JMeter (`jmeter -g merged.jtl -o report/`) está **pendiente**; hoy se sirve un resumen HTML propio generado por el agregador.

### 8.6 Cancelación (contrato implementado)
- `POST /api/executions/{id}/cancel` solo aplica a ejecuciones no terminales (409 si ya terminó).
- Borra el Job de K8s con propagación `BACKGROUND` (los pods mueren de forma asíncrona); si el borrado falla se loguea y la cancelación continúa igualmente.
- La ejecución pasa a `CANCELLED` con `finishedAt`, se registra evento de auditoría y se cierra el stream SSE.
- Los `.jtl` parciales ya escritos en el almacén **se conservan** para inspección manual; no se agrega ni se genera reporte consolidado de una ejecución cancelada.
- El reconciler solo itera ejecuciones no terminales, así que una ejecución cancelada deja de reconciliarse. Para una ejecución `RUNNING`, un Job ausente se interpreta como fin de prueba (pasa a `AGGREGATING`), nunca como error — esto también cubre Jobs borrados por `ttlSecondsAfterFinished`.

## 9. Requisitos priorizados

**P0 — sin esto no sirve**
- Subir script, lanzar en N pods, registrar la ejecución y ver el reporte consolidado.
- Parámetros básicos de prueba: hilos, rampa, duración, target, número de nodos.
- Historial persistente de ejecuciones.

**P1 — mejora fuerte, fast-follow**
- Pre-test, versionado con editor, presets y relanzamiento, SSE de progreso, cancelación.

**P2 — futuro, pero condiciona el diseño**
- Comparación contra baseline, editor por formularios, programación de pruebas (cron), integración con pipelines CI/CD.

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
- [x] Versionado + validación al guardar (vía API; el editor Monaco llega con la UI)
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
- [x] Backend Listener → InfluxDB + Prometheus + dashboards Grafana en vivo (verificado en minikube)
- [ ] Editor de scripts in-app: ver/editar el XML y guardar versión nueva sin salir de la UI
  (el backend ya lo soporta: `GET .../versions/{v}/content` + `POST .../versions`)
- [ ] Resumen de métricas del **detalle** de ejecución como tiles (hoy JSON crudo) y
  deep-link a Grafana por ejecución (acotado a su ventana temporal)
- [ ] Confirmación en acciones destructivas (cancelar ejecución, borrar preset)

**Criterio:** un usuario no experto lanza una prueba guiado por la UI y ve métricas en
tiempo real. ✅ para el flujo básico; el editor in-app y los tiles del detalle son el
fast-follow de usabilidad.

### Fase 6 — Endurecimiento (continuo) — PENDIENTE
- [ ] OIDC + roles (sustituye `X-User`), lista blanca de targets por entorno, quotas
- [ ] Retención y limpieza de artefactos (job `@Scheduled`), backups de BD
- [ ] Prueba de resiliencia del propio orquestador (reinicio a mitad de ejecución → reconciliación)
- [ ] Registrar `workerImage` en `Execution` (reproducibilidad de relanzamientos)

### Deuda de calidad transversal (añadida 2026-07-19)
Hoy solo existe un test unitario (`ExecParamsTest`). Por orden de retorno:
- [ ] Tests de integración `@QuarkusTest` + Testcontainers (SQL Server) para el ciclo scripts → presets → executions contra la API real
- [ ] Kubernetes Mock Server de Fabric8 para `KubernetesJobService` y el reconciler (probar lanzamiento/cancelación/reconciliación sin cluster real)
- [ ] CI que ejecute `mvn package` + ambas suites en cada push

## 12. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| JTLs enormes en pruebas largas | Summariser/muestreo, compresión, subida en streaming, retención corta de crudos |
| Workers OOMKilled con muchos hilos | Dimensionar `-Xmx` vs hilos por pod; preferir más pods con menos hilos c/u |
| La carga afecta a otros equipos del cluster | Namespace dedicado, node pool propio, `ResourceQuota` |
| Prueba apuntando al entorno equivocado | Lista blanca por entorno + confirmación + roles |
| Datos CSV duplicados entre pods | Partición por `pod.index` / `pod.count` |
| Reinicio del orquestador durante una prueba | Estado en BD + reconciliación por labels de los Jobs |
| Resultados inconsistentes entre pods (relojes) | NTP del cluster + fusión por timestamp; validar en Fase 2 |
| El PVC de artefactos se llena | Job de limpieza `@Scheduled` + retención corta de crudos + alerta de uso del volumen (pendiente, Fase 6) |
| PVC RWX no compartible entre namespaces | En local: orquestador y workers conviven en `jmeter-workers`. En AKS: misma Azure File share montada en ambos namespaces, o mantener el mismo namespace |
| Cabecera `X-User` spoofeable (identidad v1) | No exponer la API fuera de la red interna hasta OIDC (Fase 6) |
| SSE detrás de ingress/LB (timeouts de conexiones largas) | Timeouts/keep-alive largos en el ingress + reconexión automática en el cliente cuando exista UI |

## 13. Decisiones abiertas

- ~~Almacén de artefactos~~ → **Resuelto (local): PVC RWX.** Para AKS queda elegir Azure Files (RWX, encaja con el diseño actual) vs Blob (requeriría implementar otro `StorageService`).
- ~~Métricas en vivo~~ → **Resuelto: ambos.** InfluxDB para métricas de cliente (Backend Listener nativo) + Prometheus para métricas del servidor objetivo; un dashboard Grafana por cada lado.
- UI v1: **Qute** server-side (más simple) vs arrancar directo con **SPA** (mejor para el editor). → Equipo (sigue abierta)
- ~~¿Namespace…?~~ → **Resuelto (local): todo en `jmeter-workers`** para compartir el PVC en single-node. En AKS queda decidir si node pool dedicado de performance.
- Sizing de referencia del worker (hilos máximos por pod según CPU/RAM). → Sigue abierta; medir con cargas mayores

## 14. Estructura de repositorio propuesta

```
jmeter-orchestrator/
├── orchestrator/                # Servicio Quarkus (JDK 21)
│   ├── src/main/java/.../rest/      # Recursos REST + SSE
│   ├── src/main/java/.../domain/    # Entidades Panache, servicios
│   ├── src/main/java/.../k8s/       # Fabric8: Jobs, informers, reconciliación
│   ├── src/main/java/.../results/   # Fusión de JTL, reporte, resumen
│   ├── src/main/java/.../storage/   # Blob / PVC
│   └── src/main/resources/db/migration/   # Flyway
├── worker/                      # Dockerfile JMeter + entrypoint.sh
├── deploy/                      # Helm chart o manifiestos (orquestador, RBAC, quotas)
├── scripts-plantilla/           # .jmx base parametrizado con __P()
└── docs/                        # Este plan, convenciones, runbooks
```
