package com.shardkv.routing;

import com.shardkv.cluster.ClusterNode;
import java.util.Optional;

public interface NodeClient {

    void putPrimary(ClusterNode node, String key, String value);

    Optional<String> getPrimary(ClusterNode node, String key);

    void deletePrimary(ClusterNode node, String key);

    void putReplica(ClusterNode node, String key, String value);

    void deleteReplica(ClusterNode node, String key);
}
