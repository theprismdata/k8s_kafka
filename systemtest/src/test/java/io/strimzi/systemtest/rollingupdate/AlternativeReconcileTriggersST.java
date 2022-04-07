/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.rollingupdate;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.StrimziPodSet;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.operator.common.Annotations;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.annotations.ParallelNamespaceTest;
import io.strimzi.systemtest.kafkaclients.clients.InternalKafkaClient;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClientsBuilder;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.templates.crd.KafkaClientsTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.templates.crd.KafkaUserTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.RollingUpdateUtils;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUserUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.systemtest.annotations.ParallelSuite;
import io.vertx.core.cli.annotations.Description;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.ROLLING_UPDATE;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Tag(REGRESSION)
@Tag(INTERNAL_CLIENTS_USED)
@Tag(ROLLING_UPDATE)
@ParallelSuite
class AlternativeReconcileTriggersST extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(AlternativeReconcileTriggersST.class);

    private final String namespace = testSuiteNamespaceManager.getMapOfAdditionalNamespaces().get(AlternativeReconcileTriggersST.class.getSimpleName()).stream().findFirst().get();

    @ParallelNamespaceTest
    @SuppressWarnings("checkstyle:MethodLength")
    void testManualTriggeringRollingUpdate(ExtensionContext extensionContext) {
        final String namespaceName = StUtils.getNamespaceBasedOnRbac(namespace, extensionContext);
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());
        final String continuousTopicName = "continuous-topic";
        // 500 messages will take 500 seconds in that case
        final int continuousClientsMessageCount = 500;
        final String producerName = "hello-world-producer";
        final String consumerName = "hello-world-consumer";

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build());

        String kafkaName = KafkaResources.kafkaStatefulSetName(clusterName);
        String zkName = KafkaResources.zookeeperStatefulSetName(clusterName);

        LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, kafkaName);
        LabelSelector zkSelector = KafkaResource.getLabelSelector(clusterName, zkName);

        Map<String, String> kafkaPods = PodUtils.podSnapshot(namespaceName, kafkaSelector);
        Map<String, String> zkPods = PodUtils.podSnapshot(namespaceName, zkSelector);

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, topicName, namespaceName).build());
        // ##############################
        // Attach clients which will continuously produce/consume messages to/from Kafka brokers during rolling update
        // ##############################
        // Setup topic, which has 3 replicas and 2 min.isr to see if producer will be able to work during rolling update
        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, continuousTopicName, 3, 3, 2).build());
        String producerAdditionConfiguration = "delivery.timeout.ms=20000\nrequest.timeout.ms=20000";
        // Add transactional id to make producer transactional
        producerAdditionConfiguration = producerAdditionConfiguration.concat("\ntransactional.id=" + continuousTopicName + ".1");
        producerAdditionConfiguration = producerAdditionConfiguration.concat("\nenable.idempotence=true");

        KafkaClients kafkaBasicClientJob = new KafkaClientsBuilder()
            .withProducerName(producerName)
            .withConsumerName(consumerName)
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(clusterName))
            .withTopicName(continuousTopicName)
            .withMessageCount(continuousClientsMessageCount)
            .withAdditionalConfig(producerAdditionConfiguration)
            .withDelayMs(1000)
            .withNamespaceName(namespaceName)
            .build();

        resourceManager.createResource(extensionContext, kafkaBasicClientJob.producerStrimzi());
        resourceManager.createResource(extensionContext, kafkaBasicClientJob.consumerStrimzi());
        // ##############################

        String userName = KafkaUserUtils.generateRandomNameOfKafkaUser();
        KafkaUser user = KafkaUserTemplates.tlsUser(clusterName, userName).build();

        resourceManager.createResource(extensionContext, user);
        resourceManager.createResource(extensionContext, false, KafkaClientsTemplates.kafkaClients(true, kafkaClientsName, user).build());

        final String kafkaClientsPodName = PodUtils.getPodsByPrefixInNameWithDynamicWait(namespaceName, kafkaClientsName).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(namespaceName)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(userName)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.produceTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        // rolling update for kafka
        // set annotation to trigger Kafka rolling update
        LOGGER.info("Annotate Kafka {} {} with manual rolling update annotation",
            Environment.isStrimziPodSetEnabled() ? StrimziPodSet.RESOURCE_KIND : Constants.STATEFUL_SET, kafkaName);

        StUtils.annotateStatefulSetOrStrimziPodSet(namespaceName, kafkaName, Collections.singletonMap(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true"));

        // check annotation to trigger rolling update
        assertThat(Boolean.parseBoolean(StUtils.getAnnotationsOfStatefulSetOrStrimziPodSet(namespaceName, kafkaName)
            .get(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE)), is(true));

        RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, kafkaSelector, 3, kafkaPods);

        // wait when annotation will be removed
        TestUtils.waitFor("CO removes rolling update annotation", Constants.WAIT_FOR_ROLLING_UPDATE_INTERVAL, Constants.GLOBAL_TIMEOUT,
                () -> StUtils.getAnnotationsOfStatefulSetOrStrimziPodSet(namespaceName, zkName) == null
                        || !StUtils.getAnnotationsOfStatefulSetOrStrimziPodSet(namespaceName, zkName).containsKey(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE));

        int received = internalKafkaClient.receiveMessagesTls();
        assertThat(received, is(MESSAGE_COUNT));

        // rolling update for zookeeper
        // set annotation to trigger Zookeeper rolling update
        LOGGER.info("Annotate Zookeeper {} {} with manual rolling update annotation",
            Environment.isStrimziPodSetEnabled() ? StrimziPodSet.RESOURCE_KIND : Constants.STATEFUL_SET, zkName);

        StUtils.annotateStatefulSetOrStrimziPodSet(namespaceName, zkName, Collections.singletonMap(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true"));

        // check annotation to trigger rolling update
        assertThat(Boolean.parseBoolean(StUtils.getAnnotationsOfStatefulSetOrStrimziPodSet(namespaceName, zkName)
            .get(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE)), is(true));

        RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, zkSelector, 3, zkPods);

        // wait when annotation will be removed
        TestUtils.waitFor("CO removes rolling update annotation", Constants.WAIT_FOR_ROLLING_UPDATE_INTERVAL, Constants.GLOBAL_TIMEOUT,
                () -> StUtils.getAnnotationsOfStatefulSetOrStrimziPodSet(namespaceName, zkName) == null
                        || !StUtils.getAnnotationsOfStatefulSetOrStrimziPodSet(namespaceName, zkName).containsKey(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE));

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        received = internalKafkaClient.receiveMessagesTls();
        assertThat(received, is(MESSAGE_COUNT));

        // Create new topic to ensure, that ZK is working properly
        String newTopicName = KafkaTopicUtils.generateRandomNameOfTopic();

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, newTopicName, 1, 1).build());

        internalKafkaClient = internalKafkaClient.toBuilder()
            .withTopicName(newTopicName)
            .withConsumerGroupName(ClientUtils.generateRandomConsumerGroup())
            .build();

        int sent = internalKafkaClient.sendMessagesTls();
        assertThat(sent, is(MESSAGE_COUNT));

        received = internalKafkaClient.receiveMessagesTls();
        assertThat(received, is(sent));

        // ##############################
        // Validate that continuous clients finished successfully
        // ##############################
        ClientUtils.waitForClientsSuccess(producerName, consumerName, namespaceName, continuousClientsMessageCount);
        // ##############################
    }

    // This test is affected by https://github.com/strimzi/strimzi-kafka-operator/issues/3913 so it needs longer operation timeout set in CO
    @Description("Test for checking that overriding of bootstrap server, triggers the rolling update and verifying that" +
            " new bootstrap DNS is appended inside certificate in subject alternative names property.")
    @ParallelNamespaceTest
    @Tag(ROLLING_UPDATE)
    void testTriggerRollingUpdateAfterOverrideBootstrap(ExtensionContext extensionContext) throws CertificateException {
        final String namespaceName = StUtils.getNamespaceBasedOnRbac(namespace, extensionContext);
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String bootstrapDns = "kafka-test.XXXX.azure.XXXX.net";

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3, 3).build());

        final LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.kafkaStatefulSetName(clusterName));
        final Map<String, String> kafkaPods = PodUtils.podSnapshot(namespaceName, kafkaSelector);

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            LOGGER.info("Adding new bootstrap dns: {} to external listeners", bootstrapDns);
            kafka.getSpec().getKafka()
                .setListeners(asList(
                    new GenericKafkaListenerBuilder()
                        .withName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
                        .withPort(9092)
                        .withType(KafkaListenerType.INTERNAL)
                        .withTls(false)
                        .build(),
                    new GenericKafkaListenerBuilder()
                        .withName(Constants.TLS_LISTENER_DEFAULT_NAME)
                        .withPort(9093)
                        .withType(KafkaListenerType.INTERNAL)
                        .withTls(true)
                        .withNewConfiguration()
                            .withNewBootstrap()
                                .withAlternativeNames(bootstrapDns)
                            .endBootstrap()
                        .endConfiguration()
                        .build()
                ));
        }, namespaceName);

        RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, kafkaSelector, 3, kafkaPods);
        KafkaUtils.waitForKafkaReady(namespaceName, clusterName);

        Map<String, String> secretData = kubeClient().getSecret(namespaceName, KafkaResources.brokersServiceName(clusterName)).getData();

        for (Map.Entry<String, String> item : secretData.entrySet()) {
            if (item.getKey().endsWith(".crt")) {
                LOGGER.info("Encoding {} cert", item.getKey());
                ByteArrayInputStream publicCert = new ByteArrayInputStream(Base64.getDecoder().decode(item.getValue().getBytes()));
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                Certificate certificate = certificateFactory.generateCertificate(publicCert);

                LOGGER.info("Verifying that new DNS is in certificate subject alternative names");
                assertThat(certificate.toString(), containsString(bootstrapDns));
            }
        }
    }

    @ParallelNamespaceTest
    void testManualRollingUpdateForSinglePod(ExtensionContext extensionContext) {
        final String namespaceName = StUtils.getNamespaceBasedOnRbac(namespace, extensionContext);
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());

        final String kafkaSsName = KafkaResources.kafkaStatefulSetName(clusterName);
        final String zkSsName = KafkaResources.zookeeperStatefulSetName(clusterName);

        final LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.kafkaStatefulSetName(clusterName));
        final LabelSelector zkSelector = KafkaResource.getLabelSelector(clusterName, KafkaResources.zookeeperStatefulSetName(clusterName));

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(clusterName, 3).build());

        Pod kafkaPod = kubeClient(namespaceName).getPod(KafkaResources.kafkaPodName(clusterName, 0));
        // snapshot of one single Kafka pod
        Map<String, String> kafkaSnapshot = Collections.singletonMap(kafkaPod.getMetadata().getName(), kafkaPod.getMetadata().getUid());

        Pod zkPod = kubeClient(namespaceName).getPod(KafkaResources.zookeeperPodName(clusterName, 0));
        // snapshot of one single ZK pod
        Map<String, String> zkSnapshot = Collections.singletonMap(zkPod.getMetadata().getName(), zkPod.getMetadata().getUid());

        LOGGER.info("Trying to roll just single Kafka and single ZK pod");
        kubeClient(namespaceName).editPod(KafkaResources.kafkaPodName(clusterName, 0)).edit(pod -> new PodBuilder(pod)
            .editMetadata()
                .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
            .endMetadata()
            .build());

        // here we are waiting just to one pod's snapshot will be changed and all 3 pods ready -> if we set expectedPods to 1,
        // the check will pass immediately without waiting for all pods to be ready -> the method picks first ready pod and return true
        kafkaSnapshot = RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, kafkaSelector, 3, kafkaSnapshot);

        kubeClient(namespaceName).editPod(KafkaResources.zookeeperPodName(clusterName, 0)).edit(pod -> new PodBuilder(pod)
            .editMetadata()
                .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
            .endMetadata()
            .build());

        // same as above
        zkSnapshot = RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, zkSelector, 3, zkSnapshot);


        LOGGER.info("Adding anno to all ZK and Kafka pods");
        kafkaSnapshot.keySet().forEach(podName -> {
            kubeClient(namespaceName).editPod(podName).edit(pod -> new PodBuilder(pod)
                .editMetadata()
                    .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
                .endMetadata()
                .build());
        });

        LOGGER.info("Checking if the rolling update will be successful for Kafka");
        RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, kafkaSelector, 3, kafkaSnapshot);

        zkSnapshot.keySet().forEach(podName -> {
            kubeClient(namespaceName).editPod(podName).edit(pod -> new PodBuilder(pod)
                .editMetadata()
                    .addToAnnotations(Annotations.ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE, "true")
                .endMetadata()
                .build());
        });

        LOGGER.info("Checking if the rolling update will be successful for ZK");
        RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, zkSelector, 3, zkSnapshot);
    }

    /**
     * Adding and removing JBOD volumes requires rolling updates in the sequential order. Otherwise the StatefulSet does
     * not like it. This tests tries to add and remove volume from JBOD to test both of these situations.
     */
    @ParallelNamespaceTest
    void testAddingAndRemovingJbodVolumes(ExtensionContext extensionContext) {
        final String namespaceName = StUtils.getNamespaceBasedOnRbac(namespace, extensionContext);
        final String clusterName = mapWithClusterNames.get(extensionContext.getDisplayName());
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());
        final String continuousTopicName = "continuous-topic";
        // 500 messages will take 500 seconds in that case
        final int continuousClientsMessageCount = 500;
        final String producerName = "hello-world-producer";
        final String consumerName = "hello-world-consumer";

        PersistentClaimStorage vol0 = new PersistentClaimStorageBuilder().withId(0).withSize("1Gi").withDeleteClaim(true).build();
        PersistentClaimStorage vol1 = new PersistentClaimStorageBuilder().withId(1).withSize("1Gi").withDeleteClaim(true).build();

        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaJBOD(clusterName, 3, 3, new JbodStorageBuilder().addToVolumes(vol0).build()).build());

        final String kafkaName = KafkaResources.kafkaStatefulSetName(clusterName);
        final LabelSelector kafkaSelector = KafkaResource.getLabelSelector(clusterName, kafkaName);

        Map<String, String> kafkaPods = PodUtils.podSnapshot(namespaceName, kafkaSelector);

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, topicName).build());
        // ##############################
        // Attach clients which will continuously produce/consume messages to/from Kafka brokers during rolling update
        // ##############################
        // Setup topic, which has 3 replicas and 2 min.isr to see if producer will be able to work during rolling update
        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(clusterName, continuousTopicName, 3, 3, 2).build());
        String producerAdditionConfiguration = "delivery.timeout.ms=20000\nrequest.timeout.ms=20000";
        // Add transactional id to make producer transactional
        producerAdditionConfiguration = producerAdditionConfiguration.concat("\ntransactional.id=" + continuousTopicName + ".1");
        producerAdditionConfiguration = producerAdditionConfiguration.concat("\nenable.idempotence=true");

        KafkaClients kafkaBasicClientJob = new KafkaClientsBuilder()
            .withProducerName(producerName)
            .withConsumerName(consumerName)
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(clusterName))
            .withTopicName(continuousTopicName)
            .withMessageCount(continuousClientsMessageCount)
            .withAdditionalConfig(producerAdditionConfiguration)
            .withDelayMs(1000)
            .withNamespaceName(namespaceName)
            .build();

        resourceManager.createResource(extensionContext, kafkaBasicClientJob.producerStrimzi());
        resourceManager.createResource(extensionContext, kafkaBasicClientJob.consumerStrimzi());
        // ##############################

        String userName = KafkaUserUtils.generateRandomNameOfKafkaUser();
        KafkaUser user = KafkaUserTemplates.tlsUser(clusterName, userName).build();

        resourceManager.createResource(extensionContext, user);
        resourceManager.createResource(extensionContext, false, KafkaClientsTemplates.kafkaClients(true, kafkaClientsName, user).build());

        final String kafkaClientsPodName = PodUtils.getPodsByPrefixInNameWithDynamicWait(namespaceName, kafkaClientsName).get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(topicName)
            .withNamespaceName(namespaceName)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withKafkaUsername(userName)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.produceTlsMessagesUntilOperationIsSuccessful(MESSAGE_COUNT);

        // Add Jbod volume to Kafka => triggers RU
        LOGGER.info("Add JBOD volume to the Kafka cluster {}", kafkaName);

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            JbodStorage storage = (JbodStorage) kafka.getSpec().getKafka().getStorage();
            storage.getVolumes().add(vol1);
        }, namespaceName);

        // Wait util it rolls
        kafkaPods = RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, kafkaSelector, 3, kafkaPods);

        // Remove Jbod volume to Kafka => triggers RU
        LOGGER.info("Remove JBOD volume to the Kafka cluster {}", kafkaName);

        KafkaResource.replaceKafkaResourceInSpecificNamespace(clusterName, kafka -> {
            JbodStorage storage = (JbodStorage) kafka.getSpec().getKafka().getStorage();
            storage.getVolumes().remove(vol1);
        }, namespaceName);

        // Wait util it rolls
        RollingUpdateUtils.waitTillComponentHasRolled(namespaceName, kafkaSelector, 3, kafkaPods);

        // ##############################
        // Validate that continuous clients finished successfully
        // ##############################
        ClientUtils.waitForClientsSuccess(producerName, consumerName, namespaceName, continuousClientsMessageCount);
        // ##############################
    }
}

