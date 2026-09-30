package com.shardkv.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.node")
public record NodeProperties(String id, String host, int port) {

    public NodeProperties {
        new ClusterNode(id, host, port);
    }

    public ClusterNode asClusterNode() {
        return new ClusterNode(id, host, port);
    }
}
