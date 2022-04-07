/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.templates.specific;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.enums.DeploymentTypes;

import java.util.HashMap;
import java.util.Map;

public class ScraperTemplates {

    private ScraperTemplates() { }

    public static DeploymentBuilder scraperPod(String namespaceName, String podName) {
        Map<String, String> label = new HashMap<>();

        label.put(Constants.SCRAPER_LABEL_KEY, Constants.SCRAPER_LABEL_VALUE);
        label.put(Constants.DEPLOYMENT_TYPE, DeploymentTypes.Scraper.name());

        return new DeploymentBuilder()
            .withNewMetadata()
                .withName(podName)
                .withLabels(label)
                .withNamespace(namespaceName)
            .endMetadata()
            .withNewSpec()
                .withNewSelector()
                    .addToMatchLabels("app", podName)
                    .addToMatchLabels(label)
                .endSelector()
                .withReplicas(1)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("app", podName)
                        .addToLabels(label)
                    .endMetadata()
                    .withNewSpec()
                        .withContainers(
                            new ContainerBuilder()
                                .withName(podName)
                                .withImage("registry.access.redhat.com/ubi8/ubi:latest")
                                .withCommand("sleep")
                                .withArgs("infinity")
                                .withImagePullPolicy(Environment.COMPONENTS_IMAGE_PULL_POLICY)
                                .withResources(new ResourceRequirementsBuilder()
                                    .addToRequests("memory", new Quantity("200M"))
                                    .build())
                                .build()
                        )
                        .endSpec()
                .endTemplate()
            .endSpec();
    }
}
