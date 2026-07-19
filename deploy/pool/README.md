# Despliegue — topología worker-pool por pull (Fase 7)

Manifiestos del modelo productivo: **orchestrator (1 réplica)** + **jmeter-worker
(N réplicas)** como microservicios normales, en el namespace `jmeter-load`. Sin PVC
compartido entre servicios y **sin RBAC de Jobs**: el orquestador no usa el API de
Kubernetes en runtime; los workers reclaman shards y suben resultados por HTTP a la
API interna (`/internal/*`).

Esto reemplaza a los manifiestos del motor de Jobs (`deploy/0X-*.yaml`,
`local-minikube-orchestrator.yaml`), que se retiran junto con Fabric8.

## Requisitos previos
- Imágenes en el cluster/minikube: `jmeter-orchestrator:0.1.0` y `jmeter-worker:5.6.3`.
  - `cd orchestrator && mvn -DskipTests package`
  - `minikube image build -t jmeter-orchestrator:0.1.0 -f orchestrator/src/main/docker/Dockerfile.jvm ./orchestrator`
  - `minikube image build -t jmeter-worker:5.6.3 ./worker`
- SQL Server accesible. Por defecto el Secret apunta al host
  (`host.minikube.internal:1433`, como en la validación local); sobrescribir `DB_URL`
  para otro entorno.
- **Cambiar `WORKER_TOKEN`** en `10-orchestrator.yaml` antes de un despliegue real.

## Aplicar
```bash
kubectl apply -f deploy/pool/00-namespace.yaml
kubectl apply -f deploy/pool/10-orchestrator.yaml
kubectl apply -f deploy/pool/20-worker.yaml
```

## Uso / verificación
```bash
# UI + API del orquestador
kubectl port-forward -n jmeter-load svc/orchestrator 18080:80
# subir script + lanzar con nodes=N (<= replicas de jmeter-worker)
curl -F name=demo -F file=@scripts-plantilla/base.jmx -H "X-User: ana" http://localhost:18080/api/scripts
curl -X POST http://localhost:18080/api/executions -H "Content-Type: application/json" -H "X-User: ana" \
  -d '{"scriptVersionId":<VID>,"parameters":{"threads":80,"nodes":2,"duration":300,"targetProtocol":"http","targetHost":"target-api.sut.svc.cluster.local"}}'
```
Escalar el pool: `kubectl scale -n jmeter-load deploy/jmeter-worker --replicas=4`
(mantener `replicas >= nodes` de la prueba mayor).
