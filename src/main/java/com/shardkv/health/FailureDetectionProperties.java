package com.shardkv.health;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.failure-detection")
public record FailureDetectionProperties(
        Duration interval,
        int failureThreshold,
        int recoveryThreshold) {

    public FailureDetectionProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("shardkv.failure-detection.interval must be greater than zero");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException(
                    "shardkv.failure-detection.failure-threshold must be greater than zero");
        }
        if (recoveryThreshold < 1) {
            throw new IllegalArgumentException(
                    "shardkv.failure-detection.recovery-threshold must be greater than zero");
        }
    }
}
