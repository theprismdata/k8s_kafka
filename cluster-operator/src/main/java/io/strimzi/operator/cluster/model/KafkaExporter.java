/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategy;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategyBuilder;
import io.fabric8.kubernetes.api.model.apps.RollingUpdateDeploymentBuilder;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaExporterResources;
import io.strimzi.api.kafka.model.KafkaExporterSpec;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.template.KafkaExporterTemplate;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KafkaExporter extends AbstractModel {
    protected static final String APPLICATION_NAME = "kafka-exporter";

    // Configuration for mounting certificates
    protected static final String KAFKA_EXPORTER_CERTS_VOLUME_NAME = "kafka-exporter-certs";
    protected static final String KAFKA_EXPORTER_CERTS_VOLUME_MOUNT = "/etc/kafka-exporter/kafka-exporter-certs/";
    protected static final String CLUSTER_CA_CERTS_VOLUME_NAME = "cluster-ca-certs";
    protected static final String CLUSTER_CA_CERTS_VOLUME_MOUNT = "/etc/kafka-exporter/cluster-ca-certs/";

    // Configuration defaults
    /*test*/ static final int DEFAULT_HEALTHCHECK_DELAY = 15;
    /*test*/ static final int DEFAULT_HEALTHCHECK_TIMEOUT = 15;
    /*test*/ static final int DEFAULT_HEALTHCHECK_PERIOD = 30;
    public static final Probe READINESS_PROBE_OPTIONS = new ProbeBuilder().withTimeoutSeconds(DEFAULT_HEALTHCHECK_TIMEOUT).withInitialDelaySeconds(DEFAULT_HEALTHCHECK_DELAY).withPeriodSeconds(DEFAULT_HEALTHCHECK_PERIOD).build();

    protected static final String ENV_VAR_KAFKA_EXPORTER_LOGGING = "KAFKA_EXPORTER_LOGGING";
    protected static final String ENV_VAR_KAFKA_EXPORTER_KAFKA_VERSION = "KAFKA_EXPORTER_KAFKA_VERSION";
    protected static final String ENV_VAR_KAFKA_EXPORTER_GROUP_REGEX = "KAFKA_EXPORTER_GROUP_REGEX";
    protected static final String ENV_VAR_KAFKA_EXPORTER_TOPIC_REGEX = "KAFKA_EXPORTER_TOPIC_REGEX";
    protected static final String ENV_VAR_KAFKA_EXPORTER_KAFKA_SERVER = "KAFKA_EXPORTER_KAFKA_SERVER";
    protected static final String ENV_VAR_KAFKA_EXPORTER_ENABLE_SARAMA = "KAFKA_EXPORTER_ENABLE_SARAMA";

    protected static final String CO_ENV_VAR_CUSTOM_KAFKA_EXPORTER_POD_LABELS = "STRIMZI_CUSTOM_KAFKA_EXPORTER_LABELS";

    protected String groupRegex = ".*";
    protected String topicRegex = ".*";
    protected boolean saramaLoggingEnabled;
    protected String logging;
    protected String version;

    protected List<ContainerEnvVar> templateContainerEnvVars;
    protected SecurityContext templateContainerSecurityContext;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_KAFKA_EXPORTER_POD_LABELS);
        if (value != null) {
            DEFAULT_POD_LABELS.putAll(Util.parseMap(value));
        }
    }

    /**
     * Constructor
     *
     * @param reconciliation The reconciliation
     * @param resource Kubernetes resource with metadata containing the namespace and cluster name
     */
    protected KafkaExporter(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, APPLICATION_NAME);
        this.name = KafkaExporterResources.deploymentName(cluster);
        this.replicas = 1;
        this.readinessPath = "/healthz";
        this.readinessProbeOptions = READINESS_PROBE_OPTIONS;
        this.livenessPath = "/healthz";
        this.livenessProbeOptions = READINESS_PROBE_OPTIONS;

        this.saramaLoggingEnabled = false;
        this.mountPath = "/var/lib/kafka";

        // Kafka Exporter is all about metrics - they are always enabled
        this.isMetricsEnabled = true;

    }

    /**
     * Builds the KafkaExporter model from the Kafka custom resource. If KafkaExporter is not enabled, it will return null.
     *
     * @param reconciliation    Reconciliation marker for logging
     * @param kafkaAssembly     The Kafka CR
     * @param versions          The list of supported Kafka versions
     *
     * @return                  KafkaExporter model object when Kafka Exporter is enabled or null if it is disabled.
     */
    public static KafkaExporter fromCrd(Reconciliation reconciliation, Kafka kafkaAssembly, KafkaVersion.Lookup versions) {
        KafkaExporterSpec spec = kafkaAssembly.getSpec().getKafkaExporter();

        if (spec != null) {
            KafkaExporter kafkaExporter = new KafkaExporter(reconciliation, kafkaAssembly);

            kafkaExporter.setResources(spec.getResources());

            if (spec.getReadinessProbe() != null) {
                kafkaExporter.setReadinessProbe(spec.getReadinessProbe());
            }

            if (spec.getLivenessProbe() != null) {
                kafkaExporter.setLivenessProbe(spec.getLivenessProbe());
            }

            kafkaExporter.groupRegex = spec.getGroupRegex();
            kafkaExporter.topicRegex = spec.getTopicRegex();

            String image = spec.getImage();
            if (image == null) {
                KafkaClusterSpec kafkaClusterSpec = kafkaAssembly.getSpec().getKafka();
                image = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KAFKA_EXPORTER_IMAGE, versions.kafkaImage(kafkaClusterSpec.getImage(), versions.defaultVersion().version()));
            }
            kafkaExporter.setImage(image);

            kafkaExporter.logging = spec.getLogging();
            kafkaExporter.saramaLoggingEnabled = spec.getEnableSaramaLogging();

            if (spec.getTemplate() != null) {
                KafkaExporterTemplate template = spec.getTemplate();

                if (template.getDeployment() != null && template.getDeployment().getMetadata() != null) {
                    kafkaExporter.templateDeploymentLabels = template.getDeployment().getMetadata().getLabels();
                    kafkaExporter.templateDeploymentAnnotations = template.getDeployment().getMetadata().getAnnotations();
                }

                if (template.getContainer() != null && template.getContainer().getEnv() != null) {
                    kafkaExporter.templateContainerEnvVars = template.getContainer().getEnv();
                }

                if (template.getContainer() != null && template.getContainer().getSecurityContext() != null) {
                    kafkaExporter.templateContainerSecurityContext = template.getContainer().getSecurityContext();
                }

                if (template.getServiceAccount() != null && template.getServiceAccount().getMetadata() != null) {
                    kafkaExporter.templateServiceAccountLabels = template.getServiceAccount().getMetadata().getLabels();
                    kafkaExporter.templateServiceAccountAnnotations = template.getServiceAccount().getMetadata().getAnnotations();
                }

                ModelUtils.parsePodTemplate(kafkaExporter, template.getPod());
                kafkaExporter.templatePodLabels = Util.mergeLabelsOrAnnotations(kafkaExporter.templatePodLabels, DEFAULT_POD_LABELS);
            }

            kafkaExporter.version = versions.supportedVersion(kafkaAssembly.getSpec().getKafka().getVersion()).version();
            kafkaExporter.setOwnerReference(kafkaAssembly);

            return kafkaExporter;
        } else {
            return null;
        }
    }

    protected List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(1);
        portList.add(createContainerPort(METRICS_PORT_NAME, METRICS_PORT, "TCP"));
        return portList;
    }

    public Deployment generateDeployment(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        DeploymentStrategy updateStrategy = new DeploymentStrategyBuilder()
                .withType("RollingUpdate")
                .withRollingUpdate(new RollingUpdateDeploymentBuilder()
                        .withMaxSurge(new IntOrString(1))
                        .withMaxUnavailable(new IntOrString(0))
                        .build())
                .build();

        return createDeployment(
                updateStrategy,
                Collections.emptyMap(),
                Collections.emptyMap(),
                getMergedAffinity(),
                getInitContainers(imagePullPolicy),
                getContainers(imagePullPolicy),
                getVolumes(isOpenShift),
                imagePullSecrets
        );
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
        List<Container> containers = new ArrayList<>(1);

        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(getImage())
                .withCommand("/opt/kafka-exporter/kafka_exporter_run.sh")
                .withEnv(getEnvVars())
                .withPorts(getContainerPortList())
                .withLivenessProbe(ProbeGenerator.httpProbe(livenessProbeOptions, livenessPath, METRICS_PORT_NAME))
                .withReadinessProbe(ProbeGenerator.httpProbe(readinessProbeOptions, readinessPath, METRICS_PORT_NAME))
                .withResources(getResources())
                .withVolumeMounts(createTempDirVolumeMount(),
                        VolumeUtils.createVolumeMount(KAFKA_EXPORTER_CERTS_VOLUME_NAME, KAFKA_EXPORTER_CERTS_VOLUME_MOUNT),
                        VolumeUtils.createVolumeMount(CLUSTER_CA_CERTS_VOLUME_NAME, CLUSTER_CA_CERTS_VOLUME_MOUNT))
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .withSecurityContext(templateContainerSecurityContext)
                .build();

        containers.add(container);

        return containers;
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();

        varList.add(buildEnvVar(ENV_VAR_KAFKA_EXPORTER_LOGGING, Integer.toString(loggingMapping(logging))));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_EXPORTER_KAFKA_VERSION, version));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_EXPORTER_GROUP_REGEX, groupRegex));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_EXPORTER_TOPIC_REGEX, topicRegex));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_EXPORTER_KAFKA_SERVER, KafkaResources.bootstrapServiceName(cluster) + ":" + KafkaCluster.REPLICATION_PORT));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_EXPORTER_ENABLE_SARAMA, String.valueOf(saramaLoggingEnabled)));

        // Add shared environment variables used for all containers
        varList.addAll(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateContainerEnvVars);

        return varList;
    }

    private static int loggingMapping(String logLevel) {
        if (logLevel.equalsIgnoreCase("info")) {
            return 0;
        } else if (logLevel.equalsIgnoreCase("debug")) {
            return 1;
        } else if (logLevel.equalsIgnoreCase("trace")) {
            return 2;
        } else {
            return 0;
        }
    }

    private List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(3);

        volumeList.add(createTempDirVolume());
        volumeList.add(VolumeUtils.createSecretVolume(KAFKA_EXPORTER_CERTS_VOLUME_NAME, KafkaExporterResources.secretName(cluster), isOpenShift));
        volumeList.add(VolumeUtils.createSecretVolume(CLUSTER_CA_CERTS_VOLUME_NAME, AbstractModel.clusterCaCertSecretName(cluster), isOpenShift));

        return volumeList;
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return null;
    }

    @Override
    protected String getServiceAccountName() {
        return KafkaExporterResources.serviceAccountName(cluster);
    }

    /**
     * Generate the Secret containing the Kafka Exporter certificate signed by the cluster CA certificate used for TLS based
     * internal communication with Kafka and Zookeeper.
     * It also contains the related Kafka Exporter private key.
     *
     * @param clusterCa The cluster CA.
     * @param isMaintenanceTimeWindowsSatisfied Indicates whether we are in the maintenance window or not.
     *                                          This is used for certificate renewals
     * @return The generated Secret.
     */
    public Secret generateSecret(ClusterCa clusterCa, boolean isMaintenanceTimeWindowsSatisfied) {
        Secret secret = clusterCa.kafkaExporterSecret();
        return ModelUtils.buildSecret(reconciliation, clusterCa, secret, namespace, KafkaExporterResources.secretName(cluster), name,
                "kafka-exporter", labels, createOwnerReference(), isMaintenanceTimeWindowsSatisfied);
    }
}
