/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kubernetes.operator.resolver;

public enum Dependency {
    KAFKA_PROXY_INGRESS,
    FILTER,
    KAFKA_CLUSTER_REF
}
