package com.shardkv.consistency;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StorageException;
import com.shardkv.storage.StoredRecord;
import com.shardkv.observability.ShardKvMetrics;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReadRepairService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadRepairService.class);

    private final ClusterMembership membership;
    private final KeyValueService localKeyValueService;
    private final NodeClient nodeClient;
    private final ShardKvMetrics metrics;
    private final LongAdder attemptedRepairs = new LongAdder();
    private final LongAdder successfulRepairs = new LongAdder();
    private final LongAdder failedRepairs = new LongAdder();

    public ReadRepairService(
            ClusterMembership membership,
            KeyValueService localKeyValueService,
            NodeClient nodeClient,
            ShardKvMetrics metrics) {
        this.membership = membership;
        this.localKeyValueService = localKeyValueService;
        this.nodeClient = nodeClient;
        this.metrics = metrics;
    }

    public RepairSummary repair(
            String key,
            Optional<StoredRecord> authoritative,
            List<ReplicaReadResult> responses) {
        if (authoritative.isEmpty()) {
            return RepairSummary.none();
        }

        StoredRecord record = authoritative.get();
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;

        for (ReplicaReadResult response : responses) {
            if (!needsRepair(response.record(), record)) {
                continue;
            }

            attempted++;
            attemptedRepairs.increment();
            metrics.readRepair("attempted");
            try {
                apply(response.node(), key, record);
                succeeded++;
                successfulRepairs.increment();
                metrics.readRepair("success");
                metrics.replicationOperation("repair", true);
            } catch (NodeCommunicationException | StorageException | RecordConflictException exception) {
                failed++;
                failedRepairs.increment();
                metrics.readRepair("failure");
                metrics.replicationOperation("repair", false);
                LOGGER.warn(
                        "Read repair for key {} on node {} failed: {}",
                        key,
                        response.node().id(),
                        exception.getMessage());
            }
        }

        return new RepairSummary(attempted, succeeded, failed);
    }

    public RepairStatistics statistics() {
        return new RepairStatistics(
                attemptedRepairs.sum(),
                successfulRepairs.sum(),
                failedRepairs.sum());
    }

    private boolean needsRepair(Optional<StoredRecord> current, StoredRecord authoritative) {
        return current.isEmpty() || current.get().version() < authoritative.version();
    }

    private void apply(ClusterNode node, String key, StoredRecord record) {
        if (node.id().equals(membership.localNode().id())) {
            localKeyValueService.applyReplicaRecord(key, record);
        } else {
            nodeClient.putReplica(node, key, record);
        }
    }

    public record RepairStatistics(long attempted, long succeeded, long failed) {
    }
}
