package com.shardkv.consistency;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.common.KeyNotFoundException;
import com.shardkv.health.NodeHealthStatus;
import com.shardkv.health.NodeHealthTracker;
import com.shardkv.replication.ReplicaPlan;
import com.shardkv.replication.ReplicaPlanner;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StorageException;
import com.shardkv.storage.StoredRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
    private final NodeHealthTracker healthTracker;
    private final ReadRepairService readRepairService;

    public QuorumReadService(
            ClusterMembership membership,
            ReplicaPlanner replicaPlanner,
            ConsistencyPolicy consistencyPolicy,
            KeyValueService localKeyValueService,
            NodeClient nodeClient,
            NodeHealthTracker healthTracker,
            ReadRepairService readRepairService) {
        this.membership = membership;
        this.replicaPlanner = replicaPlanner;
        this.consistencyPolicy = consistencyPolicy;
        this.localKeyValueService = localKeyValueService;
        this.nodeClient = nodeClient;
        this.healthTracker = healthTracker;
        this.readRepairService = readRepairService;
    }

    public String get(String key, ConsistencyLevel level) {
        ReplicaPlan plan = replicaPlanner.planFor(key);
        int required = consistencyPolicy.requiredAcknowledgements(
                level,
                replicaPlanner.replicationFactor());

        List<ReplicaReadResult> successfulResponses = level == ConsistencyLevel.ONE
                ? readOne(key, plan)
                : readRequired(key, plan, required);

        requireResponses("read", required, successfulResponses.size());
        Optional<StoredRecord> newest = reconcile(key, successfulResponses);
        readRepairService.repair(key, newest, successfulResponses);
        return logicalValue(key, newest);
    }

    public RecoveryResult recover(String key) {
        ReplicaPlan plan = replicaPlanner.planFor(key);
        List<ReplicaReadResult> successfulResponses = new ArrayList<>();
        for (ClusterNode node : placement(plan)) {
            readFromNode(node, key).ifPresent(successfulResponses::add);
        }

        int required = consistencyPolicy.requiredAcknowledgements(
                ConsistencyLevel.QUORUM,
                replicaPlanner.replicationFactor());
        requireResponses("recovery", required, successfulResponses.size());

        Optional<StoredRecord> newest = reconcile(key, successfulResponses);
        RepairSummary repairs = readRepairService.repair(key, newest, successfulResponses);
        return new RecoveryResult(
                key,
                required,
                successfulResponses.size(),
                newest.map(StoredRecord::version).orElse(null),
                newest.map(StoredRecord::tombstone).orElse(null),
                repairs);
    }

    private List<ReplicaReadResult> readOne(String key, ReplicaPlan plan) {
        for (ClusterNode node : healthOrderedPlacement(plan)) {
            Optional<ReplicaReadResult> response = readFromNode(node, key);
            if (response.isPresent()) {
                return List.of(response.get());
            }
        }
        return List.of();
    }

    private List<ReplicaReadResult> readRequired(
            String key,
            ReplicaPlan plan,
            int required) {
        List<ClusterNode> ordered = healthOrderedPlacement(plan);
        List<ReplicaReadResult> successfulResponses = new ArrayList<>();

        for (ClusterNode node : ordered) {
            if (healthTracker.status(node) == NodeHealthStatus.UNHEALTHY
                    && successfulResponses.size() >= required) {
                continue;
            }
            readFromNode(node, key).ifPresent(successfulResponses::add);
        }
        return successfulResponses;
    }

    private Optional<ReplicaReadResult> readFromNode(ClusterNode node, String key) {
        try {
            Optional<StoredRecord> record = isLocal(node)
                    ? localKeyValueService.getRecord(key)
                    : nodeClient.getRecord(node, key);
            return Optional.of(new ReplicaReadResult(node, record));
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
            List<ReplicaReadResult> successfulResponses) {
        StoredRecord newest = null;
        for (ReplicaReadResult response : successfulResponses) {
            if (response.record().isEmpty()) {
                continue;
            }
            StoredRecord candidate = response.record().get();
            if (newest == null || candidate.version() > newest.version()) {
                newest = candidate;
            } else if (candidate.version() == newest.version() && !candidate.equals(newest)) {
                throw new RecordConflictException(key, candidate.version());
            }
        }
        return Optional.ofNullable(newest);
    }

    private List<ClusterNode> healthOrderedPlacement(ReplicaPlan plan) {
        List<ClusterNode> ordered = new ArrayList<>(placement(plan));
        ordered.sort(Comparator.comparingInt(node -> healthRank(healthTracker.status(node))));
        return ordered;
    }

    private List<ClusterNode> placement(ReplicaPlan plan) {
        return Stream.concat(Stream.of(plan.primary()), plan.replicas().stream()).toList();
    }

    private int healthRank(NodeHealthStatus status) {
        return switch (status) {
            case HEALTHY -> 0;
            case SUSPECT -> 1;
            case UNHEALTHY -> 2;
        };
    }

    private void requireResponses(String operation, int required, int successful) {
        if (successful < required) {
            throw new ConsistencyUnavailableException(operation, required, successful);
        }
    }

    private String logicalValue(String key, Optional<StoredRecord> newest) {
        if (newest.isEmpty() || newest.get().tombstone()) {
            throw new KeyNotFoundException(key);
        }
        return newest.get().value();
    }

    private boolean isLocal(ClusterNode node) {
        return node.id().equals(membership.localNode().id());
    }
}
