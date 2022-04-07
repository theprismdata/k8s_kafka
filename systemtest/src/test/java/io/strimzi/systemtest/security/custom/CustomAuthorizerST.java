/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.security.custom;


import io.strimzi.api.kafka.model.AclOperation;
import io.strimzi.api.kafka.model.KafkaAuthorizationSimple;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.annotations.ParallelSuite;
import io.strimzi.systemtest.annotations.ParallelTest;
import io.strimzi.systemtest.kafkaclients.clients.InternalKafkaClient;
import io.strimzi.systemtest.templates.crd.KafkaClientsTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.templates.crd.KafkaUserTemplates;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@Tag(REGRESSION)
@ParallelSuite
public class CustomAuthorizerST extends AbstractST {
    static final String CLUSTER_NAME = "custom-authorizer";
    static final String ADMIN = "sre-admin";
    private static final Logger LOGGER = LogManager.getLogger(CustomAuthorizerST.class);

    private final String namespace = testSuiteNamespaceManager.getMapOfAdditionalNamespaces().get(CustomAuthorizerST.class.getSimpleName()).stream().findFirst().get();

    @ParallelTest
    @Tag(INTERNAL_CLIENTS_USED)
    void testAclRuleReadAndWrite(ExtensionContext extensionContext) {
        final String kafkaUserWrite = "kafka-user-write";
        final String kafkaUserRead = "kafka-user-read";
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final int numberOfMessages = 500;
        final String consumerGroupName = "consumer-group-name-1";
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(CLUSTER_NAME, topicName, namespace).build());

        KafkaUser writeUser = KafkaUserTemplates.tlsUser(namespace, CLUSTER_NAME, kafkaUserWrite)
            .editSpec()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                        .endAclRuleTopicResource()
                        .withOperation(AclOperation.WRITE)
                    .endAcl()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                        .endAclRuleTopicResource()
                        .withOperation(AclOperation.DESCRIBE)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
            .endSpec()
            .build();

        KafkaUser readUser = KafkaUserTemplates.tlsUser(namespace, CLUSTER_NAME, kafkaUserRead)
            .editSpec()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                        .endAclRuleTopicResource()
                        .withOperation(AclOperation.READ)
                    .endAcl()
                    .addNewAcl()
                        .withNewAclRuleGroupResource()
                            .withName(consumerGroupName)
                        .endAclRuleGroupResource()
                        .withOperation(AclOperation.READ)
                    .endAcl()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                        .endAclRuleTopicResource()
                        .withOperation(AclOperation.DESCRIBE)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
            .endSpec()
            .build();

        resourceManager.createResource(extensionContext, writeUser);
        resourceManager.createResource(extensionContext, readUser);

        LOGGER.info("Checking KafkaUser {} that is able to send messages to topic '{}'", kafkaUserWrite, topicName);

        resourceManager.createResource(extensionContext, KafkaClientsTemplates.kafkaClients(namespace, true, kafkaClientsName, writeUser, readUser).build());

        String kafkaClientsPodName = kubeClient(namespace).listPodsByPrefixInName(namespace, kafkaClientsName).get(0).getMetadata().getName();

        InternalKafkaClient writeKafkaClient = new InternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(namespace)
            .withClusterName(CLUSTER_NAME)
            .withKafkaUsername(kafkaUserWrite)
            .withMessageCount(numberOfMessages)
            .withUsingPodName(kafkaClientsPodName)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        assertThat(writeKafkaClient.sendMessagesTls(), is(numberOfMessages));
        assertThat(writeKafkaClient.receiveMessagesTls(), is(0));

        InternalKafkaClient readKafkaClient = new InternalKafkaClient.Builder()
                .withTopicName(topicName)
                .withNamespaceName(namespace)
                .withClusterName(CLUSTER_NAME)
                .withKafkaUsername(kafkaUserRead)
                .withMessageCount(numberOfMessages)
                .withUsingPodName(kafkaClientsPodName)
                .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
                .withConsumerGroupName(consumerGroupName)
                .build();

        assertThat(readKafkaClient.receiveMessagesTls(), is(numberOfMessages));

        LOGGER.info("Checking KafkaUser {} that is not able to send messages to topic '{}'", kafkaUserRead, topicName);
        assertThat(readKafkaClient.sendMessagesTls(), is(-1));
    }

    @ParallelTest
    @Tag(INTERNAL_CLIENTS_USED)
    void testAclWithSuperUser(ExtensionContext extensionContext) {
        final String topicName = mapWithTestTopics.get(extensionContext.getDisplayName());
        final String kafkaClientsName = mapWithKafkaClientNames.get(extensionContext.getDisplayName());

        resourceManager.createResource(extensionContext, KafkaTopicTemplates.topic(CLUSTER_NAME, topicName, namespace).build());

        KafkaUser adminUser = KafkaUserTemplates.tlsUser(namespace, CLUSTER_NAME, ADMIN)
            .editSpec()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                        .endAclRuleTopicResource()
                        .withOperation(AclOperation.WRITE)
                    .endAcl()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                        .endAclRuleTopicResource()
                        .withOperation(AclOperation.DESCRIBE)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
            .endSpec()
            .build();

        resourceManager.createResource(extensionContext, adminUser);
        resourceManager.createResource(extensionContext, KafkaClientsTemplates.kafkaClients(namespace, true, kafkaClientsName, adminUser).build());

        String kafkaClientsPodName = kubeClient(namespace).listPodsByPrefixInName(namespace, kafkaClientsName).get(0).getMetadata().getName();

        LOGGER.info("Checking kafka super user:{} that is able to send messages to topic:{}", ADMIN, topicName);

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withTopicName(topicName)
            .withNamespaceName(namespace)
            .withClusterName(CLUSTER_NAME)
            .withKafkaUsername(ADMIN)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .withUsingPodName(kafkaClientsPodName)
            .build();

        assertThat(internalKafkaClient.sendMessagesTls(), is(MESSAGE_COUNT));

        LOGGER.info("Checking kafka super user:{} that is able to read messages to topic:{} regardless that " +
                "we configured Acls with only write operation", ADMIN, TOPIC_NAME);

        assertThat(internalKafkaClient.receiveMessagesTls(), is(MESSAGE_COUNT));
    }

    @BeforeAll
    public void setup(ExtensionContext extensionContext) {
        resourceManager.createResource(extensionContext, KafkaTemplates.kafkaPersistent(CLUSTER_NAME, 1, 1)
            .editMetadata()
                .withNamespace(namespace)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withNewKafkaAuthorizationCustom()
                        .withAuthorizerClass(KafkaAuthorizationSimple.AUTHORIZER_CLASS_NAME)
                        .withSupportsAdminApi(true)
                        .withSuperUsers("CN=" + ADMIN)
                    .endKafkaAuthorizationCustom()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(Constants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationTls())
                            .build())
                .endKafka()
            .endSpec()
            .build());
    }
}
