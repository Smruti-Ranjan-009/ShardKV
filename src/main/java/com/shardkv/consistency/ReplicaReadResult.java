package com.shardkv.consistency;

import com.shardkv.cluster.ClusterNode;
import com.shardkv.storage.StoredRecord;
import java.util.Optional;

public record ReplicaReadResult(ClusterNode node, Optional<StoredRecord> record) {

    public ReplicaReadResult {
        if (node == null) {
            throw new IllegalArgumentException("Read-result node must not be null");
        }
        if (record == null) {
            throw new IllegalArgumentException("Read-result record must not be null");
        }
    }
}
