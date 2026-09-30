package com.shardkv.consistency;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.consistency")
public record ConsistencyProperties(ConsistencyLevel defaultLevel) {

    public ConsistencyProperties {
        if (defaultLevel == null) {
            throw new IllegalArgumentException("shardkv.consistency.default-level must be configured");
        }
    }
}
