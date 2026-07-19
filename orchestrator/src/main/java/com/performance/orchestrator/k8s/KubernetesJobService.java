package com.performance.orchestrator.k8s;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Crea, observa y borra Jobs de JMeter en Kubernetes.
 *
 * Estrategia (seccion 4 del plan): NO se usa el modo maestro/esclavo RMI.
 * Cada ejecucion es un unico Job "Indexed" con completions=parallelism=N;
 * cada pod recibe su JOB_COMPLETION_INDEX y se comporta como un shard
 * independiente (jmeter -n). El label executionId permite reconciliar el
 * estado tras un reinicio del orquestador.
 */
@ApplicationScoped
public class KubernetesJobService {

    public static final String LABEL_APP = "app.kubernetes.io/name";
    public static final String LABEL_EXECUTION = "orchestrator/executionId";

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "orchestrator.k8s.namespace", defaultValue = "jmeter-workers")
    String namespace;

    @ConfigProperty(name = "orchestrator.k8s.worker-image", defaultValue = "jmeter-worker:5.6.3")
    String workerImage;

    @ConfigProperty(name = "orchestrator.k8s.image-pull-policy", defaultValue = "IfNotPresent")
    String imagePullPolicy;

    @ConfigProperty(name = "orchestrator.k8s.worker.cpu-request", defaultValue = "500m")
    String cpuRequest;

    @ConfigProperty(name = "orchestrator.k8s.worker.cpu-limit", defaultValue = "2")
    String cpuLimit;

    @ConfigProperty(name = "orchestrator.k8s.worker.mem-request", defaultValue = "1Gi")
    String memRequest;

    @ConfigProperty(name = "orchestrator.k8s.worker.mem-limit", defaultValue = "2Gi")
    String memLimit;

    @ConfigProperty(name = "orchestrator.k8s.deadline-margin-seconds", defaultValue = "120")
    int deadlineMargin;

    @ConfigProperty(name = "orchestrator.k8s.ttl-after-finished-seconds", defaultValue = "600")
    int ttlAfterFinished;

    @ConfigProperty(name = "orchestrator.k8s.artifacts-pvc", defaultValue = "jmeter-artifacts")
    String artifactsPvc;

    @ConfigProperty(name = "orchestrator.k8s.artifacts-mount", defaultValue = "/artifacts")
    String artifactsMount;

    public String jobName(long executionId) {
        return "jmeter-exec-" + executionId;
    }

    /** Crea el Job Indexed para la ejecucion. Devuelve el nombre del Job. */
    public String createExecutionJob(LaunchSpec spec) {
        String name = jobName(spec.executionId());

        Map<String, String> labels = Map.of(
                LABEL_APP, "jmeter-worker",
                LABEL_EXECUTION, String.valueOf(spec.executionId()));

        List<EnvVar> env = List.of(
                new EnvVar("EXECUTION_ID", String.valueOf(spec.executionId()), null),
                new EnvVar("SCRIPT_PATH", artifactsMount + "/" + spec.scriptPath(), null),
                new EnvVar("RESULTS_DIR", artifactsMount + "/" + spec.resultsDir(), null),
                new EnvVar("POD_COUNT", String.valueOf(spec.nodes()), null),
                new EnvVar("THREADS_TOTAL", String.valueOf(spec.totalThreads()), null),
                new EnvVar("RAMP_UP", String.valueOf(spec.rampUpSeconds()), null),
                new EnvVar("DURATION", String.valueOf(spec.durationSeconds()), null),
                new EnvVar("TARGET_HOST", nullToEmpty(spec.targetHost()), null),
                new EnvVar("TARGET_PROTOCOL", nullToEmpty(spec.targetProtocol()), null),
                new EnvVar("EXTRA_PROPS", nullToEmpty(spec.extraProps()), null));

        long deadline = (long) spec.durationSeconds() + spec.rampUpSeconds() + deadlineMargin;

        Job job = new JobBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withCompletionMode("Indexed")
                    .withCompletions(spec.nodes())
                    .withParallelism(spec.nodes())
                    .withBackoffLimit(0)
                    .withActiveDeadlineSeconds(deadline)
                    .withTtlSecondsAfterFinished(ttlAfterFinished)
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewVolume()
                                .withName("artifacts")
                                .withNewPersistentVolumeClaim()
                                    .withClaimName(artifactsPvc)
                                .endPersistentVolumeClaim()
                            .endVolume()
                            .addNewContainer()
                                .withName("jmeter")
                                .withImage(workerImage)
                                .withImagePullPolicy(imagePullPolicy)
                                .withEnv(env)
                                .withResources(new ResourceRequirementsBuilder()
                                        .withRequests(Map.of(
                                                "cpu", new Quantity(cpuRequest),
                                                "memory", new Quantity(memRequest)))
                                        .withLimits(Map.of(
                                                "cpu", new Quantity(cpuLimit),
                                                "memory", new Quantity(memLimit)))
                                        .build())
                                .addNewVolumeMount()
                                    .withName("artifacts")
                                    .withMountPath(artifactsMount)
                                .endVolumeMount()
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        client.batch().v1().jobs().inNamespace(namespace).resource(job).create();
        Log.infof("Job creado %s (namespace=%s, nodes=%d, threads=%d)",
                name, namespace, spec.nodes(), spec.totalThreads());
        return name;
    }

    /** Devuelve el Job de la ejecucion o null si ya no existe. */
    public Job getJob(long executionId) {
        return client.batch().v1().jobs().inNamespace(namespace).withName(jobName(executionId)).get();
    }

    /** Lista los Jobs vivos gestionados por el orquestador (para reconciliacion). */
    public List<Job> listOrchestratorJobs() {
        return client.batch().v1().jobs().inNamespace(namespace)
                .withLabel(LABEL_APP, "jmeter-worker")
                .list().getItems();
    }

    /** Borra el Job (y sus pods) de una ejecucion; usado en cancelacion. */
    public boolean deleteExecutionJob(long executionId) {
        return !client.batch().v1().jobs().inNamespace(namespace)
                .withName(jobName(executionId))
                .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.BACKGROUND)
                .delete().isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
