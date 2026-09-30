package com.shardkv.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConsistentHashRingTests {

    private static final List<ClusterNode> NODES = List.of(
            new ClusterNode("node-1", "localhost", 8081),
            new ClusterNode("node-2", "localhost", 8082),
            new ClusterNode("node-3", "localhost", 8083));

    @Test
    void sameNodeListCreatesIdenticalOwnership() {
        ConsistentHashRing first = ConsistentHashRing.create(NODES, 128);
        ConsistentHashRing second = ConsistentHashRing.create(List.of(NODES.get(2), NODES.get(0), NODES.get(1)), 128);

        for (int key = 0; key < 10_000; key++) {
            assertThat(second.owner("key-" + key)).isEqualTo(first.owner("key-" + key));
        }
    }

    @Test
    void sameKeyAlwaysResolvesToSameNode() {
        ConsistentHashRing ring = ConsistentHashRing.create(NODES, 128);
        ClusterNode expectedOwner = ring.owner("customer-42");

        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(ring.owner("customer-42")).isEqualTo(expectedOwner);
        }
    }

    @Test
    void differentKeysDistributeAcrossPhysicalNodes() {
        ConsistentHashRing ring = ConsistentHashRing.create(NODES, 128);

        Set<String> owners = IntStream.range(0, 1_000)
                .mapToObj(key -> ring.owner("key-" + key).id())
                .collect(Collectors.toSet());

        assertThat(owners).containsExactlyInAnyOrder("node-1", "node-2", "node-3");
    }

    @Test
    void virtualNodeCountCreatesExpectedRingPositions() {
        ConsistentHashRing ring = ConsistentHashRing.create(NODES, 128);

        assertThat(ring.virtualNodesPerNode()).isEqualTo(128);
        assertThat(ring.ringPositionCount()).isEqualTo(3 * 128);
    }

    @Test
    void emptyMembershipIsRejected() {
        assertThatThrownBy(() -> ConsistentHashRing.create(List.of(), 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one node");
    }

    @Test
    void duplicateNodeIdsAreRejected() {
        List<ClusterNode> duplicateIds = List.of(
                new ClusterNode("node-1", "localhost", 8081),
                new ClusterNode("node-1", "localhost", 8082));

        assertThatThrownBy(() -> ConsistentHashRing.create(duplicateIds, 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate cluster node ID");
    }

    @Test
    void ownershipIsDeterministicAcrossNewRingObjects() {
        ClusterNode firstOwner = ConsistentHashRing.create(NODES, 128).owner("invoice-2026-0001");
        ClusterNode recreatedOwner = ConsistentHashRing.create(NODES, 128).owner("invoice-2026-0001");

        assertThat(recreatedOwner).isEqualTo(firstOwner);
    }

    @Test
    void distributionIsNotCatastrophicallySkewed() {
        ConsistentHashRing ring = ConsistentHashRing.create(NODES, 128);
        Map<String, Integer> ownershipCounts = new HashMap<>();

        for (int key = 0; key < 100_000; key++) {
            ownershipCounts.merge(ring.owner("distribution-key-" + key).id(), 1, Integer::sum);
        }

        assertThat(ownershipCounts).containsOnlyKeys("node-1", "node-2", "node-3");
        // This broad range catches a broken ring without asserting unrealistic perfect balance.
        assertThat(ownershipCounts.values()).allSatisfy(count -> assertThat(count).isBetween(15_000, 55_000));
    }
}
