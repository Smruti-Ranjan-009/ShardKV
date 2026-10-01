package com.shardkv.routing;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.QuorumReadService;
import com.shardkv.replication.PrimaryReplicationService;
import com.shardkv.observability.ShardKvMetrics;
import org.springframework.stereotype.Service;

@Service
public class KeyRouter {

    private final ClusterMembership membership;
    private final ConsistentHashRing hashRing;
    private final PrimaryReplicationService replicationService;
    private final QuorumReadService quorumReadService;
    private final NodeClient nodeClient;
    private final ShardKvMetrics metrics;

    public KeyRouter(
            ClusterMembership membership,
            ConsistentHashRing hashRing,
            PrimaryReplicationService replicationService,
            QuorumReadService quorumReadService,
            NodeClient nodeClient,
            ShardKvMetrics metrics) {
        this.membership = membership;
        this.hashRing = hashRing;
        this.replicationService = replicationService;
        this.quorumReadService = quorumReadService;
        this.nodeClient = nodeClient;
        this.metrics = metrics;
    }

    public void put(String key, String value, ConsistencyLevel consistencyLevel) {
        observe("put", consistencyLevel, () -> {
            ClusterNode primary = hashRing.owner(key);
            if (isLocal(primary)) {
                replicationService.put(key, value, consistencyLevel);
            } else {
                nodeClient.putPrimary(primary, key, value, consistencyLevel);
            }
            return null;
        });
    }

    public String get(String key, ConsistencyLevel consistencyLevel) {
        return observe("get", consistencyLevel, () -> quorumReadService.get(key, consistencyLevel));
    }

    public void delete(String key, ConsistencyLevel consistencyLevel) {
        observe("delete", consistencyLevel, () -> {
            ClusterNode primary = hashRing.owner(key);
            if (isLocal(primary)) {
                replicationService.delete(key, consistencyLevel);
            } else {
                nodeClient.deletePrimary(primary, key, consistencyLevel);
            }
            return null;
        });
    }

    private <T> T observe(
            String operation,
            ConsistencyLevel consistencyLevel,
            java.util.function.Supplier<T> action) {
        try {
            T result = action.get();
            metrics.consistencyOperation(operation, consistencyLevel, true);
            return result;
        } catch (RuntimeException exception) {
            metrics.consistencyOperation(operation, consistencyLevel, false);
            throw exception;
        }
    }

    private boolean isLocal(ClusterNode owner) {
        return owner.id().equals(membership.localNode().id());
    }
}
