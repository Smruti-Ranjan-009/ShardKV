package com.shardkv.consistency;

import org.springframework.stereotype.Component;

@Component
public class ConsistencyPolicy {

    private final ConsistencyLevel defaultLevel;

    public ConsistencyPolicy(ConsistencyProperties properties) {
        this.defaultLevel = properties.defaultLevel();
    }

    public ConsistencyLevel resolve(ConsistencyLevel requestedLevel) {
        return requestedLevel == null ? defaultLevel : requestedLevel;
    }

    public int requiredAcknowledgements(ConsistencyLevel level, int replicationFactor) {
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("Replication factor must be greater than zero");
        }
        return switch (level) {
            case ONE -> 1;
            case QUORUM -> (replicationFactor / 2) + 1;
            case ALL -> replicationFactor;
        };
    }
}
