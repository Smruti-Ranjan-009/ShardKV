package com.shardkv.health;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.shardkv.observability.ShardKvMetrics;

@Component
public class NodeHealthTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeHealthTracker.class);

    private final String localNodeId;
    private final int failureThreshold;
    private final int recoveryThreshold;
    private final Map<String, Observation> observations = new ConcurrentHashMap<>();
    private final LongAdder successfulProbes = new LongAdder();
    private final LongAdder failedProbes = new LongAdder();
    private final LongAdder unhealthyTransitions = new LongAdder();
    private final ShardKvMetrics metrics;

    public NodeHealthTracker(
            ClusterMembership membership,
            FailureDetectionProperties properties,
            ShardKvMetrics metrics) {
        this.localNodeId = membership.localNode().id();
        this.failureThreshold = properties.failureThreshold();
        this.recoveryThreshold = properties.recoveryThreshold();
        this.metrics = metrics;
        for (ClusterNode member : membership.members()) {
            observations.put(member.id(), Observation.healthy());
        }
        for (ClusterNode member : membership.members()) {
            metrics.registerNodeHealth(member.id(), () -> status(member.id()));
        }
    }

    public NodeHealthStatus status(ClusterNode node) {
        return observation(node.id()).status();
    }

    public NodeHealthStatus status(String nodeId) {
        return observation(nodeId).status();
    }

    public void recordSuccess(ClusterNode node) {
        recordSuccess(node.id());
    }

    public void recordSuccess(String nodeId) {
        requireKnownNode(nodeId);
        successfulProbes.increment();
        if (nodeId.equals(localNodeId)) {
            return;
        }

        observations.compute(nodeId, (ignored, current) -> {
            if (current.status() == NodeHealthStatus.HEALTHY) {
                return Observation.healthy();
            }

            int successes = current.consecutiveSuccesses() + 1;
            if (successes >= recoveryThreshold) {
                LOGGER.info("Cluster node {} transitioned to HEALTHY", nodeId);
                metrics.healthTransition(nodeId, current.status(), NodeHealthStatus.HEALTHY);
                return Observation.healthy();
            }
            return new Observation(current.status(), 0, successes);
        });
    }

    public void recordFailure(ClusterNode node) {
        recordFailure(node.id());
    }

    public void recordFailure(String nodeId) {
        requireKnownNode(nodeId);
        failedProbes.increment();
        if (nodeId.equals(localNodeId)) {
            return;
        }

        observations.compute(nodeId, (ignored, current) -> {
            if (current.status() == NodeHealthStatus.UNHEALTHY) {
                return new Observation(NodeHealthStatus.UNHEALTHY, current.consecutiveFailures() + 1, 0);
            }

            int failures = current.consecutiveFailures() + 1;
            if (failures >= failureThreshold) {
                unhealthyTransitions.increment();
                LOGGER.warn("Cluster node {} transitioned to UNHEALTHY", nodeId);
                metrics.healthTransition(nodeId, current.status(), NodeHealthStatus.UNHEALTHY);
                return new Observation(NodeHealthStatus.UNHEALTHY, failures, 0);
            }
            if (current.status() != NodeHealthStatus.SUSPECT) {
                metrics.healthTransition(nodeId, current.status(), NodeHealthStatus.SUSPECT);
            }
            return new Observation(NodeHealthStatus.SUSPECT, failures, 0);
        });
    }

    public HealthStatistics statistics() {
        return new HealthStatistics(
                successfulProbes.sum(),
                failedProbes.sum(),
                unhealthyTransitions.sum());
    }

    Observation observationForTesting(String nodeId) {
        return observation(nodeId);
    }

    private Observation observation(String nodeId) {
        requireKnownNode(nodeId);
        if (nodeId.equals(localNodeId)) {
            return Observation.healthy();
        }
        return observations.get(nodeId);
    }

    private void requireKnownNode(String nodeId) {
        if (!observations.containsKey(nodeId)) {
            throw new IllegalArgumentException("Unknown cluster node: " + nodeId);
        }
    }

    record Observation(
            NodeHealthStatus status,
            int consecutiveFailures,
            int consecutiveSuccesses) {

        private static Observation healthy() {
            return new Observation(NodeHealthStatus.HEALTHY, 0, 0);
        }
    }

    public record HealthStatistics(
            long successfulProbes,
            long failedProbes,
            long unhealthyTransitions) {
    }
}
