package com.shardkv.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.query")
public record QueryProperties(int maxResults, int parallelism, int queueCapacity) {

    public QueryProperties {
        if (maxResults < 1) {
            throw new IllegalArgumentException("shardkv.query.max-results must be greater than zero");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("shardkv.query.parallelism must be greater than zero");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("shardkv.query.queue-capacity must be greater than zero");
        }
    }
}
