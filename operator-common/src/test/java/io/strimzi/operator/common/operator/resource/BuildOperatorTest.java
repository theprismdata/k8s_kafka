/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.dsl.BuildResource;
import io.vertx.core.Vertx;

import static org.mockito.Mockito.when;

public class BuildOperatorTest extends AbstractResourceOperatorTest<OpenShiftClient, Build, BuildList, BuildResource<Build, LogWatch>> {

    @Override
    protected void mocker(OpenShiftClient mockClient, MixedOperation mockCms) {
        when(mockClient.builds()).thenReturn(mockCms);
    }

    @Override
    protected BuildOperator createResourceOperations(Vertx vertx, OpenShiftClient mockClient) {
        return new BuildOperator(vertx, mockClient);
    }

    @Override
    protected Class<OpenShiftClient> clientType() {
        return OpenShiftClient.class;
    }

    @Override
    protected Class<BuildResource> resourceType() {
        return BuildResource.class;
    }

    @Override
    protected Build resource() {
        return new BuildBuilder()
                .withNewMetadata()
                    .withName(RESOURCE_NAME)
                    .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .withNewSource()
                        .withType("Dockerfile")
                        .withDockerfile("FROM centos:7\nUSER 1001")
                    .endSource()
                    .withNewStrategy()
                        .withType("Docker")
                        .withNewDockerStrategy()
                        .endDockerStrategy()
                    .endStrategy()
                    .withNewOutput()
                        .withNewTo()
                            .withKind("Docker")
                            .withName("image-registry.openshift-image-registry.svc:5000/" + NAMESPACE + "/" + RESOURCE_NAME + ":test")
                        .endTo()
                    .endOutput()
                .endSpec()
                .build();
    }

    @Override
    protected Build modifiedResource() {
        return new BuildBuilder(resource())
                .editSpec()
                    .editSource()
                        .withNewDockerfile("FROM centos:8\nUSER 1001")
                    .endSource()
                .endSpec()
                .build();
    }
}
