/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.config;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.proxy.config.tls.Tls;
import io.kroxylicious.proxy.internal.clusternetworkaddressconfigprovider.ClusterNetworkAddressConfigProviderContributorManager;
import io.kroxylicious.proxy.service.ClusterNetworkAddressConfigProvider;

public record VirtualCluster(TargetCluster targetCluster,
                             @JsonProperty(required = true) ClusterNetworkAddressConfigProviderDefinition clusterNetworkAddressConfigProvider,

                             @JsonProperty() Optional<Tls> tls,
                             boolean logNetwork,
                             boolean logFrames) {
    public io.kroxylicious.proxy.model.VirtualCluster toVirtualClusterModel(String virtualClusterNodeName) {
        return new io.kroxylicious.proxy.model.VirtualCluster(virtualClusterNodeName,
                targetCluster(),
                toClusterNetworkAddressConfigProviderModel(),
                tls(),
                logNetwork(), logFrames());
    }

    private ClusterNetworkAddressConfigProvider toClusterNetworkAddressConfigProviderModel() {
        return ClusterNetworkAddressConfigProviderContributorManager.getInstance()
                .getClusterEndpointConfigProvider(clusterNetworkAddressConfigProvider().type(), clusterNetworkAddressConfigProvider().config());
    }
}
