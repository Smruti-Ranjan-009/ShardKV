package com.shardkv.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.shardkv.service.KeyValueService;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeyRouterTests {

    private ClusterMembership membership;
    private ConsistentHashRing ring;
    private KeyValueService localService;
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
        nodeClient = mock(NodeClient.class);
        router = new KeyRouter(membership, ring, localService, nodeClient);
    }

    @Test
    void locallyOwnedPutUsesLocalStorageService() {
        String key = keyOwnedBy("node-1");

        router.put(key, "value");

        verify(localService).put(key, "value");
        verify(nodeClient, never()).put(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void remotelyOwnedPutCallsOwnerExactlyOnceWithoutLocalFallback() {
        String key = keyOwnedBy("node-2");
        ClusterNode owner = ring.owner(key);

        router.put(key, "value");

        verify(nodeClient, times(1)).put(owner, key, "value");
        verify(localService, never()).put(key, "value");
    }

    @Test
    void locallyOwnedGetReadsLocalStorageService() {
        String key = keyOwnedBy("node-1");
        when(localService.get(key)).thenReturn("local-value");

        assertThat(router.get(key)).isEqualTo("local-value");
        verify(nodeClient, never()).get(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void remotelyOwnedGetCallsOwner() {
        String key = keyOwnedBy("node-3");
        ClusterNode owner = ring.owner(key);
        when(nodeClient.get(owner, key)).thenReturn(Optional.of("remote-value"));

        assertThat(router.get(key)).isEqualTo("remote-value");
        verify(localService, never()).get(key);
    }

    @Test
    void deleteFollowsOwnershipForLocalAndRemoteKeys() {
        String localKey = keyOwnedBy("node-1");
        String remoteKey = keyOwnedBy("node-2");
        ClusterNode remoteOwner = ring.owner(remoteKey);

        router.delete(localKey);
        router.delete(remoteKey);

        verify(localService).delete(localKey);
        verify(nodeClient).delete(remoteOwner, remoteKey);
        verify(localService, never()).delete(remoteKey);
    }

    @Test
    void remoteFailuresPropagateWithoutWritingLocally() {
        String key = keyOwnedBy("node-2");
        ClusterNode owner = ring.owner(key);
        NodeCommunicationException failure = new NodeCommunicationException(owner.id(), "write key");
        doThrow(failure).when(nodeClient).put(owner, key, "value");

        assertThatThrownBy(() -> router.put(key, "value")).isSameAs(failure);
        verify(localService, never()).put(key, "value");
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
