package com.shardkv.routing;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.common.KeyNotFoundException;
import com.shardkv.replication.PrimaryReplicationService;
import com.shardkv.service.KeyValueService;
import org.springframework.stereotype.Service;

@Service
public class KeyRouter {

    private final ClusterMembership membership;
    private final ConsistentHashRing hashRing;
    private final KeyValueService localKeyValueService;
    private final PrimaryReplicationService replicationService;
    private final NodeClient nodeClient;

    public KeyRouter(
            ClusterMembership membership,
            ConsistentHashRing hashRing,
            KeyValueService localKeyValueService,
            PrimaryReplicationService replicationService,
            NodeClient nodeClient) {
        this.membership = membership;
        this.hashRing = hashRing;
        this.localKeyValueService = localKeyValueService;
        this.replicationService = replicationService;
        this.nodeClient = nodeClient;
    }

    public void put(String key, String value) {
        ClusterNode owner = hashRing.owner(key);
        if (isLocal(owner)) {
            replicationService.put(key, value);
        } else {
            nodeClient.putPrimary(owner, key, value);
        }
    }

    public String get(String key) {
        ClusterNode owner = hashRing.owner(key);
        if (isLocal(owner)) {
            return localKeyValueService.get(key);
        }
        return nodeClient.getPrimary(owner, key).orElseThrow(() -> new KeyNotFoundException(key));
    }

    public void delete(String key) {
        ClusterNode owner = hashRing.owner(key);
        if (isLocal(owner)) {
            replicationService.delete(key);
        } else {
            nodeClient.deletePrimary(owner, key);
        }
    }

    private boolean isLocal(ClusterNode owner) {
        return owner.id().equals(membership.localNode().id());
    }
}
