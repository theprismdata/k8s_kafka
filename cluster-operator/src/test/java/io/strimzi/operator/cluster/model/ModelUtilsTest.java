/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.NodeSelectorTermBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.storage.EphemeralStorageBuilder;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.DeploymentStrategy;
import io.strimzi.api.kafka.model.template.DeploymentTemplate;
import io.strimzi.api.kafka.model.template.DeploymentTemplateBuilder;
import io.strimzi.api.kafka.model.template.InternalServiceTemplate;
import io.strimzi.api.kafka.model.template.InternalServiceTemplateBuilder;
import io.strimzi.api.kafka.model.template.IpFamily;
import io.strimzi.api.kafka.model.template.IpFamilyPolicy;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplateBuilder;
import io.strimzi.api.kafka.model.template.PodTemplate;
import io.strimzi.api.kafka.model.template.PodTemplateBuilder;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.test.annotations.ParallelSuite;
import io.strimzi.test.annotations.ParallelTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.strimzi.operator.common.Util.parseMap;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings({"checkstyle:ClassDataAbstractionCoupling"})
@ParallelSuite
public class ModelUtilsTest {
    @ParallelTest
    public void testParseImageMap() {
        Map<String, String> m = parseMap(
                KafkaVersionTestUtils.LATEST_KAFKA_VERSION + "=" + KafkaVersionTestUtils.LATEST_KAFKA_IMAGE + "\n  " +
                        KafkaVersionTestUtils.PREVIOUS_KAFKA_VERSION + "=" + KafkaVersionTestUtils.PREVIOUS_KAFKA_IMAGE + "\n ");
        assertThat(m.size(), is(2));
        assertThat(m.get(KafkaVersionTestUtils.LATEST_KAFKA_VERSION), is(KafkaVersionTestUtils.LATEST_KAFKA_IMAGE));
        assertThat(m.get(KafkaVersionTestUtils.PREVIOUS_KAFKA_VERSION), is(KafkaVersionTestUtils.PREVIOUS_KAFKA_IMAGE));

        m = parseMap(
                KafkaVersionTestUtils.LATEST_KAFKA_VERSION + "=" + KafkaVersionTestUtils.LATEST_KAFKA_IMAGE + "," +
                KafkaVersionTestUtils.PREVIOUS_KAFKA_VERSION + "=" + KafkaVersionTestUtils.PREVIOUS_KAFKA_IMAGE);
        assertThat(m.size(), is(2));
        assertThat(m.get(KafkaVersionTestUtils.LATEST_KAFKA_VERSION), is(KafkaVersionTestUtils.LATEST_KAFKA_IMAGE));
        assertThat(m.get(KafkaVersionTestUtils.PREVIOUS_KAFKA_VERSION), is(KafkaVersionTestUtils.PREVIOUS_KAFKA_IMAGE));
    }

    @Test
    public void testAnnotationsOrLabelsImageMap() {
        Map<String, String> m = parseMap(" discovery.3scale.net=true");
        assertThat(m.size(), is(1));
        assertThat(m.get("discovery.3scale.net"), is("true"));

        m = parseMap(" discovery.3scale.net/scheme=http\n" +
                "        discovery.3scale.net/port=8080\n" +
                "        discovery.3scale.net/path=path/\n" +
                "        discovery.3scale.net/description-path=oapi/");
        assertThat(m.size(), is(4));
        assertThat(m.get("discovery.3scale.net/scheme"), is("http"));
        assertThat(m.get("discovery.3scale.net/port"), is("8080"));
        assertThat(m.get("discovery.3scale.net/path"), is("path/"));
        assertThat(m.get("discovery.3scale.net/description-path"), is("oapi/"));
    }

    @Test
    public void testParsePodDisruptionBudgetTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .build();

        PodDisruptionBudgetTemplate template = new PodDisruptionBudgetTemplateBuilder()
                .withNewMetadata()
                .withAnnotations(Collections.singletonMap("annoKey", "annoValue"))
                .withLabels(Collections.singletonMap("labelKey", "labelValue"))
                .endMetadata()
                .withMaxUnavailable(2)
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parsePodDisruptionBudgetTemplate(model, template);
        assertThat(model.templatePodDisruptionBudgetLabels, is(Collections.singletonMap("labelKey", "labelValue")));
        assertThat(model.templatePodDisruptionBudgetAnnotations, is(Collections.singletonMap("annoKey", "annoValue")));
        assertThat(model.templatePodDisruptionBudgetMaxUnavailable, is(2));
    }

    @Test
    public void testParseNullPodDisruptionBudgetTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parsePodDisruptionBudgetTemplate(model, null);
        assertThat(model.templatePodDisruptionBudgetLabels, is(nullValue()));
        assertThat(model.templatePodDisruptionBudgetAnnotations, is(nullValue()));
        assertThat(model.templatePodDisruptionBudgetMaxUnavailable, is(1));
    }

    @Test
    public void testParsePodTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .build();

        LocalObjectReference secret1 = new LocalObjectReference("some-pull-secret");
        LocalObjectReference secret2 = new LocalObjectReference("some-other-pull-secret");

        Affinity affinity = new AffinityBuilder()
                .withNewNodeAffinity()
                    .withNewRequiredDuringSchedulingIgnoredDuringExecution()
                        .withNodeSelectorTerms(new NodeSelectorTermBuilder()
                                .addNewMatchExpression()
                                    .withKey("key1")
                                    .withOperator("In")
                                    .withValues("value1", "value2")
                                .endMatchExpression()
                                .build())
                    .endRequiredDuringSchedulingIgnoredDuringExecution()
                .endNodeAffinity()
                .build();

        List<Toleration> tolerations = singletonList(new TolerationBuilder()
                .withEffect("NoExecute")
                .withKey("key1")
                .withOperator("Equal")
                .withValue("value1")
                .build());

        PodTemplate template = new PodTemplateBuilder()
                .withNewMetadata()
                .withAnnotations(Collections.singletonMap("annoKey", "annoValue"))
                .withLabels(Collections.singletonMap("labelKey", "labelValue"))
                .endMetadata()
                .withSecurityContext(new PodSecurityContextBuilder().withFsGroup(123L).withRunAsGroup(456L).withRunAsUser(789L).build())
                .withImagePullSecrets(secret1, secret2)
                .withTerminationGracePeriodSeconds(123)
                .withAffinity(affinity)
                .withTolerations(tolerations)
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parsePodTemplate(model, template);
        assertThat(model.templatePodLabels, is(Collections.singletonMap("labelKey", "labelValue")));
        assertThat(model.templatePodAnnotations, is(Collections.singletonMap("annoKey", "annoValue")));
        assertThat(model.templateTerminationGracePeriodSeconds, is(123));
        assertThat(model.templateImagePullSecrets.size(), is(2));
        assertThat(model.templateImagePullSecrets.contains(secret1), is(true));
        assertThat(model.templateImagePullSecrets.contains(secret2), is(true));
        assertThat(model.templateSecurityContext, is(notNullValue()));
        assertThat(model.templateSecurityContext.getFsGroup(), is(Long.valueOf(123)));
        assertThat(model.templateSecurityContext.getRunAsGroup(), is(Long.valueOf(456)));
        assertThat(model.templateSecurityContext.getRunAsUser(), is(Long.valueOf(789)));
        assertThat(model.getUserAffinity(), is(affinity));
        assertThat(model.getTolerations(), is(tolerations));
    }

    @Test
    public void testParseNullPodTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parsePodTemplate(model, null);
        assertThat(model.templatePodLabels, is(nullValue()));
        assertThat(model.templatePodAnnotations, is(nullValue()));
        assertThat(model.templateImagePullSecrets, is(nullValue()));
        assertThat(model.templateSecurityContext, is(nullValue()));
        assertThat(model.templateTerminationGracePeriodSeconds, is(30));
    }

    @Test
    public void testParseDeploymentTemplate()  {
        KafkaConnect connect = new KafkaConnectBuilder()
                .withNewMetadata()
                .withName("my-connect-cluster")
                .withNamespace("my-namespace")
                .endMetadata()
                .build();

        DeploymentTemplate template = new DeploymentTemplateBuilder()
                .withNewMetadata()
                    .withAnnotations(Collections.singletonMap("annoKey", "annoValue"))
                    .withLabels(Collections.singletonMap("labelKey", "labelValue"))
                .endMetadata()
                .withDeploymentStrategy(DeploymentStrategy.RECREATE)
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, connect);

        ModelUtils.parseDeploymentTemplate(model, template);
        assertThat(model.templateDeploymentLabels, is(Collections.singletonMap("labelKey", "labelValue")));
        assertThat(model.templateDeploymentAnnotations, is(Collections.singletonMap("annoKey", "annoValue")));
        assertThat(model.templateDeploymentStrategy, is(DeploymentStrategy.RECREATE));
    }

    @Test
    public void testParseNullDeploymentTemplate()  {
        KafkaConnect connect = new KafkaConnectBuilder()
                .withNewMetadata()
                    .withName("my-connect-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, connect);

        ModelUtils.parseDeploymentTemplate(model, null);
        assertThat(model.templateDeploymentAnnotations, is(nullValue()));
        assertThat(model.templateDeploymentLabels, is(nullValue()));
        assertThat(model.templateDeploymentStrategy, is(DeploymentStrategy.ROLLING_UPDATE));
    }

    @Test
    public void testParseInternalServiceTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .build();

        InternalServiceTemplate template = new InternalServiceTemplateBuilder()
                .withNewMetadata()
                    .withAnnotations(Collections.singletonMap("annoKey", "annoValue"))
                    .withLabels(Collections.singletonMap("labelKey", "labelValue"))
                .endMetadata()
                .withIpFamilyPolicy(IpFamilyPolicy.REQUIRE_DUAL_STACK)
                .withIpFamilies(IpFamily.IPV6, IpFamily.IPV4)
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parseInternalServiceTemplate(model, template);
        assertThat(model.templateServiceLabels, is(Collections.singletonMap("labelKey", "labelValue")));
        assertThat(model.templateServiceAnnotations, is(Collections.singletonMap("annoKey", "annoValue")));
        assertThat(model.templateServiceIpFamilies, contains(IpFamily.IPV6, IpFamily.IPV4));
        assertThat(model.templateServiceIpFamilyPolicy, is(IpFamilyPolicy.REQUIRE_DUAL_STACK));
    }

    @Test
    public void testParseNullInternalServiceTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parseInternalServiceTemplate(model, null);
        assertThat(model.templateServiceLabels, is(nullValue()));
        assertThat(model.templateServiceAnnotations, is(nullValue()));
        assertThat(model.templateServiceIpFamilies, is(nullValue()));
        assertThat(model.templateServiceIpFamilyPolicy, is(nullValue()));
    }

    @Test
    public void testParseInternalHeadlessServiceTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                .withName("my-cluster")
                .withNamespace("my-namespace")
                .endMetadata()
                .build();

        InternalServiceTemplate template = new InternalServiceTemplateBuilder()
                .withNewMetadata()
                    .withAnnotations(Collections.singletonMap("annoKey", "annoValue"))
                    .withLabels(Collections.singletonMap("labelKey", "labelValue"))
                .endMetadata()
                .withIpFamilyPolicy(IpFamilyPolicy.REQUIRE_DUAL_STACK)
                .withIpFamilies(IpFamily.IPV6, IpFamily.IPV4)
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parseInternalHeadlessServiceTemplate(model, template);
        assertThat(model.templateHeadlessServiceLabels, is(Collections.singletonMap("labelKey", "labelValue")));
        assertThat(model.templateHeadlessServiceAnnotations, is(Collections.singletonMap("annoKey", "annoValue")));
        assertThat(model.templateHeadlessServiceIpFamilies, contains(IpFamily.IPV6, IpFamily.IPV4));
        assertThat(model.templateHeadlessServiceIpFamilyPolicy, is(IpFamilyPolicy.REQUIRE_DUAL_STACK));
    }

    @Test
    public void testParseNullInternalHeadlessServiceTemplate()  {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                .withName("my-cluster")
                .withNamespace("my-namespace")
                .endMetadata()
                .build();

        Model model = new Model(Reconciliation.DUMMY_RECONCILIATION, kafka);

        ModelUtils.parseInternalHeadlessServiceTemplate(model, null);
        assertThat(model.templateHeadlessServiceLabels, is(nullValue()));
        assertThat(model.templateHeadlessServiceAnnotations, is(nullValue()));
        assertThat(model.templateHeadlessServiceIpFamilies, is(nullValue()));
        assertThat(model.templateHeadlessServiceIpFamilyPolicy, is(nullValue()));
    }

    private class Model extends AbstractModel   {
        public Model(Reconciliation reconciliation, HasMetadata resource) {
            super(reconciliation, resource, "model-app");
        }

        @Override
        protected String getDefaultLogConfigFileName() {
            return null;
        }

        @Override
        protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
            return null;
        }
    }

    @ParallelTest
    public void testStorageSerializationAndDeserialization()    {
        Storage jbod = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-st1").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage ephemeral = new EphemeralStorageBuilder().build();

        Storage persistent = new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build();

        assertThat(ModelUtils.decodeStorageFromJson(ModelUtils.encodeStorageToJson(jbod)), is(jbod));
        assertThat(ModelUtils.decodeStorageFromJson(ModelUtils.encodeStorageToJson(ephemeral)), is(ephemeral));
        assertThat(ModelUtils.decodeStorageFromJson(ModelUtils.encodeStorageToJson(persistent)), is(persistent));
    }

    @ParallelTest
    public void testExistingCertificatesDiffer()   {
        Secret defaultSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("my-secret")
                .endMetadata()
                .addToData("my-cluster-kafka-0.crt", "Certificate0")
                .addToData("my-cluster-kafka-0.key", "Key0")
                .addToData("my-cluster-kafka-1.crt", "Certificate1")
                .addToData("my-cluster-kafka-1.key", "Key1")
                .addToData("my-cluster-kafka-2.crt", "Certificate2")
                .addToData("my-cluster-kafka-2.key", "Key2")
                .build();

        Secret sameAsDefaultSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("my-secret")
                .endMetadata()
                .addToData("my-cluster-kafka-0.crt", "Certificate0")
                .addToData("my-cluster-kafka-0.key", "Key0")
                .addToData("my-cluster-kafka-1.crt", "Certificate1")
                .addToData("my-cluster-kafka-1.key", "Key1")
                .addToData("my-cluster-kafka-2.crt", "Certificate2")
                .addToData("my-cluster-kafka-2.key", "Key2")
                .build();

        Secret scaleDownSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("my-secret")
                .endMetadata()
                .addToData("my-cluster-kafka-0.crt", "Certificate0")
                .addToData("my-cluster-kafka-0.key", "Key0")
                .build();

        Secret scaleUpSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("my-secret")
                .endMetadata()
                .addToData("my-cluster-kafka-0.crt", "Certificate0")
                .addToData("my-cluster-kafka-0.key", "Key0")
                .addToData("my-cluster-kafka-1.crt", "Certificate1")
                .addToData("my-cluster-kafka-1.key", "Key1")
                .addToData("my-cluster-kafka-2.crt", "Certificate2")
                .addToData("my-cluster-kafka-2.key", "Key2")
                .addToData("my-cluster-kafka-3.crt", "Certificate3")
                .addToData("my-cluster-kafka-3.key", "Key3")
                .addToData("my-cluster-kafka-4.crt", "Certificate4")
                .addToData("my-cluster-kafka-4.key", "Key4")
                .build();

        Secret changedSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("my-secret")
                .endMetadata()
                .addToData("my-cluster-kafka-0.crt", "Certificate0")
                .addToData("my-cluster-kafka-0.key", "Key0")
                .addToData("my-cluster-kafka-1.crt", "Certificate1")
                .addToData("my-cluster-kafka-1.key", "NewKey1")
                .addToData("my-cluster-kafka-2.crt", "Certificate2")
                .addToData("my-cluster-kafka-2.key", "Key2")
                .build();

        Secret changedScaleUpSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("my-secret")
                .endMetadata()
                .addToData("my-cluster-kafka-0.crt", "Certificate0")
                .addToData("my-cluster-kafka-0.key", "Key0")
                .addToData("my-cluster-kafka-1.crt", "Certificate1")
                .addToData("my-cluster-kafka-1.key", "Key1")
                .addToData("my-cluster-kafka-2.crt", "NewCertificate2")
                .addToData("my-cluster-kafka-2.key", "Key2")
                .addToData("my-cluster-kafka-3.crt", "Certificate3")
                .addToData("my-cluster-kafka-3.key", "Key3")
                .addToData("my-cluster-kafka-4.crt", "Certificate4")
                .addToData("my-cluster-kafka-4.key", "Key4")
                .build();

        Secret changedScaleDownSecret = new SecretBuilder()
                .withNewMetadata()
                .withName("my-secret")
                .endMetadata()
                .addToData("my-cluster-kafka-0.crt", "NewCertificate0")
                .addToData("my-cluster-kafka-0.key", "NewKey0")
                .build();

        assertThat(ModelUtils.doExistingCertificatesDiffer(defaultSecret, defaultSecret), is(false));
        assertThat(ModelUtils.doExistingCertificatesDiffer(defaultSecret, sameAsDefaultSecret), is(false));
        assertThat(ModelUtils.doExistingCertificatesDiffer(defaultSecret, scaleDownSecret), is(false));
        assertThat(ModelUtils.doExistingCertificatesDiffer(defaultSecret, scaleUpSecret), is(false));
        assertThat(ModelUtils.doExistingCertificatesDiffer(defaultSecret, changedSecret), is(true));
        assertThat(ModelUtils.doExistingCertificatesDiffer(defaultSecret, changedScaleUpSecret), is(true));
        assertThat(ModelUtils.doExistingCertificatesDiffer(defaultSecret, changedScaleDownSecret), is(true));
    }

    @ParallelTest
    public void testEmptyTolerations() {
        Toleration t1 = new TolerationBuilder()
                .withValue("")
                .withEffect("NoExecute")
                .build();

        Toleration t2 = new TolerationBuilder()
                .withValue(null)
                .withEffect("NoExecute")
                .build();

        PodTemplate pt1 = new PodTemplate();
        pt1.setTolerations(singletonList(t1));
        PodTemplate pt2 = new PodTemplate();
        pt2.setTolerations(singletonList(t2));

        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                    .withNamespace("my-namespace")
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .withReplicas(3)
                        .withNewEphemeralStorage()
                        .endEphemeralStorage()
                        .withListeners(new GenericKafkaListenerBuilder()
                                .withType(KafkaListenerType.INTERNAL)
                                .withPort(9092)
                                .withName("plain")
                                .withTls(false)
                                .build())
                    .endKafka()
                .endSpec()
                .build();

        KafkaCluster model1 = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kafka, KafkaVersionTestUtils.getKafkaVersionLookup());
        ModelUtils.parsePodTemplate(model1, pt1);

        KafkaCluster model2 = KafkaCluster.fromCrd(Reconciliation.DUMMY_RECONCILIATION, kafka, KafkaVersionTestUtils.getKafkaVersionLookup());
        ModelUtils.parsePodTemplate(model2, pt2);

        assertThat(model1.getTolerations(), is(model2.getTolerations()));
    }

    @ParallelTest
    public void testCONetworkPolicyPeerNamespaceSelectorSameNS()  {
        NetworkPolicyPeer peer = new NetworkPolicyPeer();
        ModelUtils.setClusterOperatorNetworkPolicyNamespaceSelector(peer, "my-ns", "my-ns", null);
        assertThat(peer.getNamespaceSelector(), is(nullValue()));
    }

    @ParallelTest
    public void testCONetworkPolicyPeerNamespaceSelectorDifferentNSNoLabels()  {
        NetworkPolicyPeer peer = new NetworkPolicyPeer();
        ModelUtils.setClusterOperatorNetworkPolicyNamespaceSelector(peer, "my-ns", "my-operator-ns", null);
        assertThat(peer.getNamespaceSelector().getMatchLabels(), is(nullValue()));

        ModelUtils.setClusterOperatorNetworkPolicyNamespaceSelector(peer, "my-ns", "my-operator-ns", Labels.fromMap(emptyMap()));
        assertThat(peer.getNamespaceSelector().getMatchLabels(), is(nullValue()));
    }

    @ParallelTest
    public void testCONetworkPolicyPeerNamespaceSelectorDifferentNSWithLabels()  {
        NetworkPolicyPeer peer = new NetworkPolicyPeer();
        Labels nsLabels = Labels.fromMap(singletonMap("labelKey", "labelValue"));
        ModelUtils.setClusterOperatorNetworkPolicyNamespaceSelector(peer, "my-ns", "my-operator-ns", nsLabels);
        assertThat(peer.getNamespaceSelector().getMatchLabels(), is(nsLabels.toMap()));
    }

    @ParallelTest
    public void testCreateOwnerReference()   {
        Kafka owner = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-kafka")
                    .withUid("some-uid")
                .endMetadata()
                .build();

        OwnerReference ref = ModelUtils.createOwnerReference(owner);

        assertThat(ref.getApiVersion(), is(owner.getApiVersion()));
        assertThat(ref.getKind(), is(owner.getKind()));
        assertThat(ref.getName(), is(owner.getMetadata().getName()));
        assertThat(ref.getUid(), is(owner.getMetadata().getUid()));
        assertThat(ref.getBlockOwnerDeletion(), is(false));
        assertThat(ref.getController(), is(false));
    }

    @ParallelTest
    public void testHasOwnerReference()    {
        OwnerReference owner = new OwnerReferenceBuilder()
                .withApiVersion("my-api")
                .withKind("my-kind")
                .withName("my-owner")
                .withUid("a02c09d8-a04f-469d-97ba-920720abe9b3")
                .withBlockOwnerDeletion(false)
                .withController(false)
                .build();

        OwnerReference otherOwner = new OwnerReferenceBuilder()
                .withApiVersion("my-other-api")
                .withKind("my-other-kind")
                .withName("my-other-owner")
                .withUid("3dfcd6b9-ad05-4277-8d13-147346fe1f70")
                .withBlockOwnerDeletion(false)
                .withController(false)
                .build();

        Pod pod = new PodBuilder()
                    .withNewMetadata()
                        .withName("my-pod")
                        .withNamespace("my-namespace")
                    .endMetadata()
                    .withNewSpec()
                        .withContainers(new ContainerBuilder()
                                .withName("busybox")
                                .withImage("busybox")
                                .withCommand("sleep", "3600")
                                .withImagePullPolicy("IfNotPresent")
                                .build())
                        .withRestartPolicy("Always")
                        .withTerminationGracePeriodSeconds(0L)
                    .endSpec()
                    .build();

        // No owner reference
        assertThat(ModelUtils.hasOwnerReference(pod, owner), is(false));

        // Only our owner reference
        pod.getMetadata().setOwnerReferences(List.of(owner));
        assertThat(ModelUtils.hasOwnerReference(pod, owner), is(true));

        // Other owner reference
        pod.getMetadata().setOwnerReferences(List.of(otherOwner));
        assertThat(ModelUtils.hasOwnerReference(pod, owner), is(false));

        // Multiple owner references
        pod.getMetadata().setOwnerReferences(List.of(otherOwner, owner));
        assertThat(ModelUtils.hasOwnerReference(pod, owner), is(true));
    }

    @ParallelTest
    public void testInvalidHeapPercentage() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> ModelUtils.heapOptions(new ArrayList<>(), 0, 0, new JvmOptions(), new ResourceRequirements()));
        assertThat(exception.getMessage(), is("The Heap percentage 0 is invalid. It has to be >0 and <= 100."));

        exception = assertThrows(RuntimeException.class, () -> ModelUtils.heapOptions(new ArrayList<>(), 101, 0, new JvmOptions(), new ResourceRequirements()));
        assertThat(exception.getMessage(), is("The Heap percentage 101 is invalid. It has to be >0 and <= 100."));
    }

    @ParallelTest
    public void testValidHeapPercentage() {
        Map<String, String> envVars = heapOptions(null, 1, 0, new ResourceRequirements(Map.of("memory", new Quantity("1Gi")), Map.of()));
        assertThat(envVars.size(), is(1));
        assertThat(envVars.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is("1"));

        envVars = heapOptions(null, 100, 0, new ResourceRequirements(Map.of("memory", new Quantity("1Gi")), Map.of()));
        assertThat(envVars.size(), is(1));
        assertThat(envVars.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is("100"));
    }
    
    @ParallelTest
    public void testJvmMemoryOptionsExplicit() {
        Map<String, String> env = heapOptions(jvmOptions("4", "4"), 50, 4_000_000_000L, null);
        assertThat(env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS), is("-Xms4 -Xmx4"));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is(nullValue()));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX), is(nullValue()));
    }

    @ParallelTest
    public void testJvmMemoryOptionsXmsOnly() {
        Map<String, String> env = heapOptions(jvmOptions(null, "4"), 50, 5_000_000_000L, null);
        assertThat(env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS), is("-Xms4"));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is(nullValue()));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX), is(nullValue()));
    }

    @ParallelTest
    public void testJvmMemoryOptionsXmxOnly() {
        Map<String, String> env = heapOptions(jvmOptions("4", null), 50, 5_000_000_000L, null);
        assertThat(env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS), is("-Xmx4"));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is(nullValue()));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX), is(nullValue()));
    }

    @ParallelTest
    public void testJvmMemoryOptionsDefaultWithNoMemoryLimitOrJvmOptions() {
        Map<String, String> env = heapOptions(jvmOptions(null, null), 50, 5_000_000_000L, null);
        assertThat(env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS), is("-Xms" + AbstractModel.DEFAULT_JVM_XMS));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is(nullValue()));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX), is(nullValue()));
    }

    @ParallelTest
    public void testJvmMemoryOptionsDefaultWithMemoryLimit() {
        Map<String, String> env = heapOptions(jvmOptions(null, "4"), 50, 5_000_000_000L, new ResourceRequirements(Map.of("memory", new Quantity("1Gi")), Map.of()));
        assertThat(env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS), is("-Xms4"));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is("50"));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX), is("5000000000"));
    }

    @ParallelTest
    public void testJvmMemoryOptionsMemoryRequest() {
        Map<String, String> env = heapOptions(null, 70, 10_000_000_000L, new ResourceRequirements(Map.of(), Map.of("memory", new Quantity("1Gi"))));
        assertThat(env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS), is(nullValue()));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_PERCENTAGE), is("70"));
        assertThat(env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX), is("10000000000"));
    }

    /**
     * Utility methods to get the heap options environment variables based on the given options
     *
     * @param jvmOpts           The JvmOptions configuration from the Strimzi model
     * @param dynamicPercentage The dynamic heap percentage
     * @param dynamicMax        The maximal heap
     * @param resources         The configured resources
     *
     * @return                  Map with the environment variables with their names as the keys of the map
     */
    private static Map<String, String> heapOptions(JvmOptions jvmOpts, int dynamicPercentage, long dynamicMax, ResourceRequirements resources)  {
        List<EnvVar> envVars = new ArrayList<>();

        ModelUtils.heapOptions(envVars, dynamicPercentage, dynamicMax, jvmOpts, resources);

        return envVars.stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    }

    /**
     * Utility method to create JvmOptions object.
     *
     * @param xmx   Configured -Xmx
     * @param xms   Configured -Xms
     *
     * @return      New JvmOptions object
     */
    private static JvmOptions jvmOptions(String xmx, String xms) {
        JvmOptions result = new JvmOptions();
        result.setXms(xms);
        result.setXmx(xmx);
        return result;
    }
}
