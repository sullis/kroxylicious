/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * FilterFactory is a pluggable source of Kroxylicious filter implementations.
 * @param <F> the {@link Filter} type
 * @param <C> the configuration type for the Filter (use {@link Void} if the Filter is not configurable)
 */
public interface FilterFactory<F extends Filter, C> {

    /**
     * The concrete type of the Filter this Contributor can instantiate
     *
     * @return type of the Filter this Contributor offers.
     */
    @NonNull
    Class<F> filterType();

    /**
     * The type of config expected by the Filter.
     * <br/>
     * The type must be deserializable with Jackson
     * If the Filter has no configuration, return {@link Void} instead.
     *
     * @return type of config expected by the Filter.
     */
    @NonNull
    Class<C> configType();

    /**
     * Validate the configuration. By default, the configuration is considered invalid if
     * the config type is not {@link Void} and the configuration is null.
     * @param config configuration
     * @throws InvalidFilterConfigurationException when the configuration is invalid
     */
    default void validateConfiguration(C config) {
        boolean requiresConfiguration = configType() != Void.class;
        if (requiresConfiguration && config == null) {
            throw new InvalidFilterConfigurationException(filterType().getSimpleName() + " requires configuration, but config object is null");
        }
    }

    /**
     * Creates an instance of the Filter.
     *
     * @param context context containing Filter configuration which may be null if the Filter instance does not accept configuration.
     * @param configuration configuration
     * @return the Filter instance.
     */
    @NonNull
    F createFilter(FilterCreationContext context, C configuration);

}
