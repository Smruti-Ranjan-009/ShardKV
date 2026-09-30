package com.shardkv.api;

import com.shardkv.cluster.ClusterNode;
import java.util.List;

public record ClusterResponse(
        ClusterNode localNode,
        List<ClusterNode> members,
        int virtualNodesPerNode) {
}
