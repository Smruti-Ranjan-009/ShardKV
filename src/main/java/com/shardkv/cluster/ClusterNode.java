package com.shardkv.cluster;

import java.net.URI;
import java.net.URISyntaxException;

public record ClusterNode(String id, String host, int port) {

    public ClusterNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Cluster node ID must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Cluster node host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Cluster node port must be between 1 and 65535");
        }

        id = id.trim();
        host = host.trim();
    }

    public URI baseUri() {
        try {
            return new URI("http", null, host, port, null, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Cluster node host cannot be represented as an HTTP URI", exception);
        }
    }
}
