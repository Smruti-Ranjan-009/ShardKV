package com.shardkv.replication;

import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ConsistentHashRing;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReplicaPlanner {

    private final ConsistentHashRing hashRing;
    private final int replicationFactor;

    public ReplicaPlanner(ConsistentHashRing hashRing, ReplicationProperties properties) {
        if (properties.factor() > hashRing.members().size()) {
            throw new IllegalArgumentException(
                    "shardkv.replication.factor must not exceed the number of physical cluster nodes");
        }
        this.hashRing = hashRing;
        this.replicationFactor = properties.factor();
    }

    public ReplicaPlan planFor(String key) {
        List<ClusterNode> placement = hashRing.nodesFor(key, replicationFactor);
        return new ReplicaPlan(placement.get(0), placement.subList(1, placement.size()));
    }

    public int replicationFactor() {
        return replicationFactor;
    }
}
