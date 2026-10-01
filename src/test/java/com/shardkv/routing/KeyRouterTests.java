package com.shardkv.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.QuorumReadService;
import com.shardkv.replication.PrimaryReplicationService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeyRouterTests {

    private ClusterMembership membership;
    private ConsistentHashRing ring;
    private PrimaryReplicationService replicationService;
    private QuorumReadService readService;
    private NodeClient nodeClient;
    private KeyRouter router;

    @BeforeEach
    void setUp() {
        ClusterProperties properties = new ClusterProperties(
                "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083",
                128,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        membership = new ClusterMembership(new NodeProperties("node-1", "localhost", 8081), properties);
        ring = new ConsistentHashRing(membership, properties);
        replicationService = mock(PrimaryReplicationService.class);
        readService = mock(QuorumReadService.class);
        nodeClient = mock(NodeClient.class);
        router = new KeyRouter(membership, ring, replicationService, readService, nodeClient);
    }

    @Test
    void locallyPrimaryPutDelegatesToReplicationCoordinatorWithConsistency() {
        String key = keyOwnedBy("node-1");

        router.put(key, "value", ConsistencyLevel.QUORUM);

        verify(replicationService).put(key, "value", ConsistencyLevel.QUORUM);
        verify(nodeClient, never()).putPrimary(any(), anyString(), anyString(), any());
        verify(nodeClient, never()).putReplica(any(), anyString(), any());
    }

    @Test
    void nonPrimaryEntryRoutesToPrimaryExactlyOnceAndDoesNotReplicate() {
        String key = keyOwnedBy("node-2");
        ClusterNode primary = ring.owner(key);

        router.put(key, "value", ConsistencyLevel.ALL);

        verify(nodeClient, times(1)).putPrimary(primary, key, "value", ConsistencyLevel.ALL);
        verify(nodeClient, never()).putReplica(any(), anyString(), any());
        verify(replicationService, never()).put(key, "value", ConsistencyLevel.ALL);
    }

    @Test
    void getDelegatesToQuorumReadCoordinator() {
        when(readService.get("key", ConsistencyLevel.ONE)).thenReturn("value");

        assertThat(router.get("key", ConsistencyLevel.ONE)).isEqualTo("value");

        verify(readService).get("key", ConsistencyLevel.ONE);
    }

    @Test
    void deleteRoutesToLocalCoordinatorOrRemotePrimary() {
        String localKey = keyOwnedBy("node-1");
        String remoteKey = keyOwnedBy("node-2");
        ClusterNode remotePrimary = ring.owner(remoteKey);

        router.delete(localKey, ConsistencyLevel.QUORUM);
        router.delete(remoteKey, ConsistencyLevel.ALL);

        verify(replicationService).delete(localKey, ConsistencyLevel.QUORUM);
        verify(nodeClient).deletePrimary(remotePrimary, remoteKey, ConsistencyLevel.ALL);
    }

    @Test
    void primaryForwardingFailurePropagatesWithoutLocalReplication() {
        String key = keyOwnedBy("node-2");
        ClusterNode primary = ring.owner(key);
        NodeCommunicationException failure = new NodeCommunicationException(primary.id(), "coordinate primary write");
        doThrow(failure).when(nodeClient)
                .putPrimary(primary, key, "value", ConsistencyLevel.QUORUM);

        assertThatThrownBy(() -> router.put(key, "value", ConsistencyLevel.QUORUM)).isSameAs(failure);
        verify(replicationService, never()).put(key, "value", ConsistencyLevel.QUORUM);
        verify(nodeClient, never()).putReplica(any(), anyString(), any());
    }

    @Test
    void unavailablePrimaryFailsPutAndDeleteAtEveryConsistencyLevel() {
        String key = keyOwnedBy("node-2");
        ClusterNode primary = ring.owner(key);

        for (ConsistencyLevel level : ConsistencyLevel.values()) {
            NodeCommunicationException putFailure = new NodeCommunicationException(
                    primary.id(), "coordinate primary write");
            doThrow(putFailure).when(nodeClient).putPrimary(primary, key, "value", level);
            assertThatThrownBy(() -> router.put(key, "value", level)).isSameAs(putFailure);

            NodeCommunicationException deleteFailure = new NodeCommunicationException(
                    primary.id(), "coordinate primary delete");
            doThrow(deleteFailure).when(nodeClient).deletePrimary(primary, key, level);
            assertThatThrownBy(() -> router.delete(key, level)).isSameAs(deleteFailure);
        }

        verify(replicationService, never()).put(anyString(), anyString(), any());
        verify(replicationService, never()).delete(anyString(), any());
    }

    private String keyOwnedBy(String nodeId) {
        for (int candidate = 0; candidate < 100_000; candidate++) {
            String key = "routing-key-" + candidate;
            if (ring.owner(key).id().equals(nodeId)) {
                return key;
            }
        }
        throw new AssertionError("Could not find a key owned by " + nodeId);
    }
}
