package com.shardkv.observability;

import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.health.NodeHealthStatus;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ShardKvMetrics {

    private final MeterRegistry registry;
    private final Timer queryDuration;
    private final DistributionSummary queryResults;

    public ShardKvMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.queryDuration = Timer.builder("shardkv.query.duration")
                .description("End-to-end distributed query duration")
                .publishPercentileHistogram()
                .register(registry);
        this.queryResults = DistributionSummary.builder("shardkv.query.results")
                .description("Documents returned by successful distributed queries")
                .serviceLevelObjectives(1, 10, 100, 1_000)
                .register(registry);
    }

    public void storageOperation(String operation, boolean success) {
        counter("shardkv.storage.operations", "operation", operation, "result", result(success));
    }

    public void replicationOperation(String operation, boolean success) {
        counter("shardkv.replication.operations", "operation", operation, "result", result(success));
    }

    public void consistencyOperation(
            String operation,
            ConsistencyLevel consistency,
            boolean success) {
        counter(
                "shardkv.consistency.operations",
                "operation", operation,
                "consistency", consistency.name(),
                "result", result(success));
    }

    public void readRepair(String result) {
        counter("shardkv.read.repair", "result", result);
    }

    public void readFailover() {
        counter("shardkv.read.failover");
    }

    public void heartbeat(String peer, boolean success) {
        counter("shardkv.heartbeat", "peer", peer, "result", result(success));
    }

    public void registerNodeHealth(String peer, Supplier<NodeHealthStatus> statusSupplier) {
        Objects.requireNonNull(statusSupplier, "statusSupplier must not be null");
        Gauge.builder("shardkv.node.health", statusSupplier, supplier -> healthValue(supplier.get()))
                .description("Observed peer health: HEALTHY=1, SUSPECT=0.5, UNHEALTHY=0")
                .tag("peer", peer)
                .strongReference(true)
                .register(registry);
    }

    public void healthTransition(String peer, NodeHealthStatus from, NodeHealthStatus to) {
        counter(
                "shardkv.node.health.transitions",
                "peer", peer,
                "from", from.name(),
                "to", to.name());
    }

    public void queryOperation(boolean success, Duration duration, Integer resultCount) {
        counter("shardkv.query.operations", "result", result(success));
        queryDuration.record(duration);
        if (success && resultCount != null) {
            queryResults.record(resultCount);
        }
    }

    public void queryFanoutFailure(String peer) {
        counter("shardkv.query.fanout.failures", "peer", peer);
    }

    private void counter(String name, String... tags) {
        registry.counter(name, tags).increment();
    }

    private static String result(boolean success) {
        return success ? "success" : "failure";
    }

    private static double healthValue(NodeHealthStatus status) {
        return switch (status) {
            case HEALTHY -> 1.0d;
            case SUSPECT -> 0.5d;
            case UNHEALTHY -> 0.0d;
        };
    }
}
