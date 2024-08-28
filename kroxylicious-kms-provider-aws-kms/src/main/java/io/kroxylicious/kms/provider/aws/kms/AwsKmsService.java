/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.aws.kms;

import java.time.Duration;
import java.util.Objects;

import io.kroxylicious.kms.provider.aws.kms.config.Config;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.proxy.plugin.Plugin;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of the {@link KmsService} interface backed by a remote instance of AWS KMS.
 */
@Plugin(configType = Config.class)
public class AwsKmsService implements KmsService<Config, String, AwsKmsEdek> {

    @SuppressWarnings("java:S3077") // Config is an immutable object
    private volatile Config config;

    @Override
    public void initialize(@NonNull Config config) {
        Objects.requireNonNull(config);
        if (this.config != null) {
            throw new IllegalStateException("KMS service is already initialized");
        }
        this.config = config;
    }

    @NonNull
    @Override
    public AwsKms buildKms() {
        if (config == null) {
            throw new IllegalStateException("KMS service not initialized");
        }
        return new AwsKms(config.endpointUrl(),
                config.accessKey().getProvidedPassword(),
                config.secretKey().getProvidedPassword(),
                config.region(),
                Duration.ofSeconds(20), config.sslContext());
    }
}
