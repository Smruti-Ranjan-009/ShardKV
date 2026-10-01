package com.shardkv.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.NodeProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeHealthTrackerTests {

    private static final ClusterNode LOCAL = new ClusterNode("node-1", "localhost", 8081);
    private static final ClusterNode REMOTE = new ClusterNode("node-2", "localhost", 8082);

    private NodeHealthTracker tracker;

    @BeforeEach
    void setUp() {
        ClusterProperties clusterProperties = new ClusterProperties(
                "node-1,localhost,8081;node-2,localhost,8082",
                128,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        ClusterMembership membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                clusterProperties);
        tracker = new NodeHealthTracker(
                membership,
                new FailureDetectionProperties(Duration.ofSeconds(2), 3, 2));
    }

    @Test
    void healthyNodeRemainsHealthyAfterSuccessfulProbe() {
        tracker.recordSuccess(REMOTE);

        assertThat(tracker.status(REMOTE)).isEqualTo(NodeHealthStatus.HEALTHY);
        assertThat(tracker.statistics().successfulProbes()).isEqualTo(1);
    }

    @Test
    void failuresBecomeSuspectBeforeThresholdAndUnhealthyAtThreshold() {
        tracker.recordFailure(REMOTE);
        assertThat(tracker.status(REMOTE)).isEqualTo(NodeHealthStatus.SUSPECT);
        assertThat(tracker.observationForTesting(REMOTE.id()).consecutiveFailures()).isEqualTo(1);

        tracker.recordFailure(REMOTE);
        assertThat(tracker.status(REMOTE)).isEqualTo(NodeHealthStatus.SUSPECT);

        tracker.recordFailure(REMOTE);
        assertThat(tracker.status(REMOTE)).isEqualTo(NodeHealthStatus.UNHEALTHY);
        assertThat(tracker.statistics().unhealthyTransitions()).isEqualTo(1);
    }

    @Test
    void recoveryRequiresConfiguredConsecutiveSuccessThreshold() {
        tracker.recordFailure(REMOTE);
        tracker.recordFailure(REMOTE);
        tracker.recordFailure(REMOTE);

        tracker.recordSuccess(REMOTE);
        assertThat(tracker.status(REMOTE)).isEqualTo(NodeHealthStatus.UNHEALTHY);
        assertThat(tracker.observationForTesting(REMOTE.id()).consecutiveSuccesses()).isEqualTo(1);

        tracker.recordSuccess(REMOTE);
        assertThat(tracker.status(REMOTE)).isEqualTo(NodeHealthStatus.HEALTHY);
    }

    @Test
    void failedProbeInterruptsRecoverySuccessSequence() {
        tracker.recordFailure(REMOTE);
        tracker.recordFailure(REMOTE);
        tracker.recordFailure(REMOTE);
        tracker.recordSuccess(REMOTE);

        tracker.recordFailure(REMOTE);
        tracker.recordSuccess(REMOTE);

        assertThat(tracker.status(REMOTE)).isEqualTo(NodeHealthStatus.UNHEALTHY);
        assertThat(tracker.observationForTesting(REMOTE.id()).consecutiveSuccesses()).isEqualTo(1);
    }

    @Test
    void localNodeAlwaysRemainsHealthy() {
        tracker.recordFailure(LOCAL);
        tracker.recordFailure(LOCAL);
        tracker.recordFailure(LOCAL);

        assertThat(tracker.status(LOCAL)).isEqualTo(NodeHealthStatus.HEALTHY);
    }

    @Test
    void unknownNodeIsRejected() {
        ClusterNode unknown = new ClusterNode("node-x", "localhost", 9000);

        assertThatThrownBy(() -> tracker.status(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown cluster node");
        assertThatThrownBy(() -> tracker.recordFailure(unknown))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveThresholdsAreRejected() {
        assertThatThrownBy(() -> new FailureDetectionProperties(Duration.ofSeconds(1), 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailureDetectionProperties(Duration.ofSeconds(1), 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailureDetectionProperties(Duration.ZERO, 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
