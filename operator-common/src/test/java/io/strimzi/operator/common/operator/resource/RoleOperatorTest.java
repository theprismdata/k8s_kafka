/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Vertx;

import static java.util.Collections.singletonMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RoleOperatorTest extends AbstractResourceOperatorTest<
        KubernetesClient,
        Role,
        RoleList,
        Resource<Role>> {

    @Override
    protected Class<KubernetesClient> clientType() {
        return KubernetesClient.class;
    }

    @Override
    protected Class<? extends Resource> resourceType() {
        return Resource.class;
    }

    @Override
    protected Role resource() {
        PolicyRule rule = new PolicyRuleBuilder()
                .withApiGroups("somegroup")
                .addNewVerb("someverb")
                .build();

        return new RoleBuilder()
                .withNewMetadata()
                    .withName(RESOURCE_NAME)
                    .withNamespace(NAMESPACE)
                    .withLabels(singletonMap("foo", "bar"))
                .endMetadata()
                .withRules(rule)
                .build();
    }

    @Override
    protected Role modifiedResource() {
        PolicyRule rule = new PolicyRuleBuilder()
                .withApiGroups("somegroup2")
                .addNewVerb("someverb2")
                .build();

        return new RoleBuilder(resource())
                .withRules(rule)
                .build();
    }

    @Override
    protected void mocker(KubernetesClient mockClient, MixedOperation op) {
        RbacAPIGroupDSL mockRbac = mock(RbacAPIGroupDSL.class);
        when(mockClient.rbac()).thenReturn(mockRbac);
        when(mockClient.rbac().roles()).thenReturn(op);
    }

    @Override
    protected AbstractResourceOperator<KubernetesClient, Role, RoleList, Resource<Role>> createResourceOperations(Vertx vertx, KubernetesClient mockClient) {
        return new RoleOperator(vertx, mockClient);
    }
}
