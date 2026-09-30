package com.shardkv.cluster;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.cluster")
public record ClusterProperties(
        String members,
        int virtualNodes,
        Duration connectTimeout,
        Duration readTimeout) {

    public ClusterProperties {
        if (members == null || members.isBlank()) {
            throw new IllegalArgumentException("shardkv.cluster.members must not be empty");
        }
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("shardkv.cluster.virtual-nodes must be greater than zero");
        }
        requirePositive(connectTimeout, "shardkv.cluster.connect-timeout");
        requirePositive(readTimeout, "shardkv.cluster.read-timeout");
    }

    private static void requirePositive(Duration duration, String propertyName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }
    }
}
