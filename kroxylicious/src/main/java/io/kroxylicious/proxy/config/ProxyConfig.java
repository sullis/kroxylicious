/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.config;

public class ProxyConfig {

    private final String address;
    private final boolean logNetwork;
    private final boolean logFrames;
    private final boolean useIoUring;
    private final String keyStoreFile;
    private final String keyPassword;

    public ProxyConfig(String address, boolean logNetwork, boolean logFrames, boolean useIoUring, String keyStoreFile, String keyPassword) {
        this.address = address;
        this.logNetwork = logNetwork;
        this.logFrames = logFrames;
        this.useIoUring = useIoUring;
        this.keyStoreFile = keyStoreFile;
        this.keyPassword = keyPassword;
    }

    public String address() {
        return address;
    }

    public boolean logNetwork() {
        return logNetwork;
    }

    public boolean logFrames() {
        return logFrames;
    }

    public boolean useIoUring() {
        return useIoUring;
    }

    public String keyStoreFile() {
        return keyStoreFile;
    }

    public String keyPassword() {
        return keyPassword;
    }
}
