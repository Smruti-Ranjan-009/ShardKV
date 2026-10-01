package com.shardkv.api;

import com.shardkv.cluster.ClusterNode;
import java.util.List;

public record ClusterResponse(
        ClusterNode localNode,
        List<ClusterMemberResponse> members,
        int virtualNodesPerNode) {

    public ClusterResponse {
        members = List.copyOf(members);
    }
}
