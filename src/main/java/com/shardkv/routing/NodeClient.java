package com.shardkv.routing;

import com.shardkv.cluster.ClusterNode;
import java.util.Optional;

public interface NodeClient {

    void put(ClusterNode node, String key, String value);

    Optional<String> get(ClusterNode node, String key);

    void delete(ClusterNode node, String key);
}
