package com.shardkv.replication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.service.KeyValueService;
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
                localService,
                nodeClient);
    }

    @Test
    void primaryPutWritesLocallyBeforeEveryReplica() {
        String key = keyPrimaryOnLocalNode();
        ReplicaPlan plan = replicaPlanner.planFor(key);
        InOrder orderedWrites = inOrder(localService, nodeClient);

        replicationService.put(key, "value");

        orderedWrites.verify(localService).put(key, "value");
        for (ClusterNode replica : plan.replicas()) {
            orderedWrites.verify(nodeClient).putReplica(replica, key, "value");
        }
        verify(nodeClient, never()).putPrimary(plan.primary(), key, "value");
    }

    @Test
    void replicaFailurePropagatesAfterPrimaryDurableWrite() {
        String key = keyPrimaryOnLocalNode();
        ClusterNode firstReplica = replicaPlanner.planFor(key).replicas().get(0);
        NodeCommunicationException failure = new NodeCommunicationException(firstReplica.id(), "write replica");
        org.mockito.Mockito.doThrow(failure)
                .when(nodeClient)
                .putReplica(firstReplica, key, "value");

        assertThatThrownBy(() -> replicationService.put(key, "value")).isSameAs(failure);
        verify(localService).put(key, "value");
    }

    @Test
    void primaryDeleteRunsLocallyBeforeDeletingEveryReplica() {
        String key = keyPrimaryOnLocalNode();
        ReplicaPlan plan = replicaPlanner.planFor(key);
        InOrder orderedDeletes = inOrder(localService, nodeClient);

        replicationService.delete(key);

        orderedDeletes.verify(localService).delete(key);
        for (ClusterNode replica : plan.replicas()) {
            orderedDeletes.verify(nodeClient).deleteReplica(replica, key);
        }
        verify(nodeClient, never()).deletePrimary(plan.primary(), key);
    }

    @Test
    void nonPrimaryNodeCannotCoordinateReplication() {
        String key = keyPrimaryOnAnotherNode();

        assertThatThrownBy(() -> replicationService.put(key, "value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary node");
        verify(localService, never()).put(key, "value");
    }

    private String keyPrimaryOnLocalNode() {
        return keyPrimaryOn("node-1");
    }

    private String keyPrimaryOnAnotherNode() {
        return keyPrimaryOn("node-2");
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
