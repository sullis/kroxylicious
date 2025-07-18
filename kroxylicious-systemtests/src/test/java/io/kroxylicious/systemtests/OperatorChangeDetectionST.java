/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil;

import io.kroxylicious.kubernetes.api.common.Condition;
import io.kroxylicious.kubernetes.api.common.FilterRef;
import io.kroxylicious.kubernetes.api.common.FilterRefBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolFilter;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProtocolFilterBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyIngress;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxyIngressBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaCluster;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaClusterBuilder;
import io.kroxylicious.kubernetes.api.v1alpha1.kafkaproxyingressspec.ClusterIP;
import io.kroxylicious.kubernetes.operator.assertj.OperatorAssertions;
import io.kroxylicious.systemtests.installation.kroxylicious.CertManager;
import io.kroxylicious.systemtests.installation.kroxylicious.Kroxylicious;
import io.kroxylicious.systemtests.installation.kroxylicious.KroxyliciousOperator;
import io.kroxylicious.systemtests.k8s.KubeClient;
import io.kroxylicious.systemtests.resources.manager.ResourceManager;
import io.kroxylicious.systemtests.templates.kroxylicious.KroxyliciousFilterTemplates;

import static io.kroxylicious.systemtests.TestTags.OPERATOR;
import static io.kroxylicious.systemtests.k8s.KubeClusterResource.kubeClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Kroxylicious system tests.
 */
@Tag(OPERATOR)
class OperatorChangeDetectionST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperatorChangeDetectionST.class);
    private static Kroxylicious kroxylicious;
    private static CertManager certManager;
    private final String kafkaClusterName = "my-cluster";
    private KroxyliciousOperator kroxyliciousOperator;

    @Test
    void shouldUpdateDeploymentWhenKafkaProxyIngressChanges(String namespace) {
        // Given
        kroxylicious.deployPortIdentifiesNodeWithNoFilters(kafkaClusterName);

        String originalChecksum = getInitialChecksum(namespace);

        updateIngresProtocol(ClusterIP.Protocol.TLS, namespace); // move to TLS but this is invalid

        // When
        // So move back to TCP and check things get updated.
        updateIngresProtocol(ClusterIP.Protocol.TCP, namespace);
        LOGGER.info("Kafka proxy ingress edited");

        // Then
        assertDeploymentUpdated(namespace, originalChecksum);
    }

    @Test
    void shouldUpdateDeploymentWhenVirtualKafkaClusterChanges(String namespace) {
        // Given
        // @formatter:off
        KafkaProtocolFilterBuilder arbitraryFilter = KroxyliciousFilterTemplates.baseFilterDeployment(namespace, "arbitrary-filter")
                .withNewSpec()
                .withType("io.kroxylicious.proxy.filter.simpletransform.ProduceRequestTransformation")
                .withConfigTemplate(Map.of("findPattern", "foo", "replacementValue", "bar"))
                .endSpec();
        // @formatter:on
        resourceManager.createOrUpdateResourceWithWait(arbitraryFilter);
        kroxylicious.deployPortIdentifiesNodeWithNoFilters("test-vkc");

        String originalChecksum = getInitialChecksum(namespace);

        FilterRef filterRef = new FilterRefBuilder().withName("arbitrary-filter").build();
        VirtualKafkaCluster virtualKafkaCluster = new VirtualKafkaClusterBuilder().withNewMetadata()
                .withName("test-vkc")
                .withNamespace(namespace)
                .and()
                .build();

        // When
        resourceManager.replaceResourceWithRetries(virtualKafkaCluster, vkc -> {
            var filterRefs = Optional.ofNullable(vkc.getSpec().getFilterRefs()).orElse(new ArrayList<>());
            filterRefs.add(filterRef);
            vkc.getSpec().setFilterRefs(filterRefs);

        });
        LOGGER.info("virtual cluster edited");

        // Then
        assertDeploymentUpdated(namespace, originalChecksum);
    }

    @Test
    void shouldUpdateDeploymentWhenDownstreamTlsCertUpdated(String namespace) {
        // Given
        var issuer = certManager.issuer(namespace);
        var cert = certManager.certFor(namespace, "my-cluster-cluster-ip." + namespace + ".svc.cluster.local");

        resourceManager.createOrUpdateResourceWithWait(issuer, cert);

        var tls = kroxylicious.tlsConfigFromCert("server-certificate");
        kroxylicious.deployPortIdentifiesNodeWithDownstreamTlsAndNoFilters("test-vkc", tls);

        String originalChecksum = getInitialChecksum(namespace);

        // When
        resourceManager.replaceResourceWithRetries(cert.build(),
                certToPatch -> {
                    var dnsNames = Optional.ofNullable(certToPatch.getSpec().getDnsNames()).orElse(new ArrayList<>());
                    dnsNames.add("test-vkc-cluster-ip.my-proxy.svc.cluster.local");
                    certToPatch.getSpec().setDnsNames(dnsNames);
                });
        LOGGER.info("SAN added to downstream tls cert");

        // Then
        assertDeploymentUpdated(namespace, originalChecksum);
    }

    @Test
    void shouldUpdateDeploymentWhenDownstreamTrustUpdated(String namespace) {
        // Given
        var issuer = certManager.issuer(namespace);
        var cert = certManager.certFor(namespace, "my-cluster-cluster-ip." + namespace + ".svc.cluster.local");

        resourceManager.createOrUpdateResourceWithWait(issuer, cert);

        var tls = kroxylicious.tlsConfigFromCert("server-certificate");

        kroxylicious.deployPortIdentifiesNodeWithDownstreamTlsAndNoFilters("test-vkc", tls);

        String originalChecksum = getInitialChecksum(namespace);
        ConfigMap trustAnchorConfig = new ConfigMapBuilder().withNewMetadata().withName(Constants.KROXYLICIOUS_TLS_CLIENT_CA_CERT).withNamespace(namespace).endMetadata()
                .build();

        // When
        resourceManager.replaceResourceWithRetries(trustAnchorConfig,
                trustConfigMap -> trustConfigMap.getData().put(Constants.KROXYLICIOUS_TLS_CA_NAME, "server-certificate1"));
        LOGGER.info("Downstream trust updated");

        // Then
        assertDeploymentUpdated(namespace, originalChecksum);
    }

    @Test
    void shouldUpdateWhenFilterConfigurationChanges(String namespace) {
        // Given
        // @formatter:off
        KafkaProtocolFilterBuilder arbitraryFilter = KroxyliciousFilterTemplates.baseFilterDeployment(namespace, "arbitrary-filter")
                .withNewSpec()
                    .withType("io.kroxylicious.proxy.filter.simpletransform.ProduceRequestTransformation")
                    .withConfigTemplate(Map.of("transformation", "Replacing", "transformationConfig",  Map.of("findPattern", "foo", "replacementValue", "bar")))
                .endSpec();
        // @formatter:on
        KubeClient kubeClient = kubeClient(namespace);
        resourceManager.createOrUpdateResourceWithWait(arbitraryFilter);
        kroxylicious.deployPortIdentifiesNodeWithFilters(kafkaClusterName, List.of("arbitrary-filter"));

        String originalChecksum = getInitialChecksum(namespace);

        // @formatter:off
        KafkaProtocolFilterBuilder updatedProtocolFilter = kubeClient.getClient().resources(KafkaProtocolFilter.class)
                .inNamespace(namespace)
                .withName("arbitrary-filter")
                .get()
                .edit()
                    .editSpec()
                    .withConfigTemplate(Map.of("transformation", "Replacing", "transformationConfig",  Map.of("findPattern", "foo", "replacementValue", "updated")))
                .endSpec();
        // @formatter:on

        // When
        resourceManager.createOrUpdateResourceWithWait(updatedProtocolFilter);
        LOGGER.info("Kafka proxy filter updated");

        // Then
        assertDeploymentUpdated(namespace, originalChecksum);
    }

    @Test
    void shouldUpdateDeploymentWhenResourceRequirementsChange(String namespace) {
        // Given
        KubeClient kubeClient = kubeClient(namespace);
        kroxylicious.deployPortIdentifiesNodeWithNoFilters(kafkaClusterName);

        KafkaProxy kafkaProxy = kubeClient.getClient().resources(KafkaProxy.class).inNamespace(namespace)
                .withName(Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME).get();
        var customResourceLimits = Map.of("cpu", Quantity.parse("600m"), "memory", Quantity.parse("516Mi"));
        var customResourceRequests = Map.of("cpu", Quantity.parse("599m"), "memory", Quantity.parse("515Mi"));

        // @formatter:off
        KafkaProxyBuilder updatedKafkaProxy = kafkaProxy.edit()
                .editOrNewSpec()
                .withNewInfrastructure()
                .withNewProxyContainer()
                .withResources(new ResourceRequirementsBuilder()
                        .withLimits(customResourceLimits)
                        .withRequests(customResourceRequests)
                        .build())
                .endProxyContainer()
                .endInfrastructure()
                .endSpec();
        // @formatter:on

        // When
        resourceManager.createOrUpdateResourceWithWait(updatedKafkaProxy);
        LOGGER.info("Kafka proxy updated");

        // Then
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            Deployment proxyDeployment = kubeClient.getDeployment(namespace, Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME);
            assertThat(proxyDeployment)
                    .isNotNull()
                    .extracting(Deployment::getSpec)
                    .extracting(DeploymentSpec::getTemplate)
                    .extracting(PodTemplateSpec::getSpec)
                    .extracting(PodSpec::getContainers, InstanceOfAssertFactories.list(Container.class))
                    .singleElement()
                    .extracting(Container::getResources)
                    .satisfies(resources -> {
                        assertThat(resources.getLimits())
                                .isEqualTo(customResourceLimits);
                        assertThat(resources.getRequests())
                                .isEqualTo(customResourceRequests);
                    });
        });
    }

    @Test
    void shouldNotUpdateDeploymentChecksumWhenKafkaProxyScaled(String namespace) {
        // Given
        KubeClient kubeClient = kubeClient(namespace);
        kroxylicious.deployPortIdentifiesNodeWithNoFilters(kafkaClusterName);

        String originalChecksum = getInitialChecksum(namespace);

        KafkaProxy kafkaProxy = kubeClient.getClient().resources(KafkaProxy.class).inNamespace(namespace)
                .withName(Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME).get();

        // @formatter:off
        KafkaProxyBuilder updatedKafkaProxy = kafkaProxy.edit()
                .editOrNewSpec()
                .withReplicas(2)
                .endSpec();
        // @formatter:on

        // When
        resourceManager.createOrUpdateResourceWithWait(updatedKafkaProxy);
        LOGGER.info("Kafka proxy updated");

        // Then
        assertDeploymentUnchanged(namespace, originalChecksum, 2);
    }

    @Test
    void shouldUpdateDeploymentWhenSecretChanges(String namespace) {
        // Given
        resourceManager.createOrUpdateResourceWithWait(
                new SecretBuilder().withNewMetadata().withName("kilted-kiwi").withNamespace(namespace).endMetadata().withType("kubernetes.io/tls")
                        .withData(Map.of("tls.crt", "whatever", "tls.key", "whatever")),
                KroxyliciousFilterTemplates.baseFilterDeployment(namespace, "arbitrary-filter")
                        .withNewSpec()
                        .withType("io.kroxylicious.proxy.filter.simpletransform.ProduceRequestTransformation")
                        .withConfigTemplate(Map.of("transformation", "Replacing", "transformationConfig",
                                Map.of("findPattern", "foo", "pathToReplacementValue", "${secret:kilted-kiwi:tls.key}")))
                        .endSpec());

        KubeClient kubeClient = kubeClient(namespace);
        kroxylicious.deployPortIdentifiesNodeWithFilters(kafkaClusterName, List.of("arbitrary-filter"));
        LOGGER.info("Kroxylicious deployed");

        String originalChecksum = getInitialChecksum(namespace);

        Secret existingSecret = kubeClient.getClient().resources(Secret.class).inNamespace(namespace)
                .withName("kilted-kiwi").get();

        // When
        resourceManager.createOrUpdateResourceWithWait(existingSecret.edit().withData(Map.of("tls.crt", "whatever", "tls.key", "unlocked")));
        LOGGER.info("secret: kilted-kiwi updated");

        // Then
        assertDeploymentUpdated(namespace, originalChecksum);
    }

    private static void assertDeploymentUpdated(String namespace, String originalChecksum) {
        var kubeClient = kubeClient(namespace);
        AtomicReference<String> newChecksumFromAnnotation = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            Deployment proxyDeployment = kubeClient.getDeployment(namespace, Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME);
            assertThat(proxyDeployment).isNotNull();
            OperatorAssertions.assertThat(proxyDeployment.getSpec().getTemplate().getMetadata()).hasAnnotationSatisfying("kroxylicious.io/referent-checksum",
                    value -> {
                        newChecksumFromAnnotation.set(value);
                        assertThat(value).isNotEqualTo(originalChecksum);
                    });
        });
        LOGGER.info("New checksum: {}", newChecksumFromAnnotation);
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertDeploymentUnchanged(String namespace, String originalChecksum, int expectedReplicaCount) {
        var kubeClient = kubeClient(namespace);
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            Deployment proxyDeployment = kubeClient.getDeployment(namespace, Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME);
            assertThat(proxyDeployment).isNotNull()
                    .extracting(Deployment::getSpec)
                    .isNotNull()
                    .satisfies(spec -> assertThat(spec.getReplicas()).isEqualTo(expectedReplicaCount))
                    .extracting(DeploymentSpec::getTemplate)
                    .isNotNull()
                    .extracting(PodTemplateSpec::getMetadata)
                    .satisfies(podTemplateSpec -> OperatorAssertions.assertThat(podTemplateSpec).hasAnnotationSatisfying("kroxylicious.io/referent-checksum",
                            value -> assertThat(value).isEqualTo(originalChecksum)));
        });
    }

    @BeforeEach
    void setUp(String namespace) throws IOException {
        kroxylicious = new Kroxylicious(namespace);
        certManager = new CertManager();
        certManager.deploy();
    }

    @AfterAll
    void cleanUp() {
        if (kroxyliciousOperator != null) {
            kroxyliciousOperator.delete();
        }
        if (certManager != null) {
            certManager.delete();
        }
    }

    @BeforeAll
    void setupBefore() {
        kroxyliciousOperator = new KroxyliciousOperator(Constants.KROXYLICIOUS_OPERATOR_NAMESPACE);
        kroxyliciousOperator.deploy();
    }

    private String getInitialChecksum(String namespace) {
        var kubeClient = kubeClient(namespace);
        AtomicReference<String> checksumFromAnnotation = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(() -> assertThat(kubeClient.getClient().resources(VirtualKafkaCluster.class)
                        .inNamespace(namespace)
                        .waitUntilCondition(OperatorChangeDetectionST::isVirtualClusterReady, 9, TimeUnit.SECONDS))
                        .isNotNull());

        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(() -> kubeClient.listPods(namespace, "app.kubernetes.io/name", "kroxylicious")
                        .stream().filter(p -> p.getMetadata().getLabels().get("app.kubernetes.io/component").equalsIgnoreCase("proxy")).toList(),
                        proxyPods -> {
                            assertThat(proxyPods)
                                    .singleElement()
                                    .extracting(Pod::getMetadata)
                                    .satisfies(podMetadata -> OperatorAssertions.assertThat(podMetadata)
                                            .hasAnnotationSatisfying("kroxylicious.io/referent-checksum", value -> assertThat(value).isNotBlank()));

                            String checksumFromPod = getChecksumFromAnnotation(proxyPods.get(0));
                            Deployment proxyDeployment = kubeClient.getDeployment(namespace, Constants.KROXYLICIOUS_PROXY_SIMPLE_NAME);
                            OperatorAssertions.assertThat(proxyDeployment.getSpec().getTemplate().getMetadata())
                                    .hasAnnotationSatisfying(
                                            "kroxylicious.io/referent-checksum",
                                            value -> assertThat(value).isEqualTo(checksumFromPod));
                            checksumFromAnnotation.set(checksumFromPod);
                        });
        LOGGER.info("initial checksum: '{}'", checksumFromAnnotation);
        return checksumFromAnnotation.get();
    }

    private String getChecksumFromAnnotation(HasMetadata entity) {
        return KubernetesResourceUtil.getOrCreateAnnotations(entity).get("kroxylicious.io/referent-checksum");
    }

    private static boolean isVirtualClusterReady(VirtualKafkaCluster virtualKafkaCluster) {
        if (virtualKafkaCluster.getStatus() != null) {
            return virtualKafkaCluster.getStatus().getConditions().stream().anyMatch(Condition::isResolvedRefsTrue);
        }
        else {
            return false;
        }
    }

    private static void updateIngresProtocol(ClusterIP.Protocol protocol, String namespace) {
        KafkaProxyIngress resolver = new KafkaProxyIngressBuilder()
                .withNewMetadata()
                .withNamespace(namespace)
                .withName(Constants.KROXYLICIOUS_INGRESS_CLUSTER_IP)
                .endMetadata()
                .build();

        ResourceManager.getInstance()
                .replaceResourceWithRetries(resolver,
                        ingress -> ingress.getSpec().getClusterIP().setProtocol(protocol));
    }
}
