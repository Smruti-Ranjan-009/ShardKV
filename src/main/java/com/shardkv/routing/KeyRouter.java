package com.shardkv.routing;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.common.KeyNotFoundException;
import com.shardkv.service.KeyValueService;
import org.springframework.stereotype.Service;

@Service
public class KeyRouter {

    private final ClusterMembership membership;
    private final ConsistentHashRing hashRing;
    private final KeyValueService localKeyValueService;
    private final NodeClient nodeClient;

    public KeyRouter(
            ClusterMembership membership,
            ConsistentHashRing hashRing,
            KeyValueService localKeyValueService,
            NodeClient nodeClient) {
        this.membership = membership;
        this.hashRing = hashRing;
        this.localKeyValueService = localKeyValueService;
        this.nodeClient = nodeClient;
    }

    public void put(String key, String value) {
        ClusterNode owner = hashRing.owner(key);
        if (isLocal(owner)) {
            localKeyValueService.put(key, value);
        } else {
            nodeClient.put(owner, key, value);
        }
    }

    public String get(String key) {
        ClusterNode owner = hashRing.owner(key);
        if (isLocal(owner)) {
            return localKeyValueService.get(key);
        }
        return nodeClient.get(owner, key).orElseThrow(() -> new KeyNotFoundException(key));
    }

    public void delete(String key) {
        ClusterNode owner = hashRing.owner(key);
        if (isLocal(owner)) {
            localKeyValueService.delete(key);
        } else {
            nodeClient.delete(owner, key);
        }
    }

    private boolean isLocal(ClusterNode owner) {
        return owner.id().equals(membership.localNode().id());
    }
}
