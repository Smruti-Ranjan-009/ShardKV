package com.shardkv.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClusterMembershipTests {

    @Test
    void membershipIsParsedAndSortedByStableNodeId() {
        ClusterMembership membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                properties("node-3,localhost,8083;node-1,localhost,8081;node-2,localhost,8082"));

        assertThat(membership.localNode()).isEqualTo(new ClusterNode("node-1", "localhost", 8081));
        assertThat(membership.members()).extracting(ClusterNode::id)
                .containsExactly("node-1", "node-2", "node-3");
    }

    @Test
    void duplicateNodeIdsAreRejected() {
        assertThatThrownBy(() -> new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                properties("node-1,localhost,8081;node-1,localhost,8082")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate cluster node ID");
    }

    @Test
    void invalidMemberPortIsRejected() {
        assertThatThrownBy(() -> new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                properties("node-1,localhost,70000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void missingLocalNodeIsRejected() {
        assertThatThrownBy(() -> new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                properties("node-2,localhost,8082")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local node ID is not present");
    }

    @Test
    void mismatchedLocalEndpointIsRejected() {
        assertThatThrownBy(() -> new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                properties("node-1,localhost,9091")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host and port must match");
    }

    @Test
    void emptyClusterConfigurationIsRejected() {
        assertThatThrownBy(() -> properties(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void invalidVirtualNodeCountIsRejected() {
        assertThatThrownBy(() -> new ClusterProperties(
                "node-1,localhost,8081",
                0,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    private static ClusterProperties properties(String members) {
        return new ClusterProperties(members, 128, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
