package com.shardkv.routing;

import com.shardkv.cluster.ClusterNode;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.storage.StoredRecord;
import com.shardkv.document.DocumentResult;
import com.shardkv.query.QueryRequest;
import java.util.List;
import java.util.Optional;

public interface NodeClient {

    void heartbeat(ClusterNode node);

    void putPrimary(ClusterNode node, String key, String value, ConsistencyLevel consistencyLevel);

    void deletePrimary(ClusterNode node, String key, ConsistencyLevel consistencyLevel);

    void putReplica(ClusterNode node, String key, StoredRecord record);

    Optional<StoredRecord> getRecord(ClusterNode node, String key);

    List<DocumentResult> queryLocal(ClusterNode node, QueryRequest request, int limit);
}
