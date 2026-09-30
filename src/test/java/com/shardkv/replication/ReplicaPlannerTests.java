package com.shardkv.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ConsistentHashRing;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplicaPlannerTests {

    private static final List<ClusterNode> NODES = List.of(
            new ClusterNode("node-1", "localhost", 8081),
            new ClusterNode("node-2", "localhost", 8082),
            new ClusterNode("node-3", "localhost", 8083));

    @Test
    void replicationFactorOneReturnsOnlyThePrimary() {
        ReplicaPlan plan = planner(NODES, 128, 1).planFor("customer-42");

        assertThat(plan.replicas()).isEmpty();
        assertThat(plan.primary()).isEqualTo(ring(NODES, 128).owner("customer-42"));
    }

    @Test
    void replicationFactorTwoReturnsTwoDistinctPhysicalNodes() {
        ReplicaPlan plan = planner(NODES, 128, 2).planFor("customer-42");

        assertThat(plan.replicas()).hasSize(1);
        assertThat(List.of(plan.primary().id(), plan.replicas().get(0).id())).doesNotHaveDuplicates();
    }

    @Test
    void replicationFactorThreeReturnsAllThreeDistinctPhysicalNodes() {
        ReplicaPlan plan = planner(NODES, 128, 3).planFor("customer-42");
        List<String> placedNodeIds = placementIds(plan);

        assertThat(placedNodeIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(placedNodeIds).containsExactlyInAnyOrder("node-1", "node-2", "node-3");
    }

    @Test
    void primaryIsTheExistingPhaseTwoOwner() {
        ConsistentHashRing ring = ring(NODES, 128);
        ReplicaPlanner planner = new ReplicaPlanner(ring, new ReplicationProperties(3));

        assertThat(planner.planFor("invoice-100").primary()).isEqualTo(ring.owner("invoice-100"));
    }

    @Test
    void replicaOrderingIsDeterministicAcrossIndependentlyConstructedRings() {
        ReplicaPlan first = planner(NODES, 128, 3).planFor("deterministic-key");
        ReplicaPlan second = planner(List.of(NODES.get(2), NODES.get(0), NODES.get(1)), 128, 3)
                .planFor("deterministic-key");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void virtualNodeDuplicatesNeverDuplicatePhysicalReplicas() {
        ReplicaPlanner planner = planner(NODES, 1_024, 3);

        for (int key = 0; key < 1_000; key++) {
            assertThat(placementIds(planner.planFor("key-" + key)))
                    .hasSize(3)
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void replicationFactorLargerThanClusterIsRejected() {
        ConsistentHashRing ring = ring(NODES, 128);

        assertThatThrownBy(() -> new ReplicaPlanner(ring, new ReplicationProperties(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void nonPositiveReplicationFactorIsRejected() {
        assertThatThrownBy(() -> new ReplicationProperties(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> new ReplicationProperties(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    private static ReplicaPlanner planner(
            List<ClusterNode> nodes,
            int virtualNodes,
            int replicationFactor) {
        return new ReplicaPlanner(ring(nodes, virtualNodes), new ReplicationProperties(replicationFactor));
    }

    private static ConsistentHashRing ring(List<ClusterNode> nodes, int virtualNodes) {
        return ConsistentHashRing.create(nodes, virtualNodes);
    }

    private static List<String> placementIds(ReplicaPlan plan) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(plan.primary()),
                        plan.replicas().stream())
                .map(ClusterNode::id)
                .toList();
    }
}
