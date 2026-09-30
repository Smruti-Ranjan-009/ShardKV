package com.shardkv.routing;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.QuorumReadService;
import com.shardkv.replication.PrimaryReplicationService;
import org.springframework.stereotype.Service;

@Service
public class KeyRouter {

    private final ClusterMembership membership;
    private final ConsistentHashRing hashRing;
    private final PrimaryReplicationService replicationService;
    private final QuorumReadService quorumReadService;
    private final NodeClient nodeClient;

    public KeyRouter(
            ClusterMembership membership,
            ConsistentHashRing hashRing,
            PrimaryReplicationService replicationService,
            QuorumReadService quorumReadService,
            NodeClient nodeClient) {
        this.membership = membership;
        this.hashRing = hashRing;
        this.replicationService = replicationService;
        this.quorumReadService = quorumReadService;
        this.nodeClient = nodeClient;
    }

    public void put(String key, String value, ConsistencyLevel consistencyLevel) {
        ClusterNode primary = hashRing.owner(key);
        if (isLocal(primary)) {
            replicationService.put(key, value, consistencyLevel);
        } else {
            nodeClient.putPrimary(primary, key, value, consistencyLevel);
        }
    }

    public String get(String key, ConsistencyLevel consistencyLevel) {
        return quorumReadService.get(key, consistencyLevel);
    }

    public void delete(String key, ConsistencyLevel consistencyLevel) {
        ClusterNode primary = hashRing.owner(key);
        if (isLocal(primary)) {
            replicationService.delete(key, consistencyLevel);
        } else {
            nodeClient.deletePrimary(primary, key, consistencyLevel);
        }
    }

    private boolean isLocal(ClusterNode owner) {
        return owner.id().equals(membership.localNode().id());
    }
}
