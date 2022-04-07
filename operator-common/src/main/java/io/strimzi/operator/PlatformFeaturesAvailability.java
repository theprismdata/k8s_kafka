/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator;

import io.fabric8.kubernetes.api.model.APIGroup;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;
import io.strimzi.operator.common.Util;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.ParseException;
import java.util.Map;

/**
 * Gives a info about certain features availability regarding to kubernetes version
 */
public class PlatformFeaturesAvailability {
    private static final Logger LOGGER = LogManager.getLogger(PlatformFeaturesAvailability.class.getName());

    private boolean routes = false;
    private boolean builds = false;
    private boolean images = false;
    private KubernetesVersion kubernetesVersion;

    public static Future<PlatformFeaturesAvailability> create(Vertx vertx, KubernetesClient client) {
        Promise<PlatformFeaturesAvailability> pfaPromise = Promise.promise();

        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability();

        Future<VersionInfo> futureVersion = getVersionInfo(vertx, client);

        futureVersion.compose(versionInfo -> {
            String major = versionInfo.getMajor().equals("") ? Integer.toString(KubernetesVersion.MINIMAL_SUPPORTED_MAJOR) : versionInfo.getMajor();
            String minor = versionInfo.getMinor().equals("") ? Integer.toString(KubernetesVersion.MINIMAL_SUPPORTED_MINOR) : versionInfo.getMinor();
            pfa.setKubernetesVersion(new KubernetesVersion(Integer.parseInt(major.split("\\D")[0]), Integer.parseInt(minor.split("\\D")[0])));

            return checkApiAvailability(vertx, client, "route.openshift.io", "v1");
        }).compose(supported -> {
            pfa.setRoutes(supported);
            return checkApiAvailability(vertx, client, "build.openshift.io", "v1");
        }).compose(supported -> {
            pfa.setBuilds(supported);
            return checkApiAvailability(vertx, client, "image.openshift.io", "v1");
        }).compose(supported -> {
            pfa.setImages(supported);
            return Future.succeededFuture(pfa);
        }).onComplete(pfaPromise);

        return pfaPromise.future();
    }

    /**
     * Gets the Kubernetes VersionInfo. It either used from the /version endpoint or from the STRIMZI_KUBERNETES_VERSION
     * environment variable. If defined, the environment variable will take the precedence. Otherwise the API server
     * endpoint will be used.
     *
     * And example of the STRIMZI_KUBERNETES_VERSION environment variable in Cluster Operator deployment:
     * <pre><code>
     *       env:
     *         - name: STRIMZI_KUBERNETES_VERSION
     *           value: |
     *                 major=1
     *                 minor=16
     *                 gitVersion=v1.16.2
     *                 gitCommit=c97fe5036ef3df2967d086711e6c0c405941e14b
     *                 gitTreeState=clean
     *                 buildDate=2019-10-15T19:09:08Z
     *                 goVersion=go1.12.10
     *                 compiler=gc
     *                 platform=linux/amd64
     * </code></pre>
     *
     * @param vertx Instance of Vert.x
     * @param client    Fabric8 Kubernetes client
     * @return  Future with the VersionInfo object describing the Kubernetes version
     */
    private static Future<VersionInfo> getVersionInfo(Vertx vertx, KubernetesClient client) {
        Future<VersionInfo> futureVersion;

        String kubernetesVersion = System.getenv("STRIMZI_KUBERNETES_VERSION");

        if (kubernetesVersion != null) {
            try {
                futureVersion = Future.succeededFuture(parseVersionInfo(kubernetesVersion));
            } catch (ParseException e) {
                throw new RuntimeException("Failed to parse the Kubernetes version information provided through STRIMZI_KUBERNETES_VERSION environment variable", e);
            }
        } else {
            futureVersion = getVersionInfoFromKubernetes(vertx, client);
        }

        return futureVersion;
    }

    static VersionInfo parseVersionInfo(String str) throws ParseException {
        Map<String, String> map = Util.parseMap(str);
        VersionInfo.Builder vib = new VersionInfo.Builder();
        for (Map.Entry<String, String> entry: map.entrySet()) {
            if (entry.getKey().equals("major")) {
                vib.withMajor(map.get(entry.getKey()));
            } else if (entry.getKey().equals("minor")) {
                vib.withMinor(map.get(entry.getKey()));
            } else if (entry.getKey().equals("gitVersion")) {
                vib.withGitVersion(map.get(entry.getKey()));
            } else if (entry.getKey().equals("gitCommit")) {
                vib.withGitCommit(map.get(entry.getKey()));
            } else if (entry.getKey().equals("gitTreeState")) {
                vib.withGitTreeState(map.get(entry.getKey()));
            } else if (entry.getKey().equals("buildDate")) {
                vib.withBuildDate(map.get(entry.getKey()));
            } else if (entry.getKey().equals("goVersion")) {
                vib.withGoVersion(map.get(entry.getKey()));
            } else if (entry.getKey().equals("compiler")) {
                vib.withCompiler(map.get(entry.getKey()));
            } else if (entry.getKey().equals("platform")) {
                vib.withPlatform(map.get(entry.getKey()));
            }
        }
        return vib.build();
    }

    private static Future<VersionInfo> getVersionInfoFromKubernetes(Vertx vertx, KubernetesClient client)   {
        Promise<VersionInfo> promise = Promise.promise();

        vertx.executeBlocking(request -> {
            try {
                request.complete(client.getKubernetesVersion());
            } catch (Exception e) {
                LOGGER.error("Detection of Kubernetes version failed.", e);
                request.fail(e);
            }
        }, promise);

        return promise.future();
    }

    private static Future<Boolean> checkApiAvailability(Vertx vertx, KubernetesClient client, String group, String version)   {
        Promise<Boolean> promise = Promise.promise();

        vertx.executeBlocking(request -> {
            try {
                APIGroup apiGroup = client.getApiGroup(group);
                boolean supported;

                if (apiGroup != null)   {
                    supported = apiGroup.getVersions().stream().anyMatch(v -> version.equals(v.getVersion()));
                } else {
                    supported = false;
                }

                LOGGER.warn("API Group {} is {}supported", group, supported ? "" : "not ");
                request.complete(supported);
            } catch (Exception e) {
                LOGGER.error("Detection of API availability failed.", e);
                request.fail(e);
            }
        }, promise);

        return promise.future();
    }

    private PlatformFeaturesAvailability() {}

    /**
     * This constructor is used in tests. It sets all OpenShift APIs to true or false depending on the isOpenShift paremeter
     *
     * @param isOpenShift           Set all OpenShift APIs to true
     * @param kubernetesVersion     Set the Kubernetes version
     */
    /* test */public PlatformFeaturesAvailability(boolean isOpenShift, KubernetesVersion kubernetesVersion) {
        this.kubernetesVersion = kubernetesVersion;
        this.routes = isOpenShift;
        this.images = isOpenShift;
        this.builds = isOpenShift;
    }

    public boolean isOpenshift() {
        return this.hasRoutes();
    }

    public KubernetesVersion getKubernetesVersion() {
        return this.kubernetesVersion;
    }

    private void setKubernetesVersion(KubernetesVersion kubernetesVersion) {
        this.kubernetesVersion = kubernetesVersion;
    }

    public boolean hasRoutes() {
        return routes;
    }

    private void setRoutes(boolean routes) {
        this.routes = routes;
    }

    public boolean hasBuilds() {
        return builds;
    }

    private void setBuilds(boolean builds) {
        this.builds = builds;
    }

    public boolean hasImages() {
        return images;
    }

    private void setImages(boolean images) {
        this.images = images;
    }

    public boolean supportsS2I() {
        return hasBuilds() && hasImages();
    }

    /**
     * Returns true when the Kubernetes cluster has V1 version of the Ingress resource (Kubernetes 1.19 and newer)
     *
     * @return True when Ingress V1 is supported. False otherwise.
     */
    public boolean hasIngressV1() {
        return this.kubernetesVersion.compareTo(KubernetesVersion.V1_19) >= 0;
    }

    /**
     * Returns true when the Kubernetes cluster has V1 version of the PodDisruptionBudget resource (Kubernetes 1.21 and newer)
     *
     * @return True when PodDisruptionBudget V1 is supported. False otherwise.
     */
    public boolean hasPodDisruptionBudgetV1() {
        return this.kubernetesVersion.compareTo(KubernetesVersion.V1_21) >= 0;
    }

    @Override
    public String toString() {
        return "PlatformFeaturesAvailability(" +
                "KubernetesVersion=" + kubernetesVersion +
                ",OpenShiftRoutes=" + routes +
                ",OpenShiftBuilds=" + builds +
                ",OpenShiftImageStreams=" + images +
                ")";
    }
}
