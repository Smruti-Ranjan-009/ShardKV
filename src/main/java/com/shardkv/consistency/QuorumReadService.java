package com.shardkv.consistency;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.common.KeyNotFoundException;
import com.shardkv.replication.ReplicaPlan;
import com.shardkv.replication.ReplicaPlanner;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StorageException;
import com.shardkv.storage.StoredRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QuorumReadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuorumReadService.class);

    private final ClusterMembership membership;
    private final ReplicaPlanner replicaPlanner;
    private final ConsistencyPolicy consistencyPolicy;
    private final KeyValueService localKeyValueService;
    private final NodeClient nodeClient;

    public QuorumReadService(
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

    public String get(String key, ConsistencyLevel level) {
        ReplicaPlan plan = replicaPlanner.planFor(key);
        List<Optional<StoredRecord>> successfulResponses = new ArrayList<>();

        readFromNode(plan.primary(), key).ifPresent(successfulResponses::add);
        for (ClusterNode replica : plan.replicas()) {
            readFromNode(replica, key).ifPresent(successfulResponses::add);
        }

        int required = consistencyPolicy.requiredAcknowledgements(
                level,
                replicaPlanner.replicationFactor());
        if (successfulResponses.size() < required) {
            throw new ConsistencyUnavailableException("read", required, successfulResponses.size());
        }

        Optional<StoredRecord> newest = reconcile(key, successfulResponses);
        if (newest.isEmpty() || newest.get().tombstone()) {
            throw new KeyNotFoundException(key);
        }
        return newest.get().value();
    }

    private Optional<Optional<StoredRecord>> readFromNode(ClusterNode node, String key) {
        try {
            Optional<StoredRecord> record = isLocal(node)
                    ? localKeyValueService.getRecord(key)
                    : nodeClient.getRecord(node, key);
            return Optional.of(record);
        } catch (NodeCommunicationException | StorageException exception) {
            LOGGER.warn(
                    "Could not read key from assigned node {}: {}",
                    node.id(),
                    exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<StoredRecord> reconcile(
            String key,
            List<Optional<StoredRecord>> successfulResponses) {
        StoredRecord newest = null;
        for (Optional<StoredRecord> response : successfulResponses) {
            if (response.isEmpty()) {
                continue;
            }
            StoredRecord candidate = response.get();
            if (newest == null || candidate.version() > newest.version()) {
                newest = candidate;
            } else if (candidate.version() == newest.version() && !candidate.equals(newest)) {
                throw new RecordConflictException(key, candidate.version());
            }
        }
        return Optional.ofNullable(newest);
    }

    private boolean isLocal(ClusterNode node) {
        return node.id().equals(membership.localNode().id());
    }
}
