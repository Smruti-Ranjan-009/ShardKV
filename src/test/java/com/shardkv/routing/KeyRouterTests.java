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
import com.shardkv.replication.PrimaryReplicationService;
import com.shardkv.service.KeyValueService;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeyRouterTests {

    private ClusterMembership membership;
    private ConsistentHashRing ring;
    private KeyValueService localService;
    private PrimaryReplicationService replicationService;
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
        localService = mock(KeyValueService.class);
        replicationService = mock(PrimaryReplicationService.class);
        nodeClient = mock(NodeClient.class);
        router = new KeyRouter(membership, ring, localService, replicationService, nodeClient);
    }

    @Test
    void locallyPrimaryPutDelegatesToReplicationCoordinator() {
        String key = keyOwnedBy("node-1");

        router.put(key, "value");

        verify(replicationService).put(key, "value");
        verify(nodeClient, never()).putPrimary(any(), anyString(), anyString());
        verify(nodeClient, never()).putReplica(any(), anyString(), anyString());
    }

    @Test
    void nonPrimaryEntryRoutesToPrimaryExactlyOnceAndDoesNotReplicate() {
        String key = keyOwnedBy("node-2");
        ClusterNode primary = ring.owner(key);

        router.put(key, "value");

        verify(nodeClient, times(1)).putPrimary(primary, key, "value");
        verify(nodeClient, never()).putReplica(any(), anyString(), anyString());
        verify(replicationService, never()).put(key, "value");
        verify(localService, never()).put(key, "value");
    }

    @Test
    void locallyPrimaryGetReadsLocalStorageWithoutReplicaFallback() {
        String key = keyOwnedBy("node-1");
        when(localService.get(key)).thenReturn("local-value");

        assertThat(router.get(key)).isEqualTo("local-value");
        verify(nodeClient, never()).getPrimary(any(), anyString());
    }

    @Test
    void remoteGetUsesPrimaryOnly() {
        String key = keyOwnedBy("node-3");
        ClusterNode primary = ring.owner(key);
        when(nodeClient.getPrimary(primary, key)).thenReturn(Optional.of("remote-value"));

        assertThat(router.get(key)).isEqualTo("remote-value");
        verify(localService, never()).get(key);
        verify(nodeClient).getPrimary(primary, key);
    }

    @Test
    void deleteRoutesToLocalCoordinatorOrRemotePrimary() {
        String localKey = keyOwnedBy("node-1");
        String remoteKey = keyOwnedBy("node-2");
        ClusterNode remotePrimary = ring.owner(remoteKey);

        router.delete(localKey);
        router.delete(remoteKey);

        verify(replicationService).delete(localKey);
        verify(nodeClient).deletePrimary(remotePrimary, remoteKey);
        verify(nodeClient, never()).deleteReplica(any(), anyString());
        verify(localService, never()).delete(remoteKey);
    }

    @Test
    void primaryForwardingFailurePropagatesWithoutLocalWriteOrReplication() {
        String key = keyOwnedBy("node-2");
        ClusterNode primary = ring.owner(key);
        NodeCommunicationException failure = new NodeCommunicationException(primary.id(), "coordinate primary write");
        doThrow(failure).when(nodeClient).putPrimary(primary, key, "value");

        assertThatThrownBy(() -> router.put(key, "value")).isSameAs(failure);
        verify(localService, never()).put(key, "value");
        verify(replicationService, never()).put(key, "value");
        verify(nodeClient, never()).putReplica(any(), anyString(), anyString());
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
