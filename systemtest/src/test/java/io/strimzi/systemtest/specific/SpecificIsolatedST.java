/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.specific;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.NodeAffinity;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.Rack;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBrokerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.BeforeAllOnce;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.kafkaclients.externalClients.ExternalKafkaClient;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClientsBuilder;
import io.strimzi.systemtest.resources.operator.SetupClusterOperator;
import io.strimzi.systemtest.annotations.IsolatedTest;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.kubernetes.NetworkPolicyResource;
import io.strimzi.systemtest.templates.crd.KafkaClientsTemplates;
import io.strimzi.systemtest.templates.crd.KafkaConnectTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.systemtest.utils.specific.BridgeUtils;
import io.strimzi.systemtest.annotations.IsolatedSuite;
import io.strimzi.systemtest.utils.specific.ScraperUtils;
import io.strimzi.test.executor.Exec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.CONNECT;
import static io.strimzi.systemtest.Constants.CONNECT_COMPONENTS;
import static io.strimzi.systemtest.Constants.CO_OPERATION_TIMEOUT_SHORT;
import static io.strimzi.systemtest.Constants.EXTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.LOADBALANCER_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.SPECIFIC;
import static io.strimzi.systemtest.k8s.Events.Created;
import static io.strimzi.systemtest.k8s.Events.Pulled;
import static io.strimzi.systemtest.k8s.Events.Scheduled;
import static io.strimzi.systemtest.k8s.Events.Started;
import static io.strimzi.systemtest.matchers.Matchers.hasAllOfReasons;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag(SPECIFIC)
@IsolatedSuite
public class SpecificIsolatedST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(SpecificIsolatedST.class);

    @IsolatedTest("UtestRackAwareConnectWrongDeploymentsing more tha one Kafka cluster in one namespace")
    @Tag(REGRESSION)
    @Tag(INTERNAL_CLIENTS_USED)
    void testRackAware(ExtensionContext extensionContext) {
        assumeFalse(Environment.isNamespaceRbacScope());

        String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        String producerName = "hello-world-producer";
        String consumerName = "hello-world-consumer";
        String rackKey = "rack-key";

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaEphemeral(clusterName, 1, 1)
            .editSpec()
                .editKafka()
                    .withNewRack()
                        .withTopologyKey(rackKey)
                    .endRack()
                .endKafka()
            .endSpec()
            .build());

        Affinity kafkaPodSpecAffinity = StUtils.getStatefulSetOrStrimziPodSetAffinity(KafkaResources.kafkaStatefulSetName(clusterName));
        NodeSelectorRequirement kafkaPodNodeSelectorRequirement = kafkaPodSpecAffinity.getNodeAffinity()
                .getRequiredDuringSchedulingIgnoredDuringExecution().getNodeSelectorTerms().get(0).getMatchExpressions().get(0);

        assertThat(kafkaPodNodeSelectorRequirement.getKey(), is(rackKey));
        assertThat(kafkaPodNodeSelectorRequirement.getOperator(), is("Exists"));

        PodAffinityTerm kafkaPodAffinityTerm = kafkaPodSpecAffinity.getPodAntiAffinity().getPreferredDuringSchedulingIgnoredDuringExecution().get(0).getPodAffinityTerm();

        assertThat(kafkaPodAffinityTerm.getTopologyKey(), is(rackKey));
        assertThat(kafkaPodAffinityTerm.getLabelSelector().getMatchLabels(), hasEntry("strimzi.io/cluster", clusterName));
        assertThat(kafkaPodAffinityTerm.getLabelSelector().getMatchLabels(), hasEntry("strimzi.io/name", KafkaResources.kafkaStatefulSetName(clusterName)));

        String rackId = cmdKubeClient().execInPod(KafkaResources.kafkaPodName(clusterName, 0), "/bin/bash", "-c", "cat /opt/kafka/init/rack.id").out();
        assertThat(rackId.trim(), is("zone"));

        String brokerRack = cmdKubeClient().execInPod(KafkaResources.kafkaPodName(clusterName, 0), "/bin/bash", "-c", "cat /tmp/strimzi.properties | grep broker.rack").out();
        assertThat(brokerRack.contains("broker.rack=zone"), is(true));

        String uid = kubeClient().getPodUid(KafkaResources.kafkaPodName(clusterName, 0));
        List<Event> events = kubeClient().listEventsByResourceUid(uid);
        assertThat(events, hasAllOfReasons(Scheduled, Pulled, Created, Started));

        KafkaClients kafkaBasicClientJob = new KafkaClientsBuilder()
            .withProducerName(producerName)
            .withConsumerName(consumerName)
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(clusterName))
            .withTopicName(TOPIC_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withDelayMs(0)
            .build();

        resourceManager.createResource(extensionContext, kafkaBasicClientJob.producerStrimzi());
        resourceManager.createResource(extensionContext, kafkaBasicClientJob.consumerStrimzi());
    }

    @IsolatedTest("Modification of shared Cluster Operator configuration")
    @Tag(CONNECT)
    @Tag(CONNECT_COMPONENTS)
    @Tag(REGRESSION)
    @Tag(INTERNAL_CLIENTS_USED)
    void testRackAwareConnectWrongDeployment(ExtensionContext extensionContext) {
        assumeFalse(Environment.isNamespaceRbacScope());

        String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());
        Map<String, String> label = Collections.singletonMap("my-label", "value");
        Map<String, String> anno = Collections.singletonMap("my-annotation", "value");

        // We need to update CO configuration to set OPERATION_TIMEOUT to shorter value, because we expect timeout in that test
        Map<String, String> coSnapshot = DeploymentUtils.depSnapshot(clusterOperator.getDeploymentNamespace(), ResourceManager.getCoDeploymentName());
        // We have to install CO in class stack, otherwise it will be deleted at the end of test case and all following tests will fail
        clusterOperator.unInstall();
        clusterOperator = new SetupClusterOperator.SetupClusterOperatorBuilder()
            .withExtensionContext(BeforeAllOnce.getSharedExtensionContext())
            .withNamespace(clusterOperator.getDeploymentNamespace())
            .withOperationTimeout(CO_OPERATION_TIMEOUT_SHORT)
            .withReconciliationInterval(Constants.RECONCILIATION_INTERVAL)
            .createInstallation()
            .runBundleInstallation();

        coSnapshot = DeploymentUtils.waitTillDepHasRolled(clusterOperator.getDeploymentNamespace(), ResourceManager.getCoDeploymentName(), 1, coSnapshot);

        String wrongRackKey = "wrong-key";
        String rackKey = "rack-key";

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaEphemeral(clusterName, 3)
            .editMetadata()
                .withNamespace(clusterOperator.getDeploymentNamespace())
            .endMetadata()
            .editSpec()
                    .editKafka()
                        .withNewRack()
                            .withTopologyKey(rackKey)
                        .endRack()
                        .addToConfig("replica.selector.class", "org.apache.kafka.common.replica.RackAwareReplicaSelector")
                    .endKafka()
                .endSpec()
                .build());

        resourceManager.createResource(extensionContext, KafkaClientsTemplates.kafkaClients(clusterOperator.getDeploymentNamespace(), false, kafkaClientsName).build());
        String kafkaClientsPodName = kubeClient(clusterOperator.getDeploymentNamespace()).listPodsByPrefixInName(clusterOperator.getDeploymentNamespace(), kafkaClientsName).get(0).getMetadata().getName();

        LOGGER.info("Deploy KafkaConnect with wrong rack-aware topology key: {}", wrongRackKey);

        KafkaConnect kc = KafkaConnectTemplates.kafkaConnect(extensionContext, clusterName, clusterName, 1)
            .editMetadata()
                .withNamespace(clusterOperator.getDeploymentNamespace())
            .endMetadata()
            .editSpec()
                .withNewRack()
                    .withTopologyKey(wrongRackKey)
                .endRack()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .editOrNewTemplate()
                    .withNewClusterRoleBinding()
                        .withNewMetadata()
                            .withAnnotations(anno)
                            .withLabels(label)
                        .endMetadata()
                    .endClusterRoleBinding()
                .endTemplate()
            .endSpec().build();

        resourceManager.createResource(extensionContext, false,  kc);

        NetworkPolicyResource.deployNetworkPolicyForResource(extensionContext, kc, KafkaConnectResources.deploymentName(clusterName));

        PodUtils.waitForPendingPod(clusterOperator.getDeploymentNamespace(), clusterName + "-connect");
        List<String> connectWrongPods = kubeClient(clusterOperator.getDeploymentNamespace()).listPodNames(clusterOperator.getDeploymentNamespace(), clusterName, Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        String connectWrongPodName = connectWrongPods.get(0);
        LOGGER.info("Waiting for ClusterOperator to get timeout operation of incorrectly set up KafkaConnect");
        KafkaConnectUtils.waitForKafkaConnectCondition("TimeoutException", "NotReady", clusterOperator.getDeploymentNamespace(), clusterName);

        PodStatus kcWrongStatus = kubeClient().getPod(clusterOperator.getDeploymentNamespace(), connectWrongPodName).getStatus();
        assertThat("Unschedulable", is(kcWrongStatus.getConditions().get(0).getReason()));
        assertThat("PodScheduled", is(kcWrongStatus.getConditions().get(0).getType()));

        KafkaConnectResource.replaceKafkaConnectResourceInSpecificNamespace(clusterName, kafkaConnect -> {
            kafkaConnect.getSpec().setRack(new Rack(rackKey));
        }, clusterOperator.getDeploymentNamespace());
        KafkaConnectUtils.waitForConnectReady(clusterOperator.getDeploymentNamespace(), clusterName);
        LOGGER.info("KafkaConnect is ready with changed rack key: '{}'.", rackKey);
        LOGGER.info("Verify KafkaConnect rack key update");
        kc = KafkaConnectResource.kafkaConnectClient().inNamespace(clusterOperator.getDeploymentNamespace()).withName(clusterName).get();
        assertThat(kc.getSpec().getRack().getTopologyKey(), is(rackKey));

        final String scraperPodName = ScraperUtils.getScraperPod(clusterOperator.getDeploymentNamespace()).getMetadata().getName();

        List<String> kcPods = kubeClient(clusterOperator.getDeploymentNamespace()).listPodNames(clusterOperator.getDeploymentNamespace(), clusterName, Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        KafkaConnectUtils.sendReceiveMessagesThroughConnect(kcPods.get(0), TOPIC_NAME, kafkaClientsPodName, scraperPodName, clusterOperator.getDeploymentNamespace(), clusterName);

        // check the ClusterRoleBinding annotations and labels in Kafka cluster
        Map<String, String> actualLabel = KafkaConnectResource.kafkaConnectClient().inNamespace(clusterOperator.getDeploymentNamespace()).withName(clusterName).get().getSpec().getTemplate().getClusterRoleBinding().getMetadata().getLabels();
        Map<String, String> actualAnno = KafkaConnectResource.kafkaConnectClient().inNamespace(clusterOperator.getDeploymentNamespace()).withName(clusterName).get().getSpec().getTemplate().getClusterRoleBinding().getMetadata().getAnnotations();

        assertThat(actualLabel, is(label));
        assertThat(actualAnno, is(anno));

        // Revert changes for CO deployment
        clusterOperator.unInstall();
        clusterOperator = new SetupClusterOperator.SetupClusterOperatorBuilder()
            .withExtensionContext(BeforeAllOnce.getSharedExtensionContext())
            .withNamespace(clusterOperator.getDeploymentNamespace())
            .createInstallation()
            .runBundleInstallation();

        DeploymentUtils.waitTillDepHasRolled(clusterOperator.getDeploymentNamespace(), ResourceManager.getCoDeploymentName(), 1, coSnapshot);
    }

    @IsolatedTest("Modification of shared Cluster Operator configuration")
    @Tag(CONNECT)
    @Tag(CONNECT_COMPONENTS)
    @Tag(ACCEPTANCE)
    @Tag(INTERNAL_CLIENTS_USED)
    void testRackAwareConnectCorrectDeployment(ExtensionContext extensionContext) {
        assumeFalse(Environment.isNamespaceRbacScope());
        assumeTrue(!Environment.isHelmInstall() && !Environment.isOlmInstall());

        String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());
        String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        // We need to update CO configuration to set OPERATION_TIMEOUT to shorter value, because we expect timeout in that test
        Map<String, String> coSnapshot = DeploymentUtils.depSnapshot(clusterOperator.getDeploymentNamespace(), ResourceManager.getCoDeploymentName());
        // We have to install CO in class stack, otherwise it will be deleted at the end of test case and all following tests will fail
        clusterOperator.unInstall();
        clusterOperator = clusterOperator.defaultInstallation()
            .withOperationTimeout(CO_OPERATION_TIMEOUT_SHORT)
            .withReconciliationInterval(Constants.RECONCILIATION_INTERVAL)
            .createInstallation()
            .runBundleInstallation();

        coSnapshot = DeploymentUtils.waitTillDepHasRolled(ResourceManager.getCoDeploymentName(), 1, coSnapshot);

        String rackKey = "rack-key";
        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaEphemeral(clusterName,  3)
            .editSpec()
                .editKafka()
                    .withNewRack()
                        .withTopologyKey(rackKey)
                    .endRack()
                    .addToConfig("replica.selector.class", "org.apache.kafka.common.replica.RackAwareReplicaSelector")
                .endKafka()
            .endSpec()
            .build());

        // we should create topic before KafkaConnect - topic is recreated if we delete it before KafkaConnect
        String topicName = "rw-" + KafkaTopicUtils.generateRandomNameOfTopic();
        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, topicName).build());

        resourceManager.createResource(extensionContext, KafkaClientsTemplates.kafkaClients(false, kafkaClientsName).build());

        String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(kafkaClientsName).get(0).getMetadata().getName();

        LOGGER.info("Deploy KafkaConnect with correct rack-aware topology key: {}", rackKey);
        KafkaConnect kc = KafkaConnectTemplates.kafkaConnect(extensionContext, clusterName, 1)
                .editSpec()
                    .withNewRack()
                        .withTopologyKey(rackKey)
                    .endRack()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .endSpec()
                .build();

        resourceManager.createResource(extensionContext, kc);

        NetworkPolicyResource.deployNetworkPolicyForResource(extensionContext, kc, KafkaConnectResources.deploymentName(clusterName));

        String connectPodName = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0);

        Affinity connectPodSpecAffinity = kubeClient().getDeployment(KafkaConnectResources.deploymentName(clusterName)).getSpec().getTemplate().getSpec().getAffinity();
        NodeSelectorRequirement connectPodNodeSelectorRequirement = connectPodSpecAffinity.getNodeAffinity()
                .getRequiredDuringSchedulingIgnoredDuringExecution().getNodeSelectorTerms().get(0).getMatchExpressions().get(0);
        Pod connectPod = kubeClient().getPod(connectPodName);
        NodeAffinity nodeAffinity = connectPod.getSpec().getAffinity().getNodeAffinity();

        LOGGER.info("PodName: {}\nNodeAffinity: {}", connectPodName, nodeAffinity);
        assertThat(connectPodNodeSelectorRequirement.getKey(), is(rackKey));
        assertThat(connectPodNodeSelectorRequirement.getOperator(), is("Exists"));

        // fetch scraper Pod name
        final String scraperPodName = ScraperUtils.getScraperPod(clusterOperator.getDeploymentNamespace()).getMetadata().getName();

        KafkaConnectUtils.sendReceiveMessagesThroughConnect(connectPodName, topicName, kafkaClientsPodName, scraperPodName, clusterOperator.getDeploymentNamespace(), clusterName);
        // Revert changes for CO deployment
        clusterOperator.unInstall();
        clusterOperator = clusterOperator.defaultInstallation()
            .createInstallation()
            .runInstallation();

        DeploymentUtils.waitTillDepHasRolled(ResourceManager.getCoDeploymentName(), 1, coSnapshot);
    }

    @IsolatedTest("Using more tha one Kafka cluster in one namespace")
    @Tag(LOADBALANCER_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    void testLoadBalancerIpOverride(ExtensionContext extensionContext) {
        String bootstrapOverrideIP = "10.0.0.1";
        String brokerOverrideIP = "10.0.0.2";
        String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaEphemeral(clusterName, 3, 1)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                            .withPort(9094)
                            .withType(KafkaListenerType.LOADBALANCER)
                            .withTls(true)
                            .withNewConfiguration()
                                .withNewBootstrap()
                                    .withLoadBalancerIP(brokerOverrideIP)
                                .endBootstrap()
                                .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                        .withBroker(0)
                                        .withLoadBalancerIP(brokerOverrideIP)
                                        .build())
                                .withFinalizers(LB_FINALIZERS)
                            .endConfiguration()
                            .build())
                .endKafka()
            .endSpec()
            .build());

        assertThat("Kafka External bootstrap doesn't contain correct loadBalancer address", kubeClient().getService(KafkaResources.externalBootstrapServiceName(clusterName)).getSpec().getLoadBalancerIP(), is(bootstrapOverrideIP));
        assertThat("Kafka Broker-0 service doesn't contain correct loadBalancer address", kubeClient().getService(KafkaResources.brokerSpecificService(clusterName, 0)).getSpec().getLoadBalancerIP(), is(brokerOverrideIP));

        ExternalKafkaClient externalKafkaClient = new ExternalKafkaClient.Builder()
            .withTopicName(TOPIC_NAME)
            .withNamespaceName(clusterOperator.getDeploymentNamespace())
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
            .build();

        externalKafkaClient.verifyProducedAndConsumedMessages(
            externalKafkaClient.sendMessagesPlain(),
            externalKafkaClient.receiveMessagesPlain()
        );
    }

    @IsolatedTest("Using more tha one Kafka cluster in one namespace")
    @Tag(REGRESSION)
    void testDeployUnsupportedKafka(ExtensionContext extensionContext) {
        String nonExistingVersion = "6.6.6";
        String nonExistingVersionMessage = "Unsupported Kafka.spec.kafka.version: " + nonExistingVersion + ". Supported versions are:.*";
        String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, false, KafkaTemplates.kafkaEphemeral(clusterName, 1, 1)
            .editSpec()
                .editKafka()
                    .withVersion(nonExistingVersion)
                .endKafka()
            .endSpec().build());

        LOGGER.info("Kafka with version {} deployed.", nonExistingVersion);

        KafkaUtils.waitForKafkaNotReady(clusterOperator.getDeploymentNamespace(), clusterName);
        KafkaUtils.waitUntilKafkaStatusConditionContainsMessage(clusterName, clusterOperator.getDeploymentNamespace(), nonExistingVersionMessage);

        KafkaResource.kafkaClient().inNamespace(clusterOperator.getDeploymentNamespace()).withName(clusterName).delete();
    }

    @IsolatedTest("Using more tha one Kafka cluster in one namespace")
    @Tag(LOADBALANCER_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    void testLoadBalancerSourceRanges(ExtensionContext extensionContext) {
        String networkInterfaces = Exec.exec("ip", "route").out();
        Pattern ipv4InterfacesPattern = Pattern.compile("[0-9]+.[0-9]+.[0-9]+.[0-9]+\\/[0-9]+ dev (eth0|enp11s0u1).*");
        Matcher ipv4InterfacesMatcher = ipv4InterfacesPattern.matcher(networkInterfaces);
        String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        ipv4InterfacesMatcher.find();
        LOGGER.info(ipv4InterfacesMatcher.group(0));
        String correctNetworkInterface = ipv4InterfacesMatcher.group(0);

        String[] correctNetworkInterfaceStrings = correctNetworkInterface.split(" ");

        String ipWithPrefix = correctNetworkInterfaceStrings[0];

        LOGGER.info("Network address of machine with associated prefix is {}", ipWithPrefix);

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3)
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                        .withName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                        .withPort(9094)
                        .withType(KafkaListenerType.LOADBALANCER)
                        .withTls(false)
                        .withNewConfiguration()
                            .withLoadBalancerSourceRanges(Collections.singletonList(ipWithPrefix))
                            .withFinalizers(LB_FINALIZERS)
                        .endConfiguration()
                        .build())
                .endKafka()
            .endSpec()
            .build());

        ExternalKafkaClient externalKafkaClient = new ExternalKafkaClient.Builder()
            .withTopicName(TOPIC_NAME)
            .withNamespaceName(clusterOperator.getDeploymentNamespace())
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
            .build();

        externalKafkaClient.verifyProducedAndConsumedMessages(
            externalKafkaClient.sendMessagesPlain(),
            externalKafkaClient.receiveMessagesPlain()
        );

        String invalidNetworkAddress = "255.255.255.111/30";

        LOGGER.info("Replacing Kafka CR invalid load-balancer source range to {}", invalidNetworkAddress);

        KafkaResource.replaceKafkaResource(clusterName, kafka ->
            kafka.getSpec().getKafka().setListeners(Collections.singletonList(new GenericKafkaListenerBuilder()
                .withName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                .withPort(9094)
                .withType(KafkaListenerType.LOADBALANCER)
                .withTls(false)
                .withNewConfiguration()
                    .withLoadBalancerSourceRanges(Collections.singletonList(ipWithPrefix))
                    .withFinalizers(LB_FINALIZERS)
                .endConfiguration()
                .build()))
        );

        LOGGER.info("Expecting that clients will not be able to connect to external load-balancer service cause of invalid load-balancer source range.");

        ExternalKafkaClient newExternalKafkaClient = externalKafkaClient.toBuilder()
            .withMessageCount(2 * MESSAGE_COUNT)
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        assertThrows(TimeoutException.class, () ->
            newExternalKafkaClient.verifyProducedAndConsumedMessages(
                newExternalKafkaClient.sendMessagesPlain(),
                newExternalKafkaClient.receiveMessagesPlain()
            ));
    }

    @BeforeAll
    void setup(ExtensionContext extensionContext) {
        clusterOperator.unInstall();
        clusterOperator.defaultInstallation()
            .createInstallation()
            .runInstallation();
        LOGGER.info(BridgeUtils.getBridgeVersion());
    }
}
