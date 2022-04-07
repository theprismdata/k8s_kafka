/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.api.kafka.model.Constants;
import io.strimzi.api.kafka.model.UnknownPropertyPreserving;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Representation of a template for Kafka MirrorMaker 2.0 resources.
 */
@Buildable(
        editableEnabled = false,
        builderPackage = Constants.FABRIC8_KUBERNETES_API
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"deployment", "pod", "apiService", "podDisruptionBudget", "mirrorMaker2Container", "serviceAccount", "jmxSecret"})
@EqualsAndHashCode
public class KafkaMirrorMaker2Template implements Serializable, UnknownPropertyPreserving {
    private static final long serialVersionUID = 1L;

    private DeploymentTemplate deployment;
    private PodTemplate pod;
    private InternalServiceTemplate apiService;
    private PodDisruptionBudgetTemplate podDisruptionBudget;
    private ContainerTemplate mirrorMaker2Container;
    private ResourceTemplate serviceAccount;
    private ResourceTemplate jmxSecret;
    private Map<String, Object> additionalProperties = new HashMap<>(0);

    @Description("Template for Kafka MirrorMaker 2.0 `Deployment`.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public DeploymentTemplate getDeployment() {
        return deployment;
    }

    public void setDeployment(DeploymentTemplate deployment) {
        this.deployment = deployment;
    }

    @Description("Template for Kafka MirrorMaker 2.0 `Pods`.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public PodTemplate getPod() {
        return pod;
    }

    public void setPod(PodTemplate pod) {
        this.pod = pod;
    }

    @Description("Template for Kafka MirrorMaker 2.0 API `Service`.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public InternalServiceTemplate getApiService() {
        return apiService;
    }

    public void setApiService(InternalServiceTemplate apiService) {
        this.apiService = apiService;
    }

    @Description("Template for Kafka MirrorMaker 2.0 `PodDisruptionBudget`.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public PodDisruptionBudgetTemplate getPodDisruptionBudget() {
        return podDisruptionBudget;
    }

    public void setPodDisruptionBudget(PodDisruptionBudgetTemplate podDisruptionBudget) {
        this.podDisruptionBudget = podDisruptionBudget;
    }

    @Description("Template for the Kafka MirrorMaker 2.0 container")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public ContainerTemplate getMirrorMaker2Container() {
        return mirrorMaker2Container;
    }

    public void setMirrorMaker2Container(ContainerTemplate mirrorMaker2Container) {
        this.mirrorMaker2Container = mirrorMaker2Container;
    }

    @Description("Template for the Kafka MirrorMaker 2 service account.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public ResourceTemplate getServiceAccount() {
        return serviceAccount;
    }

    public void setServiceAccount(ResourceTemplate serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    @Description("Template for Secret of the Kafka MirrorMaker 2 Cluster JMX authentication.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public ResourceTemplate getJmxSecret() {
        return jmxSecret;
    }
    public void setJmxSecret(ResourceTemplate jmxSecret) {
        this.jmxSecret = jmxSecret;
    }

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @Override
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }
}
