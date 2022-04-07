/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.user.operator;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.KafkaUserBuilder;
import io.strimzi.api.kafka.model.KafkaUserQuotas;
import io.strimzi.api.kafka.model.status.KafkaUserStatus;
import io.strimzi.certs.CertManager;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.MockCertManager;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.SecretOperator;
import io.strimzi.operator.user.ResourceUtils;
import io.strimzi.operator.user.model.KafkaUserModel;
import io.strimzi.operator.user.model.acl.SimpleAclRule;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaUserOperatorTest {
    protected static Vertx vertx;
    private final CertManager mockCertManager = new MockCertManager();

    @BeforeAll
    public static void before() {
        //Setup Micrometer metrics options
        VertxOptions options = new VertxOptions().setMetricsOptions(
                new MicrometerMetricsOptions()
                        .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                        .setEnabled(true));
        vertx = Vertx.vertx(options);
    }

    @AfterAll
    public static void after() {
        vertx.close();
    }

    @Test
    public void testCreateTlsUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserTls();
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();
        when(mockSecretOps.getAsync(anyString(), eq("user-cert"))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq("user-key"))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(ResourceUtils.NAME))).thenReturn(Future.succeededFuture(null));

        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.createOrUpdate(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME), user)
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();
                assertThat(capturedSecrets, hasSize(1));
                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(),
                        is(Labels.fromMap(user.getMetadata().getLabels())
                                .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withKubernetesInstance(ResourceUtils.NAME)
                                .withKubernetesPartOf(ResourceUtils.NAME)
                                .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .toMap()));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("ca.crt"))), is("clients-ca-crt"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.crt"))), is("crt file"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.key"))), is("key file"));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();
                assertThat(capturedAcls, hasSize(2));

                Set<SimpleAclRule> aclRules = capturedAcls.get(0);
                assertThat(aclRules, hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(aclRules, is(ResourceUtils.createExpectedSimpleAclRules(user)));

                assertThat(capturedAcls.get(1), is(nullValue()));

                async.flag();
            })));
    }

    @Test
    public void testCreateUserWithAclsDisabled(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig(Map.of(), false, "12"));
        KafkaUser user = new KafkaUserBuilder()
                .withNewMetadata()
                    .withName(ResourceUtils.NAME)
                    .withNamespace(ResourceUtils.NAMESPACE)
                .endMetadata()
                .withNewSpec()
                    .withNewKafkaUserTlsClientAuthentication()
                    .endKafkaUserTlsClientAuthentication()
                    .withNewQuotas()
                        .withConsumerByteRate(1024 * 1024)
                        .withProducerByteRate(1024 * 1024)
                    .endQuotas()
                .endSpec()
                .build();

        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();
        when(mockSecretOps.getAsync(anyString(), eq("user-cert"))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq("user-key"))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(ResourceUtils.NAME))).thenReturn(Future.succeededFuture(null));

        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.createOrUpdate(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME), user)
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();
                assertThat(capturedSecrets, hasSize(1));
                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(),
                        is(Labels.fromMap(user.getMetadata().getLabels())
                                .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withKubernetesInstance(ResourceUtils.NAME)
                                .withKubernetesPartOf(ResourceUtils.NAME)
                                .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .toMap()));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("ca.crt"))), is("clients-ca-crt"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.crt"))), is("crt file"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.key"))), is("key file"));

                verify(aclOps, never()).reconcile(any(), any(), any());

                async.flag();
            })));
    }

    @Test
    public void testUpdateUserNoChange(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaUser user = ResourceUtils.createKafkaUserTls();

        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();
        Secret userCert = ResourceUtils.createUserSecretTls();
        when(mockSecretOps.getAsync(anyString(), eq(clientsCa.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq(clientsCaKey.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(userCert));

        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.createOrUpdate(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME), user)
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();
                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(userCert.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(userCert.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(), is(userCert.getMetadata().getLabels()));
                assertThat(captured.getData().get("ca.crt"), is(userCert.getData().get("ca.crt")));
                assertThat(captured.getData().get("user.crt"), is(userCert.getData().get("user.crt")));
                assertThat(captured.getData().get("user.key"), is(userCert.getData().get("user.key")));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();

                assertThat(capturedAcls, hasSize(2));
                Set<SimpleAclRule> aclRules = capturedAcls.get(0);

                assertThat(aclRules, hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(aclRules, is(ResourceUtils.createExpectedSimpleAclRules(user)));
                assertThat(capturedAcls.get(1), is(nullValue()));

                async.flag();
            })));
    }

    /**
     * Tests what happens when the TlsClientAuthentication and SimpleAuthorization are disabled for the user
     * (delete entries from the spec of the KafkaUser resource)
     */
    @Test
    public void testUpdateUserNoAuthenticationAndNoAuthorization(VertxTestContext context) {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());
        when(mockSecretOps.getAsync(anyString(), eq(ResourceUtils.NAME))).thenReturn(Future.succeededFuture(null));

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaUser user = ResourceUtils.createKafkaUserTls();
        user.getSpec().setAuthorization(null);
        user.getSpec().setAuthentication(null);

        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());

        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.createOrUpdate(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME), user)
            .onComplete(context.succeeding(v -> context.verify(() -> {
                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();

                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured, is(nullValue()));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();

                assertThat(capturedAcls, hasSize(2));
                assertThat(capturedAcls.get(0), is(nullValue()));
                assertThat(capturedAcls.get(1), is(nullValue()));

                async.flag();
            })));
    }

    @Test
    public void testUpdateUserNewCert(VertxTestContext context) {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserTls();

        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        clientsCa.getData().put("ca.crt", Base64.getEncoder().encodeToString("different-clients-ca-crt".getBytes()));
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();
        clientsCaKey.getData().put("ca.key", Base64.getEncoder().encodeToString("different-clients-ca-key".getBytes()));
        Secret userCert = ResourceUtils.createUserSecretTls();

        when(mockSecretOps.getAsync(anyString(), eq(clientsCa.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq(clientsCaKey.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(userCert));

        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.createOrUpdate(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME), user)
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();

                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(userCert.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(userCert.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(), is(userCert.getMetadata().getLabels()));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("ca.crt"))), is("different-clients-ca-crt"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.crt"))), is("crt file"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.key"))), is("key file"));

                async.flag();
            })));
    }

    @Test
    public void testDeleteTlsUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        when(quotasOps.reconcile(any(), anyString(), eq(null))).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());

        Checkpoint async = context.checkpoint();
        op.delete(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                async.flag();
            })));
    }

    @Test
    public void testReconcileNewTlsUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserTls();
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.getAsync(anyString(), eq(clientsCa.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq(clientsCaKey.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(null));

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(user);
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();

                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(),
                                is(Labels.fromMap(user.getMetadata().getLabels())
                                .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withKubernetesInstance(ResourceUtils.NAME)
                                .withKubernetesPartOf(ResourceUtils.NAME)
                                .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .toMap()));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("ca.crt"))), is("clients-ca-crt"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.crt"))), is("crt file"));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get("user.key"))), is("key file"));


                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();

                assertThat(capturedAcls, hasSize(2));
                Set<SimpleAclRule> aclRules = capturedAcls.get(0);

                assertThat(aclRules, hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(aclRules, is(ResourceUtils.createExpectedSimpleAclRules(user)));
                assertThat(capturedAcls.get(1), is(nullValue()));

                async.flag();
            })));
    }

    @Test
    public void testReconcileExistingTlsUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserTls();
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();
        Secret userCert = ResourceUtils.createUserSecretTls();

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.getAsync(anyString(), eq(clientsCa.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq(clientsCaKey.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(userCert));

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(user);
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(ResourceUtils.NAME, is(capturedNames.get(0)));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();

                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(),
                        is(Labels.fromMap(user.getMetadata().getLabels())
                                .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withKubernetesInstance(ResourceUtils.NAME)
                                .withKubernetesPartOf(ResourceUtils.NAME)
                                .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                .toMap()));
                assertThat(captured.getData().get("ca.crt"), is(userCert.getData().get("ca.crt")));
                assertThat(captured.getData().get("user.crt"), is(userCert.getData().get("user.crt")));
                assertThat(captured.getData().get("user.key"), is(userCert.getData().get("user.key")));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();

                assertThat(capturedAcls, hasSize(2));
                Set<SimpleAclRule> aclRules = capturedAcls.get(0);

                assertThat(aclRules, hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(aclRules, is(ResourceUtils.createExpectedSimpleAclRules(user)));
                assertThat(capturedAcls.get(1), is(nullValue()));

                async.flag();
            })));

    }

    @Test
    public void testReconcileDeleteTlsUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserTls();
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret userCert = ResourceUtils.createUserSecretTls();

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.get(eq(clientsCa.getMetadata().getNamespace()), eq(clientsCa.getMetadata().getName()))).thenReturn(clientsCa);
        when(mockSecretOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(userCert);

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(null);

        when(quotasOps.reconcile(any(), anyString(), eq(null))).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                async.flag();
            })));
    }

    @Test
    public void testReconcileAll(VertxTestContext context) {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUser newTlsUser = ResourceUtils.createKafkaUserTls();
        newTlsUser.getMetadata().setName("new-tls-user");
        KafkaUser newScramShaUser = ResourceUtils.createKafkaUserScramSha();
        newScramShaUser.getMetadata().setName("new-scram-sha-user");
        KafkaUser existingTlsUser = ResourceUtils.createKafkaUserTls();
        existingTlsUser.getMetadata().setName("existing-tls-user");
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret existingTlsUserSecret = ResourceUtils.createUserSecretTls();
        existingTlsUserSecret.getMetadata().setName("existing-tls-user");
        Secret existingScramShaUserSecret = ResourceUtils.createUserSecretScramSha();
        existingScramShaUserSecret.getMetadata().setName("existing-scram-sha-user");
        KafkaUser existingScramShaUser = ResourceUtils.createKafkaUserTls();
        existingScramShaUser.getMetadata().setName("existing-scram-sha-user");

        when(mockCrdOps.listAsync(eq(ResourceUtils.NAMESPACE), eq(Optional.of(new LabelSelector(null, Labels.fromMap(ResourceUtils.LABELS).toMap()))))).thenReturn(
                Future.succeededFuture(Arrays.asList(newTlsUser, newScramShaUser, existingTlsUser, existingScramShaUser)));
        when(mockSecretOps.list(eq(ResourceUtils.NAMESPACE), eq(Labels.fromMap(ResourceUtils.LABELS).withStrimziKind(KafkaUser.RESOURCE_KIND)))).thenReturn(Arrays.asList(existingTlsUserSecret, existingScramShaUserSecret));
        when(aclOps.getAllUsers()).thenReturn(Future.succeededFuture(new HashSet<String>(Arrays.asList("existing-tls-user", "second-deleted-user"))));
        when(scramOps.getAllUsers()).thenReturn(Future.succeededFuture(List.of("existing-tls-user", "deleted-scram-sha-user")));
        when(quotasOps.getAllUsers()).thenReturn(Future.succeededFuture(Set.of("existing-tls-user", "quota-user")));

        when(mockCrdOps.get(eq(newTlsUser.getMetadata().getNamespace()), eq(newTlsUser.getMetadata().getName()))).thenReturn(newTlsUser);
        when(mockCrdOps.get(eq(newScramShaUser.getMetadata().getNamespace()), eq(newScramShaUser.getMetadata().getName()))).thenReturn(newScramShaUser);
        when(mockCrdOps.get(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingTlsUser.getMetadata().getName()))).thenReturn(existingTlsUser);
        when(mockCrdOps.get(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingScramShaUser.getMetadata().getName()))).thenReturn(existingScramShaUser);
        when(mockCrdOps.getAsync(eq(newTlsUser.getMetadata().getNamespace()), eq(newTlsUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(newTlsUser));
        when(mockCrdOps.getAsync(eq(newScramShaUser.getMetadata().getNamespace()), eq(newScramShaUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(newScramShaUser));
        when(mockCrdOps.getAsync(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingTlsUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(existingTlsUser));
        when(mockCrdOps.getAsync(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingScramShaUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(existingScramShaUser));
        when(mockCrdOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.get(eq(clientsCa.getMetadata().getNamespace()), eq(clientsCa.getMetadata().getName()))).thenReturn(clientsCa);
        when(mockSecretOps.get(eq(newTlsUser.getMetadata().getNamespace()), eq(newTlsUser.getMetadata().getName()))).thenReturn(null);
        when(mockSecretOps.get(eq(newScramShaUser.getMetadata().getNamespace()), eq(newScramShaUser.getMetadata().getName()))).thenReturn(null);
        when(mockSecretOps.get(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingTlsUser.getMetadata().getName()))).thenReturn(existingTlsUserSecret);
        when(mockSecretOps.get(eq(existingScramShaUser.getMetadata().getNamespace()), eq(existingScramShaUser.getMetadata().getName()))).thenReturn(existingScramShaUserSecret);

        Set<String> createdOrUpdated = new CopyOnWriteArraySet<>();
        Set<String> deleted = new CopyOnWriteArraySet<>();

        Checkpoint async = context.checkpoint();

        Promise reconcileAllCompleted = Promise.promise();

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig(ResourceUtils.LABELS)) {
            @Override
            public Future<KafkaUserStatus> createOrUpdate(Reconciliation reconciliation, KafkaUser resource) {
                createdOrUpdated.add(resource.getMetadata().getName());
                return Future.succeededFuture(new KafkaUserStatus());
            }
            @Override
            public Future<Boolean> delete(Reconciliation reconciliation) {
                deleted.add(reconciliation.name());
                return Future.succeededFuture(Boolean.TRUE);
            }
        };

        // call reconcileAll and pass in promise to the handler to run assertions on completion
        op.reconcileAll("test", ResourceUtils.NAMESPACE, ar -> reconcileAllCompleted.complete());

        reconcileAllCompleted.future().compose(v -> context.verify(() -> {
            assertThat(createdOrUpdated, is(new HashSet(asList("new-tls-user", "existing-tls-user",
                    "new-scram-sha-user", "existing-scram-sha-user"))));
            assertThat(deleted, is(new HashSet(asList("quota-user", "second-deleted-user", "deleted-scram-sha-user"))));
            async.flag();
        }));
    }

    @Test
    public void testReconcileAllWithoutAcls(VertxTestContext context) {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUser newTlsUser = ResourceUtils.createKafkaUserTls();
        newTlsUser.getMetadata().setName("new-tls-user");
        KafkaUser newScramShaUser = ResourceUtils.createKafkaUserScramSha();
        newScramShaUser.getMetadata().setName("new-scram-sha-user");
        KafkaUser existingTlsUser = ResourceUtils.createKafkaUserTls();
        existingTlsUser.getMetadata().setName("existing-tls-user");
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret existingTlsUserSecret = ResourceUtils.createUserSecretTls();
        existingTlsUserSecret.getMetadata().setName("existing-tls-user");
        Secret existingScramShaUserSecret = ResourceUtils.createUserSecretScramSha();
        existingScramShaUserSecret.getMetadata().setName("existing-scram-sha-user");
        KafkaUser existingScramShaUser = ResourceUtils.createKafkaUserTls();
        existingScramShaUser.getMetadata().setName("existing-scram-sha-user");

        when(mockCrdOps.listAsync(eq(ResourceUtils.NAMESPACE), eq(Optional.of(new LabelSelector(null, Labels.fromMap(ResourceUtils.LABELS).toMap()))))).thenReturn(
                Future.succeededFuture(Arrays.asList(newTlsUser, newScramShaUser, existingTlsUser, existingScramShaUser)));
        when(mockSecretOps.list(eq(ResourceUtils.NAMESPACE), eq(Labels.fromMap(ResourceUtils.LABELS).withStrimziKind(KafkaUser.RESOURCE_KIND)))).thenReturn(Arrays.asList(existingTlsUserSecret, existingScramShaUserSecret));
        when(scramOps.getAllUsers()).thenReturn(Future.succeededFuture(List.of("existing-tls-user", "deleted-scram-sha-user")));
        when(quotasOps.getAllUsers()).thenReturn(Future.succeededFuture(Set.of("existing-tls-user", "quota-user")));

        when(mockCrdOps.get(eq(newTlsUser.getMetadata().getNamespace()), eq(newTlsUser.getMetadata().getName()))).thenReturn(newTlsUser);
        when(mockCrdOps.get(eq(newScramShaUser.getMetadata().getNamespace()), eq(newScramShaUser.getMetadata().getName()))).thenReturn(newScramShaUser);
        when(mockCrdOps.get(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingTlsUser.getMetadata().getName()))).thenReturn(existingTlsUser);
        when(mockCrdOps.get(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingScramShaUser.getMetadata().getName()))).thenReturn(existingScramShaUser);
        when(mockCrdOps.getAsync(eq(newTlsUser.getMetadata().getNamespace()), eq(newTlsUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(newTlsUser));
        when(mockCrdOps.getAsync(eq(newScramShaUser.getMetadata().getNamespace()), eq(newScramShaUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(newScramShaUser));
        when(mockCrdOps.getAsync(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingTlsUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(existingTlsUser));
        when(mockCrdOps.getAsync(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingScramShaUser.getMetadata().getName()))).thenReturn(Future.succeededFuture(existingScramShaUser));
        when(mockCrdOps.updateStatusAsync(any(), any())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.get(eq(clientsCa.getMetadata().getNamespace()), eq(clientsCa.getMetadata().getName()))).thenReturn(clientsCa);
        when(mockSecretOps.get(eq(newTlsUser.getMetadata().getNamespace()), eq(newTlsUser.getMetadata().getName()))).thenReturn(null);
        when(mockSecretOps.get(eq(newScramShaUser.getMetadata().getNamespace()), eq(newScramShaUser.getMetadata().getName()))).thenReturn(null);
        when(mockSecretOps.get(eq(existingTlsUser.getMetadata().getNamespace()), eq(existingTlsUser.getMetadata().getName()))).thenReturn(existingTlsUserSecret);
        when(mockSecretOps.get(eq(existingScramShaUser.getMetadata().getNamespace()), eq(existingScramShaUser.getMetadata().getName()))).thenReturn(existingScramShaUserSecret);

        Set<String> createdOrUpdated = new CopyOnWriteArraySet<>();
        Set<String> deleted = new CopyOnWriteArraySet<>();

        Checkpoint async = context.checkpoint();

        Promise reconcileAllCompleted = Promise.promise();

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig(ResourceUtils.LABELS, false, "12")) {
            @Override
            public Future<KafkaUserStatus> createOrUpdate(Reconciliation reconciliation, KafkaUser resource) {
                createdOrUpdated.add(resource.getMetadata().getName());
                return Future.succeededFuture(new KafkaUserStatus());
            }
            @Override
            public Future<Boolean> delete(Reconciliation reconciliation) {
                deleted.add(reconciliation.name());
                return Future.succeededFuture(Boolean.TRUE);
            }
        };

        // call reconcileAll and pass in promise to the handler to run assertions on completion
        op.reconcileAll("test", ResourceUtils.NAMESPACE, ar -> reconcileAllCompleted.complete());

        reconcileAllCompleted.future().compose(v -> context.verify(() -> {
            assertThat(createdOrUpdated, is(new HashSet(asList("new-tls-user", "existing-tls-user",
                    "new-scram-sha-user", "existing-scram-sha-user"))));
            assertThat(deleted, is(new HashSet(asList("quota-user", "deleted-scram-sha-user"))));
            verify(aclOps, never()).getAllUsers();
            async.flag();
        }));
    }

    @Test
    public void testReconcileNewScramShaUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserScramSha();

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> scramUserCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scramPasswordCaptor = ArgumentCaptor.forClass(String.class);
        when(scramOps.reconcile(any(), scramUserCaptor.capture(), scramPasswordCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(null));

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(user);
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();

                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(),
                        is(Labels.fromMap(user.getMetadata().getLabels())
                                .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withKubernetesInstance(ResourceUtils.NAME)
                                .withKubernetesPartOf(ResourceUtils.NAME)
                                .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                .toMap()));
                assertThat(scramPasswordCaptor.getValue(), is(new String(Base64.getDecoder().decode(captured.getData().get(KafkaUserModel.KEY_PASSWORD)))));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get(KafkaUserModel.KEY_PASSWORD))).matches("[a-zA-Z0-9]{12}"), is(true));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();

                assertThat(capturedAcls, hasSize(2));
                Set<SimpleAclRule> aclRules = capturedAcls.get(1);

                assertThat(aclRules, hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(aclRules, is(ResourceUtils.createExpectedSimpleAclRules(user)));
                assertThat(capturedAcls.get(0), is(nullValue()));

                async.flag();
            })));
    }

    @Test
    public void testReconcileNewScramShaUserwithConfigurableLength(VertxTestContext context)    {

        String scramShaPasswordLength = "30";
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig(scramShaPasswordLength));
        KafkaUser user = ResourceUtils.createKafkaUserScramSha();

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> scramUserCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scramPasswordCaptor = ArgumentCaptor.forClass(String.class);
        when(scramOps.reconcile(any(), scramUserCaptor.capture(), scramPasswordCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(null));

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(user);
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
                .onComplete(context.succeeding(v -> context.verify(() -> {

                    List<String> capturedNames = secretNameCaptor.getAllValues();
                    assertThat(capturedNames, hasSize(1));
                    assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                    List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                    assertThat(capturedNamespaces, hasSize(1));
                    assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                    List<Secret> capturedSecrets = secretCaptor.getAllValues();

                    assertThat(capturedSecrets, hasSize(1));

                    Secret captured = capturedSecrets.get(0);
                    assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                    assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                    assertThat(captured.getMetadata().getLabels(),
                            is(Labels.fromMap(user.getMetadata().getLabels())
                                    .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                    .withKubernetesInstance(ResourceUtils.NAME)
                                    .withKubernetesPartOf(ResourceUtils.NAME)
                                    .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                    .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                    .toMap()));

                    assertThat(scramPasswordCaptor.getValue(), is(new String(Base64.getDecoder().decode(captured.getData().get(KafkaUserModel.KEY_PASSWORD)))));
                    assertThat(new String(Base64.getDecoder().decode(captured.getData().get(KafkaUserModel.KEY_PASSWORD))).matches("[a-zA-Z0-9]{30}"), is(true));

                    async.flag();
                })));
    }


    @Test
    public void testReconcileNewScramShaUserWithProvidedPassword(VertxTestContext context)    {
        String desiredPassword = "12345678";

        KafkaUser user = new KafkaUserBuilder(ResourceUtils.createKafkaUserScramSha())
            .editSpec()
                .withNewKafkaUserScramSha512ClientAuthentication()
                    .withNewPassword()
                        .withNewValueFrom()
                            .withNewSecretKeyRef("my-password", "my-secret", false)
                        .endValueFrom()
                    .endPassword()
                .endKafkaUserScramSha512ClientAuthentication()
            .endSpec()
            .build();

        Secret desiredPasswordSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("my-secret")
                .withNamespace(ResourceUtils.NAMESPACE)
                .endMetadata()
                .addToData("my-password", Base64.getEncoder().encodeToString(desiredPassword.getBytes(StandardCharsets.UTF_8)))
                .build();

        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> scramUserCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scramPasswordCaptor = ArgumentCaptor.forClass(String.class);
        when(scramOps.reconcile(any(), scramUserCaptor.capture(), scramPasswordCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(null));
        when(mockSecretOps.getAsync(anyString(), eq(desiredPasswordSecret.getMetadata().getName()))).thenReturn(Future.succeededFuture(desiredPasswordSecret));

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(user);
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();

                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(),
                        is(Labels.fromMap(user.getMetadata().getLabels())
                                .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withKubernetesInstance(ResourceUtils.NAME)
                                .withKubernetesPartOf(ResourceUtils.NAME)
                                .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                .toMap()));

                assertThat(scramPasswordCaptor.getValue(), is(desiredPassword));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get(KafkaUserModel.KEY_PASSWORD))), is(desiredPassword));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();

                assertThat(capturedAcls, hasSize(2));
                Set<SimpleAclRule> aclRules = capturedAcls.get(1);

                assertThat(aclRules, hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(aclRules, is(ResourceUtils.createExpectedSimpleAclRules(user)));
                assertThat(capturedAcls.get(0), is(nullValue()));

                async.flag();
            })));
    }

    @Test
    public void testReconcileExistingScramShaUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserScramSha();
        Secret userCert = ResourceUtils.createUserSecretScramSha();
        String password = new String(Base64.getDecoder().decode(userCert.getData().get(KafkaUserModel.KEY_PASSWORD)));

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> scramUserCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scramPasswordCaptor = ArgumentCaptor.forClass(String.class);
        when(scramOps.reconcile(any(), scramUserCaptor.capture(), scramPasswordCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(userCert));

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(user);
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();

                assertThat(capturedSecrets, hasSize(1));

                Secret captured = capturedSecrets.get(0);
                assertThat(captured.getMetadata().getName(), is(user.getMetadata().getName()));
                assertThat(captured.getMetadata().getNamespace(), is(user.getMetadata().getNamespace()));
                assertThat(captured.getMetadata().getLabels(),
                        is(Labels.fromMap(user.getMetadata().getLabels())
                                .withKubernetesName(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withKubernetesInstance(ResourceUtils.NAME)
                                .withKubernetesPartOf(ResourceUtils.NAME)
                                .withKubernetesManagedBy(KafkaUserModel.KAFKA_USER_OPERATOR_NAME)
                                .withStrimziKind(KafkaUser.RESOURCE_KIND)
                                .toMap()));
                assertThat(new String(Base64.getDecoder().decode(captured.getData().get(KafkaUserModel.KEY_PASSWORD))), is(password));
                assertThat(scramPasswordCaptor.getValue(), is(password));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();

                assertThat(capturedAcls, hasSize(2));
                Set<SimpleAclRule> aclRules = capturedAcls.get(1);

                assertThat(aclRules, hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(aclRules, is(ResourceUtils.createExpectedSimpleAclRules(user)));
                assertThat(capturedAcls.get(0), is(nullValue()));

                async.flag();
            })));

    }

    @Test
    public void testReconcileDeleteScramShaUser(VertxTestContext context)    {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        KafkaUser user = ResourceUtils.createKafkaUserScramSha();
        Secret userCert = ResourceUtils.createUserSecretTls();

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> scramUserCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scramPasswordCaptor = ArgumentCaptor.forClass(String.class);
        when(scramOps.reconcile(any(), scramUserCaptor.capture(), scramPasswordCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(userCert);

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(null);

        when(quotasOps.reconcile(any(), anyString(), eq(null))).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                assertThat(scramUserCaptor.getAllValues(), is(singletonList(ResourceUtils.NAME)));
                assertThat(scramPasswordCaptor.getAllValues(), is(singletonList(null)));

                async.flag();
            })));
    }

    @Test
    public void testReconcileTlsExternalUser(VertxTestContext context)    {
        KafkaUser user = new KafkaUserBuilder(ResourceUtils.createKafkaUserQuotas(1000000, 2000000, 55, 10.0))
            .editSpec()
                .withNewKafkaUserTlsExternalClientAuthentication()
                .endKafkaUserTlsExternalClientAuthentication()
            .endSpec()
            .build();

        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();

        ArgumentCaptor<String> secretNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
        when(mockSecretOps.reconcile(any(), secretNamespaceCaptor.capture(), secretNameCaptor.capture(), secretCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> aclNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Set<SimpleAclRule>> aclRulesCaptor = ArgumentCaptor.forClass(Set.class);
        when(aclOps.reconcile(any(), aclNameCaptor.capture(), aclRulesCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> scramUserCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scramPasswordCaptor = ArgumentCaptor.forClass(String.class);
        when(scramOps.reconcile(any(), scramUserCaptor.capture(), scramPasswordCaptor.capture())).thenReturn(Future.succeededFuture());

        when(mockSecretOps.getAsync(anyString(), eq(clientsCa.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq(clientsCaKey.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(null));

        when(mockCrdOps.get(eq(user.getMetadata().getNamespace()), eq(user.getMetadata().getName()))).thenReturn(user);
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.updateStatusAsync(any(), any(KafkaUser.class))).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> quotasUserNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<KafkaUserQuotas> quotasCaptor = ArgumentCaptor.forClass(KafkaUserQuotas.class);
        when(quotasOps.reconcile(any(), quotasUserNameCaptor.capture(), quotasCaptor.capture())).thenReturn(Future.succeededFuture());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {

                List<String> capturedNames = secretNameCaptor.getAllValues();
                assertThat(capturedNames, hasSize(1));
                assertThat(capturedNames.get(0), is(ResourceUtils.NAME));

                List<String> capturedNamespaces = secretNamespaceCaptor.getAllValues();
                assertThat(capturedNamespaces, hasSize(1));
                assertThat(capturedNamespaces.get(0), is(ResourceUtils.NAMESPACE));

                List<Secret> capturedSecrets = secretCaptor.getAllValues();
                assertThat(capturedSecrets, hasSize(1));
                assertThat(capturedSecrets.get(0), is(nullValue()));

                assertThat(scramUserCaptor.getValue(), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));
                assertThat(scramPasswordCaptor.getValue(), is(nullValue()));

                List<String> capturedAclNames = aclNameCaptor.getAllValues();
                assertThat(capturedAclNames, hasSize(2));
                assertThat(capturedAclNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedAclNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<Set<SimpleAclRule>> capturedAcls = aclRulesCaptor.getAllValues();
                assertThat(capturedAcls, hasSize(2));
                assertThat(capturedAcls.get(0), hasSize(ResourceUtils.createExpectedSimpleAclRules(user).size()));
                assertThat(capturedAcls.get(0), is(ResourceUtils.createExpectedSimpleAclRules(user)));
                assertThat(capturedAcls.get(1), is(nullValue()));

                List<String> capturedQuotasNames = quotasUserNameCaptor.getAllValues();
                assertThat(capturedQuotasNames, hasSize(2));
                assertThat(capturedQuotasNames.get(0), is(KafkaUserModel.getTlsUserName(ResourceUtils.NAME)));
                assertThat(capturedQuotasNames.get(1), is(KafkaUserModel.getScramUserName(ResourceUtils.NAME)));

                List<KafkaUserQuotas> capturedQuotas = quotasCaptor.getAllValues();
                assertThat(capturedQuotas, hasSize(2));
                assertThat(capturedQuotas.get(0), is(notNullValue()));
                assertThat(capturedQuotas.get(0).getConsumerByteRate(), is(1000000));
                assertThat(capturedQuotas.get(0).getProducerByteRate(), is(2000000));
                assertThat(capturedQuotas.get(0).getRequestPercentage(), is(55));
                assertThat(capturedQuotas.get(0).getControllerMutationRate(), is(10.0));
                assertThat(capturedQuotas.get(1), is(nullValue()));

                async.flag();
            })));
    }

    @Test
    public void testUserStatusNotReadyIfSecretFailedReconcile(VertxTestContext context) {
        String failureMsg = "failure";
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUser user = ResourceUtils.createKafkaUserTls();
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();
        when(mockSecretOps.getAsync(anyString(), eq(clientsCa.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq(clientsCaKey.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(null));
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.get(anyString(), anyString())).thenReturn(user);

        when(mockSecretOps.reconcile(any(), anyString(), anyString(), any(Secret.class))).thenReturn(Future.failedFuture(failureMsg));
        when(aclOps.reconcile(any(), anyString(), any())).thenReturn(Future.succeededFuture());
        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<KafkaUser> userCaptor = ArgumentCaptor.forClass(KafkaUser.class);
        when(mockCrdOps.updateStatusAsync(any(), userCaptor.capture())).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.failing(e -> context.verify(() -> {
                List<KafkaUser> capturedStatuses = userCaptor.getAllValues();
                assertThat(capturedStatuses.get(0).getStatus().getUsername(), is("CN=user"));
                assertThat(capturedStatuses.get(0).getStatus().getConditions().get(0).getStatus(), is("True"));
                assertThat(capturedStatuses.get(0).getStatus().getConditions().get(0).getMessage(), is(failureMsg));
                assertThat(capturedStatuses.get(0).getStatus().getConditions().get(0).getType(), is("NotReady"));
                async.flag();
            })));
    }

    @Test
    public void testUserStatusReady(VertxTestContext context) {
        CrdOperator mockCrdOps = mock(CrdOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);
        SimpleAclOperator aclOps = mock(SimpleAclOperator.class);
        ScramCredentialsOperator scramOps = mock(ScramCredentialsOperator.class);
        QuotasOperator quotasOps = mock(QuotasOperator.class);

        KafkaUser user = ResourceUtils.createKafkaUserTls();
        Secret clientsCa = ResourceUtils.createClientsCaCertSecret();
        Secret clientsCaKey = ResourceUtils.createClientsCaKeySecret();
        when(mockSecretOps.getAsync(anyString(), eq(clientsCa.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCa));
        when(mockSecretOps.getAsync(anyString(), eq(clientsCaKey.getMetadata().getName()))).thenReturn(Future.succeededFuture(clientsCaKey));
        when(mockSecretOps.getAsync(anyString(), eq(user.getMetadata().getName()))).thenReturn(Future.succeededFuture(null));
        when(mockCrdOps.getAsync(anyString(), anyString())).thenReturn(Future.succeededFuture(user));
        when(mockCrdOps.get(anyString(), anyString())).thenReturn(user);

        when(mockSecretOps.reconcile(any(), anyString(), anyString(), any(Secret.class))).thenReturn(Future.succeededFuture());
        when(aclOps.reconcile(any(), anyString(), any())).thenReturn(Future.succeededFuture());
        when(scramOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());
        ArgumentCaptor<KafkaUser> userCaptor = ArgumentCaptor.forClass(KafkaUser.class);
        when(mockCrdOps.updateStatusAsync(any(), userCaptor.capture())).thenReturn(Future.succeededFuture());
        when(quotasOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        KafkaUserOperator op = new KafkaUserOperator(vertx, mockCertManager, mockCrdOps, mockSecretOps, scramOps, quotasOps, aclOps, ResourceUtils.createUserOperatorConfig());

        Checkpoint async = context.checkpoint();
        op.reconcile(new Reconciliation("test-trigger", KafkaUser.RESOURCE_KIND, ResourceUtils.NAMESPACE, ResourceUtils.NAME))
            .onComplete(context.succeeding(v -> context.verify(() -> {
                List<KafkaUser> capturedStatuses = userCaptor.getAllValues();
                assertThat(capturedStatuses.get(0).getStatus().getUsername(), is("CN=user"));
                assertThat(capturedStatuses.get(0).getStatus().getConditions().get(0).getStatus(), is("True"));
                assertThat(capturedStatuses.get(0).getStatus().getConditions().get(0).getType(), is("Ready"));
                async.flag();
            })));
    }
}
