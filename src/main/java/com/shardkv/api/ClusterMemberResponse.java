package com.shardkv.api;

import com.shardkv.cluster.ClusterNode;
import com.shardkv.health.NodeHealthStatus;

public record ClusterMemberResponse(
        String id,
        String host,
        int port,
        NodeHealthStatus status) {

    public static ClusterMemberResponse from(ClusterNode node, NodeHealthStatus status) {
        return new ClusterMemberResponse(node.id(), node.host(), node.port(), status);
    }
}
