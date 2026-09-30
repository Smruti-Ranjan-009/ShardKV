package com.shardkv.replication;

import com.shardkv.cluster.ClusterNode;
import java.util.List;

public record ReplicaPlan(ClusterNode primary, List<ClusterNode> replicas) {

    public ReplicaPlan {
        replicas = List.copyOf(replicas);
    }
}
