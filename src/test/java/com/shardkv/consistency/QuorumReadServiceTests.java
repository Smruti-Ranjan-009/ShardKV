package com.shardkv.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.common.KeyNotFoundException;
import com.shardkv.health.FailureDetectionProperties;
import com.shardkv.health.NodeHealthStatus;
import com.shardkv.health.NodeHealthTracker;
import com.shardkv.replication.ReplicaPlan;
import com.shardkv.replication.ReplicaPlanner;
import com.shardkv.replication.ReplicationProperties;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StorageException;
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
    private NodeHealthTracker healthTracker;
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
        healthTracker = new NodeHealthTracker(
                membership,
                new FailureDetectionProperties(Duration.ofSeconds(2), 3, 2));
        ReadRepairService repairService = new ReadRepairService(
                membership,
                localService,
                nodeClient);
        readService = new QuorumReadService(
                membership,
                planner,
                new ConsistencyPolicy(new ConsistencyProperties(ConsistencyLevel.QUORUM)),
                localService,
                nodeClient,
                healthTracker,
                repairService);
    }

    @Test
    void quorumSelectsHighestVersionAndRepairsStaleLiveReplica() {
        ReplicaPlan plan = planner.planFor(KEY);
        StoredRecord newest = StoredRecord.live("new", 5);
        ClusterNode staleNode = plan.replicas().get(0);
        respond(plan.primary(), KEY, Optional.of(newest));
        respond(staleNode, KEY, Optional.of(StoredRecord.live("old", 4)));
        respond(plan.replicas().get(1), KEY, Optional.of(newest));

        assertThat(readService.get(KEY, ConsistencyLevel.QUORUM)).isEqualTo("new");

        verifyRepair(staleNode, KEY, newest);
    }

    @Test
    void newerTombstoneWinsAndRepairsStaleLiveReplicaBeforeReturningNotFound() {
        ReplicaPlan plan = planner.planFor(KEY);
        StoredRecord tombstone = StoredRecord.tombstone(8);
        ClusterNode staleNode = plan.replicas().get(0);
        respond(plan.primary(), KEY, Optional.of(tombstone));
        respond(staleNode, KEY, Optional.of(StoredRecord.live("stale", 7)));
        respond(plan.replicas().get(1), KEY, Optional.of(tombstone));

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.QUORUM))
                .isInstanceOf(KeyNotFoundException.class);

        verifyRepair(staleNode, KEY, tombstone);
    }

    @Test
    void missingRecordCountsAsSuccessAndIsRepairedFromAuthoritativeVersion() {
        ReplicaPlan plan = planner.planFor(KEY);
        ClusterNode missingNode = plan.primary();
        StoredRecord newest = StoredRecord.live("found", 3);
        respond(missingNode, KEY, Optional.empty());
        respond(plan.replicas().get(0), KEY, Optional.of(newest));
        respond(plan.replicas().get(1), KEY, Optional.of(newest));

        assertThat(readService.get(KEY, ConsistencyLevel.QUORUM)).isEqualTo("found");

        verifyRepair(missingNode, KEY, newest);
    }

    @Test
    void oneSkipsUnhealthyPrimaryAndReadsAHealthyReplica() {
        String key = keyPrimaryOn("node-2");
        ReplicaPlan plan = planner.planFor(key);
        markUnhealthy(plan.primary());
        respond(plan.replicas().get(0), key, Optional.of(StoredRecord.live("replica", 3)));

        assertThat(readService.get(key, ConsistencyLevel.ONE)).isEqualTo("replica");

        verify(nodeClient, never()).getRecord(plan.primary(), key);
    }

    @Test
    void oneFallsBackWhenApparentlyHealthyPrimaryActuallyFails() {
        ReplicaPlan plan = planner.planFor(KEY);
        fail(plan.primary(), KEY);
        respond(plan.replicas().get(0), KEY, Optional.of(StoredRecord.live("replica-value", 3)));

        assertThat(readService.get(KEY, ConsistencyLevel.ONE)).isEqualTo("replica-value");
    }

    @Test
    void quorumSucceedsFromTwoReplicasWhenPrimaryIsUnavailable() {
        ReplicaPlan plan = planner.planFor(KEY);
        fail(plan.primary(), KEY);
        respond(plan.replicas().get(0), KEY, Optional.of(StoredRecord.live("old", 4)));
        respond(plan.replicas().get(1), KEY, Optional.of(StoredRecord.live("new", 5)));

        assertThat(readService.get(KEY, ConsistencyLevel.QUORUM)).isEqualTo("new");
    }

    @Test
    void allFailsWhenOneAssignedNodeIsUnavailable() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), KEY, Optional.of(StoredRecord.live("value", 5)));
        respond(plan.replicas().get(0), KEY, Optional.of(StoredRecord.live("value", 5)));
        fail(plan.replicas().get(1), KEY);

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.ALL))
                .isInstanceOf(ConsistencyUnavailableException.class);
    }

    @Test
    void quorumFailsWithOnlyOneSuccessfulResponse() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), KEY, Optional.of(StoredRecord.live("value", 5)));
        fail(plan.replicas().get(0), KEY);
        fail(plan.replicas().get(1), KEY);

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.QUORUM))
                .isInstanceOf(ConsistencyUnavailableException.class);
    }

    @Test
    void failedBestEffortRepairDoesNotInvalidateSuccessfulQuorumRead() {
        ReplicaPlan plan = planner.planFor(KEY);
        ClusterNode staleRemote = remoteNode(plan);
        StoredRecord newest = StoredRecord.live("new", 5);
        for (ClusterNode node : placement(plan)) {
            StoredRecord record = node.equals(staleRemote)
                    ? StoredRecord.live("old", 4)
                    : newest;
            respond(node, KEY, Optional.of(record));
        }
        doThrow(new NodeCommunicationException(staleRemote.id(), "repair record"))
                .when(nodeClient)
                .putReplica(staleRemote, KEY, newest);

        assertThat(readService.get(KEY, ConsistencyLevel.QUORUM)).isEqualTo("new");
    }

    @Test
    void contradictoryContentsAtHighestVersionFailClearly() {
        ReplicaPlan plan = planner.planFor(KEY);
        respond(plan.primary(), KEY, Optional.of(StoredRecord.live("first", 5)));
        respond(plan.replicas().get(0), KEY, Optional.of(StoredRecord.live("second", 5)));
        respond(plan.replicas().get(1), KEY, Optional.empty());

        assertThatThrownBy(() -> readService.get(KEY, ConsistencyLevel.QUORUM))
                .isInstanceOf(RecordConflictException.class);
    }

    @Test
    void recoveryQueriesEvenUnhealthyAssignedNodesAndRepairsMissingCopy() {
        ReplicaPlan plan = planner.planFor(KEY);
        ClusterNode missingRemote = remoteNode(plan);
        StoredRecord newest = StoredRecord.live("recovered", 6);
        markUnhealthy(missingRemote);
        for (ClusterNode node : placement(plan)) {
            respond(node, KEY, node.equals(missingRemote) ? Optional.empty() : Optional.of(newest));
        }

        RecoveryResult result = readService.recover(KEY);

        assertThat(result.successfulResponses()).isEqualTo(3);
        assertThat(result.requiredResponses()).isEqualTo(2);
        assertThat(result.authoritativeVersion()).isEqualTo(6);
        assertThat(result.repairs()).isEqualTo(new RepairSummary(1, 1, 0));
        verify(nodeClient).getRecord(missingRemote, KEY);
        verify(nodeClient).putReplica(missingRemote, KEY, newest);
        verify(nodeClient, never()).putPrimary(any(), anyString(), anyString(), any());
        verify(nodeClient, never()).deletePrimary(any(), anyString(), any());
    }

    private void respond(ClusterNode node, String key, Optional<StoredRecord> response) {
        if (isLocal(node)) {
            when(localService.getRecord(key)).thenReturn(response);
        } else {
            when(nodeClient.getRecord(node, key)).thenReturn(response);
        }
    }

    private void fail(ClusterNode node, String key) {
        if (isLocal(node)) {
            when(localService.getRecord(key)).thenThrow(new StorageException(
                    "local read failed", new RuntimeException()));
        } else {
            when(nodeClient.getRecord(node, key))
                    .thenThrow(new NodeCommunicationException(node.id(), "read stored record"));
        }
    }

    private void verifyRepair(ClusterNode node, String key, StoredRecord record) {
        if (isLocal(node)) {
            verify(localService).applyReplicaRecord(key, record);
        } else {
            verify(nodeClient).putReplica(node, key, record);
        }
    }

    private void markUnhealthy(ClusterNode node) {
        healthTracker.recordFailure(node);
        healthTracker.recordFailure(node);
        healthTracker.recordFailure(node);
        assertThat(healthTracker.status(node)).isEqualTo(NodeHealthStatus.UNHEALTHY);
    }

    private ClusterNode remoteNode(ReplicaPlan plan) {
        return placement(plan).stream()
                .filter(node -> !isLocal(node))
                .findFirst()
                .orElseThrow();
    }

    private java.util.List<ClusterNode> placement(ReplicaPlan plan) {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(plan.primary()),
                        plan.replicas().stream())
                .toList();
    }

    private boolean isLocal(ClusterNode node) {
        return node.id().equals(membership.localNode().id());
    }

    private String keyPrimaryOn(String nodeId) {
        for (int candidate = 0; candidate < 100_000; candidate++) {
            String key = "health-aware-key-" + candidate;
            if (planner.planFor(key).primary().id().equals(nodeId)) {
                return key;
            }
        }
        throw new AssertionError("Could not find key primary on " + nodeId);
    }
}
