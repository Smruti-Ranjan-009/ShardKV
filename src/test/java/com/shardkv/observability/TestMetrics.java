package com.shardkv.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public final class TestMetrics {

    private TestMetrics() {
    }

    public static ShardKvMetrics create() {
        return new ShardKvMetrics(new SimpleMeterRegistry());
    }
}
