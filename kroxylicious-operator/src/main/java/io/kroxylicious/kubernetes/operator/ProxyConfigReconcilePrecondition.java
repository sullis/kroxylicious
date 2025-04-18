/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.api.reconciler.dependent.managed.ManagedWorkflowAndDependentResourceContext;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

import io.kroxylicious.kubernetes.api.common.FilterRef;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;
import io.kroxylicious.kubernetes.api.v1alpha1.KafkaService;
import io.kroxylicious.kubernetes.api.v1alpha1.VirtualKafkaCluster;
import io.kroxylicious.kubernetes.filter.api.v1alpha1.KafkaProtocolFilterSpec;
import io.kroxylicious.kubernetes.operator.model.ProxyModel;
import io.kroxylicious.kubernetes.operator.model.ingress.ProxyIngressModel;
import io.kroxylicious.kubernetes.operator.resolver.ProxyResolutionResult;
import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.config.IllegalConfigurationException;
import io.kroxylicious.proxy.config.NamedFilterDefinition;
import io.kroxylicious.proxy.config.TargetCluster;
import io.kroxylicious.proxy.config.VirtualCluster;
import io.kroxylicious.proxy.config.admin.EndpointsConfiguration;
import io.kroxylicious.proxy.config.admin.ManagementConfiguration;
import io.kroxylicious.proxy.config.admin.PrometheusMetricsConfig;

/**
 * Tests whether the proxy's config would be a legal state.
 */
public class ProxyConfigReconcilePrecondition implements Condition<ConfigMap, KafkaProxy> {

    @Override
    public boolean isMet(DependentResource<ConfigMap, KafkaProxy> dependentResource, KafkaProxy primary, Context<KafkaProxy> context) {
        try {
            var configuration = generateProxyConfig(context);
            context.managedWorkflowAndDependentResourceContext().put(ProxyConfigDependentResource.CONFIGURATION_DATA_KEY, configuration);
            return true;
        }
        catch (IllegalConfigurationException ice) {
            context.managedWorkflowAndDependentResourceContext().put(ProxyConfigDependentResource.CONFIGURATION_DATA_KEY, null);
            return false;
        }
    }

    private Configuration generateProxyConfig(Context<KafkaProxy> context) {

        var model = KafkaProxyContext.proxyContext(context).model();

        List<NamedFilterDefinition> allFilterDefinitions = buildFilterDefinitions(context, model);
        Map<String, NamedFilterDefinition> namedDefinitions = allFilterDefinitions.stream().collect(Collectors.toMap(NamedFilterDefinition::name, f -> f));

        var virtualClusters = buildVirtualClusters(namedDefinitions.keySet(), model);

        List<NamedFilterDefinition> referencedFilters = virtualClusters.stream().flatMap(c -> Optional.ofNullable(c.filters()).stream().flatMap(Collection::stream))
                .distinct()
                .map(namedDefinitions::get).toList();

        return new Configuration(
                new ManagementConfiguration(null, null, new EndpointsConfiguration(new PrometheusMetricsConfig())), referencedFilters,
                null, // no defaultFilters <= each of the virtualClusters specifies its own
                virtualClusters,
                List.of(), false,
                // micrometer
                Optional.empty());
    }

    private static List<VirtualCluster> buildVirtualClusters(Set<String> successfullyBuiltFilterNames, ProxyModel model) {
        return model.clustersWithValidIngresses().stream()
                .filter(cluster -> Optional.ofNullable(cluster.getSpec().getFilterRefs()).stream().flatMap(Collection::stream).allMatch(
                        filters -> successfullyBuiltFilterNames.contains(filterDefinitionName(filters))))
                .map(cluster -> getVirtualCluster(cluster, model.resolutionResult().kafkaServiceRef(cluster).orElseThrow(), model.ingressModel()))
                .toList();
    }

    private List<NamedFilterDefinition> buildFilterDefinitions(Context<KafkaProxy> context,
                                                               ProxyModel model) {
        List<NamedFilterDefinition> filterDefinitions = new ArrayList<>();
        Set<NamedFilterDefinition> uniqueValues = new HashSet<>();
        for (VirtualKafkaCluster cluster : model.clustersWithValidIngresses()) {
            for (NamedFilterDefinition namedFilterDefinition : filterDefinitions(context, cluster, model.resolutionResult())) {
                if (uniqueValues.add(namedFilterDefinition)) {
                    filterDefinitions.add(namedFilterDefinition);
                }
            }
        }
        filterDefinitions.sort(Comparator.comparing(NamedFilterDefinition::name));
        return filterDefinitions;
    }

    private static List<String> filterNamesForCluster(VirtualKafkaCluster cluster) {
        return Optional.ofNullable(cluster.getSpec().getFilterRefs())
                .orElse(List.of())
                .stream()
                .map(ProxyConfigReconcilePrecondition::filterDefinitionName)
                .toList();
    }

    private static String filterDefinitionName(FilterRef filterCrRef) {
        return filterCrRef.getName() + "." + filterCrRef.getKind() + "." + filterCrRef.getGroup();
    }

    private List<NamedFilterDefinition> filterDefinitions(Context<KafkaProxy> context, VirtualKafkaCluster cluster, ProxyResolutionResult resolutionResult) {

        return Optional.ofNullable(cluster.getSpec().getFilterRefs()).orElse(List.of()).stream().map(filterCrRef -> {

            String filterDefinitionName = filterDefinitionName(filterCrRef);

            var filterCr = resolutionResult.filter(filterCrRef).orElseThrow();
            var spec = filterCr.getSpec();
            String type = spec.getType();
            SecureConfigInterpolator.InterpolationResult interpolationResult = interpolateConfig(context, spec);
            ManagedWorkflowAndDependentResourceContext ctx = context.managedWorkflowAndDependentResourceContext();
            putOrMerged(ctx, ProxyConfigDependentResource.SECURE_VOLUME_KEY, interpolationResult.volumes());
            putOrMerged(ctx, ProxyConfigDependentResource.SECURE_VOLUME_MOUNT_KEY, interpolationResult.mounts());
            return new NamedFilterDefinition(filterDefinitionName, type, interpolationResult.config());

        }).toList();
    }

    private static <T> void putOrMerged(ManagedWorkflowAndDependentResourceContext ctx, String ctxKey, Set<T> set) {
        Optional<Set<T>> ctxVolumes = (Optional) ctx.get(ctxKey, Set.class);
        if (ctxVolumes.isPresent()) {
            ctxVolumes.get().addAll(set);
        }
        else {
            ctx.put(ctxKey, new LinkedHashSet<>(set));
        }
    }

    private SecureConfigInterpolator.InterpolationResult interpolateConfig(Context<KafkaProxy> context, KafkaProtocolFilterSpec spec) {
        SecureConfigInterpolator secureConfigInterpolator = KafkaProxyContext.proxyContext(context).secureConfigInterpolator();
        Object configTemplate = Objects.requireNonNull(spec.getConfigTemplate(), "ConfigTemplate is required in the KafkaProtocolFilterSpec");
        return secureConfigInterpolator.interpolate(configTemplate);
    }

    private static VirtualCluster getVirtualCluster(VirtualKafkaCluster cluster,
                                                    KafkaService kafkaServiceRef,
                                                    ProxyIngressModel ingressModel) {

        ProxyIngressModel.VirtualClusterIngressModel virtualClusterIngressModel = ingressModel.clusterIngressModel(cluster).orElseThrow();
        String bootstrap = kafkaServiceRef.getSpec().getBootstrapServers();
        return new VirtualCluster(
                ResourcesUtil.name(cluster), new TargetCluster(bootstrap, Optional.empty()),
                null,
                Optional.empty(),
                virtualClusterIngressModel.gateways(),
                false, false,
                filterNamesForCluster(cluster));
    }

}
