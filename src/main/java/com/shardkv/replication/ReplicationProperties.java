package com.shardkv.replication;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.replication")
public record ReplicationProperties(int factor) {

    public ReplicationProperties {
        if (factor < 1) {
            throw new IllegalArgumentException("shardkv.replication.factor must be greater than zero");
        }
    }
}
