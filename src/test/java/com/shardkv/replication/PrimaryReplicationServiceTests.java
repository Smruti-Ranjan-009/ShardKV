package com.shardkv.replication;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.ConsistencyPolicy;
import com.shardkv.consistency.ConsistencyProperties;
import com.shardkv.consistency.ConsistencyUnavailableException;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.StorageException;
import com.shardkv.storage.StoredRecord;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PrimaryReplicationServiceTests {

    private ClusterMembership membership;
    private ReplicaPlanner replicaPlanner;
    private KeyValueService localService;
    private NodeClient nodeClient;
    private PrimaryReplicationService replicationService;

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
        ConsistentHashRing ring = new ConsistentHashRing(membership, clusterProperties);
        replicaPlanner = new ReplicaPlanner(ring, new ReplicationProperties(3));
        localService = mock(KeyValueService.class);
        nodeClient = mock(NodeClient.class);
        replicationService = new PrimaryReplicationService(
                membership,
                replicaPlanner,
                new ConsistencyPolicy(new ConsistencyProperties(ConsistencyLevel.QUORUM)),
                localService,
                nodeClient);
    }

    @Test
    void primaryDurableWriteComesBeforeBroadReplicaAttempts() {
        String key = keyPrimaryOn("node-1");
        StoredRecord record = StoredRecord.live("value", 7);
        ReplicaPlan plan = replicaPlanner.planFor(key);
        when(localService.putNextVersion(key, "value")).thenReturn(record);
        InOrder orderedWrites = inOrder(localService, nodeClient);

        replicationService.put(key, "value", ConsistencyLevel.ALL);

        orderedWrites.verify(localService).putNextVersion(key, "value");
        for (ClusterNode replica : plan.replicas()) {
            orderedWrites.verify(nodeClient).putReplica(replica, key, record);
        }
        verify(nodeClient, never()).putPrimary(
                plan.primary(), key, "value", ConsistencyLevel.ALL);
    }

    @Test
    void primaryPlusOneReplicaSatisfiesOneAndQuorumButNotAll() {
        String key = keyPrimaryOn("node-1");
        StoredRecord record = StoredRecord.live("value", 1);
        ReplicaPlan plan = replicaPlanner.planFor(key);
        when(localService.putNextVersion(key, "value")).thenReturn(record);
        failReplica(plan.replicas().get(1), key, record);

        assertThatCode(() -> replicationService.put(key, "value", ConsistencyLevel.ONE))
                .doesNotThrowAnyException();
        assertThatCode(() -> replicationService.put(key, "value", ConsistencyLevel.QUORUM))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> replicationService.put(key, "value", ConsistencyLevel.ALL))
                .isInstanceOf(ConsistencyUnavailableException.class);
    }

    @Test
    void primaryOnlySatisfiesOneButNotQuorumOrAll() {
        String key = keyPrimaryOn("node-1");
        StoredRecord record = StoredRecord.live("value", 1);
        ReplicaPlan plan = replicaPlanner.planFor(key);
        when(localService.putNextVersion(key, "value")).thenReturn(record);
        for (ClusterNode replica : plan.replicas()) {
            failReplica(replica, key, record);
        }

        assertThatCode(() -> replicationService.put(key, "value", ConsistencyLevel.ONE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> replicationService.put(key, "value", ConsistencyLevel.QUORUM))
                .isInstanceOf(ConsistencyUnavailableException.class);
        assertThatThrownBy(() -> replicationService.put(key, "value", ConsistencyLevel.ALL))
                .isInstanceOf(ConsistencyUnavailableException.class);
    }

    @Test
    void primaryStorageFailureFailsEveryConsistencyLevel() {
        String key = keyPrimaryOn("node-1");
        StorageException failure = new StorageException("primary failed", new RuntimeException());
        when(localService.putNextVersion(key, "value")).thenThrow(failure);

        for (ConsistencyLevel level : ConsistencyLevel.values()) {
            assertThatThrownBy(() -> replicationService.put(key, "value", level)).isSameAs(failure);
        }
    }

    @Test
    void deleteReplicatesTheExactTombstoneAndUsesRequestedThreshold() {
        String key = keyPrimaryOn("node-1");
        StoredRecord tombstone = StoredRecord.tombstone(9);
        ReplicaPlan plan = replicaPlanner.planFor(key);
        when(localService.tombstoneNextVersion(key)).thenReturn(tombstone);
        failReplica(plan.replicas().get(1), key, tombstone);

        assertThatCode(() -> replicationService.delete(key, ConsistencyLevel.QUORUM))
                .doesNotThrowAnyException();
        for (ClusterNode replica : plan.replicas()) {
            verify(nodeClient).putReplica(replica, key, tombstone);
        }

        assertThatThrownBy(() -> replicationService.delete(key, ConsistencyLevel.ALL))
                .isInstanceOf(ConsistencyUnavailableException.class);
    }

    @Test
    void nonPrimaryNodeCannotCoordinateMutation() {
        String key = keyPrimaryOn("node-2");

        assertThatThrownBy(() -> replicationService.put(key, "value", ConsistencyLevel.ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary node");
        verify(localService, never()).putNextVersion(key, "value");
    }

    private void failReplica(ClusterNode replica, String key, StoredRecord record) {
        doThrow(new NodeCommunicationException(replica.id(), "write replica record"))
                .when(nodeClient)
                .putReplica(replica, key, record);
    }

    private String keyPrimaryOn(String nodeId) {
        for (int candidate = 0; candidate < 100_000; candidate++) {
            String key = "replication-key-" + candidate;
            if (replicaPlanner.planFor(key).primary().id().equals(nodeId)) {
                return key;
            }
        }
        throw new AssertionError("Could not find a key primary on " + nodeId);
    }
}
