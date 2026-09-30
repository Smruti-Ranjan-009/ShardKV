package com.shardkv.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.common.KeyNotFoundException;
import com.shardkv.replication.ReplicaPlan;
import com.shardkv.replication.ReplicaPlanner;
import com.shardkv.replication.ReplicationProperties;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StoredRecord;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuorumReadServiceTests {

    private static final String KEY = "quorum-key";

    private ClusterMembership membership;
    private ReplicaPlanner planner;
    private KeyValueService localService;
    private NodeClient nodeClient;
    private QuorumReadService readService;

    @BeforeEach
    void setUp() {
        ClusterProperties clusterProperties = new ClusterProperties(
                "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083",
                128,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                clusterProperties);
        planner = new ReplicaPlanner(
                new ConsistentHashRing(membership, clusterProperties),
                new ReplicationProperties(3));
        localService = mock(KeyValueService.class);
        nodeClient = mock(NodeClient.class);
        readService = new QuorumReadService(
                membership,
                planner,
                new ConsistencyPolicy(new ConsistencyProperties(ConsistencyLevel.QUORUM)),
                localService,
                nodeClient);
    }

    @Test
    void quorumSelectsHighestVersionAcrossSuccessfulResponses() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), Optional.of(StoredRecord.live("new", 5)));
        respond(plan.replicas().get(0), Optional.of(StoredRecord.live("old", 4)));
        respond(plan.replicas().get(1), Optional.of(StoredRecord.live("new", 5)));

        assertThat(readService.get(KEY, ConsistencyLevel.QUORUM)).isEqualTo("new");
    }

    @Test
    void newerTombstoneWinsOverOlderLiveValue() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), Optional.of(StoredRecord.tombstone(8)));
        respond(plan.replicas().get(0), Optional.of(StoredRecord.live("stale", 7)));
        respond(plan.replicas().get(1), Optional.of(StoredRecord.tombstone(8)));

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.QUORUM))
                .isInstanceOf(KeyNotFoundException.class);
    }

    @Test
    void missingRecordCountsAsSuccessfulResponseAndIsOlderThanVersionedRecord() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), Optional.empty());
        respond(plan.replicas().get(0), Optional.of(StoredRecord.live("found", 5)));
        fail(plan.replicas().get(1));

        assertThat(readService.get(KEY, ConsistencyLevel.QUORUM)).isEqualTo("found");
    }

    @Test
    void quorumFailsWithOnlyOneSuccessfulResponse() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), Optional.of(StoredRecord.live("value", 5)));
        fail(plan.replicas().get(0));
        fail(plan.replicas().get(1));

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.QUORUM))
                .isInstanceOf(ConsistencyUnavailableException.class);
    }

    @Test
    void allFailsWhenOneAssignedNodeIsUnavailable() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), Optional.of(StoredRecord.live("value", 5)));
        respond(plan.replicas().get(0), Optional.of(StoredRecord.live("value", 5)));
        fail(plan.replicas().get(1));

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.ALL))
                .isInstanceOf(ConsistencyUnavailableException.class);
    }

    @Test
    void oneCanReadFromReplicaWhenPrimaryIsUnavailable() {
        ReplicaPlan plan = planner.planFor(KEY);
        fail(plan.primary());
        respond(plan.replicas().get(0), Optional.of(StoredRecord.live("replica-value", 3)));
        fail(plan.replicas().get(1));

        assertThat(readService.get(KEY, ConsistencyLevel.ONE)).isEqualTo("replica-value");
    }

    @Test
    void contradictoryContentsAtHighestVersionFailClearly() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), Optional.of(StoredRecord.live("first", 5)));
        respond(plan.replicas().get(0), Optional.of(StoredRecord.live("second", 5)));
        respond(plan.replicas().get(1), Optional.empty());

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.QUORUM))
                .isInstanceOf(RecordConflictException.class);
    }

    private void respond(ClusterNode node, Optional<StoredRecord> response) {
        if (node.id().equals(membership.localNode().id())) {
            when(localService.getRecord(KEY)).thenReturn(response);
        } else {
            when(nodeClient.getRecord(node, KEY)).thenReturn(response);
        }
    }

    private void fail(ClusterNode node) {
        if (node.id().equals(membership.localNode().id())) {
            when(localService.getRecord(KEY)).thenThrow(new com.shardkv.storage.StorageException(
                    "local read failed", new RuntimeException()));
        } else {
            when(nodeClient.getRecord(node, KEY))
                    .thenThrow(new NodeCommunicationException(node.id(), "read stored record"));
        }
    }
}
