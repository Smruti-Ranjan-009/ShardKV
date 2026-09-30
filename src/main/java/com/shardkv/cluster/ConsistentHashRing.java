package com.shardkv.cluster;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class ConsistentHashRing {

    private final NavigableMap<Long, ClusterNode> ring;
    private final List<ClusterNode> members;
    private final int virtualNodesPerNode;

    @Autowired
    public ConsistentHashRing(ClusterMembership membership, ClusterProperties properties) {
        this(membership.members(), properties.virtualNodes());
    }

    private ConsistentHashRing(List<ClusterNode> nodes, int virtualNodesPerNode) {
        validate(nodes, virtualNodesPerNode);

        List<ClusterNode> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort((left, right) -> left.id().compareTo(right.id()));

        NavigableMap<Long, ClusterNode> positions = new TreeMap<>(Long::compareUnsigned);
        for (ClusterNode node : sortedNodes) {
            for (int virtualNode = 0; virtualNode < virtualNodesPerNode; virtualNode++) {
                long position = hash(node.id() + "#" + virtualNode);
                ClusterNode existing = positions.putIfAbsent(position, node);
                if (existing != null) {
                    throw new IllegalStateException("SHA-256 virtual-node position collision");
                }
            }
        }

        this.ring = Collections.unmodifiableNavigableMap(positions);
        this.members = List.copyOf(sortedNodes);
        this.virtualNodesPerNode = virtualNodesPerNode;
    }

    public static ConsistentHashRing create(List<ClusterNode> nodes, int virtualNodesPerNode) {
        return new ConsistentHashRing(nodes, virtualNodesPerNode);
    }

    public ClusterNode owner(String key) {
        return nodesFor(key, 1).get(0);
    }

    public List<ClusterNode> nodesFor(String key, int nodeCount) {
        Objects.requireNonNull(key, "key must not be null");
        if (nodeCount < 1) {
            throw new IllegalArgumentException("Requested node count must be greater than zero");
        }
        if (nodeCount > members.size()) {
            throw new IllegalArgumentException("Requested node count must not exceed physical cluster size");
        }

        long keyPosition = hash(key);
        var currentEntry = ring.ceilingEntry(keyPosition);
        if (currentEntry == null) {
            currentEntry = ring.firstEntry();
        }

        List<ClusterNode> selectedNodes = new ArrayList<>(nodeCount);
        Set<String> selectedNodeIds = new HashSet<>();
        for (int visitedPositions = 0; visitedPositions < ring.size(); visitedPositions++) {
            ClusterNode candidate = currentEntry.getValue();
            if (selectedNodeIds.add(candidate.id())) {
                selectedNodes.add(candidate);
                if (selectedNodes.size() == nodeCount) {
                    return List.copyOf(selectedNodes);
                }
            }

            currentEntry = ring.higherEntry(currentEntry.getKey());
            if (currentEntry == null) {
                currentEntry = ring.firstEntry();
            }
        }

        throw new IllegalStateException("Consistent hash ring did not contain enough distinct physical nodes");
    }

    public List<ClusterNode> members() {
        return members;
    }

    public int virtualNodesPerNode() {
        return virtualNodesPerNode;
    }

    public int ringPositionCount() {
        return ring.size();
    }

    private static void validate(List<ClusterNode> nodes, int virtualNodesPerNode) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("Consistent hash ring requires at least one node");
        }
        if (virtualNodesPerNode < 1) {
            throw new IllegalArgumentException("Virtual-node count must be greater than zero");
        }

        Set<String> nodeIds = new HashSet<>();
        for (ClusterNode node : nodes) {
            Objects.requireNonNull(node, "Cluster nodes must not contain null entries");
            if (!nodeIds.add(node.id())) {
                throw new IllegalArgumentException("Duplicate cluster node ID: " + node.id());
            }
        }
    }

    private static long hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
