package com.shardkv.replication;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.ConsistencyPolicy;
import com.shardkv.consistency.ConsistencyUnavailableException;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PrimaryReplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrimaryReplicationService.class);

    private final ClusterMembership membership;
    private final ReplicaPlanner replicaPlanner;
    private final ConsistencyPolicy consistencyPolicy;
    private final KeyValueService localKeyValueService;
    private final NodeClient nodeClient;

    public PrimaryReplicationService(
            ClusterMembership membership,
            ReplicaPlanner replicaPlanner,
            ConsistencyPolicy consistencyPolicy,
            KeyValueService localKeyValueService,
            NodeClient nodeClient) {
        this.membership = membership;
        this.replicaPlanner = replicaPlanner;
        this.consistencyPolicy = consistencyPolicy;
        this.localKeyValueService = localKeyValueService;
        this.nodeClient = nodeClient;
    }

    public StoredRecord put(String key, String value, ConsistencyLevel level) {
        ReplicaPlan plan = requireLocalPrimary(key);
        StoredRecord record = localKeyValueService.putNextVersion(key, value);
        requireWriteAcknowledgements(key, record, level, plan);
        return record;
    }

    public StoredRecord delete(String key, ConsistencyLevel level) {
        ReplicaPlan plan = requireLocalPrimary(key);
        StoredRecord tombstone = localKeyValueService.tombstoneNextVersion(key);
        requireWriteAcknowledgements(key, tombstone, level, plan);
        return tombstone;
    }

    private void requireWriteAcknowledgements(
            String key,
            StoredRecord record,
            ConsistencyLevel level,
            ReplicaPlan plan) {
        int successfulAcknowledgements = 1;
        for (ClusterNode replica : plan.replicas()) {
            try {
                nodeClient.putReplica(replica, key, record);
                successfulAcknowledgements++;
            } catch (NodeCommunicationException exception) {
                LOGGER.warn(
                        "Replica {} did not acknowledge key {}: {}",
                        replica.id(),
                        key,
                        exception.getMessage());
            }
        }

        int required = consistencyPolicy.requiredAcknowledgements(
                level,
                replicaPlanner.replicationFactor());
        if (successfulAcknowledgements < required) {
            throw new ConsistencyUnavailableException(
                    "write",
                    required,
                    successfulAcknowledgements);
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
