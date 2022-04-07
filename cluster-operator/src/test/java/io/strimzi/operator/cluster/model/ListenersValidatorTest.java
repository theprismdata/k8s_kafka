/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationOAuthBuilder;
import io.strimzi.api.kafka.model.listener.NodeAddressType;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBootstrapBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBroker;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBrokerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfigurationBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.template.ExternalTrafficPolicy;
import io.strimzi.api.kafka.model.template.IpFamily;
import io.strimzi.api.kafka.model.template.IpFamilyPolicy;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.test.annotations.ParallelSuite;
import io.strimzi.test.annotations.ParallelTest;

import java.util.Collections;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ParallelSuite
public class ListenersValidatorTest {
    @ParallelTest
    public void testValidateListeners() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener2 = new GenericKafkaListenerBuilder()
                .withName("listener2")
                .withPort(9901)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1, listener2);
        ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners);
    }

    @ParallelTest
    public void testValidateThrowsException() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener2 = new GenericKafkaListenerBuilder()
                .withName("listener2")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1, listener2);

        Exception exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners));
        assertThat(exception.getMessage(), containsString("every listener needs to have a unique port number"));
    }

    @ParallelTest
    public void testValidateDuplicatePorts() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener2 = new GenericKafkaListenerBuilder()
                .withName("listener2")
                .withPort(9901)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener3 = new GenericKafkaListenerBuilder()
                .withName("listener3")
                .withPort(9901)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1, listener2, listener3);
        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder("every listener needs to have a unique port number"));
    }

    @ParallelTest
    public void testValidateForbiddenPortByRange() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9000)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);
        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder("port 9000 is forbidden and cannot be used"));
    }

    @ParallelTest
    public void testValidateForbiddenPortByException() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9404)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);
        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder("port 9404 is forbidden and cannot be used"));
    }

    @ParallelTest
    public void testValidateDuplicateNames() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener2 = new GenericKafkaListenerBuilder()
                .withName("listener2")
                .withPort(9901)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener3 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9902)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1, listener2, listener3);
        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder("every listener needs to have a unique name"));
    }

    @ParallelTest
    public void testInvalidNames() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener 1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener2 = new GenericKafkaListenerBuilder()
                .withName("LISTENER2")
                .withPort(9901)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener listener3 = new GenericKafkaListenerBuilder()
                .withName("listener12345678901234567890")
                .withPort(9902)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1, listener2, listener3);
        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder("listener names [listener 1, LISTENER2, listener12345678901234567890] are invalid and do not match the pattern ^[a-z0-9]{1,11}$"));
    }

    @ParallelTest
    public void testTlsAuthOnNonTlsListener() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("plain")
                .withPort(9092)
                .withType(KafkaListenerType.INTERNAL)
                .withTls(false)
                .withNewKafkaListenerAuthenticationTlsAuth()
                .endKafkaListenerAuthenticationTlsAuth()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);
        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder("listener plain cannot use tls type authentication with disabled TLS encryption"));
    }

    @ParallelTest
    public void testTlsCustomCertOnNonTlsListener() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("plain")
                .withPort(9092)
                .withType(KafkaListenerType.INTERNAL)
                .withTls(false)
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withCertificate("cert")
                        .withKey("key")
                        .withSecretName("secretName")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);
        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder("listener plain cannot configure custom TLS certificate with disabled TLS encryption"));
    }

    @ParallelTest
    public void testInternalListener() {
        String name = "plain";

        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName(name)
                .withPort(9092)
                .withType(KafkaListenerType.INTERNAL)
                .withNewConfiguration()
                    .withIngressClass("my-ingress")
                    .withUseServiceDnsDomain(true)
                    .withExternalTrafficPolicy(ExternalTrafficPolicy.LOCAL)
                    .withIpFamilyPolicy(IpFamilyPolicy.REQUIRE_DUAL_STACK)
                    .withIpFamilies(IpFamily.IPV4, IpFamily.IPV6)
                    .withPreferredNodePortAddressType(NodeAddressType.INTERNAL_DNS)
                    .withLoadBalancerSourceRanges(asList("10.0.0.0/8", "130.211.204.1/32"))
                    .withFinalizers(asList("service.kubernetes.io/load-balancer-cleanup"))
                    .withNewBootstrap()
                        .withAlternativeNames(asList("my-name-1", "my-name-2"))
                        .withLoadBalancerIP("130.211.204.1")
                        .withNodePort(32189)
                        .withHost("my-host")
                        .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                        .withLabels(Collections.singletonMap("label", "label-value"))
                    .endBootstrap()
                    .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(0)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .withLabels(Collections.singletonMap("label", "label-value"))
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(1)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .withLabels(Collections.singletonMap("label", "label-value"))
                                    .build())
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        List<String> expectedErrors = asList(
                "listener " + name + " cannot configure ingressClass because it is not Ingress based listener",
                "listener " + name + " cannot configure externalTrafficPolicy because it is not LoadBalancer or NodePort based listener",
                "listener " + name + " cannot configure loadBalancerSourceRanges because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure finalizers because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure preferredAddressType because it is not NodePort based listener",
                "listener " + name + " cannot configure bootstrap.host because it is not Route ot Ingress based listener",
                "listener " + name + " cannot configure bootstrap.loadBalancerIP because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure bootstrap.nodePort because it is not NodePort based listener",
                "listener " + name + " cannot configure bootstrap.annotations because it is not LoadBalancer, NodePort, Route, or Ingress based listener",
                "listener " + name + " cannot configure bootstrap.labels because it is not LoadBalancer, NodePort, Route, or Ingress based listener",
                "listener " + name + " cannot configure brokers[].host because it is not Route ot Ingress based listener",
                "listener " + name + " cannot configure brokers[].loadBalancerIP because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure brokers[].nodePort because it is not NodePort based listener",
                "listener " + name + " cannot configure brokers[].annotations because it is not LoadBalancer, NodePort, Route, or Ingress based listener",
                "listener " + name + " cannot configure brokers[].labels because it is not LoadBalancer, NodePort, Route, or Ingress based listener",
                "listener " + name + " cannot configure ipFamilyPolicy because it is not internal listener",
                "listener " + name + " cannot configure ipFamilies because it is not internal listener"
        );

        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder(expectedErrors.toArray()));
    }

    @ParallelTest
    public void testLoadBalancerListener() {
        String name = "lb";

        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName(name)
                .withPort(9092)
                .withType(KafkaListenerType.LOADBALANCER)
                .withNewConfiguration()
                    .withIngressClass("my-ingress")
                    .withUseServiceDnsDomain(true)
                    .withExternalTrafficPolicy(ExternalTrafficPolicy.LOCAL)
                    .withIpFamilyPolicy(IpFamilyPolicy.REQUIRE_DUAL_STACK)
                    .withIpFamilies(IpFamily.IPV4, IpFamily.IPV6)
                    .withPreferredNodePortAddressType(NodeAddressType.INTERNAL_DNS)
                    .withLoadBalancerSourceRanges(asList("10.0.0.0/8", "130.211.204.1/32"))
                    .withFinalizers(asList("service.kubernetes.io/load-balancer-cleanup"))
                    .withNewBootstrap()
                        .withAlternativeNames(asList("my-name-1", "my-name-2"))
                        .withLoadBalancerIP("130.211.204.1")
                        .withNodePort(32189)
                        .withHost("my-host")
                        .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                    .endBootstrap()
                    .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(0)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(1)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .build())
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        List<String> expectedErrors = asList(
                "listener " + name + " cannot configure ingressClass because it is not Ingress based listener",
                "listener " + name + " cannot configure useServiceDnsDomain because it is not internal listener",
                "listener " + name + " cannot configure preferredAddressType because it is not NodePort based listener",
                "listener " + name + " cannot configure bootstrap.host because it is not Route ot Ingress based listener",
                "listener " + name + " cannot configure bootstrap.nodePort because it is not NodePort based listener",
                "listener " + name + " cannot configure brokers[].host because it is not Route ot Ingress based listener",
                "listener " + name + " cannot configure brokers[].nodePort because it is not NodePort based listener"
        );

        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder(expectedErrors.toArray()));
    }

    @ParallelTest
    public void testNodePortListener() {
        String name = "nodeport";

        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName(name)
                .withPort(9092)
                .withType(KafkaListenerType.NODEPORT)
                .withNewConfiguration()
                    .withIngressClass("my-ingress")
                    .withUseServiceDnsDomain(true)
                    .withExternalTrafficPolicy(ExternalTrafficPolicy.LOCAL)
                    .withIpFamilyPolicy(IpFamilyPolicy.REQUIRE_DUAL_STACK)
                    .withIpFamilies(IpFamily.IPV4, IpFamily.IPV6)
                    .withPreferredNodePortAddressType(NodeAddressType.INTERNAL_DNS)
                    .withLoadBalancerSourceRanges(asList("10.0.0.0/8", "130.211.204.1/32"))
                    .withFinalizers(asList("service.kubernetes.io/load-balancer-cleanup"))
                    .withNewBootstrap()
                        .withAlternativeNames(asList("my-name-1", "my-name-2"))
                        .withLoadBalancerIP("130.211.204.1")
                        .withNodePort(32189)
                        .withHost("my-host")
                        .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                    .endBootstrap()
                    .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(0)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(1)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .build())
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        List<String> expectedErrors = asList(
                "listener " + name + " cannot configure ingressClass because it is not Ingress based listener",
                "listener " + name + " cannot configure useServiceDnsDomain because it is not internal listener",
                "listener " + name + " cannot configure loadBalancerSourceRanges because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure finalizers because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure bootstrap.host because it is not Route ot Ingress based listener",
                "listener " + name + " cannot configure bootstrap.loadBalancerIP because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure brokers[].host because it is not Route ot Ingress based listener",
                "listener " + name + " cannot configure brokers[].loadBalancerIP because it is not LoadBalancer based listener"
        );

        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder(expectedErrors.toArray()));
    }

    @ParallelTest
    public void testRouteListenerWithoutTls() {
        String name = "route";

        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName(name)
                .withPort(9092)
                .withType(KafkaListenerType.ROUTE)
                .withTls(false)
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        List<String> expectedErrors = asList(
                "listener " + name + " is Route or Ingress type listener and requires enabled TLS encryption"
        );

        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder(expectedErrors.toArray()));
    }

    @ParallelTest
    public void testRouteListener() {
        String name = "route";

        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName(name)
                .withPort(9092)
                .withType(KafkaListenerType.ROUTE)
                .withTls(true)
                .withNewConfiguration()
                    .withIngressClass("my-ingress")
                    .withUseServiceDnsDomain(true)
                    .withExternalTrafficPolicy(ExternalTrafficPolicy.LOCAL)
                    .withIpFamilyPolicy(IpFamilyPolicy.REQUIRE_DUAL_STACK)
                    .withIpFamilies(IpFamily.IPV4, IpFamily.IPV6)
                    .withPreferredNodePortAddressType(NodeAddressType.INTERNAL_DNS)
                    .withLoadBalancerSourceRanges(asList("10.0.0.0/8", "130.211.204.1/32"))
                    .withFinalizers(asList("service.kubernetes.io/load-balancer-cleanup"))
                    .withNewBootstrap()
                        .withAlternativeNames(asList("my-name-1", "my-name-2"))
                        .withLoadBalancerIP("130.211.204.1")
                        .withNodePort(32189)
                        .withHost("my-host")
                        .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                        .withLabels(Collections.singletonMap("label", "label-value"))
                    .endBootstrap()
                    .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(0)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .withLabels(Collections.singletonMap("label", "label-value"))
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(1)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .withLabels(Collections.singletonMap("label", "label-value"))
                                    .build())
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        List<String> expectedErrors = asList(
                "listener " + name + " cannot configure ingressClass because it is not Ingress based listener",
                "listener " + name + " cannot configure useServiceDnsDomain because it is not internal listener",
                "listener " + name + " cannot configure externalTrafficPolicy because it is not LoadBalancer or NodePort based listener",
                "listener " + name + " cannot configure loadBalancerSourceRanges because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure finalizers because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure preferredAddressType because it is not NodePort based listener",
                "listener " + name + " cannot configure bootstrap.loadBalancerIP because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure bootstrap.nodePort because it is not NodePort based listener",
                "listener " + name + " cannot configure brokers[].loadBalancerIP because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure brokers[].nodePort because it is not NodePort based listener"
        );

        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), containsInAnyOrder(expectedErrors.toArray()));
    }

    @ParallelTest
    public void testIngressListenerWithoutTls() {
        String name = "ingress";

        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName(name)
                .withPort(9092)
                .withType(KafkaListenerType.INGRESS)
                .withTls(false)
                .withNewConfiguration()
                    .withNewBootstrap()
                        .withHost("my-host")
                    .endBootstrap()
                    .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(0)
                                    .withHost("my-host")
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(1)
                                    .withHost("my-host")
                                    .build())
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        List<String> expectedErrors = asList(
                "listener " + name + " is Route or Ingress type listener and requires enabled TLS encryption"
        );

        assertThat(ListenersValidator.validateAndGetErrorMessages(2, listeners), containsInAnyOrder(expectedErrors.toArray()));
    }

    @ParallelTest
    public void testIngressListener() {
        String name = "ingress";

        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName(name)
                .withPort(9092)
                .withType(KafkaListenerType.INGRESS)
                .withTls(true)
                .withNewConfiguration()
                    .withIngressClass("my-ingress")
                    .withUseServiceDnsDomain(true)
                    .withExternalTrafficPolicy(ExternalTrafficPolicy.LOCAL)
                    .withIpFamilyPolicy(IpFamilyPolicy.REQUIRE_DUAL_STACK)
                    .withIpFamilies(IpFamily.IPV4, IpFamily.IPV6)
                    .withPreferredNodePortAddressType(NodeAddressType.INTERNAL_DNS)
                    .withLoadBalancerSourceRanges(asList("10.0.0.0/8", "130.211.204.1/32"))
                    .withFinalizers(asList("service.kubernetes.io/load-balancer-cleanup"))
                    .withNewBootstrap()
                        .withAlternativeNames(asList("my-name-1", "my-name-2"))
                        .withLoadBalancerIP("130.211.204.1")
                        .withNodePort(32189)
                        .withHost("my-host")
                        .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                    .endBootstrap()
                    .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(0)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(1)
                                    .withAdvertisedHost("advertised-host")
                                    .withAdvertisedPort(9092)
                                    .withLoadBalancerIP("130.211.204.1")
                                    .withNodePort(32189)
                                    .withHost("my-host")
                                    .withAnnotations(Collections.singletonMap("dns-anno", "dns-value"))
                                    .build())
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        List<String> expectedErrors = asList(
                "listener " + name + " cannot configure useServiceDnsDomain because it is not internal listener",
                "listener " + name + " cannot configure externalTrafficPolicy because it is not LoadBalancer or NodePort based listener",
                "listener " + name + " cannot configure loadBalancerSourceRanges because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure finalizers because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure preferredAddressType because it is not NodePort based listener",
                "listener " + name + " cannot configure bootstrap.loadBalancerIP because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure bootstrap.nodePort because it is not NodePort based listener",
                "listener " + name + " cannot configure brokers[].loadBalancerIP because it is not LoadBalancer based listener",
                "listener " + name + " cannot configure brokers[].nodePort because it is not NodePort based listener"
        );

        assertThat(ListenersValidator.validateAndGetErrorMessages(2, listeners), containsInAnyOrder(expectedErrors.toArray()));
    }

    @ParallelTest
    public void testIngressListenerHostNames() {
        GenericKafkaListener listener = new GenericKafkaListenerBuilder()
                .withName("ingress")
                .withPort(9092)
                .withType(KafkaListenerType.INGRESS)
                .withTls(true)
                .build();

        assertThat(ListenersValidator.validateAndGetErrorMessages(2, asList(listener)), containsInAnyOrder("listener ingress is missing a configuration with host names which is required for Ingress based listeners"));

        listener.setConfiguration(new GenericKafkaListenerConfigurationBuilder()
                .withBrokers((List<GenericKafkaListenerConfigurationBroker>) null)
                .build());

        assertThat(ListenersValidator.validateAndGetErrorMessages(2, asList(listener)), containsInAnyOrder("listener ingress is missing a bootstrap host name which is required for Ingress based listeners",
                "listener ingress is missing a broker configuration with host names which is required for Ingress based listeners"));

        listener.setConfiguration(new GenericKafkaListenerConfigurationBuilder()
                .withBootstrap(new GenericKafkaListenerConfigurationBootstrapBuilder()
                                .build())
                .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                .withBroker(0)
                                .build(),
                        new GenericKafkaListenerConfigurationBrokerBuilder()
                                .withBroker(1)
                                .build())
                .build());

        assertThat(ListenersValidator.validateAndGetErrorMessages(2, asList(listener)), containsInAnyOrder("listener ingress is missing a bootstrap host name which is required for Ingress based listeners",
                "listener ingress is missing a broker host name for broker with ID 0 which is required for Ingress based listeners",
                "listener ingress is missing a broker host name for broker with ID 1 which is required for Ingress based listeners"));

        listener.setConfiguration(new GenericKafkaListenerConfigurationBuilder()
                .withBootstrap(new GenericKafkaListenerConfigurationBootstrapBuilder()
                        .withHost("bootstrap-host")
                        .build())
                .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                .withBroker(1)
                                .withHost("host-1")
                                .build())
                .build());

        assertThat(ListenersValidator.validateAndGetErrorMessages(2, asList(listener)), containsInAnyOrder("listener ingress is missing a broker host name for broker with ID 0 which is required for Ingress based listeners"));

        listener.setConfiguration(new GenericKafkaListenerConfigurationBuilder()
                .withBootstrap(new GenericKafkaListenerConfigurationBootstrapBuilder()
                        .withHost("bootstrap-host")
                        .build())
                .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                .withBroker(0)
                                .withHost("host-0")
                                .build(),
                        new GenericKafkaListenerConfigurationBrokerBuilder()
                                .withBroker(1)
                                .withHost("host-1")
                                .build())
                .build());

        assertThat(ListenersValidator.validateAndGetErrorMessages(2, asList(listener)), hasSize(0));
    }

    @ParallelTest
    public void testValidateOauth() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .withAuth(new KafkaListenerAuthenticationOAuthBuilder().build())
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        Exception exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners));
        assertThat(exception.getMessage(), allOf(
                containsString("listener listener1: Introspection endpoint URI or JWKS endpoint URI has to be specified"),
                containsString("listener listener1: Valid Issuer URI has to be specified or 'checkIssuer' set to 'false'")));
    }

    @ParallelTest
    public void testValidateBrokerCertChainAndKey() {
        GenericKafkaListener listener1 = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .withNewConfiguration()
                    .withNewBrokerCertChainAndKey()
                        .withCertificate("")
                        .withKey("")
                    .endBrokerCertChainAndKey()
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(listener1);

        Exception exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners));
        assertThat(exception.getMessage(), allOf(
                containsString("listener 'listener1' cannot have an empty secret name in the brokerCertChainAndKey"),
                containsString("listener 'listener1' cannot have an empty key in the brokerCertChainAndKey"),
                containsString("listener 'listener1' cannot have an empty certificate in the brokerCertChainAndKey")));
    }

    @ParallelTest
    public void testMinimalConfiguration() {
        GenericKafkaListener internal = new GenericKafkaListenerBuilder()
                .withName("internal")
                .withPort(9092)
                .withType(KafkaListenerType.INTERNAL)
                .build();

        GenericKafkaListener route = new GenericKafkaListenerBuilder()
                .withName("route")
                .withPort(9093)
                .withType(KafkaListenerType.ROUTE)
                .withTls(true)
                .build();

        GenericKafkaListener lb = new GenericKafkaListenerBuilder()
                .withName("lb")
                .withPort(9094)
                .withType(KafkaListenerType.LOADBALANCER)
                .build();

        GenericKafkaListener np = new GenericKafkaListenerBuilder()
                .withName("np")
                .withPort(9095)
                .withType(KafkaListenerType.NODEPORT)
                .build();

        GenericKafkaListener inq = new GenericKafkaListenerBuilder()
                .withName("inq")
                .withPort(9096)
                .withType(KafkaListenerType.INGRESS)
                .withTls(true)
                .withNewConfiguration()
                    .withNewBootstrap()
                        .withHost("my-host")
                    .endBootstrap()
                    .withBrokers(new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(0)
                                    .withHost("my-host")
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(1)
                                    .withHost("my-host")
                                    .build(),
                            new GenericKafkaListenerConfigurationBrokerBuilder()
                                    .withBroker(2)
                                    .withHost("my-host")
                                    .build())
                .endConfiguration()
                .build();

        List<GenericKafkaListener> listeners = asList(internal, route, lb, np, inq);

        assertThat(ListenersValidator.validateAndGetErrorMessages(3, listeners), empty());
    }

    @ParallelTest
    public void testValidateOauthPlain() {
        KafkaListenerAuthenticationOAuthBuilder authBuilder = new KafkaListenerAuthenticationOAuthBuilder()
                .withEnableOauthBearer(false);

        GenericKafkaListenerBuilder listenerBuilder = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .withAuth(authBuilder.build());

        GenericKafkaListener listener = listenerBuilder.withAuth(authBuilder.build())
                .build();

        List<GenericKafkaListener> listeners = asList(listener);

        Exception exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners));
        assertThat(exception.getMessage(), allOf(
                containsString("listener listener1: At least one of 'enablePlain', 'enableOauthBearer' has to be set to 'true'")));

        // enable plain with neither introspectionEndpointUri nor jwksEndpointUri set
        authBuilder.withEnablePlain(true);
        listener = listenerBuilder.withAuth(authBuilder.build())
                .build();
        List<GenericKafkaListener> listeners2 = asList(listener);

        exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners2));
        assertThat(exception.getMessage(), allOf(
                containsString("listener listener1: Introspection endpoint URI or JWKS endpoint URI has to be specified")));

        // enable plain with jwksEndpointUri set but tokenEndpointUri not set
        authBuilder.withJwksEndpointUri("http://localhost:8080/jwks").withCheckIssuer(false);
        listener = listenerBuilder.withAuth(authBuilder.build())
                .build();
        List<GenericKafkaListener> listeners3 = asList(listener);

        assertDoesNotThrow(() -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners3));
    }

    @ParallelTest
    public void testValidateAudienceOauth() {
        KafkaListenerAuthenticationOAuthBuilder authBuilder = new KafkaListenerAuthenticationOAuthBuilder()
                .withCheckAudience(true);

        GenericKafkaListenerBuilder listenerBuilder = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .withAuth(authBuilder.build());

        GenericKafkaListener listener = listenerBuilder.withAuth(authBuilder.build())
                .build();

        List<GenericKafkaListener> listeners = asList(listener);

        Exception exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners));
        assertThat(exception.getMessage(), allOf(
                containsString("listener listener1: 'clientId' has to be configured when 'checkAudience' is 'true'")));

        // set clientId
        authBuilder.withClientId("kafka");

        listener = listenerBuilder.withAuth(authBuilder.build())
                .build();
        List<GenericKafkaListener> listeners2 = asList(listener);

        exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners2));
        assertThat(exception.getMessage(), allOf(
                not(containsString("listener listener1: 'clientId' has to be configured when 'checkAudience' is 'true'"))));
    }

    @ParallelTest
    public void testValidateCustomClaimCheckOauth() {
        KafkaListenerAuthenticationOAuthBuilder authBuilder = new KafkaListenerAuthenticationOAuthBuilder()
                .withCustomClaimCheck("invalid");

        GenericKafkaListenerBuilder listenerBuilder = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .withAuth(authBuilder.build());

        GenericKafkaListener listener = listenerBuilder.withAuth(authBuilder.build())
                .build();

        List<GenericKafkaListener> listeners = asList(listener);

        Exception exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners));
        assertThat(exception.getMessage(), allOf(
                containsString("listener listener1: 'customClaimCheck' value not a valid JsonPath filter query - Failed to parse filter query: \"invalid\"")));

        // set valid JsonPath query
        authBuilder.withCustomClaimCheck("@.valid == 'value'");

        listener = listenerBuilder.withAuth(authBuilder.build())
                .build();
        List<GenericKafkaListener> listeners2 = asList(listener);

        exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners2));
        assertThat(exception.getMessage(), allOf(
                not(containsString("listener listener1: 'customClaimCheck' value not a valid JsonPath filter query - Failed to parse query: \"invalid\" at position: 0"))));
    }

    @ParallelTest
    public void testValidateTimeoutsOauth() {
        KafkaListenerAuthenticationOAuthBuilder authBuilder = new KafkaListenerAuthenticationOAuthBuilder()
                .withConnectTimeoutSeconds(0);

        GenericKafkaListenerBuilder listenerBuilder = new GenericKafkaListenerBuilder()
                .withName("listener1")
                .withPort(9900)
                .withType(KafkaListenerType.INTERNAL)
                .withAuth(authBuilder.build());


        List<GenericKafkaListener> listeners = asList(listenerBuilder.withAuth(authBuilder.build()).build());

        Exception exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners));
        assertThat(exception.getMessage(), allOf(
                containsString("listener listener1: 'connectTimeoutSeconds' needs to be a positive integer (set to: 0)")));

        // set valid connectTimeoutSeconds
        authBuilder.withConnectTimeoutSeconds(1);

        List<GenericKafkaListener> listeners2 = asList(listenerBuilder.withAuth(authBuilder.build()).build());

        exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners2));
        assertThat(exception.getMessage(), allOf(
                not(containsString("listener listener1: 'connectTimeoutSeconds' needs to be a positive integer"))));

        //
        // Repeat for readTimeoutSeconds
        //
        authBuilder.withConnectTimeoutSeconds(null)
                .withReadTimeoutSeconds(0);

        List<GenericKafkaListener> listeners3 = asList(listenerBuilder.withAuth(authBuilder.build()).build());

        exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners3));
        assertThat(exception.getMessage(), allOf(
                containsString("listener listener1: 'readTimeoutSeconds' needs to be a positive integer (set to: 0)")));

        // set valid readTimeoutSeconds
        authBuilder.withReadTimeoutSeconds(1);

        List<GenericKafkaListener> listeners4 = asList(listenerBuilder.withAuth(authBuilder.build()).build());

        exception = assertThrows(InvalidResourceException.class, () -> ListenersValidator.validate(Reconciliation.DUMMY_RECONCILIATION, 3, listeners4));
        assertThat(exception.getMessage(), allOf(
                not(containsString("listener listener1: 'readTimeoutSeconds' needs to be a positive integer"))));

    }
}
