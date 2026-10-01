package com.shardkv.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.health.NodeHealthStatus;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ShardKvMetricsTests {

    @Test
    void recordsBoundedDomainMetricsAndHealthGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShardKvMetrics metrics = new ShardKvMetrics(registry);
        AtomicReference<NodeHealthStatus> health = new AtomicReference<>(NodeHealthStatus.HEALTHY);

        metrics.storageOperation("put", true);
        metrics.storageOperation("put", false);
        metrics.consistencyOperation("put", ConsistencyLevel.QUORUM, true);
        metrics.replicationOperation("repair", true);
        metrics.readRepair("attempted");
        metrics.readRepair("success");
        metrics.readRepair("failure");
        metrics.readFailover();
        metrics.heartbeat("node-2", true);
        metrics.heartbeat("node-2", false);
        metrics.registerNodeHealth("node-2", health::get);
        metrics.healthTransition("node-2", NodeHealthStatus.HEALTHY, NodeHealthStatus.SUSPECT);
        metrics.queryOperation(true, Duration.ofMillis(12), 4);
        metrics.queryOperation(false, Duration.ofMillis(5), null);
        metrics.queryFanoutFailure("node-3");

        assertThat(registry.get("shardkv.storage.operations")
                .tags("operation", "put", "result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.storage.operations")
                .tags("operation", "put", "result", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.consistency.operations")
                .tags("operation", "put", "consistency", "QUORUM", "result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.replication.operations")
                .tags("operation", "repair", "result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.read.repair").tag("result", "attempted")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.read.repair").tag("result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.read.repair").tag("result", "failure")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.read.failover").counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.heartbeat")
                .tags("peer", "node-2", "result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.heartbeat")
                .tags("peer", "node-2", "result", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.node.health.transitions")
                .tags("peer", "node-2", "from", "HEALTHY", "to", "SUSPECT")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.query.operations").tag("result", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.query.operations").tag("result", "failure")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.query.duration").timer().count()).isEqualTo(2);
        assertThat(registry.get("shardkv.query.results").summary().totalAmount()).isEqualTo(4);
        assertThat(registry.get("shardkv.query.fanout.failures").tag("peer", "node-3")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("shardkv.node.health").tag("peer", "node-2")
                .gauge().value()).isEqualTo(1.0);

        health.set(NodeHealthStatus.SUSPECT);
        assertThat(registry.get("shardkv.node.health").tag("peer", "node-2")
                .gauge().value()).isEqualTo(0.5);
        health.set(NodeHealthStatus.UNHEALTHY);
        assertThat(registry.get("shardkv.node.health").tag("peer", "node-2")
                .gauge().value()).isZero();
    }

    @Test
    void metricTagsNeverContainUserKeysOrQueryValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShardKvMetrics metrics = new ShardKvMetrics(registry);
        String secretKey = "customer-secret-key";
        String secretValue = "Bengaluru-secret-filter";

        metrics.storageOperation("get", true);
        metrics.consistencyOperation("get", ConsistencyLevel.ONE, true);
        metrics.queryOperation(true, Duration.ofMillis(1), 1);

        assertThat(registry.getMeters())
                .flatMap(meter -> meter.getId().getTags())
                .extracting(Tag::getValue)
                .doesNotContain(secretKey, secretValue);
        assertThat(registry.getMeters())
                .flatMap(meter -> meter.getId().getTags())
                .allSatisfy(tag -> {
                    assertThat(tag.getKey()).isNotIn(
                            "key", "document_key", "field", "value", "query_value");
                    assertThat(tag.getValue()).doesNotContain(secretKey, secretValue);
                });
    }
}
