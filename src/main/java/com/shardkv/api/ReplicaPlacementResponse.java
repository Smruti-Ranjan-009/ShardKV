package com.shardkv.api;

import com.shardkv.cluster.ClusterNode;
import java.util.List;

public record ReplicaPlacementResponse(
        String key,
        int replicationFactor,
        ClusterNode primary,
        List<ClusterNode> replicas) {

    public ReplicaPlacementResponse {
        replicas = List.copyOf(replicas);
    }
}
