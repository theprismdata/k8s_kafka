/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test.k8s.cluster;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.strimzi.test.executor.Exec;
import io.strimzi.test.k8s.KubeClient;
import io.strimzi.test.k8s.exceptions.KubeClusterException;
import io.strimzi.test.k8s.cmdClient.KubeCmdClient;
import io.strimzi.test.k8s.cmdClient.Kubectl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;

/**
 * A {@link KubeCluster} implementation for {@code minikube}.
 */
public class Minikube implements KubeCluster {

    public static final String CMD = "minikube";
    private static final String OLM_NAMESPACE = "operators";
    private static final Logger LOGGER = LogManager.getLogger(Minikube.class);


    @Override
    public boolean isAvailable() {
        return Exec.isExecutableOnPath(CMD);
    }

    @Override
    public boolean isClusterUp() {
        List<String> cmd = Arrays.asList(CMD, "status");
        try {
            return Exec.exec(cmd).exitStatus();
        } catch (KubeClusterException e) {
            LOGGER.debug("'" + String.join(" ", cmd) + "' failed. Please double check connectivity to your cluster!");
            LOGGER.debug(e);
            return false;
        }
    }

    @Override
    public KubeCmdClient defaultCmdClient() {
        return new Kubectl();
    }

    @Override
    public KubeClient defaultClient() {
        return new KubeClient(new DefaultKubernetesClient(CONFIG), "default");
    }

    public String toString() {
        return CMD;
    }

    @Override
    public String defaultOlmNamespace() {
        return OLM_NAMESPACE;
    }
}
