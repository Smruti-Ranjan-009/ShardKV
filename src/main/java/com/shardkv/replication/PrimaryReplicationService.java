package com.shardkv.replication;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.routing.NodeClient;
import com.shardkv.service.KeyValueService;
import org.springframework.stereotype.Service;

@Service
public class PrimaryReplicationService {

    private final ClusterMembership membership;
    private final ReplicaPlanner replicaPlanner;
    private final KeyValueService localKeyValueService;
    private final NodeClient nodeClient;

    public PrimaryReplicationService(
            ClusterMembership membership,
            ReplicaPlanner replicaPlanner,
            KeyValueService localKeyValueService,
            NodeClient nodeClient) {
        this.membership = membership;
        this.replicaPlanner = replicaPlanner;
        this.localKeyValueService = localKeyValueService;
        this.nodeClient = nodeClient;
    }

    public void put(String key, String value) {
        ReplicaPlan plan = requireLocalPrimary(key);
        localKeyValueService.put(key, value);
        for (ClusterNode replica : plan.replicas()) {
            nodeClient.putReplica(replica, key, value);
        }
    }

    public void delete(String key) {
        ReplicaPlan plan = requireLocalPrimary(key);
        localKeyValueService.delete(key);
        for (ClusterNode replica : plan.replicas()) {
            nodeClient.deleteReplica(replica, key);
        }
    }

    private ReplicaPlan requireLocalPrimary(String key) {
        ReplicaPlan plan = replicaPlanner.planFor(key);
        if (!plan.primary().id().equals(membership.localNode().id())) {
            throw new IllegalStateException("Replication coordination must execute on the primary node");
        }
        return plan;
    }
}
